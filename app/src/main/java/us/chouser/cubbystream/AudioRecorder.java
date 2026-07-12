package us.chouser.cubbystream;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Consumes interleaved PCM frames from {@link AudioTap} and writes a
 * timestamped mono 16kHz 16-bit WAV file to the same logs directory used
 * by {@link DetectionLogger}.
 *
 * <p>Downsampling is done by integer decimation: accumulate {@code ratio}
 * input samples per channel, average them to one mono sample, write one
 * output sample.  This is adequate for the labeling use case where audio
 * quality is secondary to file size.
 *
 * <p>The WAV header is written with placeholder lengths on open, then
 * re-patched every {@link #PATCH_INTERVAL_MS} milliseconds and again on
 * close.  If the process crashes between patches the file is still valid
 * WAV — the header will simply underreport the length by up to one patch
 * interval's worth of audio; most tools will play it to EOF anyway.
 *
 * <p>All file I/O runs on a single-threaded background executor so it
 * never blocks the audio pipeline.
 */
public class AudioRecorder {
    private static final String TAG              = "AudioRecorder";
    private static final int    TARGET_RATE      = 16000;
    private static final int    BYTES_PER_SAMPLE = 2; // 16-bit PCM
    private static final long   PATCH_INTERVAL_MS = 5_000;

    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    // File state (writer thread only)
    private File             outputFile;
    private FileOutputStream fos;
    private long             totalSamples  = 0;
    private long             lastPatchedMs = 0;

    // Volatile flag checked on audio thread before doing any work
    private volatile boolean open = false;

    // Decimation state (audio thread only)
    private int   inputRate    = 44100;
    private int   channelCount = 2;
    private int   ratio        = Math.max(1, Math.round(44100f / TARGET_RATE));
    private float accumMono    = 0f;
    private int   accumCount   = 0;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Open a new WAV file. Uses the same directory and timestamp-plus-title
     * naming convention as {@link DetectionLogger}.
     */
    public void open(Context context, String streamTitle) {
        accumMono      = 0f;
        accumCount     = 0;
        diagFrameCount = 0;

        writer.execute(() -> {
            try {
                File dir = DetectionLogger.getLogDir(context);
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.e(TAG, "Could not create log dir");
                    return;
                }
                String ts   = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String safe = streamTitle.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                outputFile   = new File(dir, ts + "_" + safe + ".wav");
                fos          = new FileOutputStream(outputFile);
                totalSamples = 0;
                writeHeader(0);
                lastPatchedMs = System.currentTimeMillis();
                open = true;
                Log.i(TAG, "Recording to " + outputFile.getName());
            } catch (IOException e) {
                Log.e(TAG, "Failed to open WAV: " + e.getMessage());
            }
        });
    }

    public void close() {
        open = false;
        writer.execute(() -> {
            if (fos == null) return;
            try {
                fos.flush();
                fos.close();
                fos = null;
                patchHeader();
                Log.i(TAG, "Closed " + outputFile.getName()
                        + " (" + totalSamples + " samples)");
            } catch (IOException e) {
                Log.e(TAG, "Close error: " + e.getMessage());
            }
        });
    }

    public boolean isOpen() { return open; }

    // =========================================================================
    // Frame delivery (audio thread)
    // =========================================================================

    // Frame counter for throttling diagnostic logs (audio thread only)
    private int diagFrameCount = 0;

    public void onAudioFrame(float[] samples, int frameSize, int channelCount, int sampleRate) {
        if (!open) return;

        // Reset decimation if format changed
        if (sampleRate != this.inputRate || channelCount != this.channelCount) {
            this.inputRate    = sampleRate;
            this.channelCount = channelCount;
            this.ratio        = Math.max(1, Math.round((float) sampleRate / TARGET_RATE));
            accumMono  = 0f;
            accumCount = 0;
            Log.i(TAG, "Format: sampleRate=" + sampleRate + " channelCount=" + channelCount
                    + " ratio=" + this.ratio
                    + " actualRate=" + (sampleRate / this.ratio));
        }

        // Log a sample of raw float values on the first few frames to check they're sane
        if (diagFrameCount < 3) {
            diagFrameCount++;
            float min = samples[0], max = samples[0];
            for (int i = 1; i < frameSize * channelCount; i++) {
                if (samples[i] < min) min = samples[i];
                if (samples[i] > max) max = samples[i];
            }
            Log.i(TAG, "Frame " + diagFrameCount + ": frameSize=" + frameSize
                    + " min=" + String.format("%.4f", min)
                    + " max=" + String.format("%.4f", max));
        }

        // Decimate to mono TARGET_RATE
        int    maxOut    = (frameSize / ratio) + 1;
        short[] outBuf   = new short[maxOut];
        int     outCount = 0;

        for (int g = 0; g < frameSize; g++) {
            // Mix channels to mono
            float mono = 0f;
            int base = g * channelCount;
            for (int c = 0; c < channelCount; c++) mono += samples[base + c];
            mono /= channelCount;

            accumMono  += mono;
            accumCount++;

            if (accumCount >= ratio) {
                float avg = accumMono / accumCount;
                int   pcm = Math.round(avg * 32767f);
                outBuf[outCount++] = (short) Math.max(-32768, Math.min(32767, pcm));
                accumMono  = 0f;
                accumCount = 0;
            }
        }

        if (outCount == 0) return;

        // Convert shorts → little-endian bytes on audio thread (avoids per-sample
        // object allocation on the writer thread)
        final byte[] bytes = new byte[outCount * BYTES_PER_SAMPLE];
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < outCount; i++) bb.putShort(outBuf[i]);

        final int samplesThisFrame = outCount;
        writer.execute(() -> {
            if (fos == null) return;
            try {
                fos.write(bytes);
                totalSamples += samplesThisFrame;

                // Periodically patch the header so the file is recoverable if we crash
                long now = System.currentTimeMillis();
                if (now - lastPatchedMs >= PATCH_INTERVAL_MS) {
                    fos.flush();
                    patchHeader();
                    lastPatchedMs = now;
                }
            } catch (IOException e) {
                Log.e(TAG, "Write error: " + e.getMessage());
            }
        });
    }

    // =========================================================================
    // WAV header (writer thread)
    // =========================================================================

    /** Write a fresh 44-byte header at the current position (beginning of file). */
    private void writeHeader(long numSamples) throws IOException {
        fos.write(buildHeader(numSamples));
    }

    /** Seek to byte 0, overwrite the two length fields, seek back to EOF. */
    private void patchHeader() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
            raf.seek(0);
            raf.write(buildHeader(totalSamples));
        }
    }

    private byte[] buildHeader(long numSamples) {
        int  actualRate = (ratio > 0) ? (inputRate / ratio) : TARGET_RATE;
        long dataBytes  = numSamples * BYTES_PER_SAMPLE;
        long chunkSize  = 36 + dataBytes;

        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);

        // RIFF chunk descriptor
        b.put(new byte[]{'R', 'I', 'F', 'F'});
        b.putInt((int) Math.min(chunkSize, 0xFFFFFFFFL));
        b.put(new byte[]{'W', 'A', 'V', 'E'});

        // fmt sub-chunk
        b.put(new byte[]{'f', 'm', 't', ' '});
        b.putInt(16);                               // sub-chunk size (PCM)
        b.putShort((short) 1);                      // audio format: PCM
        b.putShort((short) 1);                      // mono
        b.putInt(actualRate);                       // sample rate
        b.putInt(actualRate * BYTES_PER_SAMPLE);    // byte rate
        b.putShort((short) BYTES_PER_SAMPLE);       // block align
        b.putShort((short) 16);                     // bits per sample

        // data sub-chunk
        b.put(new byte[]{'d', 'a', 't', 'a'});
        b.putInt((int) Math.min(dataBytes, 0xFFFFFFFFL));

        return b.array();
    }
}

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
 * timestamped 16kHz 16-bit WAV file to the same logs directory used by
 * {@link DetectionLogger}, preserving the source's actual channel count
 * (mono stays mono; stereo is written as genuine interleaved stereo, not
 * mixed down). This matters beyond fidelity: {@link ClassicMidBandEnergyDetector}
 * byte-swaps raw interleaved samples *before* mono-mixing, and that specific
 * order can only be reproduced from a recording if real per-channel data was
 * actually preserved -- a mono-only recording of a stereo source can never
 * get this right, no matter how it's processed afterward.
 *
 * <p>Downsampling is done by integer decimation: accumulate {@code ratio}
 * input samples per channel *independently per channel*, average each
 * channel down to one output sample, write one interleaved output frame.
 * This is adequate for the labeling use case where audio quality is
 * secondary to file size.
 *
 * <p>The WAV header is written with placeholder lengths on open, then
 * re-patched every {@link #PATCH_INTERVAL_MS} milliseconds and again on
 * close. If the process crashes between patches the file is still valid
 * WAV — the header will simply underreport the length by up to one patch
 * interval's worth of audio; most tools will play it to EOF anyway. The
 * channel count in the header is likewise only settled once the first real
 * frame arrives (see the format-change block in {@link #onAudioFrame}) and
 * self-corrects on the next patch, the same way the sample rate already did.
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
    // Decimated frame count written so far -- a "frame" here means one sample
    // PER CHANNEL (i.e. NOT multiplied by channelCount); buildHeader() does
    // that multiplication itself when computing byte counts.
    private long             totalFrames   = 0;
    private long             lastPatchedMs = 0;

    // Volatile flag checked on audio thread before doing any work
    private volatile boolean open = false;

    // Decimation state (audio thread only)
    private int     inputRate    = 44100;
    private int     channelCount = 2;
    private int     ratio        = Math.max(1, Math.round(44100f / TARGET_RATE));
    // Per-channel accumulator (grown if a source ever reports more channels
    // than this) -- replaces the old single accumMono float now that each
    // channel is decimated independently instead of being mixed first.
    private float[] accum        = new float[2];
    private int      accumCount   = 0;

    // Decimated output-sample count, updated SYNCHRONOUSLY on the audio thread
    // as each frame is decimated -- unlike totalFrames above (which only
    // advances once the async writer thread actually flushes bytes, and so
    // can lag behind by however much I/O backlog exists). DetectionLogger
    // reads this via getDecimatedSampleCount() to log an exact, race-free
    // correspondence between a CSV row and a position in the WAV file, with
    // no dependence on wall-clock time at all -- see the class doc comment.
    private volatile long decimatedSoFar = 0;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Open a new WAV file. Uses the same directory and timestamp-plus-title
     * naming convention as {@link DetectionLogger}.
     */
    public void open(Context context, String streamTitle) {
        java.util.Arrays.fill(accum, 0f);
        accumCount     = 0;
        decimatedSoFar = 0;
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
                totalFrames  = 0;
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
                        + " (" + totalFrames + " frames, " + channelCount + "ch)");
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
            if (accum.length < channelCount) accum = new float[channelCount];
            java.util.Arrays.fill(accum, 0f);
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

        // Decimate to TARGET_RATE, preserving channelCount channels -- each
        // channel is accumulated/averaged independently (no mono mixing) so
        // real stereo separation survives into the WAV file. See the class
        // doc comment for why this matters for the classic byte-swap path.
        int     maxOutFrames = (frameSize / ratio) + 1;
        short[] outBuf       = new short[maxOutFrames * channelCount];
        int     outFrames    = 0;

        for (int g = 0; g < frameSize; g++) {
            int base = g * channelCount;
            for (int c = 0; c < channelCount; c++) accum[c] += samples[base + c];
            accumCount++;

            if (accumCount >= ratio) {
                int outBase = outFrames * channelCount;
                for (int c = 0; c < channelCount; c++) {
                    float avg = accum[c] / accumCount;
                    int   pcm = Math.round(avg * 32767f);
                    outBuf[outBase + c] = (short) Math.max(-32768, Math.min(32767, pcm));
                    accum[c] = 0f;
                }
                accumCount = 0;
                outFrames++;
            }
        }

        if (outFrames == 0) return;
        decimatedSoFar += outFrames;

        // Convert shorts → little-endian bytes on audio thread (avoids per-sample
        // object allocation on the writer thread)
        final int   totalShorts = outFrames * channelCount;
        final byte[] bytes      = new byte[totalShorts * BYTES_PER_SAMPLE];
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < totalShorts; i++) bb.putShort(outBuf[i]);

        final int framesThisCall = outFrames;
        writer.execute(() -> {
            if (fos == null) return;
            try {
                fos.write(bytes);
                totalFrames += framesThisCall;

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

    /**
     * The number of decimated output samples (per channel) produced so far,
     * updated synchronously on the audio thread as each frame is decimated --
     * NOT the same as (and always at least as current as) {@link #totalFrames},
     * which only advances once the async writer thread actually flushes those
     * samples to disk. Safe to call from the audio thread (e.g. from
     * DetectionLogger.onAudioFrame, which runs on the same thread just before
     * this class processes the same frame -- so the value read there reflects
     * everything through the *previous* frame, a fixed sub-frame lag that
     * doesn't accumulate over the length of a recording).
     *
     * <p>Dividing this by the WAV's actualRate (see {@link #buildHeader}) gives
     * the exact position, in WAV-seconds, that a given CSV row corresponds to
     * -- with no dependence on wall-clock timestamps, timezones, or whether
     * the upstream stream ever stalled/skipped, since both this counter and
     * the WAV file itself are driven off the identical sequence of frames
     * delivered by AudioTap.
     */
    public long getDecimatedSampleCount() {
        return decimatedSoFar;
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
            raf.write(buildHeader(totalFrames));
        }
    }

    /**
     * @param numFrames decimated frame count (per-channel; NOT multiplied by
     *                   channelCount -- see the field doc comment on totalFrames).
     */
    private byte[] buildHeader(long numFrames) {
        int  actualRate  = (ratio > 0) ? (inputRate / ratio) : TARGET_RATE;
        int  numChannels = Math.max(1, channelCount);
        long dataBytes   = numFrames * numChannels * BYTES_PER_SAMPLE;
        long chunkSize    = 36 + dataBytes;

        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);

        // RIFF chunk descriptor
        b.put(new byte[]{'R', 'I', 'F', 'F'});
        b.putInt((int) Math.min(chunkSize, 0xFFFFFFFFL));
        b.put(new byte[]{'W', 'A', 'V', 'E'});

        // fmt sub-chunk
        b.put(new byte[]{'f', 'm', 't', ' '});
        b.putInt(16);                                        // sub-chunk size (PCM)
        b.putShort((short) 1);                               // audio format: PCM
        b.putShort((short) numChannels);                      // mono or stereo, whatever the source is
        b.putInt(actualRate);                                 // sample rate
        b.putInt(actualRate * BYTES_PER_SAMPLE * numChannels); // byte rate
        b.putShort((short) (BYTES_PER_SAMPLE * numChannels));  // block align
        b.putShort((short) 16);                                // bits per sample

        // data sub-chunk
        b.put(new byte[]{'d', 'a', 't', 'a'});
        b.putInt((int) Math.min(dataBytes, 0xFFFFFFFFL));

        return b.array();
    }
}

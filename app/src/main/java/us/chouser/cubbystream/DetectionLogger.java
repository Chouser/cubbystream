package us.chouser.cubbystream;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Independently analyses raw PCM frames and writes per-frame metrics to a CSV
 * file on external storage.
 *
 * <p>The logger receives the same mono frames as the active {@link AdDetector}
 * via {@link AudioTap}, but does its own full analysis — RMS, FFT, band energies,
 * spectral flatness, flux, PAPR, ZCR — without sharing any intermediate data
 * with the detector.  When it is ready to write a row it queries the detector's
 * current state directly via {@link AdDetector#isInAdBreak()}.
 *
 * <p>Frame delivery ({@link #onAudioFrame}) is called on an audio thread.  File
 * I/O is dispatched to a single-threaded background executor so it never blocks
 * the audio pipeline.
 */
public class DetectionLogger {
    private static final String TAG = "DetectionLogger";

    private static final String CSV_HEADER =
            "timestamp_ms,total_volume,energy,flatness,flux,papr,zcr," +
            "low_band,mid_band,high_band,threshold,detector_state,volume_mode,stream_title\n";

    // ---- I/O ----
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private BufferedWriter bw;
    private volatile boolean open = false;

    // ---- Context supplied by caller at log time ----
    private volatile String volumeMode  = "auto";
    private volatile String streamTitle = "";

    // ---- Working buffers (audio thread only) ----
    private final float[] monoBuf  = new float[AudioTap.FRAME_SIZE];
    private final float[] realPart = new float[AudioTap.FRAME_SIZE];
    private final float[] imagPart = new float[AudioTap.FRAME_SIZE];
    private final float[] prevMag  = new float[AudioTap.FRAME_SIZE / 2];

    // ---- ZCR state ----
    private float lastSample = 0f;
    private int   zcCount    = 0;
    private int   totalSamples = 0;

    // ---- Throttle: log ~1 frame/second (every 4th frame at ~4 frames/sec) ----
    private int frameSkip = 0;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public void open(Context context, String streamTitle) {
        this.streamTitle = streamTitle;
        writer.execute(() -> {
            try {
                File dir = new File(context.getExternalFilesDir(null), "logs");
                if (!dir.exists() && !dir.mkdirs()) return;

                String ts   = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String safe = streamTitle.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                File file   = new File(dir, ts + "_" + safe + ".csv");

                bw = new BufferedWriter(new FileWriter(file, true));
                bw.write(CSV_HEADER);
                bw.flush();
                open = true;
            } catch (IOException e) {
                Log.e(TAG, "Failed to open log: " + e.getMessage());
            }
        });
    }

    public void close() {
        open = false;
        writer.execute(() -> {
            try {
                if (bw != null) { bw.close(); bw = null; }
            } catch (IOException e) {
                Log.e(TAG, "Close error: " + e.getMessage());
            }
        });
    }

    public boolean isOpen() { return open; }

    /** Update the volume-mode label written into each CSV row. */
    public void setVolumeMode(String mode) { this.volumeMode = mode; }

    // =========================================================================
    // Audio frame delivery (called on audio thread by AudioTap)
    // =========================================================================

    /**
     * @param samples      interleaved float frame from {@link AudioTap}
     * @param frameSize    number of sample-groups
     * @param channelCount channels per group
     * @param sampleRate   Hz
     * @param detector     the currently active detector; queried for its state
     */
    public void onAudioFrame(float[] samples, int frameSize, int channelCount, int sampleRate,
                             AdDetector detector) {
        if (!open) return;

        AudioFrameUtils.toMono(samples, frameSize, channelCount, monoBuf);

        // ZCR accumulates across every frame; only run the full analysis every Nth
        for (int i = 0; i < frameSize; i++) {
            float s = monoBuf[i];
            if ((lastSample > 0 && s <= 0) || (lastSample < 0 && s >= 0)) zcCount++;
            lastSample = s;
            totalSamples++;
        }

        if (++frameSkip < 4) return;
        frameSkip = 0;

        analyseAndWrite(monoBuf, frameSize, sampleRate, detector);

        zcCount      = 0;
        totalSamples = 0;
    }

    // =========================================================================
    // Full analysis (audio thread — no allocations in hot path)
    // =========================================================================

    private void analyseAndWrite(float[] mono, int frameSize, int sampleRate,
                                 AdDetector detector) {
        float rms = AudioFrameUtils.rms(mono, frameSize);

        AudioFrameUtils.hannWindow(mono, realPart, imagPart, AudioTap.FRAME_SIZE);
        AudioFrameUtils.fft(realPart, imagPart, AudioTap.FRAME_SIZE);

        AudioFrameUtils.SpectralStats ss =
                AudioFrameUtils.spectralStats(realPart, imagPart, prevMag, AudioTap.FRAME_SIZE);

        float zcr   = totalSamples > 0 ? (float) zcCount / totalSamples : 0f;
        float lowE  = AudioFrameUtils.bandEnergy(realPart, imagPart,   20,  120, sampleRate, AudioTap.FRAME_SIZE);
        float midE  = AudioFrameUtils.bandEnergy(realPart, imagPart,  120, 1800, sampleRate, AudioTap.FRAME_SIZE);
        float highE = AudioFrameUtils.bandEnergy(realPart, imagPart, 1800, 8000, sampleRate, AudioTap.FRAME_SIZE);

        boolean inAdBreak  = detector != null && detector.isInAdBreak();
        float   threshold = (detector != null) ? detector.getThreshold() : Float.NaN;

        final long   ts         = System.currentTimeMillis();
        final float  fRms       = rms;
        final float  fMidE      = midE;
        final float  fFlatness  = ss.flatness;
        final float  fFlux      = ss.flux;
        final float  fPapr      = ss.papr;
        final float  fZcr       = zcr;
        final float  fLowE      = lowE;
        final float  fHighE     = highE;
        final float  fThreshold = threshold;
        final String state      = inAdBreak ? "ads" : "game";
        final String mode       = volumeMode;
        final String title      = streamTitle;

        writer.execute(() -> {
            if (bw == null) return;
            try {
                String safeTitle = "\"" + title.replace("\"", "\"\"") + "\"";
                String thrStr    = Float.isNaN(fThreshold)
                        ? "" : String.format(Locale.US, "%.4f", fThreshold);
                String row = String.format(Locale.US,
                        "%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%s,%s,%s\n",
                        ts, fRms, fMidE, fFlatness, fFlux, fPapr, fZcr,
                        fLowE, fMidE, fHighE, thrStr, state, mode, safeTitle);
                bw.write(row);
                bw.flush();
            } catch (IOException e) {
                Log.e(TAG, "Write error: " + e.getMessage());
            }
        });
    }

    // =========================================================================
    // Utility
    // =========================================================================

    public static File getLogDir(Context context) {
        return new File(context.getExternalFilesDir(null), "logs");
    }
}

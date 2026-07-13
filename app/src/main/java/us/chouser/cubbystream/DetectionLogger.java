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
 * Independently analyses raw PCM frames and writes windowed metrics to a CSV.
 *
 * <p>Every incoming frame is fully analysed (FFT, band energies, spectral
 * stats, ZCR).  Metrics are accumulated into a 40-frame ring buffer.  Every
 * 20 frames the window is aggregated and one CSV row is written, giving ~50%
 * overlap and a write cadence of roughly one row per second at 48 kHz /
 * FRAME_SIZE=2048.
 *
 * <p>Aggregation per metric:
 * <ul>
 *   <li>mid_band_classic, mid_band, low_band, high_band, total_volume,
 *       flatness, zcr — mean over the window</li>
 *   <li>flux — sum over the window (total spectral activity)</li>
 *   <li>papr — max over the window (worst-case peak)</li>
 * </ul>
 *
 * <p>mid_band_classic replicates the byte-swap that
 * {@link ClassicMidBandEnergyDetector} applies before its FFT, so the logged
 * value is directly comparable to the working detector's signal.
 *
 * <p>All file I/O is dispatched to a single-threaded background executor.
 */
public class DetectionLogger {
    private static final String TAG = "DetectionLogger";

    private static final int WINDOW_FRAMES = 40;
    private static final int WRITE_EVERY   = 20;

    private static final String CSV_HEADER =
            "timestamp_ms,total_volume,mid_band_classic,mid_band,low_band,high_band," +
            "flatness,flux,papr,zcr,threshold,detector_state,volume_mode,stream_title\n";

    // ---- I/O ----
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private BufferedWriter bw;
    private volatile boolean open = false;

    // ---- Context supplied by caller ----
    private volatile String volumeMode  = "auto";
    private volatile String streamTitle = "";

    // ---- Working buffers (audio thread only) ----
    private final float[] monoBuf      = new float[AudioTap.FRAME_SIZE];
    private final float[] swappedBuf   = new float[AudioTap.FRAME_SIZE];
    private final float[] realPart     = new float[AudioTap.FRAME_SIZE];
    private final float[] imagPart     = new float[AudioTap.FRAME_SIZE];
    private final float[] prevMag      = new float[AudioTap.FRAME_SIZE / 2];
    private final float[] prevMagClass = new float[AudioTap.FRAME_SIZE / 2];

    // ---- Ring buffer of per-frame metrics (audio thread only) ----
    private final float[] wMidClassic = new float[WINDOW_FRAMES];
    private final float[] wMid        = new float[WINDOW_FRAMES];
    private final float[] wLow        = new float[WINDOW_FRAMES];
    private final float[] wHigh       = new float[WINDOW_FRAMES];
    private final float[] wRms        = new float[WINDOW_FRAMES];
    private final float[] wFlatness   = new float[WINDOW_FRAMES];
    private final float[] wFlux       = new float[WINDOW_FRAMES];
    private final float[] wPapr       = new float[WINDOW_FRAMES];
    private final float[] wZcr        = new float[WINDOW_FRAMES];

    private int  windowPos   = 0;   // next slot to write in ring
    private int  windowFill  = 0;   // how many slots are valid (0..WINDOW_FRAMES)
    private int  framesSinceWrite = 0;

    // ---- ZCR state carried across frames ----
    private float lastSample = 0f;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public void open(Context context, String streamTitle) {
        this.streamTitle = streamTitle;
        writer.execute(() -> {
            try {
                File dir = getLogDir(context);
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

    public void setVolumeMode(String mode) { this.volumeMode = mode; }

    // =========================================================================
    // Frame delivery (audio thread)
    // =========================================================================

    public void onAudioFrame(float[] samples, int frameSize, int channelCount, int sampleRate,
                             AdDetector detector) {
        if (!open) return;

        // --- Mix to mono ---
        AudioFrameUtils.toMono(samples, frameSize, channelCount, monoBuf);

        // --- ZCR (time-domain, per-sample) ---
        float zcr = AudioFrameUtils.zcr(monoBuf, frameSize, lastSample);
        lastSample = monoBuf[frameSize - 1];

        // --- Modern FFT path ---
        AudioFrameUtils.hannWindow(monoBuf, realPart, imagPart, AudioTap.FRAME_SIZE);
        AudioFrameUtils.fft(realPart, imagPart, AudioTap.FRAME_SIZE);
        AudioFrameUtils.SpectralStats ss =
                AudioFrameUtils.spectralStats(realPart, imagPart, prevMag, AudioTap.FRAME_SIZE);
        float rms   = AudioFrameUtils.rms(monoBuf, frameSize);
        float lowE  = AudioFrameUtils.bandEnergy(realPart, imagPart,   20,  120, sampleRate, AudioTap.FRAME_SIZE);
        float midE  = AudioFrameUtils.bandEnergy(realPart, imagPart,  120, 1800, sampleRate, AudioTap.FRAME_SIZE);
        float highE = AudioFrameUtils.bandEnergy(realPart, imagPart, 1800, 8000, sampleRate, AudioTap.FRAME_SIZE);

        // --- Classic FFT path (byte-swap each sample before FFT) ---
        byteSwapSamples(monoBuf, swappedBuf, frameSize);
        AudioFrameUtils.hannWindow(swappedBuf, realPart, imagPart, AudioTap.FRAME_SIZE);
        AudioFrameUtils.fft(realPart, imagPart, AudioTap.FRAME_SIZE);
        AudioFrameUtils.spectralStats(realPart, imagPart, prevMagClass, AudioTap.FRAME_SIZE);
        float midEClassic = AudioFrameUtils.bandEnergy(realPart, imagPart, 120, 1800, sampleRate, AudioTap.FRAME_SIZE);

        // --- Push into ring buffer ---
        int slot = windowPos;
        wMidClassic[slot] = midEClassic;
        wMid[slot]        = midE;
        wLow[slot]        = lowE;
        wHigh[slot]       = highE;
        wRms[slot]        = rms;
        wFlatness[slot]   = ss.flatness;
        wFlux[slot]       = ss.flux;
        wPapr[slot]       = ss.papr;
        wZcr[slot]        = zcr;

        windowPos  = (windowPos + 1) % WINDOW_FRAMES;
        if (windowFill < WINDOW_FRAMES) windowFill++;

        // --- Write every WRITE_EVERY frames once window is primed ---
        if (windowFill < WINDOW_FRAMES) return;
        if (++framesSinceWrite < WRITE_EVERY) return;
        framesSinceWrite = 0;

        // Aggregate window
        float aMidClassic = 0, aMid = 0, aLow = 0, aHigh = 0;
        float aRms = 0, aFlatness = 0, aZcr = 0;
        float aFlux = 0, aMaxPapr = 0;
        for (int i = 0; i < WINDOW_FRAMES; i++) {
            aMidClassic += wMidClassic[i];
            aMid        += wMid[i];
            aLow        += wLow[i];
            aHigh       += wHigh[i];
            aRms        += wRms[i];
            aFlatness   += wFlatness[i];
            aZcr        += wZcr[i];
            aFlux       += wFlux[i];
            if (wPapr[i] > aMaxPapr) aMaxPapr = wPapr[i];
        }
        aMidClassic /= WINDOW_FRAMES;
        aMid        /= WINDOW_FRAMES;
        aLow        /= WINDOW_FRAMES;
        aHigh       /= WINDOW_FRAMES;
        aRms        /= WINDOW_FRAMES;
        aFlatness   /= WINDOW_FRAMES;
        aZcr        /= WINDOW_FRAMES;
        // aFlux is already summed; aMaxPapr is already max

        boolean inAdBreak = detector != null && detector.isInAdBreak();
        float   threshold = detector != null ? detector.getThreshold() : Float.NaN;

        final long   ts          = System.currentTimeMillis();
        final float  fMidClassic = aMidClassic;
        final float  fMid        = aMid;
        final float  fLow        = aLow;
        final float  fHigh       = aHigh;
        final float  fRms        = aRms;
        final float  fFlatness   = aFlatness;
        final float  fFlux       = aFlux;
        final float  fPapr       = aMaxPapr;
        final float  fZcr        = aZcr;
        final float  fThreshold  = threshold;
        final String state       = inAdBreak ? "ads" : "game";
        final String mode        = volumeMode;
        final String title       = streamTitle;

        writer.execute(() -> {
            if (bw == null) return;
            try {
                String thrStr    = Float.isNaN(fThreshold) ? ""
                        : String.format(Locale.US, "%.4f", fThreshold);
                String safeTitle = "\"" + title.replace("\"", "\"\"") + "\"";
                bw.write(String.format(Locale.US,
                        "%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%s,%s,%s\n",
                        ts, fRms, fMidClassic, fMid, fLow, fHigh,
                        fFlatness, fFlux, fPapr, fZcr,
                        thrStr, state, mode, safeTitle));
                bw.flush();
            } catch (IOException e) {
                Log.e(TAG, "Write error: " + e.getMessage());
            }
        });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void byteSwapSamples(float[] src, float[] dst, int frameSize) {
        ClassicMidBandEnergyDetector.byteSwapSamples(src, dst, frameSize);
    }

    public static File getLogDir(Context context) {
        return new File(context.getExternalFilesDir(null), "logs");
    }
}

package us.chouser.cubbystream;

import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

/**
 * Ad detector based on smoothed mid-band spectral energy.
 *
 * <p>Receives interleaved PCM frames from {@link AudioTap}, mixes to mono,
 * runs an FFT, smooths the mid-band (120–1800 Hz) energy over a rolling window,
 * and compares the result against a configurable threshold.  When the smoothed
 * value drops below the threshold for {@link #TRIGGER_FRAMES} consecutive frames
 * the detector declares an ad break; when it rises back above for the same
 * count it declares that the game has resumed.
 *
 * <p>Only the computation needed for the decision is done here.  Richer
 * diagnostic metrics are the logger's business.
 */
public class MidBandEnergyDetector implements AdDetector {

    public static final String ALGORITHM_KEY = "energy";

    private static final int FFT_SIZE       = 2048;
    private static final int SMOOTH_FRAMES  = 40;
    private static final int TRIGGER_FRAMES = 25;

    // ---- Configurable ----
    public float threshold = 200f;

    // ---- Listener ----
    private AdDetector.Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ---- Working buffers ----
    private final float[] monoBuf  = new float[FFT_SIZE];
    private final float[] realPart = new float[FFT_SIZE];
    private final float[] imagPart = new float[FFT_SIZE];

    // ---- Smoothing ----
    private final float[] smoothBuf = new float[SMOOTH_FRAMES];
    private int   smoothIdx = 0;
    private float smoothSum = 0f;

    // ---- UI output (volatile — read on main thread, written on audio thread) ----
    private volatile float latestSignal = Float.NaN;

    // ---- State machine ----
    private int     belowCount  = 0;
    private int     aboveCount  = 0;
    private boolean inAdBreak   = false;

    // =========================================================================
    // AdDetector
    // =========================================================================

    @Override public String  getDisplayName()  { return "Mid-Band Energy"; }
    @Override public String  getAlgorithmKey() { return ALGORITHM_KEY; }
    @Override public boolean hasThreshold()    { return true; }
    @Override public float   getThreshold()    { return threshold; }
    @Override public float   getSignalLevel()  { return latestSignal; }

    @Override
    public String getStatusText() {
        float s = latestSignal;
        if (Float.isNaN(s)) return null;
        return String.format(Locale.US, "%.0f / %.0f", s, threshold);
    }

    @Override public void setListener(AdDetector.Listener listener) { this.listener = listener; }
    @Override public boolean isInAdBreak() { return inAdBreak; }

    @Override public void resetCounters() { belowCount = 0; aboveCount = 0; }

    @Override
    public void reset() {
        belowCount = aboveCount = smoothIdx = 0;
        smoothSum    = 0f;
        latestSignal = Float.NaN;
        inAdBreak    = false;
        for (int i = 0; i < SMOOTH_FRAMES; i++) smoothBuf[i] = 0f;
    }

    @Override
    public void onAudioFrame(float[] samples, int frameSize, int channelCount, int sampleRate) {
        AudioFrameUtils.toMono(samples, frameSize, channelCount, monoBuf);
        AudioFrameUtils.hannWindow(monoBuf, realPart, imagPart, FFT_SIZE);
        AudioFrameUtils.fft(realPart, imagPart, FFT_SIZE);

        float midE = AudioFrameUtils.bandEnergy(realPart, imagPart, 120, 1800, sampleRate, FFT_SIZE);

        smoothSum -= smoothBuf[smoothIdx];
        smoothBuf[smoothIdx] = midE;
        smoothSum += midE;
        smoothIdx = (smoothIdx + 1) % SMOOTH_FRAMES;
        float avgMid = smoothSum / SMOOTH_FRAMES;

        latestSignal = avgMid;
        updateStateMachine(avgMid);
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private void updateStateMachine(float avg) {
        if (avg < threshold) {
            belowCount++; aboveCount = 0;
            if (!inAdBreak && belowCount >= TRIGGER_FRAMES) {
                inAdBreak = true;
                mainHandler.post(() -> { if (listener != null) listener.onAdBreakStarted(); });
            }
        } else {
            aboveCount++; belowCount = 0;
            if (inAdBreak && aboveCount >= TRIGGER_FRAMES) {
                inAdBreak = false;
                mainHandler.post(() -> { if (listener != null) listener.onGameResumed(); });
            }
        }
    }
}

package us.chouser.cubbystream;

import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

/**
 * Ad detector based purely on smoothed low-band (20–120 Hz) spectral energy.
 *
 * <p>Unlike {@link MidBandEnergyDetector}'s mid-band signal, low-band energy
 * goes <em>up</em> during ad breaks and down during game audio (see
 * conversation — visible directly in a spectrogram as the sub-100 Hz band
 * filling in during commercials). That's the opposite polarity of
 * {@link AdDetector#getSignalLevel()}'s documented contract ("higher values
 * indicate game audio"), which the progress bar's color/fill and every
 * other detector's threshold comparison both depend on. So the raw
 * low-band average is transformed as {@code THRESHOLD_SPACE_MAX - lowBand}
 * before being exposed as the signal or compared to the threshold — this
 * restores the expected polarity (higher = game) while keeping the
 * threshold in the same 0–{@link #THRESHOLD_SPACE_MAX} range a person
 * configures it in, so "the low-band value above which is an ad break"
 * and "the configured threshold" are the same number, just on opposite
 * sides of the subtraction.
 *
 * <p>The rolling average is gated on {@link AudioFrameUtils.RollingStats#ready()}
 * before it's used for anything (signal, status text, or the state
 * machine) — deliberately unlike {@link MidBandEnergyDetector}'s own
 * hand-rolled smoothing, which starts comparing against a zero-padded
 * average from frame one and can briefly misfire on startup. Nothing
 * about that older behavior is required by the {@link AdDetector} contract,
 * so there's no reason to copy the flaw into a new detector.
 */
public class LowBandEnergyDetector implements AdDetector {

    public static final String ALGORITHM_KEY = "low_band";

    /**
     * Upper bound of the threshold/signal space this detector operates in
     * (not a hard cap on raw low-band energy, which can exceed it — see
     * getSignalLevel()'s clamping). Matches the 0–300 range discussed for
     * the threshold seekbar; the shared seekbar in SettingsSheet currently
     * goes to 350, so values between 300 and 350 are accepted but placing
     * the threshold above 300 makes this detector unable to ever declare
     * an ad break (the transformed signal can never be negative once
     * clamped, so it can never fall below a threshold > 300) — a harmless
     * degenerate case, not a crash, but worth knowing if the UI range ever
     * gets revisited.
     */
    private static final float THRESHOLD_SPACE_MAX = 300f;

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
    private final AudioFrameUtils.RollingStats lowBandRoll = new AudioFrameUtils.RollingStats(SMOOTH_FRAMES);

    // ---- UI output (volatile — read on main thread, written on audio thread) ----
    private volatile float latestSignal = Float.NaN;

    // ---- State machine ----
    private int     belowCount = 0;
    private int     aboveCount = 0;
    private boolean inAdBreak  = false;

    // =========================================================================
    // AdDetector
    // =========================================================================

    @Override public String  getDisplayName()  { return "Low-Band Energy"; }
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
        belowCount = aboveCount = 0;
        latestSignal = Float.NaN;
        inAdBreak    = false;
        lowBandRoll.reset();
    }

    @Override
    public void onAudioFrame(float[] samples, int frameSize, int channelCount, int sampleRate) {
        AudioFrameUtils.toMono(samples, frameSize, channelCount, monoBuf);
        AudioFrameUtils.hannWindow(monoBuf, realPart, imagPart, FFT_SIZE);
        AudioFrameUtils.fft(realPart, imagPart, FFT_SIZE);

        float lowE = AudioFrameUtils.bandEnergy(realPart, imagPart, 20, 120, sampleRate, FFT_SIZE);
        lowBandRoll.push(lowE);

        if (!lowBandRoll.ready()) return; // stay in whatever state we started in until primed

        float avgLow = lowBandRoll.mean();
        // Transform to satisfy AdDetector's "higher = game" convention (see
        // class doc comment) and clamp to the documented signal/threshold
        // range so the progress bar never shows something outside 0..300.
        float signal = THRESHOLD_SPACE_MAX - avgLow;
        if (signal < 0f) signal = 0f;
        if (signal > THRESHOLD_SPACE_MAX) signal = THRESHOLD_SPACE_MAX;

        latestSignal = signal;
        updateStateMachine(signal);
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private void updateStateMachine(float signal) {
        if (signal < threshold) {
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

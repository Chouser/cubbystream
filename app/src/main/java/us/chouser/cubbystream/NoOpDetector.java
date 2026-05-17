package us.chouser.cubbystream;

import java.util.Locale;

/**
 * An {@link AdDetector} that performs no ad/game detection and never triggers
 * commercial or game-resumed events.
 *
 * <p>Users who want to hear the full stream without any automatic volume changes
 * can select this algorithm in the settings panel.  The logger, if enabled,
 * still receives audio frames and operates independently.
 *
 * <p>As a lightweight courtesy, this detector computes and exposes the RMS
 * amplitude of each frame so the progress bar in the main activity still shows
 * something useful (current signal level) even when detection is disabled.
 */
public class NoOpDetector implements AdDetector {

    public static final String ALGORITHM_KEY = "none";

    private final float[] monoBuf = new float[AudioTap.FRAME_SIZE];

    /** RMS ×1000, updated on the audio thread, read on the main thread. */
    private volatile float latestRms = Float.NaN;

    @Override public String  getDisplayName()  { return "No ad detection"; }
    @Override public String  getAlgorithmKey() { return ALGORITHM_KEY; }
    @Override public boolean hasThreshold()    { return false; }
    @Override public float   getThreshold()    { return Float.NaN; }
    @Override public float   getSignalLevel()  { return latestRms; }

    @Override
    public String getStatusText() {
        float r = latestRms;
        return Float.isNaN(r) ? null : String.format(Locale.US, "rms %.0f", r);
    }

    @Override public void setListener(AdDetector.Listener listener) { /* ignored */ }
    @Override public boolean isInCommercial() { return false; }
    @Override public void resetCounters()     { /* nothing */ }
    @Override public void reset()             { latestRms = Float.NaN; }

    @Override
    public void onAudioFrame(float[] samples, int frameSize, int channelCount, int sampleRate) {
        AudioFrameUtils.toMono(samples, frameSize, channelCount, monoBuf);
        latestRms = AudioFrameUtils.rms(monoBuf, frameSize);
    }
}

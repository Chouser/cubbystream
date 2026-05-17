package us.chouser.cubbystream;

/**
 * Contract for a pluggable ad-detection algorithm.
 *
 * <p>Implementations receive raw interleaved PCM frames from {@link AudioTap}
 * and fire listener callbacks when they believe the stream has switched between
 * game audio and a commercial break.  They do not sit in the ExoPlayer pipeline
 * directly — that is {@link AudioTap}'s job — so swapping algorithms never
 * requires rebuilding the player.
 *
 * <p>Each implementation also exposes lightweight UI outputs (a scalar signal,
 * a threshold, and a status string) so {@link MainActivity} can drive the
 * progress bar and mode indicator without knowing which algorithm is active.
 * Methods return {@link Float#NaN} / {@code null} to indicate "not applicable",
 * which the UI uses to hide those elements.
 *
 * <p>All listener callbacks are dispatched on the main thread.
 */
public interface AdDetector {

    /** Callbacks fired on the main thread when detection state changes. */
    interface Listener {
        void onCommercialDetected();
        void onGameResumed();
    }

    /** Set the listener that receives commercial/game transition events. */
    void setListener(Listener listener);

    /**
     * Deliver one frame of raw interleaved PCM for analysis.
     *
     * <p>Called on an audio thread by {@link AudioTap}. Implementations must
     * not block and must not modify the array.
     *
     * @param samples      interleaved float samples in [-1, 1]:
     *                     {@code samples[g * channelCount + c]} is sample-group
     *                     {@code g}, channel {@code c}
     * @param frameSize    number of sample-groups (not total array length)
     * @param channelCount channels per group (1 = mono, 2 = stereo, etc.)
     * @param sampleRate   sample rate in Hz
     */
    void onAudioFrame(float[] samples, int frameSize, int channelCount, int sampleRate);

    /** @return true when the detector currently believes a commercial is playing. */
    boolean isInCommercial();

    /**
     * Reset internal consecutive-frame counters.  Call when the user manually
     * overrides the volume mode so the detector does not immediately flip back.
     */
    void resetCounters();

    /** Reset all internal state (counters, smoothing buffers, FFT state, etc.). */
    void reset();

    /** Human-readable label shown in both the algorithm picker and the mode indicator. */
    String getDisplayName();

    /** Short stable key stored in SharedPreferences. */
    String getAlgorithmKey();

    // -------------------------------------------------------------------------
    // UI outputs — polled by MainActivity on a timer, not pushed per-frame
    // -------------------------------------------------------------------------

    /**
     * Whether this detector uses a user-configurable threshold.
     * The settings sheet shows or hides the threshold seekbar accordingly.
     */
    boolean hasThreshold();

    /**
     * The primary scalar signal used for the progress bar, in the same units
     * as {@link #getThreshold()}.  Return {@link Float#NaN} to hide the bar.
     */
    float getSignalLevel();

    /**
     * The current threshold value, in the same units as {@link #getSignalLevel()}.
     * Return {@link Float#NaN} if {@link #hasThreshold()} is false.
     */
    float getThreshold();

    /**
     * A short status string appended to the mode indicator when in Auto mode,
     * e.g. {@code "energy 142 / thr 200"}.  Return {@code null} for no suffix.
     */
    String getStatusText();
}

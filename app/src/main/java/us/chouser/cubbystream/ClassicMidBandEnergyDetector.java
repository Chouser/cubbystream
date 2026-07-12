package us.chouser.cubbystream;

/**
 * Variant of {@link MidBandEnergyDetector} that replicates the pre-fix byte
 * order behaviour: input float samples are re-interpreted as if the underlying
 * PCM bytes were big-endian (i.e. the two bytes of each 16-bit sample are
 * swapped before conversion to float).
 *
 * <p>This exists solely to preserve compatibility with thresholds that were
 * tuned against the original scrambled audio, while the modern detector is
 * re-calibrated against correctly decoded audio.  Once a good threshold is
 * found for the modern detector this class can be retired.
 */
public class ClassicMidBandEnergyDetector extends MidBandEnergyDetector {

    public static final String ALGORITHM_KEY = "energy_classic";

    @Override public String getDisplayName()  { return "Mid-Band Energy (Classic)"; }
    @Override public String getAlgorithmKey() { return ALGORITHM_KEY; }

    // Reusable buffer — same size as AudioTap.FRAME_SIZE * max channels.
    // Allocated lazily to match whatever frame size arrives.
    private float[] swappedSamples = new float[0];

    @Override
    public void onAudioFrame(float[] samples, int frameSize, int channelCount, int sampleRate) {
        int total = frameSize * channelCount;
        if (swappedSamples.length < total) swappedSamples = new float[total];

        // Re-swap the bytes of each sample to undo the correct little-endian
        // interpretation and restore the old scrambled values.
        // AudioTap converts: raw bytes → getShort() → float.
        // The old code read LE bytes as BE shorts, which is the same as reading
        // the correct short and then swapping its two bytes.
        for (int i = 0; i < total; i++) {
            // Convert float back to the short it came from (undo the /32768 scale)
            short correct = (short) Math.round(samples[i] * 32768f);
            // Swap the two bytes, as the old big-endian getShort() would have produced
            short scrambled = (short) (((correct & 0xFF) << 8) | ((correct >> 8) & 0xFF));
            swappedSamples[i] = scrambled / 32768f;
        }

        super.onAudioFrame(swappedSamples, frameSize, channelCount, sampleRate);
    }
}

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
        byteSwapSamples(samples, swappedSamples, total);
        super.onAudioFrame(swappedSamples, frameSize, channelCount, sampleRate);
    }

    /**
     * Swap the two bytes of each sample's underlying 16-bit value, replicating
     * the old big-endian getShort() mis-read on little-endian PCM data.
     * Exposed as a static so {@link DetectionLogger} and GeneratedDetector can
     * use the same transformation without duplicating it.
     *
     * @param src   source float samples in [-1, 1]
     * @param dst   destination array (may be the same as src)
     * @param count number of samples to process
     */
    static void byteSwapSamples(float[] src, float[] dst, int count) {
        for (int i = 0; i < count; i++) {
            short correct   = (short) Math.round(src[i] * 32768f);
            short scrambled = (short) (((correct & 0xFF) << 8) | ((correct >> 8) & 0xFF));
            dst[i] = scrambled / 32768f;
        }
    }
}

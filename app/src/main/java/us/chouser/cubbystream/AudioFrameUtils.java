package us.chouser.cubbystream;

/**
 * Stateless DSP helpers shared by {@link AdDetector} implementations and
 * {@link DetectionLogger}.
 *
 * <p>All methods operate on the interleaved float frames delivered by
 * {@link AudioTap}: {@code samples[g * channelCount + c]} is sample-group
 * {@code g}, channel {@code c}.
 */
public final class AudioFrameUtils {

    private AudioFrameUtils() {}

    /**
     * Mix all channels to mono in-place into {@code out}.
     *
     * @param samples     interleaved frame from {@link AudioTap}
     * @param frameSize   number of sample-groups
     * @param channelCount channels per group
     * @param out         destination array, length >= frameSize
     */
    public static void toMono(float[] samples, int frameSize, int channelCount, float[] out) {
        if (channelCount == 1) {
            System.arraycopy(samples, 0, out, 0, frameSize);
            return;
        }
        float scale = 1f / channelCount;
        for (int g = 0; g < frameSize; g++) {
            float sum = 0f;
            int base = g * channelCount;
            for (int c = 0; c < channelCount; c++) sum += samples[base + c];
            out[g] = sum * scale;
        }
    }

    /**
     * RMS of a mono array, scaled ×1000 for readability.
     *
     * @param mono      mono samples (e.g. from {@link #toMono})
     * @param frameSize number of valid samples
     */
    public static float rms(float[] mono, int frameSize) {
        float sumSq = 0f;
        for (int i = 0; i < frameSize; i++) sumSq += mono[i] * mono[i];
        return (float) Math.sqrt(sumSq / frameSize) * 1000f;
    }

    /**
     * Apply a Hann window to {@code mono} and write into {@code re};
     * zero-fill {@code im}.  Both arrays must be at least {@code n} long.
     */
    public static void hannWindow(float[] mono, float[] re, float[] im, int n) {
        for (int i = 0; i < n; i++) {
            float hann = 0.5f * (1f - (float) Math.cos(2.0 * Math.PI * i / (n - 1)));
            re[i] = mono[i] * hann;
            im[i] = 0f;
        }
    }

    /**
     * In-place iterative Cooley-Tukey FFT.  {@code n} must be a power of two.
     */
    public static void fft(float[] re, float[] im, int n) {
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            float wRe = (float) Math.cos(ang), wIm = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float curRe = 1f, curIm = 0f;
                for (int k = 0; k < len / 2; k++) {
                    float uRe = re[i + k], uIm = im[i + k];
                    float vRe = re[i + k + len/2] * curRe - im[i + k + len/2] * curIm;
                    float vIm = re[i + k + len/2] * curIm + im[i + k + len/2] * curRe;
                    re[i + k]         = uRe + vRe;  im[i + k]         = uIm + vIm;
                    re[i + k + len/2] = uRe - vRe;  im[i + k + len/2] = uIm - vIm;
                    float nr = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nr;
                }
            }
        }
    }

    /**
     * Mean energy of FFT bins corresponding to the frequency band [loHz, hiHz].
     *
     * @param re        real part after {@link #fft}
     * @param im        imaginary part after {@link #fft}
     * @param loHz      lower bound of band in Hz (inclusive)
     * @param hiHz      upper bound of band in Hz (inclusive)
     * @param sampleRate sample rate in Hz
     * @param n         FFT size (== re.length)
     */
    public static float bandEnergy(float[] re, float[] im,
                                   int loHz, int hiHz, int sampleRate, int n) {
        int loBin = (int) Math.ceil((double) loHz * n / sampleRate);
        int hiBin = Math.min((int) Math.floor((double) hiHz * n / sampleRate), n / 2 - 1);
        if (loBin > hiBin) return 0f;
        float sum = 0f;
        for (int i = loBin; i <= hiBin; i++) sum += re[i] * re[i] + im[i] * im[i];
        return sum / (hiBin - loBin + 1);
    }
}

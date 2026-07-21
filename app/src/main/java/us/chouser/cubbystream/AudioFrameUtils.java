package us.chouser.cubbystream;

/**
 * Stateless DSP helpers shared by {@link AdDetector} implementations and
 * {@link DetectionLogger}.
 *
 * <p>All methods operate on the interleaved float frames delivered by
 * {@link AudioTap}: {@code samples[g * channelCount + c]} is sample-group
 * {@code g}, channel {@code c}.
 *
 * <p>Stateful helpers (rolling mean/std) are provided as inner classes so
 * each consumer owns its own instance with no shared state.
 */
public final class AudioFrameUtils {

    private AudioFrameUtils() {}

    // =========================================================================
    // Channel mixing
    // =========================================================================

    /**
     * Mix all channels to mono into {@code out}.
     *
     * @param samples      interleaved frame from {@link AudioTap}
     * @param frameSize    number of sample-groups
     * @param channelCount channels per group
     * @param out          destination array, length >= frameSize
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

    // =========================================================================
    // Time-domain stats
    // =========================================================================

    /**
     * RMS of a mono array, scaled ×1000 for readability.
     */
    public static float rms(float[] mono, int frameSize) {
        float sumSq = 0f;
        for (int i = 0; i < frameSize; i++) sumSq += mono[i] * mono[i];
        return (float) Math.sqrt(sumSq / frameSize) * 1000f;
    }

    /**
     * Zero-crossing rate: fraction of consecutive-sample sign changes.
     *
     * @param mono        mono samples
     * @param frameSize   valid sample count
     * @param lastSample  last sample of the previous frame (for continuity)
     */
    public static float zcr(float[] mono, int frameSize, float lastSample) {
        int crossings = 0;
        float prev = lastSample;
        for (int i = 0; i < frameSize; i++) {
            float s = mono[i];
            if ((prev > 0 && s <= 0) || (prev < 0 && s >= 0)) crossings++;
            prev = s;
        }
        return (float) crossings / frameSize;
    }

    // =========================================================================
    // Frequency domain — windowing and FFT
    // =========================================================================

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

    // =========================================================================
    // Frequency domain — spectral features
    // =========================================================================

    /**
     * Mean energy of FFT bins in the frequency band [loHz, hiHz].
     *
     * @param re         real part after {@link #fft}
     * @param im         imaginary part after {@link #fft}
     * @param loHz       lower bound in Hz (inclusive)
     * @param hiHz       upper bound in Hz (inclusive)
     * @param sampleRate sample rate in Hz
     * @param n          FFT size
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

    /**
     * Immutable snapshot of spectral statistics computed from one FFT frame.
     * Obtain via {@link #spectralStats(float[], float[], float[], int)}.
     */
    public static final class SpectralStats {
        /** Spectral flatness (Wiener entropy), in [0, 1]. */
        public final float flatness;
        /** Positive spectral flux (half-wave rectified frame-to-frame magnitude change). */
        public final float flux;
        /** Peak-to-average power ratio. */
        public final float papr;

        private SpectralStats(float flatness, float flux, float papr) {
            this.flatness = flatness;
            this.flux     = flux;
            this.papr     = papr;
        }
    }

    /**
     * Compute {@link SpectralStats} from a completed FFT frame.
     *
     * <p>Because flux requires the previous frame's magnitudes, the caller
     * owns a {@code prevMag} array of length {@code n/2} and passes it in;
     * this method updates it in-place.
     *
     * @param re      real part after {@link #fft}, length >= n
     * @param im      imaginary part after {@link #fft}, length >= n
     * @param prevMag previous frame magnitudes (length n/2); updated by this call
     * @param n       FFT size
     */
    public static SpectralStats spectralStats(float[] re, float[] im,
                                              float[] prevMag, int n) {
        int   bins      = n / 2;
        float sumMag    = 0, sumLogMag = 0, maxMag = 0, flux = 0;
        for (int i = 0; i < bins; i++) {
            float mag  = (float) Math.sqrt(re[i] * re[i] + im[i] * im[i]);
            sumMag    += mag;
            sumLogMag += (float) Math.log(mag + 1e-6f);
            if (mag > maxMag) maxMag = mag;
            float diff = mag - prevMag[i];
            if (diff > 0) flux += diff;
            prevMag[i] = mag;
        }
        float avgMag   = sumMag / bins;
        float flatness = (float) Math.exp(sumLogMag / bins) / (avgMag + 1e-6f);
        float papr     = maxMag / (avgMag + 1e-6f);
        return new SpectralStats(flatness, flux, papr);
    }

    /**
     * Ratio of mid-band to high-band energy, clamped to avoid division by zero.
     * Matches the {@code mid_high_ratio} feature in the training pipeline.
     */
    public static float midHighRatio(float midBand, float highBand) {
        return midBand / Math.max(highBand, 1.0f);
    }

    /**
     * Ratio of flux to energy, clamped to avoid division by zero.
     * Matches the {@code flux_energy_ratio} feature in the training pipeline.
     */
    public static float fluxEnergyRatio(float flux, float energy) {
        return flux / Math.max(energy, 1.0f);
    }

    public static final class StereoStats {
        /** sum(L*R)/sqrt(sum(L^2)*sum(R^2)) in [-1,1]. 1 = mono-compatible,
         *  0 = uncorrelated, negative = out of phase. 1.0 for mono input. */
        public final float corr;
        /** RMS of the (L-R)/2 "side" signal. 0 = mono-compatible, higher =
         *  wider stereo image. 0.0 for mono input. */
        public final float width;

        private StereoStats(float corr, float width) {
            this.corr  = corr;
            this.width = width;
        }
    }

    /**
     * Time-domain stereo correlation/width from a raw interleaved frame,
     * computed on channels 0 and 1 only (i.e. L/R; any further channels are
     * ignored). Deliberately takes the RAW interleaved samples, not a
     * mono-mixed buffer -- mixing is exactly what erases the difference
     * this measures, so it must be computed before/independent of any
     * mono-mixing step, not after.
     *
     * <p>A classic broadcast-engineering "phase correlation" / "stereo
     * width" pair. Hypothesis this exists to test (unconfirmed, not yet
     * used by any detector): produced commercial audio may show measurably
     * wider stereo than a sports commentary/crowd-noise feed, which
     * broadcasters often keep narrower for mono-compatibility.
     *
     * @param samples      raw interleaved samples, frameSize*channelCount long
     * @param channelCount if less than 2, returns corr=1.0f width=0.0f trivially
     */
    public static StereoStats stereoStats(float[] samples, int frameSize, int channelCount) {
        if (channelCount < 2) return new StereoStats(1f, 0f);
        double sumLL = 0, sumRR = 0, sumLR = 0, sumDiff2 = 0;
        for (int g = 0; g < frameSize; g++) {
            int base = g * channelCount;
            float l = samples[base];
            float r = samples[base + 1];
            sumLL += (double) l * l;
            sumRR += (double) r * r;
            sumLR += (double) l * r;
            double side = (l - r) * 0.5;
            sumDiff2 += side * side;
        }
        double denom = Math.sqrt(sumLL * sumRR);
        float corr  = denom > 0 ? (float) (sumLR / denom) : 1f;
        float width = (float) Math.sqrt(sumDiff2 / frameSize);
        return new StereoStats(corr, width);
    }

    // =========================================================================
    // Stateful helpers — one instance per consumer
    // =========================================================================

    /**
     * Rolling mean and standard deviation over a fixed window of scalar values.
     *
     * <p>Uses Welford's online algorithm for numerically stable variance.
     * The window must be full before {@link #mean()} and {@link #std()} are
     * meaningful; check {@link #ready()} before using them.
     */
    public static final class RollingStats {
        private final float[] buf;
        private final int     window;
        private int   pos   = 0;
        private int   count = 0;
        private float sum   = 0f;
        private float sumSq = 0f;

        public RollingStats(int window) {
            this.window = window;
            this.buf    = new float[window];
        }

        /** Push a new value; drops the oldest when the window is full. */
        public void push(float value) {
            float old = buf[pos];
            buf[pos] = value;
            pos = (pos + 1) % window;
            if (count < window) {
                count++;
                sum   += value;
                sumSq += value * value;
            } else {
                sum   += value - old;
                sumSq += value * value - old * old;
            }
        }

        /** True once {@code window} values have been pushed. */
        public boolean ready() { return count == window; }

        public float mean() { return sum / count; }

        public float std() {
            if (count < 2) return 0f;
            float variance = (sumSq - (sum * sum) / count) / (count - 1);
            return (float) Math.sqrt(Math.max(variance, 0f));
        }

        /** Clears all accumulated state back to empty, as if freshly constructed. */
        public void reset() {
            java.util.Arrays.fill(buf, 0f);
            pos   = 0;
            count = 0;
            sum   = 0f;
            sumSq = 0f;
        }
    }
}

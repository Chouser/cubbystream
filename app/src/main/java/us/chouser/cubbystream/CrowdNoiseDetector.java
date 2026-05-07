package us.chouser.cubbystream;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Crowd noise detector implemented as a Media3 AudioProcessor.
 * Pass-through: audio is copied unchanged; PCM is tapped inline for FFT analysis.
 *
 * Fires onCommercialDetected / onGameResumed on the main thread.
 * Also fires onEnergyUpdate every frame with the current smoothed energy
 * and threshold, so the UI can show a live level meter for calibration.
 */
@UnstableApi
public class CrowdNoiseDetector implements AudioProcessor {

    private static final String TAG = "CrowdNoiseDetector";

    private static final int FFT_SIZE       = 2048;
    private static final int CROWD_LOW_HZ   = 120;
    private static final int CROWD_HIGH_HZ  = 1800;
    private static final int SMOOTH_FRAMES  = 40;
    private static final int TRIGGER_FRAMES = 25;

    /** Adjustable threshold — expose for live display and future settings screen. */
    public float threshold = 200f;

    public interface Listener {
        void onCommercialDetected();
        void onGameResumed();
        /** Called ~10x/sec with the current smoothed energy level and threshold. */
        void onEnergyUpdate(float energy, float threshold);
    }

    Listener listener; // package-private — wired by PlaybackService
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // AudioProcessor state
    private AudioFormat inputFormat  = AudioFormat.NOT_SET;
    private ByteBuffer  outputBuffer = EMPTY_BUFFER;
    private boolean     inputEnded   = false;

    // Analysis buffers
    private final float[] monoAccum = new float[FFT_SIZE];
    private int           accumPos  = 0;
    private final float[] realPart  = new float[FFT_SIZE];
    private final float[] imagPart  = new float[FFT_SIZE];

    // Rolling average
    private final float[] smoothBuf = new float[SMOOTH_FRAMES];
    private int   smoothIdx = 0;
    private float smoothSum = 0f;

    // UI throttle: fire onEnergyUpdate every N FFT frames (~4x/sec at 44100/2048)
    private static final int UI_UPDATE_EVERY = 6;
    private int uiFrameCount = 0;

    // State machine
    private int     belowCount   = 0;
    private int     aboveCount   = 0;
    private boolean inCommercial = false;

    // Cached from configure()
    private int sampleRate   = 44100;
    private int channelCount = 2;

    public CrowdNoiseDetector(Listener listener) {
        this.listener = listener;
    }

    // =========================================================================
    // AudioProcessor
    // =========================================================================

    @Override
    public AudioFormat configure(AudioFormat inputFormat) throws UnhandledAudioFormatException {
        if (inputFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputFormat);
        }
        this.inputFormat  = inputFormat;
        this.sampleRate   = inputFormat.sampleRate;
        this.channelCount = inputFormat.channelCount;
        Log.d(TAG, "Configured: " + sampleRate + " Hz, " + channelCount + " ch");
        return inputFormat;
    }

    @Override public boolean isActive() { return inputFormat != AudioFormat.NOT_SET; }

    @Override
    public void queueInput(ByteBuffer input) {
        int remaining = input.remaining();
        if (remaining == 0) return;
        if (outputBuffer.capacity() < remaining) {
            outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder());
        }
        outputBuffer.clear();
        ByteBuffer forAnalysis = input.duplicate();
        outputBuffer.put(input);
        outputBuffer.flip();
        analyseBuffer(forAnalysis, remaining);
    }

    @Override public void queueEndOfStream() { inputEnded = true; }

    @Override
    public ByteBuffer getOutput() {
        ByteBuffer out = outputBuffer;
        outputBuffer = EMPTY_BUFFER;
        return out;
    }

    @Override public boolean isEnded() { return inputEnded && outputBuffer == EMPTY_BUFFER; }
    @Override public void flush() { outputBuffer = EMPTY_BUFFER; inputEnded = false; accumPos = 0; }

    @Override
    public void reset() {
        flush();
        inputFormat = AudioFormat.NOT_SET;
        resetDetectionState();
    }

    // =========================================================================
    // Analysis
    // =========================================================================

    private void analyseBuffer(ByteBuffer buf, int byteCount) {
        int frameCount = byteCount / (2 * channelCount);
        for (int f = 0; f < frameCount; f++) {
            float mono = 0f;
            for (int c = 0; c < channelCount; c++) mono += buf.getShort() / 32768f;
            monoAccum[accumPos++] = mono / channelCount;
            if (accumPos == FFT_SIZE) { processFrame(); accumPos = 0; }
        }
    }

    private void processFrame() {
        for (int i = 0; i < FFT_SIZE; i++) {
            float hann = 0.5f * (1f - (float) Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
            realPart[i] = monoAccum[i] * hann;
            imagPart[i] = 0f;
        }
        fft(realPart, imagPart, FFT_SIZE);

        float bandEnergy = computeBandEnergy(
                realPart, imagPart, CROWD_LOW_HZ, CROWD_HIGH_HZ, sampleRate, FFT_SIZE);

        smoothSum -= smoothBuf[smoothIdx];
        smoothBuf[smoothIdx] = bandEnergy;
        smoothSum += bandEnergy;
        smoothIdx = (smoothIdx + 1) % SMOOTH_FRAMES;

        float avg = smoothSum / SMOOTH_FRAMES;
        float snap = threshold; // capture for lambda

        // Deliver energy update to UI at ~4x/sec (every UI_UPDATE_EVERY frames)
        if (++uiFrameCount >= UI_UPDATE_EVERY) {
            uiFrameCount = 0;
            mainHandler.post(() -> { if (listener != null) listener.onEnergyUpdate(avg, snap); });
        }

        updateStateMachine(avg);
    }

    private void updateStateMachine(float avg) {
        if (avg < threshold) {
            belowCount++; aboveCount = 0;
            if (!inCommercial && belowCount >= TRIGGER_FRAMES) {
                inCommercial = true;
                mainHandler.post(() -> { if (listener != null) listener.onCommercialDetected(); });
            }
        } else {
            aboveCount++; belowCount = 0;
            if (inCommercial && aboveCount >= TRIGGER_FRAMES) {
                inCommercial = false;
                mainHandler.post(() -> { if (listener != null) listener.onGameResumed(); });
            }
        }
    }

    // =========================================================================
    // DSP
    // =========================================================================

    private float computeBandEnergy(float[] re, float[] im, int lo, int hi, int sr, int n) {
        int loBin = (int) Math.ceil((double) lo * n / sr);
        int hiBin = Math.min((int) Math.floor((double) hi * n / sr), n / 2 - 1);
        if (loBin > hiBin) return 0f;
        float sum = 0f;
        for (int i = loBin; i <= hiBin; i++) sum += re[i]*re[i] + im[i]*im[i];
        return sum / (hiBin - loBin + 1);
    }

    private void fft(float[] re, float[] im, int n) {
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
            float wRe = (float)Math.cos(ang), wIm = (float)Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float curRe = 1f, curIm = 0f;
                for (int k = 0; k < len/2; k++) {
                    float uRe=re[i+k], uIm=im[i+k];
                    float vRe=re[i+k+len/2]*curRe - im[i+k+len/2]*curIm;
                    float vIm=re[i+k+len/2]*curIm + im[i+k+len/2]*curRe;
                    re[i+k]=uRe+vRe; im[i+k]=uIm+vIm;
                    re[i+k+len/2]=uRe-vRe; im[i+k+len/2]=uIm-vIm;
                    float nr=curRe*wRe-curIm*wIm; curIm=curRe*wIm+curIm*wRe; curRe=nr;
                }
            }
        }
    }

    // =========================================================================
    // Public control
    // =========================================================================

    /** Called when user enters Game or Ads mode — clears consecutive-frame counters. */
    public void resetCounters() {
        belowCount = 0;
        aboveCount = 0;
    }

    public boolean isInCommercial() { return inCommercial; }

    private void resetDetectionState() {
        belowCount = aboveCount = accumPos = smoothIdx = uiFrameCount = 0;
        smoothSum = 0f; inCommercial = false;
        for (int i = 0; i < SMOOTH_FRAMES; i++) smoothBuf[i] = 0f;
    }
}

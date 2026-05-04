package com.baseballstream;

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
 *
 * This sits INSIDE ExoPlayer's audio rendering pipeline, receiving the exact
 * same PCM data that will be sent to the speaker — no microphone, no ambient
 * noise, no dependency on the output device type. Works identically whether
 * the user is on speaker, wired headphones, or Bluetooth.
 *
 * How it fits in:
 *   ExoPlayer → DefaultAudioSink → [CrowdNoiseDetector] → speakers
 *
 * The processor is pass-through: it copies input to output unchanged, and
 * analyses the PCM inline (the FFT on 2048 frames is ~0.1 ms — well within
 * normal buffer sizes, no underrun risk).
 *
 * Detection algorithm:
 *   1. Accumulate incoming PCM frames into a 2048-sample mono buffer.
 *   2. Apply a Hann window, then run an in-place Cooley-Tukey FFT.
 *   3. Measure mean power in 120–1800 Hz (the crowd noise band).
 *   4. Maintain a rolling average over 40 frames (~4 s at 44100 Hz).
 *   5. After TRIGGER_FRAMES consecutive frames below/above threshold,
 *      fire onCommercialDetected() / onGameResumed() on the main thread.
 */
@UnstableApi
public class CrowdNoiseDetector implements AudioProcessor {

    private static final String TAG = "CrowdNoiseDetector";

    // ---- FFT / detection parameters ----
    private static final int FFT_SIZE       = 2048;
    private static final int CROWD_LOW_HZ   = 120;
    private static final int CROWD_HIGH_HZ  = 1800;
    private static final int SMOOTH_FRAMES  = 40;   // rolling window length
    private static final int TRIGGER_FRAMES = 25;   // consecutive frames to flip state

    /** Energy threshold. Raise to reduce false commercial detections; lower for sensitivity. */
    public float threshold = 0.012f;

    // ---- Listener ----
    public interface Listener {
        void onCommercialDetected();
        void onGameResumed();
    }

    Listener listener; // package-private so PlaybackService can wire it after construction
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ---- AudioProcessor state ----
    private AudioFormat inputFormat  = AudioFormat.NOT_SET;
    private ByteBuffer  outputBuffer = EMPTY_BUFFER;
    private boolean     inputEnded   = false;

    // ---- Analysis state ----
    private final float[] monoAccum = new float[FFT_SIZE];
    private int           accumPos  = 0;
    private final float[] realPart  = new float[FFT_SIZE];
    private final float[] imagPart  = new float[FFT_SIZE];

    // Rolling average
    private final float[] smoothBuf = new float[SMOOTH_FRAMES];
    private int   smoothIdx = 0;
    private float smoothSum = 0f;

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
    // AudioProcessor implementation
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
        return inputFormat; // pass-through: output format == input format
    }

    @Override
    public boolean isActive() {
        return inputFormat != AudioFormat.NOT_SET;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int remaining = input.remaining();
        if (remaining == 0) return;

        // Grow output buffer if needed
        if (outputBuffer.capacity() < remaining) {
            outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder());
        }
        outputBuffer.clear();

        // Duplicate so analysis reads the same bytes without disturbing position
        ByteBuffer forAnalysis = input.duplicate();

        // Copy input → output unchanged (pass-through)
        outputBuffer.put(input);
        outputBuffer.flip();

        // Analyse the duplicate
        analyseBuffer(forAnalysis, remaining);
    }

    @Override
    public void queueEndOfStream() {
        inputEnded = true;
    }

    @Override
    public ByteBuffer getOutput() {
        ByteBuffer out = outputBuffer;
        outputBuffer = EMPTY_BUFFER;
        return out;
    }

    @Override
    public boolean isEnded() {
        return inputEnded && outputBuffer == EMPTY_BUFFER;
    }

    @Override
    public void flush() {
        outputBuffer = EMPTY_BUFFER;
        inputEnded   = false;
        accumPos     = 0; // discard partial frame on seek
    }

    @Override
    public void reset() {
        flush();
        inputFormat = AudioFormat.NOT_SET;
        resetDetectionState();
    }

    // =========================================================================
    // Analysis — runs on ExoPlayer's audio thread
    // =========================================================================

    private void analyseBuffer(ByteBuffer buf, int byteCount) {
        // 16-bit LE samples, interleaved channels
        int frameCount = byteCount / (2 * channelCount);

        for (int f = 0; f < frameCount; f++) {
            // Downmix all channels to mono
            float mono = 0f;
            for (int c = 0; c < channelCount; c++) {
                mono += buf.getShort() / 32768f;
            }
            monoAccum[accumPos++] = mono / channelCount;

            if (accumPos == FFT_SIZE) {
                processFrame();
                accumPos = 0;
            }
        }
    }

    private void processFrame() {
        // Apply Hann window
        for (int i = 0; i < FFT_SIZE; i++) {
            float hann = 0.5f * (1f - (float) Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
            realPart[i] = monoAccum[i] * hann;
            imagPart[i] = 0f;
        }

        fft(realPart, imagPart, FFT_SIZE);

        float bandEnergy = computeBandEnergy(
                realPart, imagPart, CROWD_LOW_HZ, CROWD_HIGH_HZ, sampleRate, FFT_SIZE);

        // Update rolling average
        smoothSum -= smoothBuf[smoothIdx];
        smoothBuf[smoothIdx] = bandEnergy;
        smoothSum += bandEnergy;
        smoothIdx = (smoothIdx + 1) % SMOOTH_FRAMES;

        updateStateMachine(smoothSum / SMOOTH_FRAMES);
    }

    private void updateStateMachine(float avgEnergy) {
        if (avgEnergy < threshold) {
            belowCount++;
            aboveCount = 0;
            if (!inCommercial && belowCount >= TRIGGER_FRAMES) {
                inCommercial = true;
                mainHandler.post(() -> { if (listener != null) listener.onCommercialDetected(); });
            }
        } else {
            aboveCount++;
            belowCount = 0;
            if (inCommercial && aboveCount >= TRIGGER_FRAMES) {
                inCommercial = false;
                mainHandler.post(() -> { if (listener != null) listener.onGameResumed(); });
            }
        }
    }

    // =========================================================================
    // DSP helpers
    // =========================================================================

    private float computeBandEnergy(float[] real, float[] imag,
                                    int lowHz, int highHz, int sr, int n) {
        int lo = (int) Math.ceil((double)  lowHz * n / sr);
        int hi = (int) Math.floor((double) highHz * n / sr);
        hi = Math.min(hi, n / 2 - 1);
        if (lo > hi) return 0f;
        float sum = 0f;
        for (int i = lo; i <= hi; i++) sum += real[i] * real[i] + imag[i] * imag[i];
        return sum / (hi - lo + 1);
    }

    /** Iterative Cooley-Tukey in-place FFT. n must be a power of 2. */
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
            float wRe = (float) Math.cos(ang), wIm = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float curRe = 1f, curIm = 0f;
                for (int k = 0; k < len / 2; k++) {
                    float uRe = re[i+k], uIm = im[i+k];
                    float vRe = re[i+k+len/2]*curRe - im[i+k+len/2]*curIm;
                    float vIm = re[i+k+len/2]*curIm + im[i+k+len/2]*curRe;
                    re[i+k]         = uRe+vRe;  im[i+k]         = uIm+vIm;
                    re[i+k+len/2]   = uRe-vRe;  im[i+k+len/2]   = uIm-vIm;
                    float nr = curRe*wRe - curIm*wIm;
                    curIm = curRe*wIm + curIm*wRe;
                    curRe = nr;
                }
            }
        }
    }

    // =========================================================================
    // Public control
    // =========================================================================

    /** Force the detector into a known state when the user overrides manually. */
    public void forceState(boolean commercial) {
        inCommercial = commercial;
        belowCount   = 0;
        aboveCount   = 0;
    }

    private void resetDetectionState() {
        belowCount = aboveCount = accumPos = smoothIdx = 0;
        smoothSum  = 0f;
        inCommercial = false;
        for (int i = 0; i < SMOOTH_FRAMES; i++) smoothBuf[i] = 0f;
    }
}

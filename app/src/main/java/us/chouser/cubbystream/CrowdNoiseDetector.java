package us.chouser.cubbystream;

import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@UnstableApi
public class CrowdNoiseDetector implements AudioProcessor {
    private static final int FFT_SIZE = 2048;
    private static final int SMOOTH_FRAMES = 40;
    private static final int TRIGGER_FRAMES = 25;

    public float threshold = 200f;

    public interface Listener {
        void onCommercialDetected();
        void onGameResumed();
        /** Now carries full spectral statistics for logging. */
        void onStatsUpdate(float energy, float flatness, float flux, float papr, 
                           float zcr, float lowB, float midB, float highB, float threshold);
    }

    Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AudioFormat inputFormat = AudioFormat.NOT_SET;
    private ByteBuffer outputBuffer = EMPTY_BUFFER;
    private boolean inputEnded = false;

    private final float[] monoAccum = new float[FFT_SIZE];
    private int accumPos = 0;
    private final float[] realPart = new float[FFT_SIZE];
    private final float[] imagPart = new float[FFT_SIZE];
    private final float[] prevMag = new float[FFT_SIZE / 2];

    private final float[] smoothBuf = new float[SMOOTH_FRAMES];
    private int smoothIdx = 0;
    private float smoothSum = 0f;

    private float lastSample = 0;
    private int zcCount = 0;
    private int totalSamplesInFrame = 0;

    private int belowCount = 0;
    private int aboveCount = 0;
    private boolean inCommercial = false;
    private int sampleRate = 44100;
    private int channelCount = 2;

    public CrowdNoiseDetector(Listener listener) { this.listener = listener; }

    @Override
    public AudioFormat configure(AudioFormat inputFormat) throws UnhandledAudioFormatException {
        if (inputFormat.encoding != C.ENCODING_PCM_16BIT) throw new UnhandledAudioFormatException(inputFormat);
        this.inputFormat = inputFormat;
        this.sampleRate = inputFormat.sampleRate;
        this.channelCount = inputFormat.channelCount;
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

    private void analyseBuffer(ByteBuffer buf, int byteCount) {
        int frameCount = byteCount / (2 * channelCount);
        for (int f = 0; f < frameCount; f++) {
            float mono = 0f;
            for (int c = 0; c < channelCount; c++) mono += buf.getShort() / 32768f;
            mono /= channelCount;

            // Zero Crossing Rate calculation
            if ((lastSample > 0 && mono <= 0) || (lastSample < 0 && mono >= 0)) zcCount++;
            lastSample = mono;
            totalSamplesInFrame++;

            monoAccum[accumPos++] = mono;
            if (accumPos == FFT_SIZE) { 
                processFrame(); 
                accumPos = 0; 
                zcCount = 0; 
                totalSamplesInFrame = 0; 
            }
        }
    }

    private void processFrame() {
        for (int i = 0; i < FFT_SIZE; i++) {
            float hann = 0.5f * (1f - (float) Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
            realPart[i] = monoAccum[i] * hann;
            imagPart[i] = 0f;
        }
        fft(realPart, imagPart, FFT_SIZE);

        // 1. Calculate Magnitudes and Stats
        float sumMag = 0, sumLogMag = 0, maxMag = 0, fluxVal = 0;
        int bins = FFT_SIZE / 2;
        
        for (int i = 0; i < bins; i++) {
            float mag = (float) Math.sqrt(realPart[i] * realPart[i] + imagPart[i] * imagPart[i]);
            sumMag += mag;
            sumLogMag += (float) Math.log(mag + 1e-6f);
            if (mag > maxMag) maxMag = mag;
            
            float diff = mag - prevMag[i];
            fluxVal += (diff > 0) ? diff : 0; 
            prevMag[i] = mag;
        }

        float avgMag = sumMag / bins;
        float flatnessVal = (float) Math.exp(sumLogMag / bins) / (avgMag + 1e-6f);
        float paprVal = maxMag / (avgMag + 1e-6f);
        float zcrVal = (float) zcCount / totalSamplesInFrame;

        // 2. Multi-band Energy
        float lowE = computeBandEnergy(realPart, imagPart, 20, 120, sampleRate, FFT_SIZE);
        float midE = computeBandEnergy(realPart, imagPart, 120, 1800, sampleRate, FFT_SIZE);
        float highE = computeBandEnergy(realPart, imagPart, 1800, 8000, sampleRate, FFT_SIZE);

        // 3. Smoothing and State Machine
        smoothSum -= smoothBuf[smoothIdx];
        smoothBuf[smoothIdx] = midE;
        smoothSum += midE;
        smoothIdx = (smoothIdx + 1) % SMOOTH_FRAMES;
        float avgMid = smoothSum / SMOOTH_FRAMES;

        // --- FINAL SNAPSHOT FOR LAMBDA ---
        // We copy these to final variables so the lambda can "capture" them safely.
        final float fAvgMid = avgMid;
        final float fFlatness = flatnessVal;
        final float fFlux = fluxVal;
        final float fPapr = paprVal;
        final float fZcr = zcrVal;
        final float fLowE = lowE;
        final float fMidE = midE;
        final float fHighE = highE;
        final float fThreshold = threshold;

        mainHandler.post(() -> {
            if (listener != null) {
                listener.onStatsUpdate(fAvgMid, fFlatness, fFlux, fPapr, fZcr, fLowE, fMidE, fHighE, fThreshold);
            }
        });

        updateStateMachine(avgMid);
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

    // [computeBandEnergy and fft methods remain identical to previous version]
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

    /** Called when user enters Game or Ads mode — clears consecutive-frame counters. */
    public void resetCounters() {
        belowCount = 0;
        aboveCount = 0;
    }

    public boolean isInCommercial() { return inCommercial; }

    @Override
    public void reset() {
        flush();
        inputFormat = AudioFormat.NOT_SET;
        resetDetectionState();
    }

    private void resetDetectionState() {
        belowCount = aboveCount = accumPos = smoothIdx = 0;
        smoothSum = 0f; 
        inCommercial = false;
        lastSample = 0;
        zcCount = 0;
        totalSamplesInFrame = 0;
        for (int i = 0; i < SMOOTH_FRAMES; i++) smoothBuf[i] = 0f;
        for (int i = 0; i < FFT_SIZE / 2; i++) prevMag[i] = 0f;
    }

    @Override public void queueEndOfStream() { inputEnded = true; }
    @Override public ByteBuffer getOutput() { ByteBuffer out = outputBuffer; outputBuffer = EMPTY_BUFFER; return out; }
    @Override public boolean isEnded() { return inputEnded && outputBuffer == EMPTY_BUFFER; }
    @Override public void flush() { outputBuffer = EMPTY_BUFFER; inputEnded = false; accumPos = 0; }
}
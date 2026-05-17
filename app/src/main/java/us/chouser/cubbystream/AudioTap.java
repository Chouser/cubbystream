package us.chouser.cubbystream;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A lightweight, permanent {@link AudioProcessor} that sits in the ExoPlayer
 * audio pipeline for the lifetime of the service.
 *
 * <p>Its only job is to accumulate incoming PCM into fixed-size frames and
 * deliver each frame to registered consumers.  Audio is converted from 16-bit
 * integers to floats in [-1, 1] but is otherwise delivered as-is: interleaved
 * channels, native channel count.  Consumers that need mono or stereo-differential
 * stats can use the helpers in {@link AudioFrameUtils}.
 *
 * <p>Both consumer references are {@code volatile} and can be swapped at any
 * time without touching the player or the pipeline.
 */
@UnstableApi
public class AudioTap implements AudioProcessor {

    /** Number of multi-channel sample-groups (frames) delivered per callback. */
    public static final int FRAME_SIZE = 2048;

    private AudioFormat inputFormat  = AudioFormat.NOT_SET;
    private ByteBuffer  outputBuffer = EMPTY_BUFFER;
    private boolean     inputEnded   = false;

    /** Interleaved float samples: [L0, R0, L1, R1, …] for stereo. */
    private float[] accumBuf   = new float[FRAME_SIZE * 2]; // sized for up to 2 ch
    private int     accumPos   = 0;   // in sample-groups
    private int     sampleRate  = 44100;
    private int     channelCount = 2;

    // Consumers — may be null; swappable at runtime
    private volatile AdDetector      detector;
    private volatile DetectionLogger logger;

    // -------------------------------------------------------------------------
    // Consumer wiring
    // -------------------------------------------------------------------------

    public void setDetector(AdDetector detector) { this.detector = detector; }
    public void setLogger(DetectionLogger logger) { this.logger   = logger;   }

    // -------------------------------------------------------------------------
    // AudioProcessor
    // -------------------------------------------------------------------------

    @Override
    public AudioFormat configure(AudioFormat inputFormat) throws UnhandledAudioFormatException {
        if (inputFormat.encoding != C.ENCODING_PCM_16BIT)
            throw new UnhandledAudioFormatException(inputFormat);
        this.inputFormat   = inputFormat;
        this.sampleRate    = inputFormat.sampleRate;
        this.channelCount  = inputFormat.channelCount;
        // Resize accumulator if channel count changed
        accumBuf = new float[FRAME_SIZE * channelCount];
        return inputFormat;
    }

    @Override public boolean isActive() { return inputFormat != AudioFormat.NOT_SET; }

    @Override
    public void queueInput(ByteBuffer input) {
        int remaining = input.remaining();
        if (remaining == 0) return;

        // Pass audio through unchanged
        if (outputBuffer.capacity() < remaining) {
            outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder());
        }
        outputBuffer.clear();
        ByteBuffer forAnalysis = input.duplicate();
        outputBuffer.put(input);
        outputBuffer.flip();

        // Convert to float and accumulate into fixed-size frames.
        // Each iteration of the loop consumes one sample-group (all channels).
        int sampleGroups = remaining / (2 * channelCount); // 2 bytes per short
        for (int g = 0; g < sampleGroups; g++) {
            int base = accumPos * channelCount;
            for (int c = 0; c < channelCount; c++) {
                accumBuf[base + c] = forAnalysis.getShort() / 32768f;
            }
            if (++accumPos == FRAME_SIZE) {
                dispatchFrame();
                accumPos = 0;
            }
        }
    }

    private void dispatchFrame() {
        AdDetector      d = detector;
        DetectionLogger l = logger;
        if (d != null) d.onAudioFrame(accumBuf, FRAME_SIZE, channelCount, sampleRate);
        if (l != null) l.onAudioFrame(accumBuf, FRAME_SIZE, channelCount, sampleRate, d);
    }

    @Override public void queueEndOfStream() { inputEnded = true; }

    @Override
    public ByteBuffer getOutput() {
        ByteBuffer out = outputBuffer;
        outputBuffer = EMPTY_BUFFER;
        return out;
    }

    @Override public boolean isEnded() { return inputEnded && outputBuffer == EMPTY_BUFFER; }

    @Override
    public void flush() {
        outputBuffer = EMPTY_BUFFER;
        inputEnded   = false;
        accumPos     = 0;
    }

    @Override
    public void reset() {
        flush();
        inputFormat = AudioFormat.NOT_SET;
    }
}

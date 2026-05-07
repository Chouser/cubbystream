package us.chouser.cubbystream;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Sits between MlbApiClient and the UI.
 *
 * Responsibilities:
 *  - Maintains an in-memory queue of GameState snapshots (newest at tail)
 *  - Tracks total display delay = baseDelayMs + extraDelayMs
 *  - extraDelayMs accumulates while stream is paused; resets on Live or new stream
 *  - A 1-second timer fires applyDueFrames(), which finds the most recent
 *    snapshot whose displayTime <= now and applies it (discarding older ones)
 */
public class GamedayController {

    public interface Listener {
        void onGameStateApplied(GameState state);
        void onNoGame(String reason);
        void onError(String message);
    }

    private final Handler    mainHandler    = new Handler(Looper.getMainLooper());
    private final MlbApiClient apiClient   = new MlbApiClient();
    private final List<GameState> queue    = new ArrayList<>(); // oldest → newest

    private Listener listener;
    private long     baseDelayMs  = 20_000L; // configurable; default 20s
    private long     extraDelayMs = 0L;

    // For tracking pause duration
    private long     pauseStartMs = -1L;

    private final Runnable tickRunnable = this::tick;
    private boolean ticking = false;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public void start(int teamId, int pollIntervalSec, long baseDelayMs, Listener listener) {
        this.listener    = listener;
        this.baseDelayMs = baseDelayMs;
        extraDelayMs     = 0;
        pauseStartMs     = -1;
        queue.clear();

        apiClient.start(teamId, pollIntervalSec, new MlbApiClient.Listener() {
            @Override public void onGameState(GameState state) { enqueue(state); }
            @Override public void onNoGame(String reason)      { if (listener != null) listener.onNoGame(reason); }
            @Override public void onError(String message)      { if (listener != null) listener.onError(message); }
        });

        startTicking();
    }

    public void stop() {
        apiClient.stop();
        stopTicking();
        queue.clear();
        listener = null;
    }

    // =========================================================================
    // Delay control
    // =========================================================================

    public void setBaseDelayMs(long ms) {
        baseDelayMs = ms;
    }

    /** Call when user pauses the audio stream. */
    public void onStreamPaused() {
        if (pauseStartMs < 0) {
            pauseStartMs = System.currentTimeMillis();
        }
    }

    /** Call when user resumes the audio stream. Accumulates pause duration into extraDelay. */
    public void onStreamResumed() {
        if (pauseStartMs >= 0) {
            extraDelayMs += System.currentTimeMillis() - pauseStartMs;
            pauseStartMs  = -1;
        }
    }

    /**
     * Call when user hits Live or starts a new stream.
     * Resets extra delay and immediately applies the best available frame.
     */
    public void onLive() {
        extraDelayMs = 0;
        pauseStartMs = -1;
        applyDueFrames(); // immediate apply at base delay
    }

    public void updatePollInterval(int newIntervalSec) {
        apiClient.updatePollInterval(newIntervalSec);
    }

    // =========================================================================
    // Queue management
    // =========================================================================

    private void enqueue(GameState state) {
        // Must be called on main thread (MlbApiClient delivers on main thread)
        queue.add(state);
    }

    // =========================================================================
    // Tick / frame dispatch
    // =========================================================================

    private void startTicking() {
        if (!ticking) {
            ticking = true;
            mainHandler.postDelayed(tickRunnable, 1000);
        }
    }

    private void stopTicking() {
        ticking = false;
        mainHandler.removeCallbacks(tickRunnable);
    }

    private void tick() {
        if (!ticking) return;
        applyDueFrames();
        mainHandler.postDelayed(tickRunnable, 1000);
    }

    /**
     * Finds the most recent snapshot whose scheduled display time <= now,
     * applies it, and discards all older entries.
     *
     * totalDelay = baseDelayMs + extraDelayMs + currentPauseDuration
     * displayTime = fetchTime + totalDelay
     * A frame is "due" when displayTime <= now, i.e. fetchTime <= now - totalDelay.
     */
    private void applyDueFrames() {
        if (queue.isEmpty() || listener == null) return;

        long now          = System.currentTimeMillis();
        long currentPause = (pauseStartMs >= 0) ? (now - pauseStartMs) : 0L;
        long totalDelay   = baseDelayMs + extraDelayMs + currentPause;
        long cutoff       = now - totalDelay; // frames fetched at or before this are due

        // Scan from the tail backward to find the most recent due frame
        int dueIndex = -1;
        for (int i = queue.size() - 1; i >= 0; i--) {
            if (queue.get(i).fetchTimeMs <= cutoff) {
                dueIndex = i;
                break;
            }
        }

        if (dueIndex < 0) return; // nothing due yet

        GameState toApply = queue.get(dueIndex);

        // Discard everything up to and including the applied frame
        queue.subList(0, dueIndex + 1).clear();

        listener.onGameStateApplied(toApply);
    }
}

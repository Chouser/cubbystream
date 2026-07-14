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
        void onNoGame(String reason, long nextGameStartMs, String gamedayUrl);
        void onError(String message);
    }

    private final Handler    mainHandler    = new Handler(Looper.getMainLooper());
    private final MlbApiClient apiClient   = new MlbApiClient();
    private final List<GameState> history = new ArrayList<>(); // oldest → newest
    private static final long MAX_HISTORY_MS = 120_000L; // see SettingsSheet.DELAY_MAX

    private Listener listener;
    private GameState lastDeliveredState = null;
    private String    lastNoGameReason   = null;
    private long      lastNoGameStartMs  = 0L;
    private String    lastNoGameUrl      = null;

    /**
     * Re-attach a listener without restarting polling (used after orientation change).
     * Immediately re-delivers the last known result so the new activity instance
     * can restore its UI without waiting for the next poll.
     */
    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener == null) return;
        if (lastDeliveredState != null) {
            listener.onGameStateApplied(lastDeliveredState);
        } else if (lastNoGameReason != null) {
            listener.onNoGame(lastNoGameReason, lastNoGameStartMs, lastNoGameUrl);
        }
    }
    private long     baseDelayMs  = 20_000L; // configurable; default 20s
    private long     extraDelayMs = 0L;

    // For tracking pause duration
    private long     pauseStartMs = -1L;

    private final Runnable tickRunnable = this::tick;
    private boolean ticking = false;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public void start(int teamId, int pollIntervalSec, long baseDelayMs, String mlbApiBase, Listener listener) {
        this.listener    = listener;
        this.baseDelayMs = baseDelayMs;
        extraDelayMs     = 0;
        pauseStartMs     = -1;
        history.clear();

        apiClient.start(teamId, pollIntervalSec, mlbApiBase, new MlbApiClient.Listener() {
            @Override public void onGameState(GameState state) { enqueue(state); }
            @Override public void onNoGame(String reason, long nextGameStartMs, String gamedayUrl) {
                lastNoGameReason   = reason;
                lastNoGameStartMs  = nextGameStartMs;
                lastNoGameUrl      = gamedayUrl;
                lastDeliveredState = null;
                if (listener != null) listener.onNoGame(reason, nextGameStartMs, gamedayUrl);
            }
            @Override public void onError(String message)      { if (listener != null) listener.onError(message); }
        });

        startTicking();
    }

    public void stop() {
        apiClient.stop();
        stopTicking();
        history.clear();
        lastDeliveredState = null;
        lastNoGameReason   = null;
        lastNoGameStartMs  = 0L;
        lastNoGameUrl      = null;
        listener           = null;
    }

    // =========================================================================
    // Delay control
    // =========================================================================

    public void setBaseDelayMs(long ms) {
        baseDelayMs = ms;
        applyDueFrames();
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
        history.add(state);
        pruneHistory();

        // Adjust poll rate based on game status
        apiClient.applyAdaptivePollRate(state.abstractGameState);

        // If this is the first frame ever, show it immediately
        if (history.size() == 1) {
            applyDueFrames();
        }
    }

    private void pruneHistory() {
        if (history.isEmpty()) return;
        long limit = System.currentTimeMillis() - MAX_HISTORY_MS - (baseDelayMs + extraDelayMs);
        while (!history.isEmpty() && history.get(0).fetchTimeMs < limit) {
            history.remove(0);
        }
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

    private GameState findBestFrame(long targetTime) {
        // If target is newer than our latest data, return latest (immediate application)
        if (targetTime >= history.get(history.size() - 1).fetchTimeMs) {
            return history.get(history.size() - 1);
        }

        // If target is older than our oldest history, return oldest
        if (targetTime <= history.get(0).fetchTimeMs) {
            return history.get(0);
        }

        // Binary search for the frame where fetchTimeMs <= targetTime
        int low = 0;
        int high = history.size() - 1;
        GameState candidate = history.get(0);

        while (low <= high) {
            int mid = (low + high) / 2;
            if (history.get(mid).fetchTimeMs <= targetTime) {
                candidate = history.get(mid);
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return candidate;
    }

    /**
     * Displays the game bestMatch game state, and keeps the history from getting too large.
     *
     * totalDelay = baseDelayMs + extraDelayMs + currentPauseDuration
     * displayTime = fetchTime + totalDelay
     * A frame is "due" when displayTime <= now, i.e. fetchTime <= now - totalDelay.
     */
    private void applyDueFrames() {
        if (history.isEmpty() || listener == null) return;

        long now = System.currentTimeMillis();
        long currentPause = (pauseStartMs >= 0) ? (now - pauseStartMs) : 0L;
        long targetTime = now - (baseDelayMs + extraDelayMs + currentPause);

        GameState bestMatch = findBestFrame(targetTime);
        if (bestMatch != null) {
            lastDeliveredState = bestMatch;
            lastNoGameReason   = null;
            listener.onGameStateApplied(bestMatch);
        }
    }
}

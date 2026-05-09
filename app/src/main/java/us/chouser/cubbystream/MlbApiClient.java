package us.chouser.cubbystream;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Polls the MLB Stats API (GUMBO) for live game data.
 *
 * Two-phase approach:
 *   1. Find today's game for the given team via /api/v1/schedule
 *   2. Poll /api/v1.1/game/{gamePk}/feed/live for live state
 *
 * Delivers parsed GameState snapshots to a Listener on the main thread.
 * The caller is responsible for all delay/queue logic.
 */
public class MlbApiClient {

    private static final String TAG = "MlbApiClient";
    private static final String BASE = "https://statsapi.mlb.com";

    /** Slowest poll rate used for Preview/Final states (seconds). */
    public static final int ADAPTIVE_POLL_SLOW_SEC = 30;

    public interface Listener {
        /** Called on the main thread each time a new GameState is fetched. */
        void onGameState(GameState state);
        /** Called on the main thread when no game is found for today. */
        void onNoGame(String reason);
        /** Called on the main thread on fetch/parse errors (non-fatal; polling continues). */
        void onError(String message);
    }

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private Listener listener;
    private int teamId;
    private int pollIntervalSec;     // current effective interval (may be adaptive)
    private int userPollIntervalSec; // user-configured interval, used during "Live"

    private long   gamePk       = -1;
    private String awaySlug     = "";
    private String homeSlug     = "";
    private String gameDate     = "";
    private int    awayTeamId   = 0;
    private int    homeTeamId   = 0;

    private ScheduledFuture<?> pollFuture;
    private boolean running = false;

    // =========================================================================
    // Public API
    // =========================================================================

    public void start(int teamId, int pollIntervalSec, Listener listener) {
        this.teamId             = teamId;
        this.pollIntervalSec    = pollIntervalSec;
        this.userPollIntervalSec = pollIntervalSec;
        this.listener           = listener;
        this.running       = true;
        this.gamePk        = -1;

        // Find today's game immediately, then start polling
        executor.execute(this::findTodaysGame);
    }

    public void stop() {
        running = false;
        if (pollFuture != null) {
            pollFuture.cancel(false);
            pollFuture = null;
        }
    }

    public void updatePollInterval(int newIntervalSec) {
        this.userPollIntervalSec = newIntervalSec;
        // Only apply immediately if we're in Live mode (not already slowed down adaptively)
        if (this.pollIntervalSec != ADAPTIVE_POLL_SLOW_SEC) {
            this.pollIntervalSec = newIntervalSec;
            if (running && gamePk > 0) {
                if (pollFuture != null) pollFuture.cancel(false);
                schedulePoll();
            }
        }
    }

    /**
     * Adjusts the poll rate based on the abstractGameState from the latest fetch.
     * "Live" uses the user-configured interval; "Preview" and "Final" use the slow rate.
     * Called by GamedayController after each delivered state.
     */
    public void applyAdaptivePollRate(String abstractGameState) {
        int desired = "Live".equalsIgnoreCase(abstractGameState)
                ? userPollIntervalSec
                : ADAPTIVE_POLL_SLOW_SEC;
        if (desired != pollIntervalSec) {
            pollIntervalSec = desired;
            if (running && gamePk > 0) {
                if (pollFuture != null) pollFuture.cancel(false);
                schedulePoll();
            }
        }
    }

    // =========================================================================
    // Game discovery
    // =========================================================================

    private void findTodaysGame() {
        if (!running) return;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String url = BASE + "/api/v1/schedule?sportId=1&teamId=" + teamId
                + "&date=" + today
                + "&hydrate=team";
        try {
            String body = get(url);
            if (body == null) return;

            JSONObject root  = new JSONObject(body);
            JSONArray  dates = root.optJSONArray("dates");
            if (dates == null || dates.length() == 0) {
                deliver(() -> listener.onNoGame("No game scheduled today."));
                return;
            }

            JSONObject dateObj = dates.getJSONObject(0);
            JSONArray  games   = dateObj.optJSONArray("games");
            if (games == null || games.length() == 0) {
                deliver(() -> listener.onNoGame("No game scheduled today."));
                return;
            }

            // Take the first game (there is occasionally a double-header)
            JSONObject game      = games.getJSONObject(0);
            gamePk               = game.getLong("gamePk");
            gameDate             = today;

            JSONObject teams     = game.getJSONObject("teams");
            JSONObject awayObj   = teams.getJSONObject("away").getJSONObject("team");
            JSONObject homeObj   = teams.getJSONObject("home").getJSONObject("team");

            awayTeamId  = awayObj.getInt("id");
            homeTeamId  = homeObj.getInt("id");
            awaySlug    = teamNameToSlug(awayObj.optString("name", ""));
            homeSlug    = teamNameToSlug(homeObj.optString("name", ""));

            // Start polling immediately
            executor.execute(this::fetchLiveFeed);
            schedulePoll();

        } catch (Exception e) {
            Log.w(TAG, "findTodaysGame error: " + e.getMessage());
            deliver(() -> listener.onError("Schedule fetch failed: " + e.getMessage()));
        }
    }

    // =========================================================================
    // Live feed polling
    // =========================================================================

    private void schedulePoll() {
        if (!running) return;
        if (pollFuture != null) pollFuture.cancel(false);
        pollFuture = executor.scheduleAtFixedRate(
                this::fetchLiveFeed, pollIntervalSec, pollIntervalSec, TimeUnit.SECONDS);
    }
    private void fetchLiveFeed() {
        if (!running || gamePk < 0) return;
        String url = BASE + "/api/v1.1/game/" + gamePk + "/feed/live"
                + "?fields=gameData,teams,teamName,abbreviation,liveData,"
                + "linescore,inningHalf,currentInning,outs,balls,strikes,"
                + "pitcher,batter,fullName,plays,currentPlay,home,away,runs,"
                + "matchup,status,abstractGameState,"
                + "datetime,officialDate,postOnFirst,postOnSecond,postOnThird,"
                + "boxscore,pitching,pitchesThrown" // for pitch count
        try {
            String body = get(url);
            if (body == null || !running) return;

            GameState state = parseLiveFeed(body);
            if (state != null) {
                deliver(() -> listener.onGameState(state));
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchLiveFeed error: " + e.getMessage());
            deliver(() -> listener.onError("Live feed error: " + e.getMessage()));
        }
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    private GameState parseLiveFeed(String body) throws Exception {
        JSONObject root     = new JSONObject(body);
        JSONObject gameData = root.getJSONObject("gameData");
        JSONObject liveData = root.getJSONObject("liveData");

        // Abstract game state
        String abstractState = gameData
                .getJSONObject("status")
                .optString("abstractGameState", "Preview");

        // Teams
        JSONObject teams    = gameData.getJSONObject("teams");
        JSONObject awayTeam = teams.getJSONObject("away");
        JSONObject homeTeam = teams.getJSONObject("home");
        String awayAbbrev   = awayTeam.optString("abbreviation", "???");
        String homeAbbrev   = homeTeam.optString("abbreviation", "???");

        // Linescore
        JSONObject linescore = liveData.getJSONObject("linescore");
        int inning      = linescore.optInt("currentInning", 0);
        String half     = linescore.optString("inningHalf", "Top");
        boolean isTop   = !"Bottom".equalsIgnoreCase(half);
        int balls       = linescore.optInt("balls", 0);
        int strikes     = linescore.optInt("strikes", 0);
        int outs        = linescore.optInt("outs", 0);

        // Scores from linescore teams block
        JSONObject lsTeams  = linescore.optJSONObject("teams");
        int awayScore = 0, homeScore = 0;
        if (lsTeams != null) {
            awayScore = lsTeams.optJSONObject("away") != null
                    ? lsTeams.getJSONObject("away").optInt("runs", 0) : 0;
            homeScore = lsTeams.optJSONObject("home") != null
                    ? lsTeams.getJSONObject("home").optInt("runs", 0) : 0;
        }

        JSONObject matchup = getDeepObject(liveData, "plays", "currentPlay", "matchup");

        String batterName  = getDeepString(matchup, "batter", "fullName");
        String pitcherName = getDeepString(matchup, "pitcher", "fullName");

        String nameFirst  = getDeepString(matchup, "postOnFirst", "fullName");
        String nameSecond = getDeepString(matchup, "postOnSecond", "fullName");
        String nameThird  = getDeepString(matchup, "postOnThird", "fullName");

        boolean onFirst  = nameFirst != null;
        boolean onSecond = nameSecond != null;
        boolean onThird  = nameThird != null;

        return new GameState(
                System.currentTimeMillis(),
                awayAbbrev, homeAbbrev,
                awayScore, homeScore,
                inning, isTop,
                balls, strikes, outs,
                onFirst, onSecond, onThird,
                nameFirst, nameSecond, nameThird,
                batterName, pitcherName,
                gamePk, awaySlug, homeSlug, gameDate,
                awayTeamId, homeTeamId,
                abstractState);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private JSONObject getDeepObjectButOne(JSONObject json, String[] keys) {
        JSONObject current = json;
        for (int i = 0; i < keys.length - 1; i++) {
            if (current == null) return null;
            current = current.optJSONObject(keys[i]);
        }
        return current;
    }

    private JSONObject getDeepObject(JSONObject json, String... keys) {
        JSONObject parent = getDeepObjectButOne(json, keys);
        return (parent != null) ? parent.optJSONObject(keys[keys.length - 1]) : null;
    }

    private String getDeepString(JSONObject json, String... keys) {
        JSONObject parent = getDeepObjectButOne(json, keys);
        return (parent != null) ? parent.optString(keys[keys.length - 1], null) : null;
    }

    private int getDeepInt(JSONObject json, String... keys) {
        JSONObject parent = getDeepObjectButOne(json, keys);
        return (parent != null) ? parent.optInt(keys[keys.length - 1], 0) : 0;
    }

    /** Synchronous HTTP GET — must be called off the main thread. */
    private String get(String url) throws IOException {
        Request req = new Request.Builder().url(url).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                deliver(() -> listener.onError("HTTP " + resp.code() + " from " + url));
                return null;
            }
            return resp.body() != null ? resp.body().string() : null;
        }
    }

    /** Deliver to the main thread (no-op if listener is null). */
    private void deliver(Runnable r) {
        if (listener != null) mainHandler.post(r);
    }

    /**
     * Converts an MLB team name to the URL slug used in Gameday URLs.
     * e.g. "Chicago Cubs" → "cubs", "New York Yankees" → "yankees"
     */
    static String teamNameToSlug(String fullName) {
        if (fullName == null || fullName.isEmpty()) return "unknown";
        // The last word of the team name is typically the nickname
        String[] parts = fullName.trim().split("\\s+");
        return parts[parts.length - 1].toLowerCase(Locale.US);
    }
}

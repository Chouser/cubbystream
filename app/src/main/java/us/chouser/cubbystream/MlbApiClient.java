package us.chouser.cubbystream;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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
 * Three-phase approach:
 *   1. Resolve the currently relevant game for the team via /api/v1/schedule
 *      (see resolveCurrentGame() for the selection algorithm)
 *   2. Poll /api/v1.1/game/{gamePk}/feed/live for live state
 *   3. Periodically re-run step 1 so doubleheader handoffs, day rollovers,
 *      and postponements are caught without restarting the app
 *
 * Delivers parsed GameState snapshots to a Listener on the main thread.
 * The caller is responsible for all delay/queue logic.
 *
 * IMPORTANT: every date/time computation in this class works in absolute
 * instants (epoch millis / UTC) — never the device's default timezone.
 * MLB's schedule is keyed to the ballpark's local "baseball day"
 * (officialDate), which has nothing to do with what timezone the phone
 * happens to be set to. Converting an instant to a human-readable local
 * time is a *display* concern, handled entirely by GameTimeFormat.
 */
public class MlbApiClient {

    private static final String TAG = "MlbApiClient";
    private static final String BASE = "https://statsapi.mlb.com";

    /** Slowest poll rate used for Preview/Final states (seconds). */
    public static final int ADAPTIVE_POLL_SLOW_SEC = 30;

    /** How often we re-resolve "which game is current" in the background. */
    private static final int RESOLVE_INTERVAL_SEC = 300; // 5 minutes

    /** How far ahead to look when there's truly no game in the near-term window. */
    private static final int LOOKAHEAD_DAYS = 30;

    public interface Listener {
        /** Called on the main thread each time a new GameState is fetched. */
        void onGameState(GameState state);
        /**
         * Called on the main thread when no game is found for the current window.
         * nextGameStartMs/gamedayUrl describe the next known game and are 0/null
         * if nothing is scheduled in the lookahead period (e.g. offseason).
         */
        void onNoGame(String reason, long nextGameStartMs, String gamedayUrl);
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
    private String gameDate     = "";   // officialDate of the tracked game, e.g. "2026-05-07"
    private int    awayTeamId   = 0;
    private int    homeTeamId   = 0;

    // Populated only while the tracked game is Final, via the lookahead search.
    private long   nextGameStartMs = 0;
    private String nextGameUrl     = null;

    private ScheduledFuture<?> pollFuture;
    private ScheduledFuture<?> resolveFuture;
    private boolean running = false;

    // =========================================================================
    // Public API
    // =========================================================================

    public void start(int teamId, int pollIntervalSec, Listener listener) {
        this.teamId              = teamId;
        this.pollIntervalSec     = pollIntervalSec;
        this.userPollIntervalSec = pollIntervalSec;
        this.listener            = listener;
        this.running             = true;
        this.gamePk              = -1;
        this.nextGameStartMs     = 0;
        this.nextGameUrl         = null;

        // Resolve the current game immediately, then keep re-resolving periodically.
        executor.execute(this::resolveCurrentGame);
        scheduleResolve();
    }

    public void stop() {
        running = false;
        if (pollFuture != null) {
            pollFuture.cancel(false);
            pollFuture = null;
        }
        if (resolveFuture != null) {
            resolveFuture.cancel(false);
            resolveFuture = null;
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

    private void scheduleResolve() {
        if (!running) return;
        if (resolveFuture != null) resolveFuture.cancel(false);
        resolveFuture = executor.scheduleAtFixedRate(
                this::resolveCurrentGame, RESOLVE_INTERVAL_SEC, RESOLVE_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /**
     * Resolves "the currently relevant game" for the team and starts/continues
     * polling it. Re-run periodically (see scheduleResolve()), not just once,
     * so this also catches:
     *   - Doubleheaders: game 1 finishes, game 2 becomes the relevant one
     *   - Day rollovers if the app is left running
     *   - Postponements / schedule corrections
     *
     * Queries a 3-day window (yesterday..tomorrow) computed in UTC — not the
     * device's timezone — which safely brackets any MLB team's "baseball day"
     * regardless of what timezone the phone is set to. Among all games in that
     * window, picks:
     *   1. Any game currently Live, else
     *   2. Whichever not-yet-started game starts soonest, else
     *   3. The most recently completed (Final) game, for score context
     * If the window is completely empty, falls through to the 30-day lookahead.
     */
    private void resolveCurrentGame() {
        if (!running) return;
        try {
            ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
            String startDate = nowUtc.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
            String endDate   = nowUtc.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);

            String url = BASE + "/api/v1/schedule?sportId=1&teamId=" + teamId
                    + "&startDate=" + startDate + "&endDate=" + endDate
                    + "&hydrate=team";
            String body = get(url);
            if (body == null) return;

            List<JSONObject> games = flattenGames(body);
            JSONObject chosen = pickMostRelevant(games);

            if (chosen == null) {
                // Nothing in the near-term window at all: true off day.
                refreshNextGameLookahead(true);
                return;
            }

            long newGamePk = chosen.getLong("gamePk");
            boolean isNewGame = (newGamePk != gamePk);

            gamePk = newGamePk;
            gameDate = chosen.optString("officialDate", startDate);

            JSONObject teams   = chosen.getJSONObject("teams");
            JSONObject awayObj = teams.getJSONObject("away").getJSONObject("team");
            JSONObject homeObj = teams.getJSONObject("home").getJSONObject("team");
            awayTeamId = awayObj.getInt("id");
            homeTeamId = homeObj.getInt("id");
            awaySlug   = teamNameToSlug(awayObj.optString("name", ""));
            homeSlug   = teamNameToSlug(homeObj.optString("name", ""));

            String state = chosen.optJSONObject("status") != null
                    ? chosen.getJSONObject("status").optString("abstractGameState", "Preview")
                    : "Preview";

            if ("Final".equalsIgnoreCase(state)) {
                // Proactively find the next game so the UI can show "Next: ..."
                // instead of the person having to relaunch the app to find out.
                refreshNextGameLookahead(false);
            } else {
                nextGameStartMs = 0;
                nextGameUrl     = null;
            }

            if (isNewGame) {
                // Switching games (e.g. doubleheader handoff) — reset to the
                // fast/live cadence rather than whatever rate the old game left us at.
                pollIntervalSec = userPollIntervalSec;
                if (pollFuture != null) pollFuture.cancel(false);
            }

            executor.execute(this::fetchLiveFeed);
            schedulePoll();

        } catch (Exception e) {
            Log.w(TAG, "resolveCurrentGame error: " + e.getMessage());
            deliver(() -> listener.onError("Schedule fetch failed: " + e.getMessage()));
        }
    }

    /** Flattens every game across every "dates" entry in a schedule response body. */
    private List<JSONObject> flattenGames(String scheduleBody) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        JSONObject root  = new JSONObject(scheduleBody);
        JSONArray  dates = root.optJSONArray("dates");
        if (dates == null) return result;
        for (int i = 0; i < dates.length(); i++) {
            JSONArray games = dates.getJSONObject(i).optJSONArray("games");
            if (games == null) continue;
            for (int j = 0; j < games.length(); j++) {
                result.add(games.getJSONObject(j));
            }
        }
        return result;
    }

    /** Selection rule described in resolveCurrentGame()'s doc comment. */
    private JSONObject pickMostRelevant(List<JSONObject> games) throws Exception {
        JSONObject liveGame = null;

        JSONObject soonestPreview   = null;
        long       soonestPreviewMs = Long.MAX_VALUE;

        JSONObject latestFinal   = null;
        long       latestFinalMs = Long.MIN_VALUE;

        for (JSONObject g : games) {
            String state = g.optJSONObject("status") != null
                    ? g.getJSONObject("status").optString("abstractGameState", "Preview")
                    : "Preview";
            long startMs = parseIso(g.optString("gameDate", null));

            if ("Live".equalsIgnoreCase(state)) {
                if (liveGame == null) liveGame = g;
            } else if ("Final".equalsIgnoreCase(state)) {
                if (startMs > latestFinalMs) { latestFinalMs = startMs; latestFinal = g; }
            } else {
                if (startMs < soonestPreviewMs) { soonestPreviewMs = startMs; soonestPreview = g; }
            }
        }

        if (liveGame != null) return liveGame;
        if (soonestPreview != null) return soonestPreview;
        return latestFinal; // may be null if games was empty
    }

    /**
     * Looks ahead up to LOOKAHEAD_DAYS for the next scheduled game and stores
     * its start time / Gameday URL in nextGameStartMs / nextGameUrl.
     *
     * @param deliverAsNoGame if true, also delivers onNoGame directly (used for
     *                        the true "nothing scheduled today" case); if false,
     *                        just updates the fields so they ride along on the
     *                        next GameState for the (Final) game still being tracked.
     */
    private void refreshNextGameLookahead(boolean deliverAsNoGame) {
        try {
            ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
            String startDate = nowUtc.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
            String endDate   = nowUtc.plusDays(LOOKAHEAD_DAYS).format(DateTimeFormatter.ISO_LOCAL_DATE);

            String url = BASE + "/api/v1/schedule?sportId=1&teamId=" + teamId
                    + "&startDate=" + startDate
                    + "&endDate="   + endDate
                    + "&hydrate=team";

            String body = get(url);
            if (body == null) {
                nextGameStartMs = 0;
                nextGameUrl     = null;
                if (deliverAsNoGame) deliver(() -> listener.onNoGame("No game scheduled today.", 0, null));
                return;
            }

            JSONObject root  = new JSONObject(body);
            JSONArray  dates = root.optJSONArray("dates");

            if (dates == null || dates.length() == 0) {
                // Offseason or no games in the lookahead window
                nextGameStartMs = 0;
                nextGameUrl     = null;
                if (deliverAsNoGame) deliver(() -> listener.onNoGame("No game scheduled today.", 0, null));
                return;
            }

            for (int i = 0; i < dates.length(); i++) {
                JSONObject dateObj = dates.getJSONObject(i);
                JSONArray  games   = dateObj.optJSONArray("games");
                if (games == null || games.length() == 0) continue;

                JSONObject game       = games.getJSONObject(0);
                long       nextGamePk = game.getLong("gamePk");
                long       startMs    = parseIso(game.optString("gameDate", null));
                String     nextDate   = dateObj.optString("date", "");

                JSONObject teams    = game.getJSONObject("teams");
                JSONObject awayObj  = teams.getJSONObject("away").getJSONObject("team");
                JSONObject homeObj  = teams.getJSONObject("home").getJSONObject("team");
                String     nextAway = teamNameToSlug(awayObj.optString("name", ""));
                String     nextHome = teamNameToSlug(homeObj.optString("name", ""));

                String datePath = nextDate.replace("-", "/");
                String builtUrl = String.format(
                        "https://www.mlb.com/gameday/%s-vs-%s/%s/%d/preview/summary/all",
                        nextAway, nextHome, datePath, nextGamePk);

                nextGameStartMs = startMs;
                nextGameUrl     = builtUrl;

                if (deliverAsNoGame) {
                    String reason = "No game today.";
                    deliver(() -> listener.onNoGame(reason, startMs, builtUrl));
                }
                return;
            }

            // Dates were present but all empty
            nextGameStartMs = 0;
            nextGameUrl     = null;
            if (deliverAsNoGame) deliver(() -> listener.onNoGame("No game scheduled today.", 0, null));

        } catch (Exception e) {
            Log.w(TAG, "refreshNextGameLookahead error: " + e.getMessage());
            nextGameStartMs = 0;
            nextGameUrl     = null;
            if (deliverAsNoGame) deliver(() -> listener.onNoGame("No game scheduled today.", 0, null));
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
                + "pitcher,batter,fullName,id,plays,currentPlay,home,away,runs,"
                + "matchup,status,abstractGameState,"
                + "boxscore,players,stats,pitching,pitchesThrown,"
                + "datetime,dateTime,officialDate,postOnFirst,postOnSecond,postOnThird";
        try {
            String body = get(url);
            if (body == null || !running) return;

            GameState state = parseLiveFeed(body);
            if (state != null) {
                deliver(() -> listener.onGameState(state));
            }
        } catch (Exception e) {
            // TODO: sometimes errors here indicate the phone has gone to sleep?
            // If there's no audio playing and the phone is off, shut down cleanly?
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

        // Authoritative "baseball day" and scheduled first-pitch instant.
        // Both are already requested via the fields= param above.
        String officialDate = gameData.optString("officialDate", gameDate);
        long   scheduledStartMs = 0;
        JSONObject datetimeObj = gameData.optJSONObject("datetime");
        if (datetimeObj != null) {
            scheduledStartMs = parseIso(datetimeObj.optString("dateTime", null));
        }
        gameDate = officialDate; // keep instance field in sync for gamedayUrl() building

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

        String batterName  = getDeepString(matchup, "batter",  "fullName");
        String pitcherName = getDeepString(matchup, "pitcher", "fullName");

        // Per-game pitch count: matchup.pitcher.id → boxscore players map
        int pitcherPitchesThrown = 0;
        int pitcherId = getDeepInt(matchup, "pitcher", "id");
        if (pitcherId > 0) {
            String playerKey = "ID" + pitcherId;
            JSONObject boxTeams = getDeepObject(liveData, "boxscore", "teams");
            for (String side : new String[]{"away", "home"}) {
                pitcherPitchesThrown = getDeepInt(
                    boxTeams, side, "players", playerKey, "stats", "pitching", "pitchesThrown");
                if (pitcherPitchesThrown > 0) break;
            }
        }

        String nameFirst  = getDeepString(matchup, "postOnFirst",  "fullName");
        String nameSecond = getDeepString(matchup, "postOnSecond", "fullName");
        String nameThird  = getDeepString(matchup, "postOnThird",  "fullName");

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
                batterName, pitcherName, pitcherPitchesThrown,
                gamePk, awaySlug, homeSlug, gameDate,
                awayTeamId, homeTeamId,
                abstractState,
                scheduledStartMs,
                "Final".equalsIgnoreCase(abstractState) ? nextGameStartMs : 0,
                "Final".equalsIgnoreCase(abstractState) ? nextGameUrl     : null);
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

    /** Parses an ISO-8601 UTC instant (e.g. "2026-07-15T00:10:00Z") to epoch millis. 0 on failure. */
    private static long parseIso(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
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

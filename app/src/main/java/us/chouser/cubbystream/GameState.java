package us.chouser.cubbystream;

/**
 * A minimal snapshot of live MLB game state, extracted from the GUMBO feed.
 * Only the fields we actually display are stored here — everything else from
 * the API response is discarded immediately after parsing.
 */
public class GameState {

    // When this snapshot was fetched (wall-clock ms, System.currentTimeMillis())
    public final long fetchTimeMs;

    // Score / inning
    public final String awayTeamAbbrev;  // e.g. "NYY"
    public final String homeTeamAbbrev;  // e.g. "CHC"
    public final int    awayScore;
    public final int    homeScore;
    public final int    inning;
    public final boolean isTopInning;

    // Count
    public final int balls;
    public final int strikes;
    public final int outs;

    // Bases (true = occupied)
    public final boolean runnerOnFirst;
    public final boolean runnerOnSecond;
    public final boolean runnerOnThird;

    // Runner names (null if base empty)
    public final String runnerNameFirst;
    public final String runnerNameSecond;
    public final String runnerNameThird;

    // Players
    public final String batterName;
    public final String pitcherName;
    public final int    pitcherPitchesThrown;

    // Game identity — used to build the Gameday URL
    public final long   gamePk;
    public final String awayTeamSlug;   // e.g. "yankees"
    public final String homeTeamSlug;   // e.g. "cubs"
    public final String gameDate;       // e.g. "2026-05-07"
    public final int    awayTeamId;
    public final int    homeTeamId;

    // Abstract game state
    public final String abstractGameState; // "Live", "Final", "Preview"

    // Scheduled first-pitch time for THIS game (epoch ms, UTC instant). 0 if unknown.
    public final long scheduledStartMs;

    // Only meaningful when abstractGameState == "Final": the next scheduled
    // game's first-pitch time and Gameday URL, so the UI can tell the person
    // when to come back without them having to relaunch the app. 0/null when
    // not applicable or not yet known.
    public final long   nextGameStartMs;
    public final String nextGameUrl;

    public GameState(
            long fetchTimeMs,
            String awayTeamAbbrev, String homeTeamAbbrev,
            int awayScore, int homeScore,
            int inning, boolean isTopInning,
            int balls, int strikes, int outs,
            boolean runnerOnFirst, boolean runnerOnSecond, boolean runnerOnThird,
            String runnerNameFirst, String runnerNameSecond, String runnerNameThird,
            String batterName, String pitcherName, int pitcherPitchesThrown,
            long gamePk,
            String awayTeamSlug, String homeTeamSlug,
            String gameDate,
            int awayTeamId, int homeTeamId,
            String abstractGameState,
            long scheduledStartMs,
            long nextGameStartMs, String nextGameUrl) {
        this.fetchTimeMs = fetchTimeMs;
        this.awayTeamAbbrev = awayTeamAbbrev;
        this.homeTeamAbbrev = homeTeamAbbrev;
        this.awayScore = awayScore;
        this.homeScore = homeScore;
        this.inning = inning;
        this.isTopInning = isTopInning;
        this.balls = balls;
        this.strikes = strikes;
        this.outs = outs;
        this.runnerOnFirst = runnerOnFirst;
        this.runnerOnSecond = runnerOnSecond;
        this.runnerOnThird = runnerOnThird;
        this.runnerNameFirst = runnerNameFirst;
        this.runnerNameSecond = runnerNameSecond;
        this.runnerNameThird = runnerNameThird;
        this.batterName = batterName;
        this.pitcherName = pitcherName;
        this.pitcherPitchesThrown = pitcherPitchesThrown;
        this.gamePk = gamePk;
        this.awayTeamSlug = awayTeamSlug;
        this.homeTeamSlug = homeTeamSlug;
        this.gameDate = gameDate;
        this.awayTeamId = awayTeamId;
        this.homeTeamId = homeTeamId;
        this.abstractGameState = abstractGameState;
        this.scheduledStartMs = scheduledStartMs;
        this.nextGameStartMs = nextGameStartMs;
        this.nextGameUrl = nextGameUrl;
    }

    /** Returns the display time for this snapshot given a total delay in ms. */
    public long displayTimeMs(long totalDelayMs) {
        return fetchTimeMs + totalDelayMs;
    }

    /** Builds the MLB Gameday URL for this game. */
    public String gamedayUrl() {
        // e.g. https://www.mlb.com/gameday/yankees-vs-cubs/2026/05/07/824683/live/summary/all
        String datePath = gameDate.replace("-", "/");
        String status = "Final".equalsIgnoreCase(abstractGameState) ? "final" : "live";
        return String.format("https://www.mlb.com/gameday/%s-vs-%s/%s/%d/%s/summary/all",
                awayTeamSlug, homeTeamSlug, datePath, gamePk, status);
    }
}

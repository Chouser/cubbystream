package us.chouser.cubbystream;

import java.util.List;

/**
 * Top-level JSON feed model.
 *
 * Expected JSON format:
 * {
 *   "updated": "2026-04-29T18:00:00Z",
 *   "mlbApiBase": "https://statsapi.mlb.com",                          <-- optional
 *   "logoUrlPattern": "https://a.espncdn.com/i/teamlogos/mlb/100/%s.png", <-- optional
 *   "streams": [
 *     {
 *       "title": "Yankees vs Red Sox",
 *       "subtitle": "ESPN Radio • 7:05 PM ET",
 *       "url": "https://example.com/stream1.m3u8",
 *       "type": "hls"          <-- optional
 *     },
 *     ...
 *   ]
 * }
 *
 * mlbApiBase and logoUrlPattern let the feed operator repoint the app at a
 * different MLB Stats API host or logo CDN without shipping a new app build,
 * if either of the real ones ever changes or goes away. Both are optional —
 * missing or blank values fall back to the current real hosts.
 * logoUrlPattern must contain a single "%s" placeholder for the team slug.
 */
public class StreamFeed {

    public static final String DEFAULT_MLB_API_BASE     = "https://statsapi.mlb.com";
    public static final String DEFAULT_LOGO_URL_PATTERN = "https://a.espncdn.com/i/teamlogos/mlb/100/%s.png";

    private String updated;
    private List<StreamItem> streams;
    private String mlbApiBase;
    private String logoUrlPattern;

    public String getUpdated() { return updated; }
    public List<StreamItem> getStreams() { return streams; }

    public String getMlbApiBase() {
        return (mlbApiBase != null && !mlbApiBase.trim().isEmpty())
                ? mlbApiBase.trim() : DEFAULT_MLB_API_BASE;
    }

    public String getLogoUrlPattern() {
        return (logoUrlPattern != null && !logoUrlPattern.trim().isEmpty())
                ? logoUrlPattern.trim() : DEFAULT_LOGO_URL_PATTERN;
    }
}

package us.chouser.cubbystream;

/**
 * Represents a single audio stream entry from the JSON feed.
 * The "type" field is optional — ExoPlayer will auto-detect format
 * from URL/headers. It's only used as a fallback hint.
 * The "teamId" field is optional — when present, MLB gameday polling
 * is activated for that team.
 */
public class StreamItem {
    private String title;
    private String subtitle;
    private String url;
    private String type;   // "hls", "mp3", "aac", "ogg" — optional hint
    private int    teamId; // MLB team ID, e.g. 112 for Cubs — 0 if absent

    public StreamItem() {}

    public String getTitle()    { return title    != null ? title    : ""; }
    public String getSubtitle() { return subtitle != null ? subtitle : ""; }
    public String getUrl()      { return url      != null ? url      : ""; }
    public String getType()     { return type     != null ? type     : "auto"; }
    public int    getTeamId()   { return teamId; }

    public boolean isValid() {
        return url != null && !url.trim().isEmpty();
    }

    public boolean hasTeam() {
        return teamId > 0;
    }
}

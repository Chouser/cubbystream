package us.chouser.cubbystream;

/**
 * Represents a single audio stream entry from the JSON feed.
 * The "type" field is optional — ExoPlayer will auto-detect format
 * from URL/headers. It's only used as a fallback hint.
 */
public class StreamItem {
    private String title;
    private String subtitle;
    private String url;
    private String type; // "hls", "mp3", "aac", "ogg" — optional hint

    public StreamItem() {}

    public StreamItem(String title, String subtitle, String url, String type) {
        this.title = title;
        this.subtitle = subtitle;
        this.url = url;
        this.type = type;
    }

    public String getTitle() { return title != null ? title : ""; }
    public String getSubtitle() { return subtitle != null ? subtitle : ""; }
    public String getUrl() { return url != null ? url : ""; }
    public String getType() { return type != null ? type : "auto"; }

    /** Returns true if this item has a valid, non-empty URL */
    public boolean isValid() {
        return url != null && !url.trim().isEmpty();
    }
}

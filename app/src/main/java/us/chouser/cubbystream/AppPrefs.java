package us.chouser.cubbystream;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Typed wrapper around SharedPreferences for all persisted app settings.
 * Single source of truth for defaults.
 */
public class AppPrefs {

    private static final String PREFS_NAME = "cubbystream_prefs";

    // Keys
    public static final String KEY_FEED_URL       = "feed_url";
    public static final String KEY_THRESHOLD      = "threshold";
    public static final String KEY_ADS_VOLUME_PCT = "ads_volume_pct";

    // Defaults
    public static final String DEFAULT_FEED_URL       = FeedFetcher.FEED_URL;
    public static final int    DEFAULT_THRESHOLD       = 200;
    public static final int    DEFAULT_ADS_VOLUME_PCT  = 10;

    private final SharedPreferences prefs;

    public AppPrefs(Context context) {
        prefs = context.getApplicationContext()
                       .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getFeedUrl() {
        return prefs.getString(KEY_FEED_URL, DEFAULT_FEED_URL);
    }
    public void setFeedUrl(String url) {
        prefs.edit().putString(KEY_FEED_URL, url).apply();
    }

    public int getThreshold() {
        return prefs.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD);
    }
    public void setThreshold(int value) {
        prefs.edit().putInt(KEY_THRESHOLD, value).apply();
    }

    public int getAdsVolumePct() {
        return prefs.getInt(KEY_ADS_VOLUME_PCT, DEFAULT_ADS_VOLUME_PCT);
    }
    public void setAdsVolumePct(int pct) {
        prefs.edit().putInt(KEY_ADS_VOLUME_PCT, pct).apply();
    }
}

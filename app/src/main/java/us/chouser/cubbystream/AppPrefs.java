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
    public static final String KEY_FEED_URL            = "feed_url";
    public static final String KEY_DETECTION_ALGORITHM = "detection_algorithm";
    public static final String KEY_THRESHOLD           = "threshold";
    public static final String KEY_ADS_VOLUME_PCT      = "ads_volume_pct";
    public static final String KEY_POLL_INTERVAL       = "poll_interval_sec";
    public static final String KEY_API_DELAY           = "api_delay_sec";
    public static final String KEY_AUTO_START_AUDIO    = "auto_start_audio";
    public static final String KEY_VOLUME_MODE         = "volume_mode";
    public static final String KEY_LOGGING_ENABLED     = "logging_enabled";

    // Defaults
    public static final String  DEFAULT_FEED_URL            = FeedFetcher.FEED_URL;
    public static final String  DEFAULT_DETECTION_ALGORITHM = MidBandEnergyDetector.ALGORITHM_KEY;
    public static final int     DEFAULT_THRESHOLD           = 200;
    public static final int     DEFAULT_ADS_VOLUME_PCT      = 10;
    public static final int     DEFAULT_POLL_INTERVAL       = 3;   // seconds
    public static final int     DEFAULT_API_DELAY           = 20;  // seconds
    public static final boolean DEFAULT_AUTO_START_AUDIO    = true;
    public static final boolean DEFAULT_LOGGING_ENABLED     = false;

    private final SharedPreferences prefs;

    public AppPrefs(Context context) {
        prefs = context.getApplicationContext()
                       .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getFeedUrl() { return prefs.getString(KEY_FEED_URL, DEFAULT_FEED_URL); }
    public void   setFeedUrl(String url) { prefs.edit().putString(KEY_FEED_URL, url).apply(); }

    public String getDetectionAlgorithm() {
        return prefs.getString(KEY_DETECTION_ALGORITHM, DEFAULT_DETECTION_ALGORITHM);
    }
    public void setDetectionAlgorithm(String key) {
        prefs.edit().putString(KEY_DETECTION_ALGORITHM, key).apply();
    }

    public int  getThreshold() { return prefs.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD); }
    public void setThreshold(int v) { prefs.edit().putInt(KEY_THRESHOLD, v).apply(); }

    public int  getAdsVolumePct() { return prefs.getInt(KEY_ADS_VOLUME_PCT, DEFAULT_ADS_VOLUME_PCT); }
    public void setAdsVolumePct(int pct) { prefs.edit().putInt(KEY_ADS_VOLUME_PCT, pct).apply(); }

    public int  getPollInterval() { return prefs.getInt(KEY_POLL_INTERVAL, DEFAULT_POLL_INTERVAL); }
    public void setPollInterval(int sec) { prefs.edit().putInt(KEY_POLL_INTERVAL, sec).apply(); }

    public int  getApiDelay() { return prefs.getInt(KEY_API_DELAY, DEFAULT_API_DELAY); }
    public void setApiDelay(int sec) { prefs.edit().putInt(KEY_API_DELAY, sec).apply(); }

    public boolean getAutoStartAudio() { return prefs.getBoolean(KEY_AUTO_START_AUDIO, DEFAULT_AUTO_START_AUDIO); }
    public void    setAutoStartAudio(boolean v) { prefs.edit().putBoolean(KEY_AUTO_START_AUDIO, v).apply(); }

    public boolean getLoggingEnabled() { return prefs.getBoolean(KEY_LOGGING_ENABLED, DEFAULT_LOGGING_ENABLED); }
    public void    setLoggingEnabled(boolean v) { prefs.edit().putBoolean(KEY_LOGGING_ENABLED, v).apply(); }

    /**
     * Returns the persisted volume mode, defaulting to AUTO.
     * Uses the enum's name() string as the stored value.
     */
    public MainActivity.VolumeMode getVolumeMode() {
        String name = prefs.getString(KEY_VOLUME_MODE, MainActivity.VolumeMode.AUTO.name());
        try {
            return MainActivity.VolumeMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return MainActivity.VolumeMode.AUTO;
        }
    }
    public void setVolumeMode(MainActivity.VolumeMode mode) {
        prefs.edit().putString(KEY_VOLUME_MODE, mode.name()).apply();
    }
}

package us.chouser.cubbystream;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Lightweight team logo loader using OkHttp (already a dependency).
 * In-memory LRU cache keyed by team abbreviation. No external image library needed.
 */
public class TeamLogoLoader {

    // Configurable at runtime via setLogoUrlPattern() (typically from the feed's
    // "logoUrlPattern" field) so the app can be repointed at a different logo
    // host without a new build if the real one ever changes. Must contain a
    // single "%s" placeholder for the team slug. Defaults to ESPN's CDN.
    private static String logoUrlPattern = StreamFeed.DEFAULT_LOGO_URL_PATTERN;

    /** Called once the feed loads; ignored if pattern is null/blank. */
    public static void setLogoUrlPattern(String pattern) {
        if (pattern != null && !pattern.trim().isEmpty()) {
            logoUrlPattern = pattern.trim();
        }
    }

    /**
     * The MLB Stats API's "abbreviation" field mostly matches the slug ESPN
     * uses for its logo CDN once lowercased, but a few teams differ. This
     * maps MLB API abbreviation (uppercase) -> ESPN logo slug (lowercase).
     * Anything not listed here just falls back to teamAbbr.toLowerCase().
     */
    private static final Map<String, String> MLB_TO_ESPN_SLUG = new HashMap<>();
    static {
        MLB_TO_ESPN_SLUG.put("CWS", "chw"); // White Sox: MLB "CWS" vs ESPN "chw"
        MLB_TO_ESPN_SLUG.put("OAK", "ath"); // Athletics: MLB may still say "OAK"; ESPN uses "ath"
        MLB_TO_ESPN_SLUG.put("ATH", "ath"); // Athletics: identity, listed for clarity
    }

    private static String espnSlug(String mlbAbbrev) {
        String upper = mlbAbbrev.toUpperCase();
        String override = MLB_TO_ESPN_SLUG.get(upper);
        return override != null ? override : mlbAbbrev.toLowerCase();
    }

    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(20) {
        @Override
        protected int sizeOf(String key, Bitmap value) { return 1; } // count by entries
    };

    private static final OkHttpClient http     = new OkHttpClient();
    private static final ExecutorService exec  = Executors.newFixedThreadPool(2);
    private static final Handler mainHandler   = new Handler(Looper.getMainLooper());

    /**
     * Loads the logo for team into imageView.
     * Returns immediately; the ImageView is updated on the main thread when ready.
     */
    public static void load(String teamAbbr, ImageView imageView) {
        if (teamAbbr == null || teamAbbr.isEmpty() || imageView == null) return;

        // Tag the view so we can detect recycling
        imageView.setTag(teamAbbr);

        Bitmap cached = cache.get(teamAbbr);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        exec.execute(() -> {
            String url = String.format(logoUrlPattern, espnSlug(teamAbbr));
            try {
                Request req = new Request.Builder().url(url).build();
                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) return;
                    InputStream is     = resp.body().byteStream();
                    Bitmap bmp         = BitmapFactory.decodeStream(is);
                    if (bmp == null) return;
                    cache.put(teamAbbr, bmp);
                    mainHandler.post(() -> {
                        // Only set if the view hasn't been reused for a different team
                        if (teamAbbr.equals(imageView.getTag())) {
                            imageView.setImageBitmap(bmp);
                        }
                    });
                }
            } catch (IOException ignored) {}
        });
    }
}

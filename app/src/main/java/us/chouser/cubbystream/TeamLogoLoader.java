package us.chouser.cubbystream;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;
import java.io.InputStream;
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

    private static final String LOGO_URL = "https://a.espncdn.com/i/teamlogos/mlb/100/%s.png";

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
    public static void load(String teamAbbr1, ImageView imageView) {
        if (teamAbbr1 == null || teamAbbr1.isEmpty() || imageView == null) return;
        final String teamAbbr = "CWS".equals(teamAbbr1) ? "chw" : teamAbbr1; // shrug

        // Tag the view so we can detect recycling
        imageView.setTag(teamAbbr);

        Bitmap cached = cache.get(teamAbbr);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        exec.execute(() -> {
            String url = String.format(LOGO_URL, teamAbbr.toLowerCase());
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

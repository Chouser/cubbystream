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
 * In-memory LRU cache keyed by teamId. No external image library needed.
 *
 * Logo URL: https://www.mlb.com/images/teams/logos/{teamId}-primary.png
 */
public class TeamLogoLoader {

    private static final String LOGO_URL = "https://www.mlb.com/images/teams/logos/%d-primary.png";

    private static final LruCache<Integer, Bitmap> cache = new LruCache<Integer, Bitmap>(20) {
        @Override
        protected int sizeOf(Integer key, Bitmap value) { return 1; } // count by entries
    };

    private static final OkHttpClient http     = new OkHttpClient();
    private static final ExecutorService exec  = Executors.newFixedThreadPool(2);
    private static final Handler mainHandler   = new Handler(Looper.getMainLooper());

    /**
     * Loads the logo for teamId into imageView.
     * Returns immediately; the ImageView is updated on the main thread when ready.
     * No-ops silently if teamId <= 0 or the fetch fails.
     */
    public static void load(int teamId, ImageView imageView) {
        if (teamId <= 0 || imageView == null) return;

        // Tag the view so we can detect recycling
        imageView.setTag(teamId);

        Bitmap cached = cache.get(teamId);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        exec.execute(() -> {
            String url = String.format(LOGO_URL, teamId);
            try {
                Request req = new Request.Builder().url(url).build();
                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) return;
                    InputStream is     = resp.body().byteStream();
                    Bitmap bmp         = BitmapFactory.decodeStream(is);
                    if (bmp == null) return;
                    cache.put(teamId, bmp);
                    mainHandler.post(() -> {
                        // Only set if the view hasn't been reused for a different team
                        if (Integer.valueOf(teamId).equals(imageView.getTag())) {
                            imageView.setImageBitmap(bmp);
                        }
                    });
                }
            } catch (IOException ignored) {}
        });
    }
}

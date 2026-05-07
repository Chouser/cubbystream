package us.chouser.cubbystream;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches the stream JSON feed from the hardcoded URL on a background thread
 * and delivers results to the main thread via a callback.
 */
public class FeedFetcher {

    /** ---- CHANGE THIS TO YOUR ACTUAL SERVER URL ---- */
    //public static final String FEED_URL = "http://192.168.0.135:8888/streams.json";
    public static final String FEED_URL = "https://chouser.us/streams.json";

    public interface Callback {
        void onSuccess(StreamFeed feed);
        void onError(String message);
    }

    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void fetch(Callback callback) {
        executor.execute(() -> {
            Request request = new Request.Builder()
                    .url(FEED_URL)
                    .addHeader("Cache-Control", "no-cache")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    deliverError(callback, "Server returned " + response.code());
                    return;
                }

                String body = response.body() != null ? response.body().string() : "";
                if (body.isEmpty()) {
                    deliverError(callback, "Empty response from server");
                    return;
                }

                StreamFeed feed = gson.fromJson(body, StreamFeed.class);
                if (feed == null || feed.getStreams() == null) {
                    deliverError(callback, "Invalid JSON — missing 'streams' array");
                    return;
                }

                mainHandler.post(() -> callback.onSuccess(feed));

            } catch (IOException e) {
                deliverError(callback, "Network error: " + e.getMessage());
            } catch (JsonSyntaxException e) {
                deliverError(callback, "JSON parse error: " + e.getMessage());
            }
        });
    }

    private void deliverError(Callback callback, String message) {
        mainHandler.post(() -> callback.onError(message));
    }
}

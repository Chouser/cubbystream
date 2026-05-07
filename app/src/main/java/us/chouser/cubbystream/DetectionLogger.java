package us.chouser.cubbystream;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Writes detection feature samples to a CSV file in the app's external files
 * directory (no special permissions needed on Android 10+).
 *
 * Pull logs off the device with:
 *   adb pull /sdcard/Android/data/us.chouser.cubbystream/files/logs/
 *
 * CSV columns:
 *   timestamp_ms, energy, threshold, detector_state, volume_mode, stream_title
 *
 *   detector_state : "game" | "ads"   (what the FFT detector currently believes)
 *   volume_mode    : "auto" | "game" | "ads"  (what is actually applied)
 *                    When mode != auto, the user has manually overridden.
 *
 * Sample rate: one row per second (callers should throttle their calls).
 */
public class DetectionLogger {

    private static final String TAG = "DetectionLogger";

    // One row per second is plenty; callers enforce this via a counter.
    private static final String CSV_HEADER =
            "timestamp_ms,energy,threshold,detector_state,volume_mode,stream_title\n";

    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private BufferedWriter bw;
    private boolean open = false;

    /** Open a new log file named by current date-time. Call once per stream. */
    public void open(Context context, String streamTitle) {
        writer.execute(() -> {
            try {
                File dir = new File(
                        context.getExternalFilesDir(null), "logs");
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.e(TAG, "Could not create log dir: " + dir);
                    return;
                }

                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(new Date());
                // Sanitise stream title for filename
                String safe = streamTitle.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                File file = new File(dir, ts + "_" + safe + ".csv");

                bw = new BufferedWriter(new FileWriter(file, true));
                bw.write(CSV_HEADER);
                bw.flush();
                open = true;
                Log.i(TAG, "Logging to: " + file.getAbsolutePath());

            } catch (IOException e) {
                Log.e(TAG, "Failed to open log: " + e.getMessage());
            }
        });
    }

    /**
     * Write one sample row. Safe to call from any thread.
     *
     * @param energy        current smoothed FFT band energy
     * @param threshold     current threshold value
     * @param detectorInAds true if the FFT detector currently believes it's an ad
     * @param volumeMode    "auto", "game", or "ads"
     * @param streamTitle   current stream title (for the row, not the filename)
     */
    public void log(float energy, float threshold,
                    boolean detectorInAds, String volumeMode, String streamTitle) {
        if (!open) return;
        long now = System.currentTimeMillis();
        // Escape title in case it contains commas
        String safeTitle = "\"" + streamTitle.replace("\"", "\"\"") + "\"";
        String row = now + "," +
                String.format(Locale.US, "%.4f", energy) + "," +
                String.format(Locale.US, "%.4f", threshold) + "," +
                (detectorInAds ? "ads" : "game") + "," +
                volumeMode + "," +
                safeTitle + "\n";

        writer.execute(() -> {
            try {
                if (bw != null) { bw.write(row); bw.flush(); }
            } catch (IOException e) {
                Log.e(TAG, "Write error: " + e.getMessage());
            }
        });
    }

    /** Close the log file. Call when the stream stops. */
    public void close() {
        open = false;
        writer.execute(() -> {
            try {
                if (bw != null) { bw.close(); bw = null; }
            } catch (IOException e) {
                Log.e(TAG, "Close error: " + e.getMessage());
            }
        });
    }

    public boolean isOpen() { return open; }
}

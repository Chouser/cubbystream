package us.chouser.cubbystream;

import android.content.Context;
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

public class DetectionLogger {
    private static final String TAG = "DetectionLogger";

    private static final String CSV_HEADER =
            "timestamp_ms,total_volume,energy,flatness,flux,papr,zcr,low_band,mid_band,high_band," +
            "threshold,detector_state,volume_mode,stream_title\n";

    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private BufferedWriter bw;
    private boolean open = false;

    public void open(Context context, String streamTitle) {
        writer.execute(() -> {
            try {
                File dir = new File(context.getExternalFilesDir(null), "logs");
                if (!dir.exists() && !dir.mkdirs()) return;

                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String safe = streamTitle.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                File file = new File(dir, ts + "_" + safe + ".csv");

                bw = new BufferedWriter(new FileWriter(file, true));
                bw.write(CSV_HEADER);
                bw.flush();
                open = true;
            } catch (IOException e) {
                Log.e(TAG, "Failed to open log: " + e.getMessage());
            }
        });
    }

    public void log(float totalVolume, float energy, float flatness, float flux, float papr, float zcr,
                    float lowBand, float midBand, float highBand,
                    float threshold, boolean detectorInAds, String volumeMode, String streamTitle) {
        if (!open) return;
        long now = System.currentTimeMillis();
        String safeTitle = "\"" + streamTitle.replace("\"", "\"\"") + "\"";

        String row = String.format(Locale.US, 
            "%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%s,%s\n",
            now, totalVolume, energy, flatness, flux, papr, zcr, lowBand, midBand, highBand,
            threshold, (detectorInAds ? "ads" : "game"), volumeMode, safeTitle);

        writer.execute(() -> {
            try {
                if (bw != null) { bw.write(row); bw.flush(); }
            } catch (IOException e) {
                Log.e(TAG, "Write error: " + e.getMessage());
            }
        });
    }

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

    /** Returns the log directory File. */
    public static File getLogDir(Context context) {
        return new File(context.getExternalFilesDir(null), "logs");
    }
}
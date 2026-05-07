package us.chouser.cubbystream;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

public class SettingsSheet extends BottomSheetDialogFragment {

    /** Callback so MainActivity can react to Feed URL / threshold / volume changes. */
    public interface Listener {
        void onFeedUrlChanged(String newUrl);
        void onThresholdChanged(int threshold);
        void onAdsVolumePctChanged(int pct);
    }

    private AppPrefs prefs;
    private Listener listener;

    // Views
    private EditText  editFeedUrl;
    private Button    btnReload;
    private Button    btnResetUrl;

    private SeekBar   seekThreshold;
    private TextView  textThresholdVal;
    private Button    btnResetThreshold;

    private SeekBar   seekAdsVolume;
    private TextView  textAdsVolumeVal;
    private Button    btnResetAdsVolume;

    private TextView  textLogPath;
    private Button    btnShareLog;
    private Button    btnOpenLogDir;
    private Button    btnDeleteLogs;

    // Slider ranges
    private static final int THRESHOLD_MIN = 100;
    private static final int THRESHOLD_MAX = 350;

    public static SettingsSheet newInstance() {
        return new SettingsSheet();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = new AppPrefs(requireContext());

        bindViews(view);
        populateViews();
        setupListeners();
    }

    private void bindViews(View v) {
        editFeedUrl       = v.findViewById(R.id.edit_feed_url);
        btnReload         = v.findViewById(R.id.btn_reload_feed);
        btnResetUrl       = v.findViewById(R.id.btn_reset_url);

        seekThreshold     = v.findViewById(R.id.seek_threshold);
        textThresholdVal  = v.findViewById(R.id.text_threshold_val);
        btnResetThreshold = v.findViewById(R.id.btn_reset_threshold);

        seekAdsVolume     = v.findViewById(R.id.seek_ads_volume);
        textAdsVolumeVal  = v.findViewById(R.id.text_ads_volume_val);
        btnResetAdsVolume = v.findViewById(R.id.btn_reset_ads_volume);

        textLogPath       = v.findViewById(R.id.text_log_path);
        btnShareLog       = v.findViewById(R.id.btn_share_log);
        btnOpenLogDir     = v.findViewById(R.id.btn_open_log_dir);
        btnDeleteLogs     = v.findViewById(R.id.btn_delete_logs);
    }

    private void populateViews() {
        // Feed URL
        editFeedUrl.setText(prefs.getFeedUrl());

        // Threshold slider: SeekBar range is 0..(MAX-MIN), displayed as MIN..MAX
        seekThreshold.setMax(THRESHOLD_MAX - THRESHOLD_MIN);
        int thresholdProgress = prefs.getThreshold() - THRESHOLD_MIN;
        seekThreshold.setProgress(thresholdProgress);
        textThresholdVal.setText(String.valueOf(prefs.getThreshold()));

        // Ads volume slider: 0..100
        seekAdsVolume.setMax(100);
        seekAdsVolume.setProgress(prefs.getAdsVolumePct());
        textAdsVolumeVal.setText(prefs.getAdsVolumePct() + "%");

        // Log path
        File logDir = getLogDir();
        textLogPath.setText(logDir != null ? logDir.getAbsolutePath() : "unavailable");

        updateLogButtons();
    }

    private void setupListeners() {
        // ---- Feed URL ----
        editFeedUrl.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                // Enable reload only when URL is non-empty
                btnReload.setEnabled(s.length() > 0);
            }
        });

        btnReload.setOnClickListener(v -> {
            String url = editFeedUrl.getText().toString().trim();
            if (url.isEmpty()) return;
            prefs.setFeedUrl(url);
            if (listener != null) listener.onFeedUrlChanged(url);
            dismiss();
        });

        btnResetUrl.setOnClickListener(v -> {
            editFeedUrl.setText(AppPrefs.DEFAULT_FEED_URL);
            prefs.setFeedUrl(AppPrefs.DEFAULT_FEED_URL);
            if (listener != null) listener.onFeedUrlChanged(AppPrefs.DEFAULT_FEED_URL);
        });

        // ---- Threshold ----
        seekThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = THRESHOLD_MIN + progress;
                textThresholdVal.setText(String.valueOf(value));
                if (fromUser) {
                    prefs.setThreshold(value);
                    if (listener != null) listener.onThresholdChanged(value);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnResetThreshold.setOnClickListener(v -> {
            int def = AppPrefs.DEFAULT_THRESHOLD;
            seekThreshold.setProgress(def - THRESHOLD_MIN);
            prefs.setThreshold(def);
            if (listener != null) listener.onThresholdChanged(def);
        });

        // ---- Ads volume ----
        seekAdsVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                textAdsVolumeVal.setText(progress + "%");
                if (fromUser) {
                    prefs.setAdsVolumePct(progress);
                    if (listener != null) listener.onAdsVolumePctChanged(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnResetAdsVolume.setOnClickListener(v -> {
            int def = AppPrefs.DEFAULT_ADS_VOLUME_PCT;
            seekAdsVolume.setProgress(def);
            prefs.setAdsVolumePct(def);
            if (listener != null) listener.onAdsVolumePctChanged(def);
        });

        // ---- Log file operations ----
        btnShareLog.setOnClickListener(v -> shareLatestLog());
        btnOpenLogDir.setOnClickListener(v -> openLogDirectory());
        btnDeleteLogs.setOnClickListener(v -> confirmDeleteLogs());
    }

    // =========================================================================
    // Log directory helpers
    // =========================================================================

    @Nullable
    private File getLogDir() {
        if (getContext() == null) return null;
        return DetectionLogger.getLogDir(requireContext());
    }

    @Nullable
    private File[] getLogFiles() {
        File dir = getLogDir();
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".csv"));
        if (files == null || files.length == 0) return null;
        // Sort by filename (which is yyyyMMdd_HHmmss_… so lexicographic = time order)
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    private void updateLogButtons() {
        File[] files = getLogFiles();
        boolean hasFiles = files != null && files.length > 0;
        btnShareLog.setEnabled(hasFiles);
        btnDeleteLogs.setEnabled(hasFiles);
        // Open dir button: enabled if dir exists (even if empty, user may want to browse)
        File dir = getLogDir();
        btnOpenLogDir.setEnabled(dir != null && dir.exists());
    }

    private void shareLatestLog() {
        File[] files = getLogFiles();
        if (files == null || files.length == 0) {
            Toast.makeText(requireContext(), "No log files found", Toast.LENGTH_SHORT).show();
            return;
        }
        File latest = files[files.length - 1]; // last = most recent (sorted ascending)
        try {
            Uri uri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    latest);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "CubbyStream log: " + latest.getName());
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share log file"));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Could not share file: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openLogDirectory() {
        File dir = getLogDir();
        if (dir == null || !dir.exists()) {
            Toast.makeText(requireContext(), "Log directory not found", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = Uri.fromFile(dir);

        // Try ACTION_VIEW on the directory URI first
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "resource/folder");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // Fall back to Files by Google
            try {
                Intent files = requireContext().getPackageManager()
                        .getLaunchIntentForPackage("com.google.android.documentsui");
                if (files != null) {
                    startActivity(files);
                } else {
                    Toast.makeText(requireContext(),
                            "No file browser found. Path:\n" + dir.getAbsolutePath(),
                            Toast.LENGTH_LONG).show();
                }
            } catch (Exception ex) {
                Toast.makeText(requireContext(),
                        "Path: " + dir.getAbsolutePath(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void confirmDeleteLogs() {
        File[] files = getLogFiles();
        int count = files != null ? files.length : 0;
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete log files")
                .setMessage("Delete all " + count + " log file" + (count == 1 ? "" : "s") + "?")
                .setPositiveButton("Delete", (d, w) -> deleteLogs())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteLogs() {
        File[] files = getLogFiles();
        if (files == null) return;
        int deleted = 0;
        for (File f : files) {
            if (f.delete()) deleted++;
        }
        Toast.makeText(requireContext(),
                "Deleted " + deleted + " log file" + (deleted == 1 ? "" : "s"),
                Toast.LENGTH_SHORT).show();
        updateLogButtons();
    }
}

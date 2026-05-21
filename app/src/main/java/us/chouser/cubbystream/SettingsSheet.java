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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

public class SettingsSheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onFeedUrlChanged(String newUrl);
        void onDetectionAlgorithmChanged(String algorithmKey);
        void onThresholdChanged(int threshold);
        void onAdsVolumePctChanged(int pct);
        void onPollIntervalChanged(int sec);
        void onApiDelayChanged(int sec);
        void onAutoStartAudioChanged(boolean enabled);
        void onLoggingEnabledChanged(boolean enabled);
    }

    private AppPrefs prefs;
    private Listener listener;

    // Views — algorithm
    private Spinner      spinnerAlgorithm;
    private LinearLayout layoutThresholdRow;

    // Views — audio
    private EditText editFeedUrl;
    private Button   btnReload, btnResetUrl;
    private SeekBar  seekThreshold;
    private TextView textThresholdVal;
    private Button   btnResetThreshold;
    private SeekBar  seekAdsVolume;
    private TextView textAdsVolumeVal;
    private Button   btnResetAdsVolume;

    // Views — gameday
    private SeekBar  seekPollInterval;
    private TextView textPollIntervalVal;
    private Button   btnResetPollInterval;
    private SeekBar  seekApiDelay;
    private TextView textApiDelayVal;
    private Button   btnResetApiDelay;
    private Switch   switchAutoStart;
    private Switch   switchLogging;

    // Views — logs
    private TextView textLogPath;
    private Button   btnShareLog, btnOpenLogDir, btnDeleteLogs;

    // Slider ranges
    private static final int THRESHOLD_MIN     = 100;
    private static final int THRESHOLD_MAX     = 350;
    private static final int POLL_MIN          = 1;
    private static final int POLL_MAX          = 30;
    private static final int DELAY_MIN         = 0;
    private static final int DELAY_MAX         = 120; // see GamedayController.MAX_HISTORY_MS

    public static SettingsSheet newInstance() { return new SettingsSheet(); }

    public void setListener(Listener l) { this.listener = l; }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_settings, container, false);
    }

    @Override
    public void onStart() {
        super.onStart();
        // Force the sheet to fully expand regardless of screen height / orientation.
        // Without this it uses the default peek height (~50% screen), which is
        // too short to be usable in landscape on phones or TV.
        View sheet = getDialog().findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
        }
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
        spinnerAlgorithm     = v.findViewById(R.id.spinner_algorithm);
        layoutThresholdRow   = v.findViewById(R.id.layout_threshold_row);
        editFeedUrl          = v.findViewById(R.id.edit_feed_url);
        btnReload            = v.findViewById(R.id.btn_reload_feed);
        btnResetUrl          = v.findViewById(R.id.btn_reset_url);
        seekThreshold        = v.findViewById(R.id.seek_threshold);
        textThresholdVal     = v.findViewById(R.id.text_threshold_val);
        btnResetThreshold    = v.findViewById(R.id.btn_reset_threshold);
        seekAdsVolume        = v.findViewById(R.id.seek_ads_volume);
        textAdsVolumeVal     = v.findViewById(R.id.text_ads_volume_val);
        btnResetAdsVolume    = v.findViewById(R.id.btn_reset_ads_volume);
        seekPollInterval     = v.findViewById(R.id.seek_poll_interval);
        textPollIntervalVal  = v.findViewById(R.id.text_poll_interval_val);
        btnResetPollInterval = v.findViewById(R.id.btn_reset_poll_interval);
        seekApiDelay         = v.findViewById(R.id.seek_api_delay);
        textApiDelayVal      = v.findViewById(R.id.text_api_delay_val);
        btnResetApiDelay     = v.findViewById(R.id.btn_reset_api_delay);
        switchAutoStart      = v.findViewById(R.id.switch_auto_start);
        switchLogging        = v.findViewById(R.id.switch_logging);
        textLogPath          = v.findViewById(R.id.text_log_path);
        btnShareLog          = v.findViewById(R.id.btn_share_log);
        btnOpenLogDir        = v.findViewById(R.id.btn_open_log_dir);
        btnDeleteLogs        = v.findViewById(R.id.btn_delete_logs);
    }

    private void populateViews() {
        // Algorithm spinner — derived from DetectorRegistry so no parallel arrays to maintain
        String[] labels = DetectorRegistry.ALL.stream()
                .map(DetectorRegistry.Entry::displayName)
                .toArray(String[]::new);
        ArrayAdapter<String> algAdapter = new ArrayAdapter<>(
                requireContext(), R.layout.spinner_item, labels);
        algAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAlgorithm.setAdapter(algAdapter);
        String savedAlg = prefs.getDetectionAlgorithm();
        for (int i = 0; i < DetectorRegistry.ALL.size(); i++) {
            if (DetectorRegistry.ALL.get(i).key.equals(savedAlg)) {
                spinnerAlgorithm.setSelection(i, false);
                break;
            }
        }
        updateThresholdRowVisibility(savedAlg);

        editFeedUrl.setText(prefs.getFeedUrl());

        seekThreshold.setMax(THRESHOLD_MAX - THRESHOLD_MIN);
        seekThreshold.setProgress(prefs.getThreshold() - THRESHOLD_MIN);
        textThresholdVal.setText(String.valueOf(prefs.getThreshold()));

        seekAdsVolume.setMax(100);
        seekAdsVolume.setProgress(prefs.getAdsVolumePct());
        textAdsVolumeVal.setText(prefs.getAdsVolumePct() + "%");

        seekPollInterval.setMax(POLL_MAX - POLL_MIN);
        seekPollInterval.setProgress(prefs.getPollInterval() - POLL_MIN);
        textPollIntervalVal.setText(prefs.getPollInterval() + "s");

        seekApiDelay.setMax(DELAY_MAX - DELAY_MIN);
        seekApiDelay.setProgress(prefs.getApiDelay() - DELAY_MIN);
        textApiDelayVal.setText(prefs.getApiDelay() + "s");

        switchAutoStart.setChecked(prefs.getAutoStartAudio());
        switchLogging.setChecked(prefs.getLoggingEnabled());

        File logDir = DetectionLogger.getLogDir(requireContext());
        textLogPath.setText(logDir != null ? logDir.getAbsolutePath() : "unavailable");
        updateLogButtons();
    }

    private void setupListeners() {
        // Algorithm picker
        spinnerAlgorithm.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean firstCall = true;
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (firstCall) { firstCall = false; return; }
                String key = DetectorRegistry.ALL.get(pos).key;
                prefs.setDetectionAlgorithm(key);
                updateThresholdRowVisibility(key);
                if (listener != null) listener.onDetectionAlgorithmChanged(key);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Feed URL
        editFeedUrl.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
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

        // Threshold
        seekThreshold.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int val = THRESHOLD_MIN + progress;
                textThresholdVal.setText(String.valueOf(val));
                if (fromUser) { prefs.setThreshold(val); if (listener != null) listener.onThresholdChanged(val); }
            }
        });
        btnResetThreshold.setOnClickListener(v -> {
            int def = AppPrefs.DEFAULT_THRESHOLD;
            seekThreshold.setProgress(def - THRESHOLD_MIN);
            prefs.setThreshold(def);
            if (listener != null) listener.onThresholdChanged(def);
        });

        // Ads volume
        seekAdsVolume.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                textAdsVolumeVal.setText(progress + "%");
                if (fromUser) { prefs.setAdsVolumePct(progress); if (listener != null) listener.onAdsVolumePctChanged(progress); }
            }
        });
        btnResetAdsVolume.setOnClickListener(v -> {
            int def = AppPrefs.DEFAULT_ADS_VOLUME_PCT;
            seekAdsVolume.setProgress(def);
            prefs.setAdsVolumePct(def);
            if (listener != null) listener.onAdsVolumePctChanged(def);
        });

        // Poll interval
        seekPollInterval.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int val = POLL_MIN + progress;
                textPollIntervalVal.setText(val + "s");
                if (fromUser) { prefs.setPollInterval(val); if (listener != null) listener.onPollIntervalChanged(val); }
            }
        });
        btnResetPollInterval.setOnClickListener(v -> {
            int def = AppPrefs.DEFAULT_POLL_INTERVAL;
            seekPollInterval.setProgress(def - POLL_MIN);
            prefs.setPollInterval(def);
            if (listener != null) listener.onPollIntervalChanged(def);
        });

        // API delay
        seekApiDelay.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int val = DELAY_MIN + progress;
                textApiDelayVal.setText(val + "s");
                if (fromUser) { prefs.setApiDelay(val); if (listener != null) listener.onApiDelayChanged(val); }
            }
        });
        btnResetApiDelay.setOnClickListener(v -> {
            int def = AppPrefs.DEFAULT_API_DELAY;
            seekApiDelay.setProgress(def - DELAY_MIN);
            prefs.setApiDelay(def);
            if (listener != null) listener.onApiDelayChanged(def);
        });

        // Log operations
        btnShareLog.setOnClickListener(v -> shareLatestLog());
        btnOpenLogDir.setOnClickListener(v -> openLogDirectory());
        btnDeleteLogs.setOnClickListener(v -> confirmDeleteLogs());

        // Auto-start
        switchAutoStart.setOnCheckedChangeListener((btn, checked) -> {
            prefs.setAutoStartAudio(checked);
            if (listener != null) listener.onAutoStartAudioChanged(checked);
        });

        switchLogging.setOnCheckedChangeListener((btn, checked) -> {
            prefs.setLoggingEnabled(checked);
            if (listener != null) listener.onLoggingEnabledChanged(checked);
        });
    }

    private void updateThresholdRowVisibility(String algorithmKey) {
        boolean show = DetectorRegistry.forKey(algorithmKey).hasThreshold();
        if (layoutThresholdRow != null) {
            layoutThresholdRow.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    // =========================================================================
    // Log helpers
    // =========================================================================

    @Nullable
    private File[] getLogFiles() {
        File dir = DetectionLogger.getLogDir(requireContext());
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".csv"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    private void updateLogButtons() {
        File[] files = getLogFiles();
        boolean hasFiles = files != null && files.length > 0;
        btnShareLog.setEnabled(hasFiles);
        btnDeleteLogs.setEnabled(hasFiles);
        File dir = DetectionLogger.getLogDir(requireContext());
        btnOpenLogDir.setEnabled(dir != null && dir.exists());
    }

    private void shareLatestLog() {
        File[] files = getLogFiles();
        if (files == null || files.length == 0) {
            Toast.makeText(requireContext(), "No log files found", Toast.LENGTH_SHORT).show();
            return;
        }
        File latest = files[files.length - 1];
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
            Toast.makeText(requireContext(), "Could not share: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openLogDirectory() {
        File dir = DetectionLogger.getLogDir(requireContext());
        if (dir == null || !dir.exists()) {
            Toast.makeText(requireContext(), "Log directory not found", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(dir), "resource/folder");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                Intent files = requireContext().getPackageManager()
                        .getLaunchIntentForPackage("com.google.android.documentsui");
                if (files != null) { startActivity(files); return; }
            } catch (Exception ignored) {}
            Toast.makeText(requireContext(), "Path: " + dir.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDeleteLogs() {
        File[] files = getLogFiles();
        int count = files != null ? files.length : 0;
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete log files")
                .setMessage("Delete all " + count + " log file" + (count == 1 ? "" : "s") + "?")
                .setPositiveButton("Delete", (d, w) -> {
                    if (files == null) return;
                    int deleted = 0;
                    for (File f : files) if (f.delete()) deleted++;
                    Toast.makeText(requireContext(),
                            "Deleted " + deleted + " log file" + (deleted == 1 ? "" : "s"),
                            Toast.LENGTH_SHORT).show();
                    updateLogButtons();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // Convenience base class to avoid re-implementing empty methods everywhere
    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar sb) {}
        @Override public void onStopTrackingTouch(SeekBar sb) {}
    }
}

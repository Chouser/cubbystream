package us.chouser.cubbystream;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity
        implements PlaybackService.PlaybackListener,
                   CrowdNoiseDetector.Listener,
                   SettingsSheet.Listener {

    private static final int REQ_NOTIFICATION = 101;

    private AppPrefs prefs;

    // ---- Volume mode ----
    private enum VolumeMode { AUTO, GAME, ADS }
    private VolumeMode volumeMode = VolumeMode.AUTO;

    // ---- Logging ----
    private final DetectionLogger logger = new DetectionLogger();
    private int logFrameCount = 0;

    // ---- Views ----
    private Spinner      spinnerStream;
    private Button       btnStop;
    private Button       btnPlay;
    private TextView     textStatus;
    private LinearLayout layoutInfoPanel;
    private TextView     textEnergyLevel;
    private TextView     textModeIndicator;
    private TextView     textThreshold;
    private ProgressBar  progressEnergy;
    private Button       btnPause;
    private Button       btnResume;
    private Button       btnModeGame;
    private Button       btnModeAds;
    private Button       btnModeAuto;

    // ---- Feed / spinner state ----
    private List<StreamItem> feedItems = new ArrayList<>();
    private int selectedPosition = 0;
    // Suppress the initial synthetic onItemSelected that fires when the adapter is set
    private boolean spinnerReady = false;

    // ---- Service ----
    private PlaybackService service;
    private boolean bound = false;

    // ---- Playback state ----
    private enum PlayState { STOPPED, PLAYING, PAUSED }
    private PlayState playState = PlayState.STOPPED;

    // ---- Current stream info (for info panel and logger) ----
    private String currentTitle    = "";

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).getService();
            service.setPlaybackListener(MainActivity.this);
            service.setCrowdNoiseListener(MainActivity.this);
            bound = true;

            // Sync UI to current service state — do NOT auto-play.
            if (service.isPlaying()) {
                playState = PlayState.PLAYING;
            } else if (service.hasActiveStream()) {
                playState = PlayState.PAUSED;
            } else {
                playState = PlayState.STOPPED;
            }
            updatePlaybackUi();
            applyVolumeMode(VolumeMode.AUTO);

            // Apply persisted settings to service
            if (prefs != null) {
                service.setThreshold(prefs.getThreshold());
                service.setAdsVolumePct(prefs.getAdsVolumePct());
            }

            // If Play was requested before the service was ready, start now.
            if (pendingPlay) {
                pendingPlay = false;
                if (!feedItems.isEmpty()) doPlayStream(feedItems.get(selectedPosition));
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            playState = PlayState.STOPPED;
            runOnUiThread(() -> {
                updatePlaybackUi();
                layoutInfoPanel.setVisibility(View.GONE);
            });
        }
    };

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupClickListeners();
        requestNotificationPermission();

        prefs = new AppPrefs(this);

        // Bind without starting — service starts only when user hits Play.
        Intent si = new Intent(this, PlaybackService.class);
        bindService(si, connection, BIND_AUTO_CREATE);

        loadFeed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bound && service != null) {
            service.setPlaybackListener(this);
            service.setCrowdNoiseListener(this);
            if (service.isPlaying()) {
                playState = PlayState.PLAYING;
            } else if (service.hasActiveStream()) {
                playState = PlayState.PAUSED;
            } else {
                playState = PlayState.STOPPED;
            }
            updatePlaybackUi();
        }
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        logger.close();
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }

    // =========================================================================
    // View wiring
    // =========================================================================

    private ImageButton btnSettings;

    private void bindViews() {
        spinnerStream      = findViewById(R.id.spinner_stream);
        btnStop            = findViewById(R.id.btn_stop);
        btnPlay            = findViewById(R.id.btn_play);
        textStatus         = findViewById(R.id.text_status);
        layoutInfoPanel    = findViewById(R.id.layout_info_panel);
        textEnergyLevel    = findViewById(R.id.text_energy_level);
        textModeIndicator  = findViewById(R.id.text_mode_indicator);
        textThreshold      = findViewById(R.id.text_threshold);
        progressEnergy     = findViewById(R.id.progress_energy);
        btnPause           = findViewById(R.id.btn_pause);
        btnResume          = findViewById(R.id.btn_resume);
        btnModeGame        = findViewById(R.id.btn_mode_game);
        btnModeAds         = findViewById(R.id.btn_mode_ads);
        btnModeAuto        = findViewById(R.id.btn_mode_auto);
        btnSettings        = findViewById(R.id.btn_settings);
    }

    private void setupClickListeners() {
        btnStop.setOnClickListener(v -> stopStream());

        btnPlay.setOnClickListener(v -> {
            if (playState == PlayState.PAUSED && bound && service != null) {
                // Resume existing paused stream
                service.resume();
            } else {
                // Start fresh with the selected stream
                startSelectedStream();
            }
        });

        btnResume.setOnClickListener(v -> {
            if (playState == PlayState.PAUSED && bound && service != null) {
                // Resume existing paused stream
                service.resume();
            } else {
                // Start fresh with the selected stream
                startSelectedStream();
            }
        });

        btnPause.setOnClickListener(v -> {
            if (bound && service != null) service.pause();
        });

        findViewById(R.id.btn_skip_to_live).setOnClickListener(v -> {
            if (bound && service != null) service.skipToLive();
        });

        btnModeGame.setOnClickListener(v -> applyVolumeMode(VolumeMode.GAME));
        btnModeAds.setOnClickListener(v  -> applyVolumeMode(VolumeMode.ADS));
        btnModeAuto.setOnClickListener(v -> applyVolumeMode(VolumeMode.AUTO));

        btnSettings.setOnClickListener(v -> {
            SettingsSheet sheet = SettingsSheet.newInstance();
            sheet.setListener(this);
            sheet.show(getSupportFragmentManager(), "settings");
        });

        spinnerStream.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!spinnerReady) return; // ignore the initial synthetic callback
                if (position == selectedPosition) return;
                selectedPosition = position;
                // Changing the stream selection stops any current stream
                if (playState != PlayState.STOPPED) stopStream();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // =========================================================================
    // Feed loading
    // =========================================================================

    private void loadFeed() {
        showStatus("Loading streams…");
        String url = (prefs != null) ? prefs.getFeedUrl() : AppPrefs.DEFAULT_FEED_URL;
        new FeedFetcher(url).fetch(new FeedFetcher.Callback() {
            @Override
            public void onSuccess(StreamFeed feed) {
                textStatus.setVisibility(View.GONE);
                List<StreamItem> streams = feed.getStreams();
                feedItems = (streams != null) ? streams : new ArrayList<>();
                if (feedItems.isEmpty()) {
                    showStatus("No streams available right now.");
                    return;
                }
                populateSpinner();
                if (feed.getUpdated() != null && !feed.getUpdated().isEmpty()) {
                    Toast.makeText(MainActivity.this,
                            "Updated: " + feed.getUpdated(), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                showStatus("Could not load streams:\n" + message);
            }
        });
    }

    private void populateSpinner() {
        List<String> titles = new ArrayList<>();
        for (StreamItem item : feedItems) titles.add(item.getTitle());

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item, titles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        spinnerReady = false;
        spinnerStream.setAdapter(adapter);
        spinnerStream.setSelection(0, false);
        selectedPosition = 0;
        spinnerReady = true;
    }

    private void showStatus(String msg) {
        textStatus.setText(msg);
        textStatus.setVisibility(View.VISIBLE);
    }

    // =========================================================================
    // Playback control
    // =========================================================================

    // Set to true when Play is tapped before the service connection is established.
    private boolean pendingPlay = false;

    private void startSelectedStream() {
        if (feedItems.isEmpty()) return;
        StreamItem item = feedItems.get(selectedPosition);
        if (!item.isValid()) {
            Toast.makeText(this, "Invalid stream URL", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent si = new Intent(this, PlaybackService.class);
        ContextCompat.startForegroundService(this, si);
        if (!bound) {
            bindService(si, connection, BIND_AUTO_CREATE);
            pendingPlay = true; // onServiceConnected will call doPlayStream
        } else {
            doPlayStream(item);
        }
    }

    private void doPlayStream(StreamItem item) {
        currentTitle    = item.getTitle();
        service.playStream(item.getUrl(), item.getTitle(), item.getType());
        logger.open(this, currentTitle);
        layoutInfoPanel.setVisibility(View.VISIBLE);
    }

    private void stopStream() {
        logger.close();
        if (bound && service != null) service.stopStream();
        playState = PlayState.STOPPED;
        updatePlaybackUi();
        layoutInfoPanel.setVisibility(View.GONE);
    }

    // =========================================================================
    // Playback UI state
    // =========================================================================

    private void updatePlaybackUi() {
        boolean playing = playState == PlayState.PLAYING;
        boolean stopped = playState == PlayState.STOPPED;
        boolean paused  = playState == PlayState.PAUSED;

        // Stop: enabled when stream exists (playing or paused)
        btnStop.setEnabled(!stopped);
        // Pause: enabled only when playing
        btnPause.setEnabled(playing);
        // Play: enabled when stopped or paused (i.e. not already playing)
        btnPlay.setEnabled(!playing);
        btnResume.setEnabled(!playing);
    }

    // =========================================================================
    // Volume mode
    // =========================================================================

    private void applyVolumeMode(VolumeMode mode) {
        volumeMode = mode;
        if (service != null) {
            switch (mode) {
                case GAME:
                    service.setCommercialVolume(false);
                    service.resetDetectorCounters();
                    break;
                case ADS:
                    service.setCommercialVolume(true);
                    service.resetDetectorCounters();
                    break;
                case AUTO:
                    service.setCommercialVolume(service.detectorIsInCommercial());
                    break;
            }
        }
        updateModeButtonTints();
        updateModeIndicatorLabel();
    }

    private void updateModeButtonTints() {
        int inactive = 0xFF555555;
        btnModeGame.setBackgroundTintList(ColorStateList.valueOf(
                volumeMode == VolumeMode.GAME ? 0xFF2E7D32 : inactive));
        btnModeAds.setBackgroundTintList(ColorStateList.valueOf(
                volumeMode == VolumeMode.ADS  ? 0xFF4A148C : inactive));
        btnModeAuto.setBackgroundTintList(ColorStateList.valueOf(
                volumeMode == VolumeMode.AUTO ? 0xFFD32F2F : inactive));
    }

    private void updateModeIndicatorLabel() {
        String label;
        switch (volumeMode) {
            case GAME: label = "Manual: Game"; break;
            case ADS:  label = "Manual: Ads";  break;
            default: // AUTO
                boolean inAd = service != null && service.detectorIsInCommercial();
                label = inAd ? "Auto: Ads" : "Auto: Game";
                break;
        }
        textModeIndicator.setText(label);
    }

    // =========================================================================
    // PlaybackService.PlaybackListener
    // =========================================================================

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        runOnUiThread(() -> {
            if (isPlaying) {
                playState = PlayState.PLAYING;
            } else if (bound && service != null && service.hasActiveStream()) {
                playState = PlayState.PAUSED;
            } else {
                playState = PlayState.STOPPED;
            }
            updatePlaybackUi();
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() ->
            Toast.makeText(this, "Playback error: " + message, Toast.LENGTH_LONG).show());
    }

    // =========================================================================
    // CrowdNoiseDetector.Listener
    // =========================================================================

    @Override
    public void onCommercialDetected() {
        if (volumeMode != VolumeMode.AUTO) return;
        if (service != null) service.setCommercialVolume(true);
        runOnUiThread(this::updateModeIndicatorLabel);
    }

    @Override
    public void onGameResumed() {
        if (volumeMode != VolumeMode.AUTO) return;
        if (service != null) service.setCommercialVolume(false);
        runOnUiThread(this::updateModeIndicatorLabel);
    }

    @Override
    public void onEnergyUpdate(float energy, float threshold) {
        if (++logFrameCount >= 4) {
            logFrameCount = 0;
            boolean detectorInAds = service != null && service.detectorIsInCommercial();
            logger.log(energy, threshold, detectorInAds,
                    volumeMode.name().toLowerCase(), currentTitle);
        }
        runOnUiThread(() -> {
            textEnergyLevel.setText(String.format("Level: %.1f", energy));
            textThreshold.setText(String.format("Thr: %.1f", threshold));
            updateModeIndicatorLabel();
            int pct = (int) Math.min((energy / threshold) * 50f, 100);
            progressEnergy.setProgress(pct);
            int color = energy >= threshold ? 0xFF2E7D32 : 0xFFB71C1C;
            progressEnergy.getProgressDrawable().setTint(color);
        });
    }

    // =========================================================================
    // SettingsSheet.Listener
    // =========================================================================

    @Override
    public void onFeedUrlChanged(String newUrl) {
        loadFeed();
    }

    @Override
    public void onThresholdChanged(int threshold) {
        if (bound && service != null) service.setThreshold(threshold);
    }

    @Override
    public void onAdsVolumePctChanged(int pct) {
        if (bound && service != null) service.setAdsVolumePct(pct);
    }

    // =========================================================================
    // Permissions
    // =========================================================================

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
    }
}

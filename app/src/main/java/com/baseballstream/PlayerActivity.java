package com.baseballstream;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class PlayerActivity extends AppCompatActivity
        implements PlaybackService.PlaybackListener,
                   CrowdNoiseDetector.Listener {

    public static final String EXTRA_URL      = "extra_url";
    public static final String EXTRA_TITLE    = "extra_title";
    public static final String EXTRA_SUBTITLE = "extra_subtitle";
    public static final String EXTRA_TYPE     = "extra_type";

    private static final int REQ_NOTIFICATION = 101;

    // ---- Volume mode ----
    /** Three mutually exclusive modes. AUTO lets the detector decide. */
    private enum VolumeMode { AUTO, GAME, ADS }
    private VolumeMode volumeMode = VolumeMode.AUTO;

    // ---- Logging ----
    private final DetectionLogger logger = new DetectionLogger();
    // onEnergyUpdate fires 4x/sec; log every 4th call = 1 sample/sec
    private int logFrameCount = 0;

    // ---- Views ----
    private TextView    textStreamTitle;
    private TextView    textStreamSubtitle;
    private TextView    textPlaybackStatus;
    private TextView    textVolumeMode;       // current mode label
    private TextView    textEnergyLevel;      // live FFT reading
    private ProgressBar progressEnergy;       // bar showing energy vs threshold
    private Button      btnPause;
    private Button      btnResume;
    private Button      btnSkipToLive;
    private Button      btnStop;
    private Button      btnModeGame;
    private Button      btnModeAds;
    private Button      btnModeAuto;

    // ---- Service ----
    private PlaybackService service;
    private boolean bound = false;

    // ---- Stream info ----
    private String streamUrl;
    private String streamTitle;
    private String streamSubtitle;
    private String streamType;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).getService();
            service.setPlaybackListener(PlayerActivity.this);
            service.setCrowdNoiseListener(PlayerActivity.this);
            bound = true;
            service.playStream(streamUrl, streamTitle, streamType);
            updatePlaybackUi(service.isPlaying());
            applyVolumeMode(VolumeMode.AUTO); // always start in Auto on a new stream
            logger.open(PlayerActivity.this, streamTitle != null ? streamTitle : "stream");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        streamUrl      = getIntent().getStringExtra(EXTRA_URL);
        streamTitle    = getIntent().getStringExtra(EXTRA_TITLE);
        streamSubtitle = getIntent().getStringExtra(EXTRA_SUBTITLE);
        streamType     = getIntent().getStringExtra(EXTRA_TYPE);

        if (streamUrl == null || streamUrl.isEmpty()) {
            Toast.makeText(this, "No stream URL provided", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        bindViews();
        setupClickListeners();
        displayStreamInfo();
        requestNotificationPermission();
        startAndBindService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bound && service != null) {
            service.setPlaybackListener(this);
            service.setCrowdNoiseListener(this);
            updatePlaybackUi(service.isPlaying());
        }
    }

    /** Back button minimises the app instead of destroying the Activity.
     *  The stream keeps playing and the user returns here via the notification
     *  or the recent-apps switcher — no need to restart the stream. */
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        logger.close();
        if (bound) { unbindService(connection); bound = false; }
        super.onDestroy();
    }

    // =========================================================================
    // View wiring
    // =========================================================================

    private void bindViews() {
        textStreamTitle    = findViewById(R.id.text_player_title);
        textStreamSubtitle = findViewById(R.id.text_player_subtitle);
        textPlaybackStatus = findViewById(R.id.text_playback_status);
        textVolumeMode     = findViewById(R.id.text_volume_mode);
        textEnergyLevel    = findViewById(R.id.text_energy_level);
        progressEnergy     = findViewById(R.id.progress_energy);
        btnPause           = findViewById(R.id.btn_pause);
        btnResume          = findViewById(R.id.btn_resume);
        btnSkipToLive      = findViewById(R.id.btn_skip_to_live);
        btnStop            = findViewById(R.id.btn_stop);
        btnModeGame        = findViewById(R.id.btn_mode_game);
        btnModeAds         = findViewById(R.id.btn_mode_ads);
        btnModeAuto        = findViewById(R.id.btn_mode_auto);
    }

    private void setupClickListeners() {
        btnPause.setOnClickListener(v -> {
            if (bound && service != null) service.pause();
        });
        btnResume.setOnClickListener(v -> {
            if (bound && service != null) service.resume();
        });
        btnSkipToLive.setOnClickListener(v -> {
            if (bound && service != null) {
                service.skipToLive();
                Toast.makeText(this, "Jumping to live…", Toast.LENGTH_SHORT).show();
            }
        });
        btnStop.setOnClickListener(v -> {
            logger.close();
            if (bound && service != null) service.stopStream();
            finish();
        });

        btnModeGame.setOnClickListener(v -> applyVolumeMode(VolumeMode.GAME));
        btnModeAds.setOnClickListener(v  -> applyVolumeMode(VolumeMode.ADS));
        btnModeAuto.setOnClickListener(v -> applyVolumeMode(VolumeMode.AUTO));
    }

    private void displayStreamInfo() {
        textStreamTitle.setText(streamTitle != null ? streamTitle : "");
        textStreamSubtitle.setText(streamSubtitle != null ? streamSubtitle : "");
    }

    // =========================================================================
    // Volume mode state machine
    // =========================================================================

    /**
     * Switch to a volume mode. In GAME and ADS the detector keeps running
     * (so the energy meter stays live) but its state-change callbacks are
     * ignored. Switching back to AUTO immediately hands control back to the
     * detector — whatever state it's currently measuring takes effect.
     */
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
                    // Apply whatever the detector currently believes
                    service.setCommercialVolume(service.detectorIsInCommercial());
                    break;
            }
        }

        updateModButtonUi();
        updateVolumeModeLabel();
    }

    private void updateModButtonUi() {
        // Highlight the active button; grey out the others
        btnModeGame.setAlpha(volumeMode == VolumeMode.GAME ? 1.0f : 0.45f);
        btnModeAds.setAlpha(volumeMode  == VolumeMode.ADS  ? 1.0f : 0.45f);
        btnModeAuto.setAlpha(volumeMode == VolumeMode.AUTO ? 1.0f : 0.45f);
    }

    private void updateVolumeModeLabel() {
        switch (volumeMode) {
            case GAME: textVolumeMode.setText("🔊 Game (manual — full volume)"); break;
            case ADS:  textVolumeMode.setText("🔇 Ads (manual — 20% volume)");  break;
            case AUTO:
                boolean inAd = service != null && service.detectorIsInCommercial();
                textVolumeMode.setText(inAd
                        ? "🤖 Auto → Ads detected"
                        : "🤖 Auto → Game detected");
                break;
        }
    }

    // =========================================================================
    // PlaybackService.PlaybackListener
    // =========================================================================

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        runOnUiThread(() -> updatePlaybackUi(isPlaying));
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> Toast.makeText(this,
                "Playback error: " + message, Toast.LENGTH_LONG).show());
    }

    private void updatePlaybackUi(boolean isPlaying) {
        textPlaybackStatus.setText(isPlaying ? "▶  Playing" : "⏸  Paused");
        btnPause.setEnabled(isPlaying);
        btnResume.setEnabled(!isPlaying);
        btnSkipToLive.setEnabled(!isPlaying);
    }

    // =========================================================================
    // CrowdNoiseDetector.Listener
    // =========================================================================

    @Override
    public void onCommercialDetected() {
        if (volumeMode != VolumeMode.AUTO) return;
        if (service != null) service.setCommercialVolume(true);
        runOnUiThread(this::updateVolumeModeLabel);
    }

    @Override
    public void onGameResumed() {
        if (volumeMode != VolumeMode.AUTO) return;
        if (service != null) service.setCommercialVolume(false);
        runOnUiThread(this::updateVolumeModeLabel);
    }

    /**
     * Called ~10x/sec from the detector with the current smoothed energy.
     * Updates the live level meter regardless of mode — so the user can
     * always see where the signal is relative to the threshold.
     */
    @Override
    public void onEnergyUpdate(float energy, float threshold) {
        // Log at 1x/sec (every 4th call at 4x/sec update rate)
        if (++logFrameCount >= 4) {
            logFrameCount = 0;
            boolean detectorInAds = service != null && service.detectorIsInCommercial();
            String modeStr = volumeMode.name().toLowerCase();
            logger.log(energy, threshold, detectorInAds, modeStr,
                    streamTitle != null ? streamTitle : "");
        }
        runOnUiThread(() -> {
            // Display raw values for calibration
            textEnergyLevel.setText(String.format(
                    "Level: %.4f  |  Threshold: %.4f", energy, threshold));

            // Progress bar: fill proportional to energy/threshold, capped at 200%
            // so the bar hits full at threshold and overflows visibly during game play.
            int pct = (int) Math.min((energy / threshold) * 50f, 100);
            progressEnergy.setProgress(pct);

            // Tint the bar: green = above threshold (crowd), red = below (ads/silence)
            int color = energy >= threshold
                    ? 0xFF2E7D32   // green
                    : 0xFFB71C1C;  // red
            progressEnergy.getProgressDrawable().setTint(color);
        });
    }

    // =========================================================================
    // Service binding
    // =========================================================================

    private void startAndBindService() {
        Intent si = new Intent(this, PlaybackService.class);
        ContextCompat.startForegroundService(this, si);
        bindService(si, connection, BIND_AUTO_CREATE);
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
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
    }
}

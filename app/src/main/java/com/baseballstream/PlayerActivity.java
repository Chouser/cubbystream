package com.baseballstream;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.content.res.ColorStateList;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.Player;


public class PlayerActivity extends AppCompatActivity
        implements PlaybackService.PlaybackListener,
                   CrowdNoiseDetector.Listener {

    public static final String EXTRA_URL      = "extra_url";
    public static final String EXTRA_TITLE    = "extra_title";
    public static final String EXTRA_SUBTITLE = "extra_subtitle";
    public static final String EXTRA_TYPE     = "extra_type";

    private static final int REQ_NOTIFICATION = 101;

    // ---- Volume mode ----
    private enum VolumeMode { AUTO, GAME, ADS }
    private VolumeMode volumeMode = VolumeMode.AUTO;

    // ---- Logging ----
    private final DetectionLogger logger = new DetectionLogger();
    private int logFrameCount = 0;

    // ---- Live offset polling ----
    // Runs every second to update the live offset display independently
    // of the energy update rate. Continues correctly while paused.
    private final Handler offsetHandler = new Handler(Looper.getMainLooper());
    private final Runnable offsetUpdater = new Runnable() {
        @Override public void run() {
            updateLiveOffsetDisplay();
            offsetHandler.postDelayed(this, 1000);
        }
    };

    // ---- Views ----
    private TextView    textStreamTitle;
    private TextView    textStreamSubtitle;
    private TextView    textPlaybackStatus;
    private TextView    textLiveOffset;
    private TextView    textVolumeMode;
    private TextView    textEnergyLevel;
    private ProgressBar progressEnergy;
    private Button      btnPause;
    private Button      btnResume;
    private Button      btnRewind;
    private Button      btnSkipToLive;
    private Button      btnStop;
    private LinearLayout layoutSeekRow;
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

    // ---- Player listener for seek/live changes ----
    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPositionDiscontinuity(
                Player.PositionInfo oldPos, Player.PositionInfo newPos, int reason) {
            runOnUiThread(() -> updateLiveOffsetDisplay());
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).getService();
            service.setPlaybackListener(PlayerActivity.this);
            service.setCrowdNoiseListener(PlayerActivity.this);
            if (service.getPlayer() != null)
                service.getPlayer().addListener(playerListener);
            bound = true;
            service.playStream(streamUrl, streamTitle, streamType);
            updatePlaybackUi(service.isPlaying());
            applyVolumeMode(VolumeMode.AUTO);
            logger.open(PlayerActivity.this, streamTitle != null ? streamTitle : "stream");
            offsetHandler.post(offsetUpdater);
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
            offsetHandler.post(offsetUpdater);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        offsetHandler.removeCallbacks(offsetUpdater);
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        offsetHandler.removeCallbacks(offsetUpdater);
        logger.close();
        if (bound) {
            if (service != null && service.getPlayer() != null)
                service.getPlayer().removeListener(playerListener);
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }

    // =========================================================================
    // View wiring
    // =========================================================================

    private void bindViews() {
        textStreamTitle    = findViewById(R.id.text_player_title);
        textStreamSubtitle = findViewById(R.id.text_player_subtitle);
        textPlaybackStatus = findViewById(R.id.text_playback_status);
        textLiveOffset     = findViewById(R.id.text_live_offset);
        textVolumeMode     = findViewById(R.id.text_volume_mode);
        textEnergyLevel    = findViewById(R.id.text_energy_level);
        progressEnergy     = findViewById(R.id.progress_energy);
        btnPause           = findViewById(R.id.btn_pause);
        btnResume          = findViewById(R.id.btn_resume);
        btnRewind          = findViewById(R.id.btn_rewind);
        btnSkipToLive      = findViewById(R.id.btn_skip_to_live);
        btnStop            = findViewById(R.id.btn_stop);
        layoutSeekRow      = findViewById(R.id.layout_seek_row);
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
        btnRewind.setOnClickListener(v -> {
            if (bound && service != null) service.rewindFifteenSeconds();
        });
        btnSkipToLive.setOnClickListener(v -> {
            if (bound && service != null) service.skipToLive();
        });
        btnStop.setOnClickListener(v -> {
            logger.close();
            offsetHandler.removeCallbacks(offsetUpdater);
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
    // Live offset display
    // =========================================================================

    private void updateLiveOffsetDisplay() {
        if (service == null) { textLiveOffset.setText(""); return; }

        if (!service.isLiveStream()) {
            textLiveOffset.setText("");
            return;
        }

        long offsetMs = service.getLiveOffsetMs();
        if (offsetMs < 0) {
            textLiveOffset.setText("live offset: —");
            return;
        }

        long totalSecs = offsetMs / 1000;
        if (totalSecs < 5) {
            textLiveOffset.setText("● Live");
        } else if (totalSecs < 60) {
            textLiveOffset.setText(totalSecs + "s behind");
        } else {
            long mins = totalSecs / 60;
            long secs = totalSecs % 60;
            textLiveOffset.setText(String.format("%dm %02ds behind", mins, secs));
        }
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
        updateVolumeModeLabel();
    }

    private void updateModeButtonBorders() {
        int inactive = 0xFF555555;
        btnModeGame.setBackgroundTintList(ColorStateList.valueOf(
                volumeMode == VolumeMode.GAME ? 0xFF2E7D32 : inactive));
        btnModeAds.setBackgroundTintList(ColorStateList.valueOf(
                volumeMode == VolumeMode.ADS  ? 0xFF4A148C : inactive));
        btnModeAuto.setBackgroundTintList(ColorStateList.valueOf(
                volumeMode == VolumeMode.AUTO ? 0xFFD32F2F : inactive));
    }

    private void updateVolumeModeLabel() {
        switch (volumeMode) {
            case GAME: textVolumeMode.setText("🔊 Game (manual — full volume)"); break;
            case ADS:  textVolumeMode.setText("🔇 Ads (manual — 10% volume)");  break;
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

    @Override
    public void onEnergyUpdate(float energy, float threshold) {
        if (++logFrameCount >= 4) {
            logFrameCount = 0;
            boolean detectorInAds = service != null && service.detectorIsInCommercial();
            logger.log(energy, threshold, detectorInAds, volumeMode.name().toLowerCase(),
                    streamTitle != null ? streamTitle : "");
        }
        runOnUiThread(() -> {
            textEnergyLevel.setText(String.format(
                    "Level: %.1f  |  Threshold: %.1f", energy, threshold));
            int pct = (int) Math.min((energy / threshold) * 50f, 100);
            progressEnergy.setProgress(pct);
            int color = energy >= threshold ? 0xFF2E7D32 : 0xFFB71C1C;
            progressEnergy.getProgressDrawable().setTint(color);
        });
    }

    // =========================================================================
    // Service binding / permissions
    // =========================================================================

    private void startAndBindService() {
        Intent si = new Intent(this, PlaybackService.class);
        ContextCompat.startForegroundService(this, si);
        bindService(si, connection, BIND_AUTO_CREATE);
    }

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

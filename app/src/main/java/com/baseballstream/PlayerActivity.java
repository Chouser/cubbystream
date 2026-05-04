package com.baseballstream;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
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
    private static final int REQ_MICROPHONE   = 102;

    // UI — top cluster (pause)
    private Button btnPauseTop;

    // UI — bottom cluster (resume is spatially separated from pause)
    private Button btnResumeBottom;
    private Button btnSkipToLive;
    private Button btnStop;

    // Volume override buttons
    private Button btnForceGame;
    private Button btnForceCommercial;

    // Status display
    private TextView textStreamTitle;
    private TextView textStreamSubtitle;
    private TextView textPlaybackStatus;
    private TextView textVolumeStatus;
    private ImageView iconVolume;

    // Service
    private PlaybackService service;
    private boolean bound = false;

    // autoDetectEnabled pauses auto-switching briefly after a manual override
    private boolean autoDetectEnabled = true;

    // Stream info from intent
    private String streamUrl;
    private String streamTitle;
    private String streamSubtitle;
    private String streamType;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            PlaybackService.LocalBinder lb = (PlaybackService.LocalBinder) binder;
            service = lb.getService();
            service.setPlaybackListener(PlayerActivity.this);
            service.setCrowdNoiseListener(PlayerActivity.this);
            bound = true;

            // Start playback
            service.playStream(streamUrl, streamTitle, streamType);
            updateUiFromService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        // Read intent extras
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
        startAndBindService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bound && service != null) {
            service.setPlaybackListener(this);
            updateUiFromService();
        }
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // View setup
    // -------------------------------------------------------------------------

    private void bindViews() {
        btnPauseTop        = findViewById(R.id.btn_pause_top);
        btnResumeBottom    = findViewById(R.id.btn_resume_bottom);
        btnSkipToLive      = findViewById(R.id.btn_skip_to_live);
        btnStop            = findViewById(R.id.btn_stop);
        btnForceGame       = findViewById(R.id.btn_force_game);
        btnForceCommercial = findViewById(R.id.btn_force_commercial);
        textStreamTitle    = findViewById(R.id.text_player_title);
        textStreamSubtitle = findViewById(R.id.text_player_subtitle);
        textPlaybackStatus = findViewById(R.id.text_playback_status);
        textVolumeStatus   = findViewById(R.id.text_volume_status);
        iconVolume         = findViewById(R.id.icon_volume);
    }

    private void setupClickListeners() {
        btnPauseTop.setOnClickListener(v -> {
            if (bound && service != null) service.pause();
        });

        btnResumeBottom.setOnClickListener(v -> {
            if (bound && service != null) service.resume();
        });

        btnSkipToLive.setOnClickListener(v -> {
            if (bound && service != null) {
                service.skipToLive();
                Toast.makeText(this, "Jumping to live…", Toast.LENGTH_SHORT).show();
            }
        });

        btnStop.setOnClickListener(v -> {
            if (bound && service != null) service.stopStream();
            finish();
        });

        btnForceGame.setOnClickListener(v -> {
            autoDetectEnabled = false; // pause auto-detect while user has control
            if (bound && service != null) service.setCommercialVolume(false);
            if (service != null) service.forceDetectorState(false);
            updateVolumeUi(false);
            // Re-enable auto-detect after a short grace period
            btnForceGame.postDelayed(() -> autoDetectEnabled = true, 8000);
        });

        btnForceCommercial.setOnClickListener(v -> {
            autoDetectEnabled = false;
            if (bound && service != null) service.setCommercialVolume(true);
            if (service != null) service.forceDetectorState(true);
            updateVolumeUi(true);
            btnForceCommercial.postDelayed(() -> autoDetectEnabled = true, 8000);
        });
    }

    private void displayStreamInfo() {
        if (textStreamTitle != null)    textStreamTitle.setText(streamTitle != null ? streamTitle : "");
        if (textStreamSubtitle != null) textStreamSubtitle.setText(streamSubtitle != null ? streamSubtitle : "");
    }

    // -------------------------------------------------------------------------
    // Service binding
    // -------------------------------------------------------------------------

    private void startAndBindService() {
        Intent serviceIntent = new Intent(this, PlaybackService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
        bindService(serviceIntent, connection, BIND_AUTO_CREATE);
    }

    // -------------------------------------------------------------------------
    // PlaybackService.PlaybackListener
    // -------------------------------------------------------------------------

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        runOnUiThread(() -> {
            textPlaybackStatus.setText(isPlaying ? "▶ Playing" : "⏸ Paused");
            btnPauseTop.setEnabled(isPlaying);
            btnResumeBottom.setEnabled(!isPlaying);
            btnSkipToLive.setEnabled(!isPlaying);
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> Toast.makeText(this,
                "Playback error: " + message, Toast.LENGTH_LONG).show());
    }

    private void updateUiFromService() {
        if (service == null) return;
        onPlaybackStateChanged(service.isPlaying());
        updateVolumeUi(service.isCommercialVolume());
    }

    // -------------------------------------------------------------------------
    // CrowdNoiseDetector.Listener
    // -------------------------------------------------------------------------

    @Override
    public void onCommercialDetected() {
        if (!autoDetectEnabled) return;
        if (bound && service != null) service.setCommercialVolume(true);
        updateVolumeUi(true);
    }

    @Override
    public void onGameResumed() {
        if (!autoDetectEnabled) return;
        if (bound && service != null) service.setCommercialVolume(false);
        updateVolumeUi(false);
    }

    private void updateVolumeUi(boolean commercial) {
        runOnUiThread(() -> {
            textVolumeStatus.setText(commercial ? "🔇 Commercial (20%)" : "🔊 Game volume (100%)");
            btnForceGame.setEnabled(commercial);
            btnForceCommercial.setEnabled(!commercial);
        });
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private void requestPermissionsIfNeeded() {
        // Android 13+ notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATION);
            }
        }

        // No microphone permission needed — detector runs inside ExoPlayer's audio pipeline
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

    }
}

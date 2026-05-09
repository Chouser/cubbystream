package us.chouser.cubbystream;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
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
                   SettingsSheet.Listener,
                   GamedayController.Listener {

    private static final int REQ_NOTIFICATION = 101;

    private AppPrefs prefs;

    // ---- Volume mode ----
    private enum VolumeMode { AUTO, GAME, ADS }
    private VolumeMode volumeMode = VolumeMode.AUTO;

    // ---- Logging ----
    private final DetectionLogger logger = new DetectionLogger();
    private int logFrameCount = 0;

    // ---- Gameday ----
    private final GamedayController gameday = new GamedayController();
    private String currentGamedayUrl = null;  // built from most recent GameState

    // ---- Auto-start ----
    // True from app start (or feed item change) until a Live state triggers play,
    // or the user manually stops the stream.
    private boolean autoStartArmed = false;

    // ---- Audio views ----
    private Spinner      spinnerStream;
    private Button       btnStop;
    private Button       btnPlay;
    private TextView     textStatus;
    private LinearLayout layoutInfoPanel;
    private TextView     textPlayerTitle;
    private TextView     textPlayerSubtitle;
    private TextView     textEnergyLevel;
    private TextView     textModeIndicator;
    private TextView     textThreshold;
    private ProgressBar  progressEnergy;
    private Button       btnPause;
    private Button       btnResume;
    private Button       btnModeGame;
    private Button       btnModeAds;
    private Button       btnModeAuto;
    private ImageButton  btnSettings;

    // ---- Gameday views ----
    private TextView     textScoreLine;
    private FrameLayout  diamondFrame;
    private TextView     textCountOuts;
    private ImageView    imgAwayLogo;
    private ImageView    imgHomeLogo;
    private ImageView    base1;
    private ImageView    base2;
    private ImageView    base3;
    private TextView     textBase1;
    private TextView     textBase2;
    private TextView     textBase3;
    private TextView     textPitcherName;
    private TextView     textBatterName;
    private TextView     textNoGame;      // shown when no game / no teamId
    private TextView     btnGamedayData;

    // ---- Feed / spinner state ----
    private List<StreamItem> feedItems = new ArrayList<>();
    private int selectedPosition = 0;
    private boolean spinnerReady = false;

    // ---- Service ----
    private PlaybackService service;
    private boolean bound = false;

    // ---- Playback state ----
    private enum PlayState { STOPPED, PLAYING, PAUSED }
    private PlayState playState = PlayState.STOPPED;

    // ---- Current stream info ----
    private String currentTitle = "";

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).getService();
            service.setPlaybackListener(MainActivity.this);
            service.setCrowdNoiseListener(MainActivity.this);
            bound = true;

            if (service.isPlaying()) {
                playState = PlayState.PLAYING;
            } else if (service.hasActiveStream()) {
                playState = PlayState.PAUSED;
            } else {
                playState = PlayState.STOPPED;
            }
            updatePlaybackUi();
            applyVolumeMode(VolumeMode.AUTO);

            if (prefs != null) {
                service.setThreshold(prefs.getThreshold());
                service.setAdsVolumePct(prefs.getAdsVolumePct());
            }

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
        autoStartArmed = prefs.getAutoStartAudio();

        Intent si = new Intent(this, PlaybackService.class);
        bindService(si, connection, BIND_AUTO_CREATE);

        loadFeed();
        showGamedayPlaceholder("Select a stream to load game data.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bound && service != null) {
            service.setPlaybackListener(this);
            service.setCrowdNoiseListener(this);
            syncWithService();
        }
    }

    /**
     * Reconciles local playState and gameday pause tracking with the actual
     * service state. Safe to call any time the service is bound.
     */
    private void syncWithService() {
        boolean nowPlaying = service.isPlaying();
        boolean hasStream  = service.hasActiveStream();

        PlayState previous = playState;

        if (nowPlaying) {
            playState = PlayState.PLAYING;
        } else if (hasStream) {
            playState = PlayState.PAUSED;
        } else {
            playState = PlayState.STOPPED;
        }

        // If the service transitioned paused → playing while we were away,
        // inform gameday so it clears the pause accumulator.
        if (previous == PlayState.PAUSED && playState == PlayState.PLAYING) {
            gameday.onStreamResumed();
        }
        // If the service transitioned playing → paused while we were away,
        // inform gameday so pause accumulation starts from now.
        if (previous == PlayState.PLAYING && playState == PlayState.PAUSED) {
            gameday.onStreamPaused();
        }
        // If no stream is active, snap gameday to live so the display stays current.
        if (playState == PlayState.STOPPED) {
            gameday.onLive();
        }

        updatePlaybackUi();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        logger.close();
        gameday.stop();
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }

    // =========================================================================
    // View wiring
    // =========================================================================

    private void bindViews() {
        spinnerStream      = findViewById(R.id.spinner_stream);
        btnStop            = findViewById(R.id.btn_stop);
        btnPlay            = findViewById(R.id.btn_play);
        textStatus         = findViewById(R.id.text_status);
        layoutInfoPanel    = findViewById(R.id.layout_info_panel);
        textPlayerTitle    = findViewById(R.id.text_player_title);
        textPlayerSubtitle = findViewById(R.id.text_player_subtitle);
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

        textScoreLine      = findViewById(R.id.text_score_line);
        diamondFrame       = findViewById(R.id.diamond_frame);
        textCountOuts      = findViewById(R.id.text_count_outs);
        imgAwayLogo        = findViewById(R.id.img_away_logo);
        imgHomeLogo        = findViewById(R.id.img_home_logo);
        base1              = findViewById(R.id.base1);
        base2              = findViewById(R.id.base2);
        base3              = findViewById(R.id.base3);
        textBase1          = findViewById(R.id.text_base1);
        textBase2          = findViewById(R.id.text_base2);
        textBase3          = findViewById(R.id.text_base3);
        textPitcherName    = findViewById(R.id.text_pitcher_name);
        textBatterName     = findViewById(R.id.text_batter_name);
        btnGamedayData     = findViewById(R.id.btn_gameday_data);

        // textNoGame shares the scoreboard slot — we create it dynamically
        // or we can reuse text_score_line area; simplest: use a tag on layout_scoreboard
        // Actually use a sibling TextView we add programmatically; but to avoid
        // touching the layout we repurpose text_score_line visibility.
        // See showGamedayPlaceholder() / applyGameState().
    }

    private void setupClickListeners() {
        btnStop.setOnClickListener(v -> stopStream());

        btnPlay.setOnClickListener(v -> {
            if (playState == PlayState.PAUSED && bound && service != null) {
                service.resume();
                gameday.onStreamResumed();
            } else {
                startSelectedStream();
            }
        });

        btnResume.setOnClickListener(v -> {
            if (playState == PlayState.PAUSED && bound && service != null) {
                service.resume();
                gameday.onStreamResumed();
            } else {
                startSelectedStream();
            }
        });

        btnPause.setOnClickListener(v -> {
            if (bound && service != null) {
                service.pause();
                gameday.onStreamPaused();
            }
        });

        findViewById(R.id.btn_skip_to_live).setOnClickListener(v -> {
            if (bound && service != null) service.skipToLive();
            gameday.onLive();
        });

        btnModeGame.setOnClickListener(v -> applyVolumeMode(VolumeMode.GAME));
        btnModeAds.setOnClickListener(v  -> applyVolumeMode(VolumeMode.ADS));
        btnModeAuto.setOnClickListener(v -> applyVolumeMode(VolumeMode.AUTO));

        btnSettings.setOnClickListener(v -> {
            SettingsSheet sheet = SettingsSheet.newInstance();
            sheet.setListener(this);
            sheet.show(getSupportFragmentManager(), "settings");
        });

        btnGamedayData.setOnClickListener(v -> {
            if (currentGamedayUrl != null) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(currentGamedayUrl));
                startActivity(i);
            } else {
                Toast.makeText(this, "No game data available yet", Toast.LENGTH_SHORT).show();
            }
        });

        spinnerStream.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!spinnerReady) return;
                if (position == selectedPosition) return;
                selectedPosition = position;
                if (playState != PlayState.STOPPED) stopStream();
                startGamedayForSelected();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
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

        // Re-arm auto-start whenever the feed is (re)loaded
        autoStartArmed = prefs != null && prefs.getAutoStartAudio();

        // Begin polling game state for the default selection immediately
        startGamedayForSelected();
    }

    /**
     * Starts (or restarts) GamedayController for the currently selected feed item.
     * Called on feed load and on spinner change — independent of audio state.
     * When audio is not playing, offset is irrelevant so we call onLive() to
     * keep the display snapped to the latest available state.
     */
    private void startGamedayForSelected() {
        gameday.stop();
        currentGamedayUrl = null;
        if (feedItems.isEmpty()) return;
        StreamItem item = feedItems.get(selectedPosition);
        if (!item.hasTeam()) {
            showGamedayPlaceholder("No team ID in stream — gameday data unavailable.");
            return;
        }
        int pollSec  = prefs != null ? prefs.getPollInterval() : AppPrefs.DEFAULT_POLL_INTERVAL;
        long delayMs = (prefs != null ? prefs.getApiDelay()    : AppPrefs.DEFAULT_API_DELAY) * 1000L;
        showGamedayPlaceholder("Loading game data…");
        gameday.start(item.getTeamId(), pollSec, delayMs, this);
        // If audio is not playing, snap display to latest (no offset applies)
        if (playState == PlayState.STOPPED) {
            gameday.onLive();
        }
    }

    private void showStatus(String msg) {
        textStatus.setText(msg);
        textStatus.setVisibility(View.VISIBLE);
    }

    // =========================================================================
    // Playback control
    // =========================================================================

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
            pendingPlay = true;
        } else {
            doPlayStream(item);
        }
    }

    private void doPlayStream(StreamItem item) {
        currentTitle = item.getTitle();
        service.playStream(item.getUrl(), item.getTitle(), item.getType());
        logger.open(this, currentTitle);
        textPlayerTitle.setText(item.getTitle());
        textPlayerSubtitle.setText(item.getSubtitle());
        layoutInfoPanel.setVisibility(View.VISIBLE);

        // Gameday is already polling from startGamedayForSelected(); just reset
        // the offset so display reflects "starting from live" for this stream session.
        gameday.onLive();
    }

    private void stopStream() {
        logger.close();
        // Disarm auto-start: user explicitly stopped, don't re-trigger automatically
        autoStartArmed = false;
        if (bound && service != null) service.stopStream();
        playState = PlayState.STOPPED;
        updatePlaybackUi();
        layoutInfoPanel.setVisibility(View.GONE);
        // Keep gameday polling but snap to live since there's no longer an audio offset
        gameday.onLive();
    }

    // =========================================================================
    // Gameday UI
    // =========================================================================

    /** Hide the scoreboard and show a placeholder message instead. */
    private void showGamedayPlaceholder(String message) {
        // Repurpose textScoreLine as the placeholder (it's in the same area)
        textScoreLine.setText(message);
        diamondFrame.setVisibility(View.GONE);
        // Reset base icons
        base1.setImageResource(R.drawable.base_diamond_empty);
        base2.setImageResource(R.drawable.base_diamond_empty);
        base3.setImageResource(R.drawable.base_diamond_empty);
        textBase1.setText("");
        textBase2.setText("");
        textBase3.setText("");
        textPitcherName.setText("");
        textBatterName.setText("");
    }

    // =========================================================================
    // GamedayController.Listener
    // =========================================================================

    @Override
    public void onGameStateApplied(GameState state) {
        // Already on main thread
        currentGamedayUrl = state.gamedayUrl();

        // Auto-start audio the first time we see a Live game, if armed
        if (autoStartArmed && "Live".equalsIgnoreCase(state.abstractGameState)
                && playState == PlayState.STOPPED) {
            autoStartArmed = false;
            startSelectedStream();
        }

        diamondFrame.setVisibility(View.VISIBLE);

        //String half = state.isTopInning ? "▲" : "▼";
        String topHalf = state.isTopInning ? "◤" : "";
        String bottomHalf = state.isTopInning ? "" : "◢";
        textScoreLine.setText(String.format("%s %d  |  %s%d%s  |  %s %d",
                state.awayTeamAbbrev, state.awayScore,
                topHalf, state.inning, bottomHalf,
                state.homeTeamAbbrev, state.homeScore));

        // Count / outs
        String outs    = repeat("●", state.outs)    + repeat("○", Math.max(0, 3 - state.outs));
        textCountOuts.setText(String.format("%d balls  %d strikes  |  %s outs", state.balls, state.strikes, outs));

        // Base runners
        base1.setImageResource(state.runnerOnFirst  ? R.drawable.base_diamond : R.drawable.base_diamond_empty);
        base2.setImageResource(state.runnerOnSecond ? R.drawable.base_diamond : R.drawable.base_diamond_empty);
        base3.setImageResource(state.runnerOnThird  ? R.drawable.base_diamond : R.drawable.base_diamond_empty);
        textBase1.setText(state.runnerNameFirst  != null ? state.runnerNameFirst  : "");
        textBase2.setText(state.runnerNameSecond != null ? state.runnerNameSecond : "");
        textBase3.setText(state.runnerNameThird  != null ? state.runnerNameThird  : "");

        // Players
        textPitcherName.setText(state.pitcherName != null ? state.pitcherName : "");
        textBatterName.setText(state.batterName   != null ? state.batterName  : "");

        // Logos
        TeamLogoLoader.load(state.awayTeamAbbrev, imgAwayLogo);
        TeamLogoLoader.load(state.homeTeamAbbrev, imgHomeLogo);
    }

    @Override
    public void onNoGame(String reason) {
        showGamedayPlaceholder(reason);
    }

    private static String repeat(String s, int n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    // =========================================================================
    // Playback UI state
    // =========================================================================

    private void updatePlaybackUi() {
        boolean playing = playState == PlayState.PLAYING;
        boolean stopped = playState == PlayState.STOPPED;
        btnStop.setEnabled(!stopped);
        btnPause.setEnabled(playing);
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
        int inactive = getColor(R.color.btn_inactive);
        btnModeGame.setBackgroundTintList(ColorStateList.valueOf(
                volumeMode == VolumeMode.GAME ? getColor(R.color.btn_game)       : inactive));
        btnModeAds.setBackgroundTintList(ColorStateList.valueOf(
                volumeMode == VolumeMode.ADS  ? getColor(R.color.btn_commercial) : inactive));
        btnModeAuto.setBackgroundTintList(ColorStateList.valueOf(
                volumeMode == VolumeMode.AUTO ? getColor(R.color.btn_auto)       : inactive));
    }

    private void updateModeIndicatorLabel() {
        String label;
        switch (volumeMode) {
            case GAME: label = "Manual: Game"; break;
            case ADS:  label = "Manual: Ads";  break;
            default:
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
                Toast.makeText(this, "Error: " + message, Toast.LENGTH_LONG).show());
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

    @Override public void onFeedUrlChanged(String newUrl)   { loadFeed(); }
    @Override public void onThresholdChanged(int threshold) { if (bound && service != null) service.setThreshold(threshold); }
    @Override public void onAdsVolumePctChanged(int pct)    { if (bound && service != null) service.setAdsVolumePct(pct); }

    @Override
    public void onPollIntervalChanged(int sec) {
        gameday.updatePollInterval(sec);
    }

    @Override
    public void onApiDelayChanged(int sec) {
        gameday.setBaseDelayMs(sec * 1000L);
    }

    @Override
    public void onAutoStartAudioChanged(boolean enabled) {
        // Re-arm if the user just turned it on (and audio isn't already playing)
        if (enabled && playState == PlayState.STOPPED) {
            autoStartArmed = true;
        } else if (!enabled) {
            autoStartArmed = false;
        }
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

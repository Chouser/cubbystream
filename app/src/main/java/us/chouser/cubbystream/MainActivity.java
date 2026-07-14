package us.chouser.cubbystream;

// TODO: when no live game, hide the base diamonds. if the inning is also 0, replace with "no live game"
// TODO: should not compute teamNameToSlug, but follow the team.link,
// then get teams[0].teamName and lowercase _that_. Probably should use
// the teamName instead of abbreviation in the main activity display as well.
// TODO: settings panel on TV -- sliders don't get focus

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.app.UiModeManager;
import android.content.res.Configuration;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.lifecycle.ViewModelProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity
        implements PlaybackService.PlaybackListener,
                   AdDetector.Listener,
                   SettingsSheet.Listener,
                   GamedayController.Listener {

    private static final int REQ_NOTIFICATION = 101;

    private AppPrefs prefs;
    private MainViewModel vm;

    // ---- Volume mode ----
    private VolumeMode volumeMode = VolumeMode.AUTO;

    // ---- Logging ----
    private final DetectionLogger logger   = new DetectionLogger();
    private final AudioRecorder   recorder = new AudioRecorder();

    // Convenience accessors so call-sites don't change much
    private MainViewModel.PlayState playState() { return vm.playState; }

    // ---- TV / Fire TV ----
    private boolean isTv = false;
    // Toggled in onResume()/onStop(). Used to suppress background error toasts —
    // a Toast popping up over whatever app the person is actually using is far
    // more disruptive than a background network hiccup is informative.
    private boolean isForeground = false;

    // ---- Audio views ----
    private Spinner      spinnerStream;
    private Button       btnStop;
    private Button       btnPlay;
    private TextView     textStatus;
    private LinearLayout layoutInfoPanel;
    private TextView     textModeIndicator;
    private ProgressBar  progressEnergy;
    private Button       btnPause;
    private Button       btnResume;
    private Button       btnModeGame;
    private Button       btnModeAds;
    private Button       btnModeAuto;
    private ImageButton  btnSettings;

    // ---- UI polling ----
    private final Handler uiPoller = new Handler(Looper.getMainLooper());
    private final Runnable uiPollRunnable = new Runnable() {
        @Override public void run() {
            updateDetectorUi();
            uiPoller.postDelayed(this, 250); // ~4 Hz
        }
    };

    // ---- Gameday views ----
    private ConstraintLayout layoutBaseballField;
    private TextView     textCountOuts;
    private TextView     textAwayScore;
    private TextView     textHomeScore;
    private TextView     textAwayAbbr;
    private TextView     textHomeAbbr;
    private ImageView    imgAwayLogo;
    private ImageView    imgHomeLogo;
    private ImageView    imgBatterLogo;
    private ImageView    imgPitcherLogo;
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

    // ---- Service ----
    private PlaybackService service;
    private boolean bound = false;

    // ---- Spinner ready flag (view-local, no need to survive rotation) ----
    private boolean spinnerReady = false;

    // ---- Current stream info ----
    private String currentTitle = "";

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).getService();
            service.setPlaybackListener(MainActivity.this);
            service.setDetectionListener(MainActivity.this);
            bound = true;

            if (service.isPlaying()) {
                vm.playState = MainViewModel.PlayState.PLAYING;
            } else if (service.hasActiveStream()) {
                vm.playState = MainViewModel.PlayState.PAUSED;
            } else {
                vm.playState = MainViewModel.PlayState.STOPPED;
            }
            updatePlaybackUi();
            applyVolumeMode(prefs != null ? prefs.getVolumeMode() : VolumeMode.AUTO);

            if (prefs != null) {
                applyDetectionAlgorithm(prefs.getDetectionAlgorithm());
                service.setThreshold(prefs.getThreshold());
                service.setAdsVolumePct(prefs.getAdsVolumePct());
            }

            if (vm.pendingPlay) {
                vm.pendingPlay = false;
                if (!vm.feedItems.isEmpty()) doPlayStream(vm.feedItems.get(vm.selectedPosition));
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            vm.playState = MainViewModel.PlayState.STOPPED;
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

        vm = new ViewModelProvider(this).get(MainViewModel.class);

        // Detect TV first — bindViews uses isTv to hide TV-inappropriate widgets.
        UiModeManager uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        isTv = uiModeManager != null &&
               uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;

        bindViews();
        setupClickListeners();
        requestNotificationPermission();

        prefs = new AppPrefs(this);

        // Only initialise autoStartArmed from prefs on first launch, not on rotation.
        if (!vm.autoStartInitialised) {
            vm.autoStartArmed = prefs.getAutoStartAudio();
            vm.autoStartInitialised = true;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Intent si = new Intent(this, PlaybackService.class);
        bindService(si, connection, BIND_AUTO_CREATE);

        // Re-attach gameday listener after rotation; only load feed on first launch.
        vm.gameday.setListener(this);
        if (vm.feedItems.isEmpty()) {
            loadFeed();
            showGamedayPlaceholder("Select a stream to load game data.");
        } else {
            // Restore spinner to previously selected item
            populateSpinner(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isForeground = true;
        if (bound && service != null) {
            service.setPlaybackListener(this);
            service.setDetectionListener(this);
            syncWithService();
        }
        uiPoller.post(uiPollRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        isForeground = false;
        uiPoller.removeCallbacks(uiPollRunnable);
        if (isTv && vm.playState != MainViewModel.PlayState.STOPPED) {
            stopStream();
        }
    }

    /**
     * Reconciles local playState and gameday pause tracking with the actual
     * service state. Safe to call any time the service is bound.
     */
    private void syncWithService() {
        boolean nowPlaying = service.isPlaying();
        boolean hasStream  = service.hasActiveStream();

        MainViewModel.PlayState previous = vm.playState;

        if (nowPlaying) {
            vm.playState = MainViewModel.PlayState.PLAYING;
        } else if (hasStream) {
            vm.playState = MainViewModel.PlayState.PAUSED;
        } else {
            vm.playState = MainViewModel.PlayState.STOPPED;
        }

        // If the service transitioned paused → playing while we were away,
        // inform gameday so it clears the pause accumulator.
        if (previous == MainViewModel.PlayState.PAUSED
                && vm.playState == MainViewModel.PlayState.PLAYING) {
            vm.gameday.onStreamResumed();
        }
        // If the service transitioned playing → paused while we were away,
        // inform gameday so pause accumulation starts from now.
        if (previous == MainViewModel.PlayState.PLAYING
                && vm.playState == MainViewModel.PlayState.PAUSED) {
            vm.gameday.onStreamPaused();
        }
        // If no stream is active, snap gameday to live so the display stays current.
        if (vm.playState == MainViewModel.PlayState.STOPPED) {
            vm.gameday.onLive();
        }

        updatePlaybackUi();

        if (isTv) {
            if (vm.playState == MainViewModel.PlayState.PLAYING) {
                btnPause.requestFocus();
            } else {
                btnPlay.requestFocus();
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (vm.playState == MainViewModel.PlayState.PLAYING) {
                    btnPause.performClick();
                } else if (vm.playState == MainViewModel.PlayState.PAUSED) {
                    btnResume.performClick();
                } else {
                    btnPlay.performClick();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (vm.playState == MainViewModel.PlayState.PAUSED) {
                    btnResume.performClick();
                } else if (vm.playState == MainViewModel.PlayState.STOPPED) {
                    btnPlay.performClick();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (vm.playState == MainViewModel.PlayState.PLAYING) btnPause.performClick();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                findViewById(R.id.btn_skip_to_live).performClick();
                return true;
            case KeyEvent.KEYCODE_MENU:
                SettingsSheet existing = (SettingsSheet) getSupportFragmentManager()
                        .findFragmentByTag("settings");
                if (existing != null && existing.isVisible()) {
                    existing.dismiss();
                } else {
                    btnSettings.performClick();
                }
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        logger.close();
        recorder.close();
        vm.gameday.setListener(null);
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
        textModeIndicator  = findViewById(R.id.text_mode_indicator);
        progressEnergy     = findViewById(R.id.progress_energy);
        btnPause           = findViewById(R.id.btn_pause);
        btnResume          = findViewById(R.id.btn_resume);
        btnModeGame        = findViewById(R.id.btn_mode_game);
        btnModeAds         = findViewById(R.id.btn_mode_ads);
        btnModeAuto        = findViewById(R.id.btn_mode_auto);
        btnSettings        = findViewById(R.id.btn_settings);

        layoutBaseballField= findViewById(R.id.layout_baseball_field);
        textAwayScore      = findViewById(R.id.text_away_score);
        textHomeScore      = findViewById(R.id.text_home_score);
        textAwayAbbr       = findViewById(R.id.text_away_abbr);
        textHomeAbbr       = findViewById(R.id.text_home_abbr);
        textCountOuts      = findViewById(R.id.text_count_outs);
        imgAwayLogo        = findViewById(R.id.img_away_logo);
        imgHomeLogo        = findViewById(R.id.img_home_logo);
        imgBatterLogo      = findViewById(R.id.img_batter_logo);
        imgPitcherLogo     = findViewById(R.id.img_pitcher_logo);
        base1              = findViewById(R.id.base1);
        base2              = findViewById(R.id.base2);
        base3              = findViewById(R.id.base3);
        textBase1          = findViewById(R.id.text_base1);
        textBase2          = findViewById(R.id.text_base2);
        textBase3          = findViewById(R.id.text_base3);
        textPitcherName    = findViewById(R.id.text_pitcher_name);
        textBatterName     = findViewById(R.id.text_batter_name);
        btnGamedayData     = findViewById(R.id.btn_gameday_data);
        if (isTv && btnGamedayData != null) {
            btnGamedayData.setVisibility(View.GONE);
        }

        // textNoGame shares the scoreboard slot — we create it dynamically
        // or we can reuse text_score_line area; simplest: use a tag on layout_scoreboard
        // Actually use a sibling TextView we add programmatically; but to avoid
        // touching the layout we repurpose text_score_line visibility.
        // See showGamedayPlaceholder() / applyGameState().
    }

    private void setupClickListeners() {
        btnStop.setOnClickListener(v -> stopStream());

        btnPlay.setOnClickListener(v -> {
            if (vm.playState == MainViewModel.PlayState.PAUSED && bound && service != null) {
                service.resume();
                vm.gameday.onStreamResumed();
            } else {
                startSelectedStream();
            }
        });

        btnResume.setOnClickListener(v -> {
            if (vm.playState == MainViewModel.PlayState.PAUSED && bound && service != null) {
                service.resume();
                vm.gameday.onStreamResumed();
            } else {
                startSelectedStream();
            }
        });

        btnPause.setOnClickListener(v -> {
            if (bound && service != null) {
                service.pause();
                vm.gameday.onStreamPaused();
            }
        });

        findViewById(R.id.btn_skip_to_live).setOnClickListener(v -> {
            if (bound && service != null) service.skipToLive();
            vm.gameday.onLive();
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
            if (vm.currentGamedayUrl != null) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(vm.currentGamedayUrl));
                startActivity(i);
            } else {
                Toast.makeText(this, "No game data available yet", Toast.LENGTH_SHORT).show();
            }
        });

        spinnerStream.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!spinnerReady) return;
                if (position == vm.selectedPosition) return;
                vm.selectedPosition = position;
                if (vm.playState != MainViewModel.PlayState.STOPPED) stopStream();
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
                vm.mlbApiBase = feed.getMlbApiBase();
                TeamLogoLoader.setLogoUrlPattern(feed.getLogoUrlPattern());
                List<StreamItem> streams = feed.getStreams();
                vm.feedItems = (streams != null) ? streams : new ArrayList<>();
                if (vm.feedItems.isEmpty()) {
                    showStatus("No streams available right now.");
                    return;
                }
                populateSpinner(true);
            }
            @Override
            public void onError(String message) {
                showStatus("Could not load streams:\n" + message
                        + "\n\nCheck the feed URL in Settings.");
            }
        });
    }

    /**
     * Populates the spinner from vm.feedItems.
     * @param resetSelection true on fresh feed load (resets to position 0 and
     *                       re-arms auto-start); false on rotation restore
     *                       (preserves vm.selectedPosition, leaves auto-start alone).
     */
    private void populateSpinner(boolean resetSelection) {
        List<String> titles = new ArrayList<>();
        for (StreamItem item : vm.feedItems) titles.add(item.getTitle());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item, titles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerReady = false;
        spinnerStream.setAdapter(adapter);
        if (resetSelection) {
            vm.selectedPosition = 0;
            // Re-arm auto-start on a fresh feed load (not on rotation)
            vm.autoStartArmed = prefs != null && prefs.getAutoStartAudio();
            startGamedayForSelected();
        }
        spinnerStream.setSelection(vm.selectedPosition, false);
        spinnerReady = true;
    }

    /**
     * Starts (or restarts) GamedayController for the currently selected feed item.
     * Called on feed load and on spinner change — independent of audio state.
     * When audio is not playing, offset is irrelevant so we call onLive() to
     * keep the display snapped to the latest available state.
     */
    private void startGamedayForSelected() {
        vm.gameday.stop();
        vm.currentGamedayUrl = null;
        if (vm.feedItems.isEmpty()) return;
        StreamItem item = vm.feedItems.get(vm.selectedPosition);
        if (!item.hasTeam()) {
            showGamedayPlaceholder("No team ID in stream — gameday data unavailable.");
            return;
        }
        int pollSec  = prefs != null ? prefs.getPollInterval() : AppPrefs.DEFAULT_POLL_INTERVAL;
        long delayMs = (prefs != null ? prefs.getApiDelay()    : AppPrefs.DEFAULT_API_DELAY) * 1000L;
        showGamedayPlaceholder("Loading game data…");
        vm.gameday.start(item.getTeamId(), pollSec, delayMs, vm.mlbApiBase, this);
        if (vm.playState == MainViewModel.PlayState.STOPPED) {
            vm.gameday.onLive();
        }
    }

    private void showStatus(String msg) {
        textStatus.setText(msg);
        textStatus.setVisibility(View.VISIBLE);
    }

    // =========================================================================
    // Playback control
    // =========================================================================

    private void startSelectedStream() {
        if (vm.feedItems.isEmpty()) return;
        StreamItem item = vm.feedItems.get(vm.selectedPosition);
        if (!item.isValid()) {
            Toast.makeText(this, "Invalid stream URL", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent si = new Intent(this, PlaybackService.class);
        ContextCompat.startForegroundService(this, si);
        if (!bound) {
            bindService(si, connection, BIND_AUTO_CREATE);
            vm.pendingPlay = true;
        } else {
            doPlayStream(item);
        }
    }

    private void doPlayStream(StreamItem item) {
        currentTitle = item.getTitle();
        service.playStream(item.getUrl(), item.getTitle(), item.getType());
        if (prefs.getLoggingEnabled()) {
            logger.open(this, currentTitle);
            logger.setVolumeMode(volumeMode.name().toLowerCase());
            service.setLogger(logger);
            recorder.open(this, currentTitle);
            service.setRecorder(recorder);
        }
        layoutInfoPanel.setVisibility(View.VISIBLE);

        // Gameday is already polling from startGamedayForSelected(); just reset
        // the offset so display reflects "starting from live" for this stream session.
        vm.gameday.onLive();
    }

    private void stopStream() {
        logger.close();
        recorder.close();
        // Disarm auto-start: user explicitly stopped, don't re-trigger automatically
        vm.autoStartArmed = false;
        if (bound && service != null) service.stopStream();
        vm.playState = MainViewModel.PlayState.STOPPED;
        updatePlaybackUi();
        layoutInfoPanel.setVisibility(View.INVISIBLE);
        // Keep gameday polling but snap to live since there's no longer an audio offset
        vm.gameday.onLive();
    }

    // =========================================================================
    // Gameday UI
    // =========================================================================

    /** Hide the scoreboard and show a placeholder message instead. */
    private void showGamedayPlaceholder(String message) {
        // Repurpose textCountOuts as the placeholder (it's in the same area)
        textCountOuts.setText(message);
        //layoutBaseballField.setVisibility(View.GONE);

        // Reset scoreboard and field data
        textAwayAbbr.setText("");
        textHomeAbbr.setText("");
        textAwayScore.setText("");
        textHomeScore.setText("");
        base1.setImageResource(R.drawable.base_diamond_empty);
        base2.setImageResource(R.drawable.base_diamond_empty);
        base3.setImageResource(R.drawable.base_diamond_empty);
        textBase1.setText("");
        textBase2.setText("");
        textBase3.setText("");
        textPitcherName.setText("");
        textBatterName.setText("");
        // TODO: clear logos on scoreboard and field.
    }

    /**
     * Clears the in-game-only details (bases, batter/pitcher) without touching
     * team abbreviations/scores/logos — used for Preview (not started yet) and
     * Final (already over) states, where the matchup and score are still
     * meaningful but the live at-bat details are not.
     */
    private void clearLiveDetails() {
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
        vm.currentGamedayUrl = state.gamedayUrl();

        // Auto-start audio the first time we see a Live game, if armed
        if (vm.autoStartArmed && "Live".equalsIgnoreCase(state.abstractGameState)
                && vm.playState == MainViewModel.PlayState.STOPPED) {
            vm.autoStartArmed = false;
            startSelectedStream();
        }

        //layoutBaseballField.setVisibility(View.VISIBLE);

        textAwayAbbr.setText(String.format("%s", state.awayTeamAbbrev));
        textHomeAbbr.setText(String.format("%s", state.homeTeamAbbrev));
        textAwayScore.setText(String.format("%d", state.awayScore));
        textHomeScore.setText(String.format("%d", state.homeScore));

        // Logos (always shown once we know the matchup, regardless of state)
        TeamLogoLoader.load(state.awayTeamAbbrev, imgAwayLogo);
        TeamLogoLoader.load(state.homeTeamAbbrev, imgHomeLogo);

        if ("Final".equalsIgnoreCase(state.abstractGameState)) {
            String label = "Final";
            if (state.scheduledStartMs > 0) {
                label += " " + GameTimeFormat.formatDateOnly(state.scheduledStartMs);
            }
            if (state.nextGameStartMs > 0) {
                label += "\nNext: " + GameTimeFormat.formatSmart(state.nextGameStartMs);
            }
            textCountOuts.setText(label);
            clearLiveDetails();

        } else if ("Preview".equalsIgnoreCase(state.abstractGameState)) {
            String when = state.scheduledStartMs > 0
                    ? "First pitch\n" + GameTimeFormat.formatSmart(state.scheduledStartMs)
                    : "Game hasn't started";
            textCountOuts.setText(when);
            clearLiveDetails();

        } else {
            // Live (or any other in-progress state) — existing formatting
            String half = state.isTopInning ? "▲" : "▼";
            String outs = repeat("●", state.outs) + repeat("○", Math.max(0, 3 - state.outs));
            textCountOuts.setText(String.format("%s %d\nouts: %s\n%d - %d",
                half, state.inning, outs, state.balls, state.strikes));

            // Base runners
            base1.setImageResource(state.runnerOnFirst  ? R.drawable.base_diamond : R.drawable.base_diamond_empty);
            base2.setImageResource(state.runnerOnSecond ? R.drawable.base_diamond : R.drawable.base_diamond_empty);
            base3.setImageResource(state.runnerOnThird  ? R.drawable.base_diamond : R.drawable.base_diamond_empty);
            textBase1.setText(state.runnerNameFirst  != null ? state.runnerNameFirst  : "");
            textBase2.setText(state.runnerNameSecond != null ? state.runnerNameSecond : "");
            textBase3.setText(state.runnerNameThird  != null ? state.runnerNameThird  : "");

            // Players
            if (state.pitcherName != null) {
                String pitcherLabel = state.pitcherPitchesThrown > 0
                        ? state.pitcherName + "\npitches: " + state.pitcherPitchesThrown
                        : state.pitcherName;
                textPitcherName.setText(pitcherLabel);
            } else {
                textPitcherName.setText("");
            }
            textBatterName.setText(state.batterName != null ? state.batterName : "");

            if (state.isTopInning) {
                TeamLogoLoader.load(state.awayTeamAbbrev, imgBatterLogo);
                TeamLogoLoader.load(state.homeTeamAbbrev, imgPitcherLogo);
            } else {
                TeamLogoLoader.load(state.awayTeamAbbrev, imgPitcherLogo);
                TeamLogoLoader.load(state.homeTeamAbbrev, imgBatterLogo);
            }
        }
    }

    @Override
    public void onNoGame(String reason, long nextGameStartMs, String gamedayUrl) {
        vm.currentGamedayUrl = gamedayUrl; // may be null in offseason
        String message = reason;
        if (nextGameStartMs > 0) {
            message += "\nNext game: " + GameTimeFormat.formatDateAndTime(nextGameStartMs);
        }
        showGamedayPlaceholder(message);
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
        boolean playing = vm.playState == MainViewModel.PlayState.PLAYING;
        boolean stopped = vm.playState == MainViewModel.PlayState.STOPPED;
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
        if (prefs != null) prefs.setVolumeMode(mode);
        logger.setVolumeMode(mode.name().toLowerCase());
        if (service != null) {
            switch (mode) {
                case GAME:
                    service.setAdBreakVolume(false);
                    service.resetDetectorCounters();
                    break;
                case ADS:
                    service.setAdBreakVolume(true);
                    service.resetDetectorCounters();
                    break;
                case AUTO:
                    service.setAdBreakVolume(service.detectorIsInAdBreak());
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
                volumeMode == VolumeMode.ADS  ? getColor(R.color.btn_ads)        : inactive));
        btnModeAuto.setBackgroundTintList(ColorStateList.valueOf(
                volumeMode == VolumeMode.AUTO ? getColor(R.color.btn_auto)       : inactive));
    }

    private void updateModeIndicatorLabel() {
        AdDetector det = (service != null) ? service.getDetector() : null;
        String label;
        switch (volumeMode) {
            case GAME: label = "Manual: Game"; break;
            case ADS:  label = "Manual: Ads";  break;
            default:
                String name   = det != null ? det.getDisplayName() : "Auto";
                boolean inAd  = det != null && det.isInAdBreak();
                String status = det != null ? det.getStatusText() : null;
                label = name + ": " + (inAd ? "ads" : "game");
                if (status != null) label += " — " + status;
                break;
        }
        textModeIndicator.setText(label);
    }

    /** Polls the active detector for signal/threshold and updates the progress bar. */
    private void updateDetectorUi() {
        AdDetector det = (service != null) ? service.getDetector() : null;
        updateModeIndicatorLabel();

        if (det == null) {
            progressEnergy.setVisibility(android.view.View.INVISIBLE);
            return;
        }

        float signal    = det.getSignalLevel();
        float threshold = det.getThreshold();

        if (Float.isNaN(signal)) {
            progressEnergy.setVisibility(android.view.View.INVISIBLE);
            return;
        }

        progressEnergy.setVisibility(android.view.View.VISIBLE);

        if (!Float.isNaN(threshold) && threshold > 0) {
            int pct = (int) Math.min((signal / (2 * threshold)) * 100f, 100);
            progressEnergy.setProgress(pct);
            int color = signal >= threshold ? 0xFF2E7D32 : 0xFFB71C1C;
            progressEnergy.getProgressDrawable().setTint(color);
        } else {
            // No threshold (e.g. GeneratedDetector) — signal is in [0,1] where 1=game, 0=ads.
            // Scale to 0–100 and apply the same game/ad color convention.
            int pct = (int) Math.min(signal * 100f, 100);
            progressEnergy.setProgress(pct);
            int color = signal >= 0.5f ? 0xFF2E7D32 : 0xFFB71C1C; // green = game, red = ads
            progressEnergy.getProgressDrawable().setTint(color);
        }
    }

    // =========================================================================
    // PlaybackService.PlaybackListener
    // =========================================================================

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        runOnUiThread(() -> {
            if (isPlaying) {
                vm.playState = MainViewModel.PlayState.PLAYING;
            } else if (bound && service != null && service.hasActiveStream()) {
                vm.playState = MainViewModel.PlayState.PAUSED;
            } else {
                vm.playState = MainViewModel.PlayState.STOPPED;
            }
            updatePlaybackUi();
        });
    }

    @Override
    public void onError(String message) {
        if (!isForeground) return; // don't pop a toast over whatever app the person is using
        runOnUiThread(() ->
                Toast.makeText(this, "Error: " + message, Toast.LENGTH_LONG).show());
    }

    // =========================================================================
    // AdDetector.Listener
    // =========================================================================

    @Override
    public void onAdBreakStarted() {
        if (volumeMode != VolumeMode.AUTO) return;
        if (service != null) service.setAdBreakVolume(true);
        runOnUiThread(this::updateModeIndicatorLabel);
    }

    @Override
    public void onGameResumed() {
        if (volumeMode != VolumeMode.AUTO) return;
        if (service != null) service.setAdBreakVolume(false);
        runOnUiThread(this::updateModeIndicatorLabel);
    }

    // =========================================================================
    // SettingsSheet.Listener
    // =========================================================================

    @Override public void onFeedUrlChanged(String newUrl) { loadFeed(); }

    @Override
    public void onDetectionAlgorithmChanged(String algorithmKey) {
        if (prefs != null) prefs.setDetectionAlgorithm(algorithmKey);
        applyDetectionAlgorithm(algorithmKey);
    }

    private void applyDetectionAlgorithm(String algorithmKey) {
        if (!bound || service == null) return;
        AdDetector detector = DetectorRegistry.forKey(algorithmKey);
        if (detector instanceof MidBandEnergyDetector && prefs != null) {
            ((MidBandEnergyDetector) detector).threshold = prefs.getThreshold();
        }
        detector.setListener(this);
        service.setDetector(detector);
    }

    @Override public void onThresholdChanged(int threshold) { if (bound && service != null) service.setThreshold(threshold); }
    @Override public void onAdsVolumePctChanged(int pct)    { if (bound && service != null) service.setAdsVolumePct(pct); }

    @Override
    public void onPollIntervalChanged(int sec) {
        vm.gameday.updatePollInterval(sec);
    }

    @Override
    public void onApiDelayChanged(int sec) {
        vm.gameday.setBaseDelayMs(sec * 1000L);
    }

    @Override
    public void onAutoStartAudioChanged(boolean enabled) {
        if (enabled && vm.playState == MainViewModel.PlayState.STOPPED) {
            vm.autoStartArmed = true;
        } else if (!enabled) {
            vm.autoStartArmed = false;
        }
    }

    @Override
    public void onLoggingEnabledChanged(boolean enabled) {
        if (enabled) {
            if (service != null && service.hasActiveStream()) {
                logger.open(this, currentTitle);
                logger.setVolumeMode(volumeMode.name().toLowerCase());
                service.setLogger(logger);
                recorder.open(this, currentTitle);
                service.setRecorder(recorder);
            }
        } else {
            if (service != null) {
                service.setLogger(null);
                service.setRecorder(null);
            }
            logger.close();
            recorder.close();
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

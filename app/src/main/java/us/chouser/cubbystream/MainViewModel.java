package us.chouser.cubbystream;

import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds state that must survive activity recreation (orientation changes).
 * The activity is destroyed and recreated on rotation; the ViewModel is not.
 */
public class MainViewModel extends ViewModel {

    // ---- Playback state (mirrors MainActivity.PlayState) ----
    enum PlayState { STOPPED, PLAYING, PAUSED }
    PlayState playState = PlayState.STOPPED;

    // ---- Feed ----
    List<StreamItem> feedItems = new ArrayList<>();
    int selectedPosition = 0;

    // ---- Auto-start ----
    // Armed once per "session" (app launch or feed change). Disarmed when a
    // Live state triggers play, or the user explicitly stops the stream.
    boolean autoStartArmed = false;
    // Set to true after autoStartArmed is initialised from prefs on first launch,
    // so rotation doesn't re-read prefs and re-arm.
    boolean autoStartInitialised = false;

    // ---- Gameday ----
    // Owned here so it keeps running across orientation changes without restart.
    final GamedayController gameday = new GamedayController();
    String currentGamedayUrl = null;

    // ---- Pending play ----
    // True when play was requested before the service was bound.
    boolean pendingPlay = false;

    @Override
    protected void onCleared() {
        // Called when the activity is permanently finished (not just rotated).
        gameday.stop();
    }
}

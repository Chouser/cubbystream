package com.baseballstream;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

@UnstableApi
public class PlaybackService extends LifecycleService {

    private static final String TAG      = "PlaybackService";
    private static final String CHANNEL_ID = "baseball_stream_channel";
    private static final int    NOTIF_ID   = 1001;

    public static final float NORMAL_VOLUME  = 1.0f;
    public static final float REDUCED_VOLUME = 0.10f;

    public static final String ACTION_PAUSE     = "com.baseballstream.PAUSE";
    public static final String ACTION_RESUME    = "com.baseballstream.RESUME";
    public static final String ACTION_STOP      = "com.baseballstream.STOP";
    public static final String ACTION_SKIP_LIVE = "com.baseballstream.SKIP_LIVE";

    public class LocalBinder extends Binder {
        public PlaybackService getService() { return PlaybackService.this; }
    }

    public interface PlaybackListener {
        void onPlaybackStateChanged(boolean isPlaying);
        void onError(String message);
    }

    private final IBinder      binder          = new LocalBinder();
    private ExoPlayer          player;
    private PlaybackListener   playbackListener;
    private CrowdNoiseDetector crowdNoiseDetector;

    private String  currentTitle      = "Baseball Stream";
    private boolean isCommercialVolume = false;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        initPlayer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PAUSE:     pause();      break;
                case ACTION_RESUME:    resume();     break;
                case ACTION_STOP:      stopStream(); break;
                case ACTION_SKIP_LIVE: skipToLive(); break;
            }
        }
        return START_STICKY;
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return binder;
    }

    @Override
    public void onDestroy() {
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // Player init
    // -------------------------------------------------------------------------

    private void initPlayer() {
        crowdNoiseDetector = new CrowdNoiseDetector(null);

        DefaultAudioSink audioSink = new DefaultAudioSink.Builder(this)
                .setAudioProcessors(new AudioProcessor[]{ crowdNoiseDetector })
                .build();

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this) {
            @Override
            protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(
                    android.content.Context context,
                    boolean enableFloatOutput,
                    boolean enableAudioTrackPlaybackParams) {
                return audioSink;
            }
        };

        player = new ExoPlayer.Builder(this, renderersFactory).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updateNotification();
                if (playbackListener != null) playbackListener.onPlaybackStateChanged(isPlaying);
            }
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "Player error: " + error.getMessage());
                if (playbackListener != null)
                    playbackListener.onError(error.getMessage() != null
                            ? error.getMessage() : "Unknown playback error");
            }
        });
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void playStream(String url, String title, String typeHint) {
        if (player == null) initPlayer();
        currentTitle = title;
        isCommercialVolume = false;
        player.setVolume(NORMAL_VOLUME);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.setPlayWhenReady(true);
        // Must call startForeground before returning from startForegroundService
        startForeground(NOTIF_ID, buildNotification(true));
    }

    public void pause()    { if (player != null) player.setPlayWhenReady(false); updateNotification(); }
    public void resume()   { if (player != null) player.setPlayWhenReady(true);  updateNotification(); }

    public void stopStream() {
        if (player != null) { player.stop(); player.clearMediaItems(); }
        stopForeground(true);
        stopSelf();
    }

    public void skipToLive() {
        if (player == null) return;
        if (player.isCurrentMediaItemLive()) player.seekToDefaultPosition();
        resume();
    }

    /**
     * Returns the live offset in milliseconds, or -1 if not a live stream
     * or offset not yet known. Updates correctly while paused.
     */
    public long getLiveOffsetMs() {
        if (player == null) return -1L;
        if (!player.isCurrentMediaItemLive()) return -1L;
        long offset = player.getCurrentLiveOffset();
        return offset == androidx.media3.common.C.TIME_UNSET ? -1L : offset;
    }

    public boolean isLiveStream() {
        return player != null && player.isCurrentMediaItemLive();
    }

    public void setVolume(float volume) {
        if (player != null) player.setVolume(volume);
        isCommercialVolume = (volume < NORMAL_VOLUME);
    }

    public void setCommercialVolume(boolean commercial) {
        isCommercialVolume = commercial;
        setVolume(commercial ? REDUCED_VOLUME : NORMAL_VOLUME);
    }

    public boolean isPlaying()         { return player != null && player.isPlaying(); }
    public boolean isCommercialVolume() { return isCommercialVolume; }

    public void setPlaybackListener(PlaybackListener l) { this.playbackListener = l; }

    public void setCrowdNoiseListener(CrowdNoiseDetector.Listener l) {
        if (crowdNoiseDetector != null) crowdNoiseDetector.listener = l;
    }

    /** Reset the detector's consecutive-frame counters when mode changes. */
    public void resetDetectorCounters() {
        if (crowdNoiseDetector != null) crowdNoiseDetector.resetCounters();
    }

    /** Current smoothed energy reading — used to initialise the UI meter on bind. */
    public boolean detectorIsInCommercial() {
        return crowdNoiseDetector != null && crowdNoiseDetector.isInCommercial();
    }

    public ExoPlayer getPlayer() { return player; }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Baseball Stream", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Baseball audio stream controls");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private PendingIntent actionIntent(String action) {
        Intent i = new Intent(this, PlaybackService.class);
        i.setAction(action);
        return PendingIntent.getService(this, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent openPlayerIntent() {
        Intent i = new Intent(this, PlayerActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification(boolean playing) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentTitle)
                .setContentText(playing ? "Playing" : "Paused")
                .setContentIntent(openPlayerIntent())
                .setOngoing(playing)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);

        if (playing) b.addAction(android.R.drawable.ic_media_pause, "Pause", actionIntent(ACTION_PAUSE));
        else         b.addAction(android.R.drawable.ic_media_play,  "Resume", actionIntent(ACTION_RESUME));
        b.addAction(android.R.drawable.ic_media_next, "Live", actionIntent(ACTION_SKIP_LIVE));
        b.addAction(android.R.drawable.ic_delete,     "Stop", actionIntent(ACTION_STOP));
        return b.build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(isPlaying()));
    }
}

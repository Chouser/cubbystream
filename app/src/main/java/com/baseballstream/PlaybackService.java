package com.baseballstream;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

/**
 * Foreground service that owns the ExoPlayer instance.
 * Runs independently of any Activity so audio continues when the user
 * switches apps, locks the screen, or dismisses the player screen.
 *
 * Volume management:
 *   NORMAL_VOLUME = 1.0f  (100%)
 *   REDUCED_VOLUME = 0.20f (20% — for commercials)
 */
@UnstableApi
public class PlaybackService extends LifecycleService {

    private static final String TAG = "PlaybackService";
    private static final String CHANNEL_ID = "baseball_stream_channel";
    private static final int NOTIF_ID = 1001;

    public static final float NORMAL_VOLUME  = 1.0f;
    public static final float REDUCED_VOLUME = 0.20f;

    // Actions sent to the service from the notification
    public static final String ACTION_PAUSE   = "com.baseballstream.PAUSE";
    public static final String ACTION_RESUME  = "com.baseballstream.RESUME";
    public static final String ACTION_STOP    = "com.baseballstream.STOP";
    public static final String ACTION_SKIP_LIVE = "com.baseballstream.SKIP_LIVE";

    /** Binder for Activity ↔ Service communication */
    public class LocalBinder extends Binder {
        public PlaybackService getService() { return PlaybackService.this; }
    }

    public interface PlaybackListener {
        void onPlaybackStateChanged(boolean isPlaying);
        void onError(String message);
    }

    private final IBinder binder = new LocalBinder();
    private ExoPlayer player;
    private PlaybackListener playbackListener;

    private String currentTitle = "Baseball Stream";
    private boolean isCommercialVolume = false;

    // The detector is wired directly into ExoPlayer's audio pipeline
    private CrowdNoiseDetector crowdNoiseDetector;

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
                case ACTION_PAUSE:    pause();    break;
                case ACTION_RESUME:   resume();   break;
                case ACTION_STOP:     stopStream(); break;
                case ACTION_SKIP_LIVE: skipToLive(); break;
            }
        }

        // START_STICKY: system restarts the service if killed, preserving intent
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return binder;
    }

    @Override
    public void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // Player init
    // -------------------------------------------------------------------------

    private void initPlayer() {
        // Create the detector first so we can inject it into ExoPlayer's audio pipeline.
        // The listener is wired in via setCrowdNoiseListener() once PlayerActivity binds.
        crowdNoiseDetector = new CrowdNoiseDetector(null);

        // Build a custom AudioSink with our processor inserted ahead of the DAC.
        // This is the canonical Media3 way to tap the decoded PCM stream.
        DefaultAudioSink audioSink = new DefaultAudioSink.Builder(this)
                .setAudioProcessors(new AudioProcessor[]{ crowdNoiseDetector })
                .build();

        // Wrap it in a RenderersFactory that substitutes our sink
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
                if (playbackListener != null) {
                    playbackListener.onPlaybackStateChanged(isPlaying);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "Player error: " + error.getMessage());
                if (playbackListener != null) {
                    playbackListener.onError(error.getMessage() != null
                            ? error.getMessage() : "Unknown playback error");
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Public API (called from PlayerActivity via binder)
    // -------------------------------------------------------------------------

    /**
     * Load and start a new stream. ExoPlayer auto-detects format from
     * URL extension and Content-Type header. The typeHint is used only
     * when auto-detection would fail (e.g., ambiguous URLs).
     */
    public void playStream(String url, String title, String typeHint) {
        if (player == null) initPlayer();

        currentTitle = title;
        isCommercialVolume = false;
        player.setVolume(NORMAL_VOLUME);

        // Build MediaItem — ExoPlayer infers HLS vs progressive from the URL/headers
        MediaItem mediaItem = MediaItem.fromUri(url);
        player.setMediaItem(mediaItem);
        player.prepare();
        player.setPlayWhenReady(true);

        // Promote to foreground immediately
        startForeground(NOTIF_ID, buildNotification(true));
    }

    public void pause() {
        if (player != null) player.setPlayWhenReady(false);
        updateNotification();
    }

    public void resume() {
        if (player != null) player.setPlayWhenReady(true);
        updateNotification();
    }

    public void stopStream() {
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        stopForeground(true);
        stopSelf();
    }

    /** Jump to the live edge of an HLS stream (no-op for finite MP3 streams) */
    public void skipToLive() {
        if (player == null) return;
        if (player.isCurrentMediaItemLive()) {
            player.seekToDefaultPosition();
        }
        resume();
    }

    public void setVolume(float volume) {
        if (player != null) player.setVolume(volume);
        isCommercialVolume = (volume < NORMAL_VOLUME);
    }

    public void setCommercialVolume(boolean commercial) {
        isCommercialVolume = commercial;
        setVolume(commercial ? REDUCED_VOLUME : NORMAL_VOLUME);
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public boolean isCommercialVolume() { return isCommercialVolume; }

    public void setPlaybackListener(PlaybackListener listener) {
        this.playbackListener = listener;
    }

    /**
     * Wire the crowd noise detector listener.
     * Called by PlayerActivity after binding so events reach the UI.
     * The detector itself lives here (in the audio pipeline) regardless.
     */
    public void setCrowdNoiseListener(CrowdNoiseDetector.Listener listener) {
        if (crowdNoiseDetector != null) {
            crowdNoiseDetector.listener = listener;
        }
    }

    /** Force the commercial-detection state (called from manual override buttons). */
    public void forceDetectorState(boolean commercial) {
        if (crowdNoiseDetector != null) crowdNoiseDetector.forceState(commercial);
    }

    public ExoPlayer getPlayer() { return player; }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Baseball Stream",
                NotificationManager.IMPORTANCE_LOW // Low = no sound for media notifications
        );
        channel.setDescription("Baseball audio stream controls");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private PendingIntent actionIntent(String action) {
        Intent i = new Intent(this, PlaybackService.class);
        i.setAction(action);
        return PendingIntent.getService(this, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent openPlayerIntent() {
        Intent i = new Intent(this, PlayerActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification(boolean playing) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentTitle)
                .setContentText(playing ? "Playing" : "Paused")
                .setContentIntent(openPlayerIntent())
                .setOngoing(playing)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        // Notification action buttons
        if (playing) {
            builder.addAction(android.R.drawable.ic_media_pause, "Pause",
                    actionIntent(ACTION_PAUSE));
        } else {
            builder.addAction(android.R.drawable.ic_media_play, "Resume",
                    actionIntent(ACTION_RESUME));
        }
        builder.addAction(android.R.drawable.ic_media_next, "Live",
                actionIntent(ACTION_SKIP_LIVE));
        builder.addAction(android.R.drawable.ic_delete, "Stop",
                actionIntent(ACTION_STOP));

        return builder.build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(isPlaying()));
        }
    }
}

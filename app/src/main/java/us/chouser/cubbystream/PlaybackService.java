package us.chouser.cubbystream;

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
    private static final String CHANNEL_ID = "cubby_stream_channel";
    private static final int    NOTIF_ID   = 1001;

    public static final float NORMAL_VOLUME  = 1.0f;
    public static final float REDUCED_VOLUME = 0.10f; // kept for reference; runtime value in adsVolume

    private float adsVolume = REDUCED_VOLUME;

    public static final String ACTION_PAUSE     = "us.chouser.cubbystream.PAUSE";
    public static final String ACTION_RESUME    = "us.chouser.cubbystream.RESUME";
    public static final String ACTION_STOP      = "us.chouser.cubbystream.STOP";
    public static final String ACTION_SKIP_LIVE = "us.chouser.cubbystream.SKIP_LIVE";

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
    private final AudioTap     audioTap        = new AudioTap();
    private AdDetector         detector        = new MidBandEnergyDetector();

    private String  currentTitle      = "Cubby Stream";
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
        audioTap.setDetector(detector);

        DefaultAudioSink audioSink = new DefaultAudioSink.Builder(this)
                .setAudioProcessors(new AudioProcessor[]{ audioTap })
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

    public void setVolume(float volume) {
        if (player != null) player.setVolume(volume);
        isCommercialVolume = (volume < NORMAL_VOLUME);
    }

    public void setCommercialVolume(boolean commercial) {
        isCommercialVolume = commercial;
        setVolume(commercial ? adsVolume : NORMAL_VOLUME);
    }

    public void setThreshold(int threshold) {
        if (detector instanceof MidBandEnergyDetector) {
            ((MidBandEnergyDetector) detector).threshold = threshold;
        }
    }

    /**
     * Swap the active detection algorithm.  The AudioTap pipeline is unaffected;
     * only the detector reference is updated.
     */
    public void setDetector(AdDetector newDetector) {
        detector = newDetector;
        audioTap.setDetector(newDetector);
    }

    /** Wire (or rewire) the logger into the tap and give it a reference to the active detector. */
    public void setLogger(DetectionLogger logger) {
        audioTap.setLogger(logger);
    }

    public void setAdsVolumePct(int pct) {
        adsVolume = pct / 100f;
        if (isCommercialVolume) setVolume(adsVolume);
    }

    public boolean isPlaying()         { return player != null && player.isPlaying(); }
    public boolean isCommercialVolume() { return isCommercialVolume; }

    public boolean hasActiveStream() {
        return player != null && player.getMediaItemCount() > 0;
    }

    public void setPlaybackListener(PlaybackListener l) { this.playbackListener = l; }

    public void setDetectionListener(AdDetector.Listener l) {
        if (detector != null) detector.setListener(l);
    }

    public void resetDetectorCounters() {
        if (detector != null) detector.resetCounters();
    }

    public boolean detectorIsInCommercial() {
        return detector != null && detector.isInCommercial();
    }

    public AdDetector getDetector() { return detector; }

    public ExoPlayer getPlayer() { return player; }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Cubby Stream", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Audio stream controls");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private PendingIntent actionIntent(String action) {
        Intent i = new Intent(this, PlaybackService.class);
        i.setAction(action);
        return PendingIntent.getService(this, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent openPlayerIntent() {
        Intent i = new Intent(this, MainActivity.class);
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

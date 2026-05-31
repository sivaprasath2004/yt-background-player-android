package com.ytplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.webkit.WebView;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;

/**
 * AudioService — Foreground Service with mediaPlayback type.
 *
 * KEY DESIGN:
 * - Runs as a foreground service (survives app minimize + screen off)
 * - Holds a PARTIAL_WAKE_LOCK so CPU stays on when screen turns off
 * - Uses MediaSessionCompat so Android treats this as a real media app
 * - Injects JS into the WebView to defeat YouTube's Page Visibility API
 */
public class AudioService extends Service {

    public static final String CHANNEL_ID   = "yt_audio_channel";
    public static final int    NOTIF_ID     = 1;
    public static final String ACTION_PLAY  = "com.ytplayer.PLAY";
    public static final String ACTION_PAUSE = "com.ytplayer.PAUSE";
    public static final String ACTION_STOP  = "com.ytplayer.STOP";

    private final IBinder binder = new LocalBinder();
    private WebView webView;
    private PowerManager.WakeLock wakeLock;
    private MediaSessionCompat mediaSession;
    private boolean isPlaying = false;

    // ── Binder ───────────────────────────────────────────────────────────────

    public class LocalBinder extends Binder {
        public AudioService getService() { return AudioService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        initMediaSession();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Handle notification button actions
        MediaButtonReceiver.handleIntent(mediaSession, intent);

        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY:
                    playVideo();
                    break;
                case ACTION_PAUSE:
                    pauseVideo();
                    break;
                case ACTION_STOP:
                    pauseVideo();
                    stopSelf();
                    return START_NOT_STICKY;
            }
        }

        // Start as foreground immediately — this is what keeps us alive
        startForeground(NOTIF_ID, buildNotification());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        releaseWakeLock();
    }

    // ── MediaSession ─────────────────────────────────────────────────────────

    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(this, "YTPlayer");

        // These flags are critical — tells Android this is a real media player
        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay()  { playVideo(); }
            @Override public void onPause() { pauseVideo(); }
            @Override public void onStop()  { pauseVideo(); stopSelf(); }
        });

        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
        mediaSession.setActive(true);
    }

    private void updatePlaybackState(int state) {
        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY |
                PlaybackStateCompat.ACTION_PAUSE |
                PlaybackStateCompat.ACTION_STOP |
                PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f);
        mediaSession.setPlaybackState(builder.build());
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setWebView(WebView wv) {
        this.webView = wv;
    }

    /**
     * Called when user goes to home screen or screen turns off.
     * Injects the visibility override JS to stop YouTube from pausing.
     */
    public void keepAlive() {
        if (webView == null) return;
        // Run on main thread since WebView requires it
        webView.post(() -> {
            // Override Page Visibility API — YouTube checks this to auto-pause
            String js =
                "(function() {" +
                "  try {" +
                "    Object.defineProperty(document, 'hidden', {" +
                "      get: function() { return false; }, configurable: true" +
                "    });" +
                "    Object.defineProperty(document, 'visibilityState', {" +
                "      get: function() { return 'visible'; }, configurable: true" +
                "    });" +
                "    Object.defineProperty(document, 'webkitHidden', {" +
                "      get: function() { return false; }, configurable: true" +
                "    });" +
                "    Object.defineProperty(document, 'webkitVisibilityState', {" +
                "      get: function() { return 'visible'; }, configurable: true" +
                "    });" +
                // Block YouTube from receiving the visibility change event
                "    document.addEventListener('visibilitychange'," +
                "      function(e) { e.stopImmediatePropagation(); }, true);" +
                "    document.addEventListener('webkitvisibilitychange'," +
                "      function(e) { e.stopImmediatePropagation(); }, true);" +
                // Re-trigger so YouTube thinks we're still visible
                "    document.dispatchEvent(new Event('visibilitychange'));" +
                // Prevent video from ever pausing due to visibility
                "    var videos = document.querySelectorAll('video');" +
                "    for (var i = 0; i < videos.length; i++) {" +
                "      (function(v) {" +
                "        var origPause = v.pause.bind(v);" +
                "        v.pause = function() {" +
                "          console.log('Pause blocked by YTPlayer');" +
                "        };" +
                "        v.addEventListener('pause', function() {" +
                "          setTimeout(function() { v.play(); }, 200);" +
                "        });" +
                "      })(videos[i]);" +
                "    }" +
                "  } catch(e) { console.log('YTPlayer inject error: ' + e); }" +
                "})();";
            webView.evaluateJavascript(js, null);
        });
    }

    // ── Video Control ─────────────────────────────────────────────────────────

    public void playVideo() {
        isPlaying = true;
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        if (webView != null) {
            webView.post(() ->
                webView.evaluateJavascript(
                    "(function(){ var v=document.querySelector('video'); if(v) v.play(); })()", null)
            );
        }
        updateNotification();
    }

    public void pauseVideo() {
        isPlaying = false;
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
        if (webView != null) {
            webView.post(() ->
                webView.evaluateJavascript(
                    "(function(){ var v=document.querySelector('video'); if(v) v.pause(); })()", null)
            );
        }
        updateNotification();
    }

    public boolean isPlaying() { return isPlaying; }

    // ── Wake Lock ─────────────────────────────────────────────────────────────

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            // PARTIAL_WAKE_LOCK keeps CPU running when screen is OFF
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "YTPlayer::AudioWakeLock"
            );
            // Hold for up to 6 hours
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "YouTube Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            );
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        // Tap notification → open app
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Play/Pause button
        Intent playPauseIntent = new Intent(this, AudioService.class);
        playPauseIntent.setAction(isPlaying ? ACTION_PAUSE : ACTION_PLAY);
        PendingIntent playPausePending = PendingIntent.getService(
            this, 1, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Stop button
        Intent stopIntent = new Intent(this, AudioService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String statusText = isPlaying ? "Audio playing in background" : "Paused";
        int playPauseIcon = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play;
        String playPauseLabel = isPlaying ? "Pause" : "Play";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("YouTube Background Player")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_yt)
            .setContentIntent(openPending)
            .addAction(playPauseIcon, playPauseLabel, playPausePending)
            .addAction(R.drawable.ic_stop, "Stop", stopPending)
            .setStyle(new MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1))
            .setOngoing(isPlaying)  // sticky when playing, dismissible when paused
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }
}

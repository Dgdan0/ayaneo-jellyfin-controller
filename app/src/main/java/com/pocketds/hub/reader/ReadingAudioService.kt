package com.pocketds.hub.reader

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.pocketds.hub.HubActivity

/**
 * The reading-audio player's home (#16, A1), apart from the video service,
 * which is bound to Jellyfin sessions and stays as it is. It owns one
 * ExoPlayer for spoken audio and its media session: the media notification,
 * the lock screen and a headset's buttons work through it, and it holds the
 * device awake while it plays with the screen off. [ReadingAudio] tells it
 * what to play.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ReadingAudioService : MediaSessionService() {
    lateinit var player: ExoPlayer
        private set
    private lateinit var session: MediaSession

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            // Playing on with the screen off: the files are on this device.
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        // Its own id: the video service's session has the default one, and two alike are refused.
        session = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .setSessionActivity(PendingIntent.getActivity(this, 1, Intent(this, HubActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
            .build()
        // Its notification is managed without any controller binding: the app talks to the player directly.
        addSession(session)
        ReadingAudio.attach(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    /** Swiped away from recent apps: a paused book goes; one still playing carries on. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) ReadingAudio.stop()
    }

    override fun onDestroy() {
        ReadingAudio.detach(this)
        session.release()
        player.release()
        super.onDestroy()
    }

    private companion object {
        const val SESSION_ID = "reading-audio"
    }
}

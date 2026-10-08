package com.pocketds.hub.reader

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.pocketds.hub.HubActivity

/**
 * The read-along voice while the screen is off (#49). The narration's player belongs to its reader
 * ([ReadAlongPlayback]); this service gives it what an app needs to go on playing in the background: a media
 * session, so the notification (and the lock screen, and a headset) can pause and skip it, and the foreground
 * service that keeps the process alive, as [ReadingAudioService] does for an audiobook. The player holds the
 * device awake while it plays ([ReadAlongPlayback] sets the wake mode).
 *
 * It starts when the voice does and goes when the reader lets the voice go ([NarrationHost.release]), so there is
 * no notification for a book that was only opened.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NarrationService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val voice = NarrationHost.attach(this)
        if (voice == null) { stopSelf(); return }
        // Its own id: the audiobook's and the video's sessions have theirs, and two alike are refused.
        session = MediaSession.Builder(this, voice.sessionPlayer())
            .setId(SESSION_ID)
            .setSessionActivity(PendingIntent.getActivity(this, 2, Intent(this, HubActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
            .build()
            .also(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** The reader let the voice go: its session off first, so nothing drives a player that is being released. */
    internal fun shutdown() {
        session?.let { removeSession(it); it.release() }
        session = null
        stopSelf()
    }

    /** Swiped away from recent apps: the reader goes with its task, and the voice with it (an audiobook, which has no page, plays on). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        NarrationHost.stop()
    }

    override fun onDestroy() {
        NarrationHost.detach(this)
        session?.release()
        session = null
        super.onDestroy()
    }

    private companion object {
        const val SESSION_ID = "read-along-narration"
    }
}

/**
 * How the reader and [NarrationService] find each other (#49), on the main thread: the voice that is playing, and
 * the service that holds it. The reader never talks to the service; it tells the host.
 */
object NarrationHost {
    private var voice: ReadAlongPlayback? = null
    private var service: NarrationService? = null

    /** The voice is starting: the service is started if it is not up, and builds its session on the voice. */
    fun engage(context: Context, playback: ReadAlongPlayback) {
        voice = playback
        // From the reader, in front when the voice starts. Without the service the voice still plays, as it did, until the screen goes.
        if (service == null) runCatching { context.applicationContext.startService(Intent(context.applicationContext, NarrationService::class.java)) }
    }

    /** The reader is done with [playback]: the session and the service go, and the player may be released. */
    fun release(playback: ReadAlongPlayback) {
        if (voice !== playback) return
        voice = null
        service?.shutdown()
        service = null
    }

    /** Whether a service is up for a voice, for a test. */
    val isHeld: Boolean get() = service != null

    internal fun attach(owner: NarrationService): ReadAlongPlayback? {
        service = owner
        return voice
    }

    internal fun detach(owner: NarrationService) {
        if (service === owner) service = null
    }

    /** The task went: the voice is let go, its place kept. */
    internal fun stop() {
        voice?.release()
    }
}

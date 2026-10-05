package com.pocketds.hub.settings

import android.content.Context
import com.pocketds.hub.reader.Listening

/**
 * How fast each book is heard (#16, A2 and A5), kept per book on this device:
 * an audiobook and its read-along edition share the speed, so a book you speed
 * up stays sped up whichever way you open it.
 */
object ListeningSettings {
    private const val PREFIX = "listening_speed:"

    fun speed(context: Context, workId: String): Float =
        Listening.clampSpeed(Prefs.of(context).getFloat(PREFIX + workId, 1f))

    fun setSpeed(context: Context, workId: String, speed: Float) {
        Prefs.of(context).edit().putFloat(PREFIX + workId, Listening.clampSpeed(speed)).apply()
    }
}

package com.pocketds.hub.settings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pocketds.hub.HubActivity
import com.pocketds.hub.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable
data class LocalAlert(val id: String, val itemId: String, val title: String, val message: String,
    val destination: String, val occurredAt: Long = System.currentTimeMillis(), val seen: Boolean = false,
    val eventKey: String = "")

/** Bounded, profile-specific event history, also available when Android notifications are disabled. */
object LocalAlerts {
    const val EXTRA_ID = "hub_alert_id"
    const val EXTRA_SCOPE = "hub_alert_scope"
    private const val CHANNEL = "hub_actionable_alerts"
    private val json = Json { ignoreUnknownKeys = true }

    fun scope(context: Context, userId: String = HubSettings.userId(context)): String =
        MessageDigest.getInstance("SHA-256").digest((HubSettings.baseUrl(context).trimEnd('/')+"\n"+userId).toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Synchronized fun list(context: Context, scope: String = scope(context)): List<LocalAlert> =
        runCatching { json.decodeFromString<List<LocalAlert>>(Prefs.of(context).getString("local_alerts_$scope", "[]")!!) }.getOrDefault(emptyList())

    @Synchronized fun markSeen(context: Context, id: String) {
        save(context, scope(context), list(context).map { if(it.id==id) it.copy(seen=true) else it })
        context.getSystemService(NotificationManager::class.java).cancel("${scope(context)}:$id",0)
    }

    fun unread(context: Context): Int = list(context).count { !it.seen }

    @Synchronized fun publish(context: Context, alert: LocalAlert, userId: String = HubSettings.userId(context)) {
        val profile = scope(context, userId)
        val previous = list(context, profile)
        // A retry of the same event never buzzes again or resurrects a seen entry.
        if(previous.any { it.id==alert.id && it.eventKey==alert.eventKey }) return
        val family = alert.id.substringBeforeLast(':')
        val resolved = previous.filterNot { it.id.substringBeforeLast(':')==family }
        save(context, profile, (listOf(alert)+resolved).take(80))
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.cancel("$profile:$family:${if(alert.id.endsWith(":ready")) "failed" else "ready"}",0)
        if(!NotificationSettings.alertsEnabled(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Downloads and subtitle alerts",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(context,HubActivity::class.java).setData(Uri.Builder().scheme("hub-alert").authority(profile).appendPath(alert.id).build())
            .putExtra(EXTRA_ID,alert.id).putExtra(EXTRA_SCOPE,profile)
        val pending=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice=NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_nav_notifications)
            .setContentTitle(alert.title).setContentText(alert.message).setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(pending).build()
        try { manager.notify("$profile:${alert.id}",0,notice) } catch (_: SecurityException) { /* History stays available in app. */ }
    }

    private fun save(context: Context, scope: String, alerts: List<LocalAlert>) {
        Prefs.of(context).edit().putString("local_alerts_$scope",json.encodeToString(alerts)).apply()
    }
}

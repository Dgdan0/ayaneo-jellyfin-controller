package com.pocketds.hub.settings

import android.content.Context
import com.pocketds.hub.model.ActivityResponse
import com.pocketds.hub.model.Stages
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** First observation establishes a baseline; subsequent state transitions create alerts. */
object TransferAlertObserver {
    @Synchronized fun observe(context: Context, response: ActivityResponse) {
        val key="transfer_alert_baseline_${LocalAlerts.scope(context)}"
        val prefs=Prefs.of(context)
        val raw=prefs.getString(key,null)
        val previous=runCatching {Json.decodeFromString<Map<String,String>>(raw ?: "{}")}.getOrDefault(emptyMap())
        val next=previous.toMutableMap()
        response.items.filter {it.id.isNotBlank()}.forEach { item ->
            val complete=item.stage==Stages.DONE || (item.stage==Stages.SEEDING && item.progress>=1.0)
            val state=if(item.isBroken) "failed:${item.diagnosis?.code.orEmpty()}" else if(complete) "complete" else item.stage
            if(raw!=null && previous[item.id]!=state) {
                if(item.isBroken) LocalAlerts.publish(context,LocalAlert("transfer:${item.id}:failed",item.id,item.headline,
                    item.diagnosis?.title?.ifBlank {null} ?: "Server transfer needs attention. Open to inspect the cause.","transfer"))
                else if(complete && previous[item.id]!=null) LocalAlerts.publish(context,
                    LocalAlert("transfer:${item.id}:ready",item.id,item.headline,"Downloaded on the server. Library import may still be pending; open to check.","transfer"))
            }
            next.remove(item.id);next[item.id]=state
        }
        // Keep missing entries across partial failures so recovery cannot replay all events.
        prefs.edit().putString(key,Json.encodeToString(next.entries.toList().takeLast(500).associate {it.toPair()})).apply()
    }
}

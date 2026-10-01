package com.pocketds.hub.offline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Calls [onChange] whenever [OfflineRepository] reports a change, between
 * [start] and [stop]. Both are safe to call twice, so a screen can start in
 * onShow and stop in onHide and onDestroyView without its own flag.
 */
class OfflineChanges(private val onChange: () -> Unit) {
    private var registeredWith: Context? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = onChange()
    }

    fun start(context: Context) {
        if (registeredWith != null) return
        ContextCompat.registerReceiver(context, receiver, IntentFilter(OfflineRepository.ACTION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        registeredWith = context
    }

    fun stop() {
        registeredWith?.unregisterReceiver(receiver)
        registeredWith = null
    }
}

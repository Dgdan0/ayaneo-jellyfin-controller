package com.pocketds.hub.offline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class OfflineBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED &&
            OfflineRepository.get(context).batches().any { batch ->
                !batch.paused && batch.jobs.any { it.state != OfflineState.COMPLETE }
            }
        ) {
            OfflineDownloadService.start(context)
        }
    }
}

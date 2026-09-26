package com.pocketds.hub.alerts

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.LocalAlerts
import com.pocketds.hub.settings.TransferAlertObserver
import kotlinx.coroutines.*

/** Android batches server checks; local download and subtitle completions publish immediately. */
class TransferAlertJob : JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var work: Job?=null
    override fun onStartJob(params: JobParameters): Boolean {
        if(HubSettings.baseUrl(this).isBlank() || HubSettings.token(this).isBlank()) return false
        val profile=LocalAlerts.scope(this)
        work=scope.launch {
            var retry=false
            try {
                when(val response=HubClient(this@TransferAlertJob).activity(true)) {
                    is HubResult.Ok -> if(profile==LocalAlerts.scope(this@TransferAlertJob)) TransferAlertObserver.observe(this@TransferAlertJob,response.value)
                    is HubResult.Failed -> retry=true
                }
            } catch(cancelled: CancellationException) {throw cancelled}
            catch(_: Exception) {retry=true}
            jobFinished(params,retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {work?.cancel();return true}
    override fun onDestroy() {scope.cancel();super.onDestroy()}
    companion object {
        fun schedule(context: Context) {
            if(HubSettings.baseUrl(context).isBlank()) return
            val scheduler=context.getSystemService(JobScheduler::class.java)
            if(scheduler.getPendingJob(4819)!=null) return
            scheduler.schedule(JobInfo.Builder(4819,ComponentName(context,TransferAlertJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000L).setPersisted(true).build())
        }
    }
}

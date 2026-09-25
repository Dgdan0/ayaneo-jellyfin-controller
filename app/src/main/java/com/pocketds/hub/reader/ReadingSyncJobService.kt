package com.pocketds.hub.reader

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.*

/** Android restarts this network-constrained job after process death or reboot. */
class ReadingSyncJobService:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var running:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        running=scope.launch {
            val retry=try { ReadingProgress.get(this@ReadingSyncJobService).flush() }
            catch(e:CancellationException) { throw e }
            catch(_:Exception) { true }
            jobFinished(params,retry)
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean { running?.cancel();return true }
    override fun onDestroy() { scope.cancel();super.onDestroy() }
}

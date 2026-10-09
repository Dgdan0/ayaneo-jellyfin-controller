package com.pocketds.hub.reader

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.pocketds.hub.model.EpubPositionBody
import com.pocketds.hub.model.ReadingAudioPosition
import com.pocketds.hub.model.ReadingPublicationProgressBody
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubConnection
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.screens.home.ReadingListsRepository
import com.pocketds.hub.screens.home.ReadingListsState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide durable outbox. Credentials stay in settings, never in checkpoint files or jobs. */
class ReadingProgress private constructor(private val context: Context) {
    val store=ReadingCheckpointStore(File(context.filesDir,"reading-checkpoints"))
    /** What this device has applied of the hub's start-overs (#60). */
    val resets=ReadingResets(PrefsResetLedger(context),store)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex=Mutex()
    private var scheduled:Job?=null
    private var cachedSession:Session?=null

    class Session(val identity:String,val api:HubClient) {
        fun key(workId:String,sourceItemId:String,kind:String)=ReadingCheckpointKey(identity,workId,sourceItemId,kind)
    }

    @Synchronized fun session():Session {
        val connection=HubConnection.capture(context)
        val identity=ReadingCheckpointKey.digest(listOf(connection.baseUrl.trimEnd('/'),connection.token,connection.userId).joinToString("\u0000"))
        return cachedSession?.takeIf { it.identity==identity } ?: Session(identity,HubClient(context,connection)).also { cachedSession=it }
    }

    // The local write completes before the view can be destroyed. Only network traffic is debounced.
    // [sync] false keeps the write on this device until the caller asks for a sync itself: the
    // audiobook player sends its place no more often than every 15 seconds (#19, A4).
    fun save(key:ReadingCheckpointKey,location:ReadingLocation,sync:Boolean=true) {
        store.save(key,location,System.currentTimeMillis())
        // Remove immediately, including offline. Never mutate a newly selected profile's shelves.
        val fraction = ((location.locator?.get("locations") as? JsonObject)?.get("totalProgression") as? JsonPrimitive)?.doubleOrNull
        if (key.scope == session().identity && ((fraction != null && fraction.isFinite() && fraction > 0.0) ||
                (location.pageIndex ?: 0) > 0 || AudioPlace.started(location))) {
            ReadingListsRepository.update(context) { it.remove(ReadingListsState.WANT_TO_READ, key.workId) }
        }
        if (sync) requestSync()
    }

    /**
     * The listening place the hub last answered for [key], with what the
     * outbox does not keep: whether it is exact or a proportion of a reader's
     * place (#19), and the sentence spoken there.
     */
    fun lastAudioPosition(key:ReadingCheckpointKey):ReadingAudioPosition? = audioPositions[key]
    private val audioPositions=java.util.concurrent.ConcurrentHashMap<ReadingCheckpointKey,ReadingAudioPosition>()

    /** Who wrote the place the hub last answered for [key], and when on the hub's clock (#62): "You listened further on ...". */
    class RemoteWriter(val device:String,val byThisDevice:Boolean,val stampMs:Long)
    private val writers=java.util.concurrent.ConcurrentHashMap<ReadingCheckpointKey,RemoteWriter>()
    fun writerOf(key:ReadingCheckpointKey):RemoteWriter? = writers[key]

    /**
     * The hub says [workId] was started over at [resetAt] (#60). When this device has not applied that, every
     * place it kept of the book goes: its checkpoints and outbox, the listening and read-along resume, what a
     * cached copy of its pages remembers, the legacy place of an audiobook out of its ZIP ([sourceItemIds] names
     * the editions it may be kept for), and a finish marked here. True when it was news.
     */
    fun noticeReset(scope:String,workId:String,resetAt:Long,sourceItemIds:List<String> = emptyList()):Boolean =
        resets.apply(scope,workId,resetAt) {
            audioPositions.keys.removeAll { it.scope==scope && it.workId==workId }
            runCatching { ReadingManifestCache.at(context).dropPlace(workId) }
            val legacy=ReadingResets.legacyAudioKeys(scope,workId,sourceItemIds)
            if (legacy.isNotEmpty()) ReadingAudio.positions(context).edit().apply { legacy.forEach(::remove) }.apply()
            ReadingCompletionRepository.update(context) { it.clear(workId) }
            ReadingListsRepository.update(context) { it.recordProgress(workId,0.0) }
        }

    /** What a refused write was refused for: the book was started over since the place it was made from (#60). */
    private suspend fun sent(bound:Session,key:ReadingCheckpointKey,result:HubResult<*>):Boolean {
        if (result is HubResult.Ok) return true
        // Read the book again: that is what applies the start over and drops this place, which is not retried.
        if (result is HubResult.Failed && result.code==RESET_CODE) fetch(bound,key)
        return false
    }

    @Synchronized fun requestSync(immediate:Boolean=false) {
        scheduleBackground()
        scheduled?.cancel()
        scheduled=scope.launch {
            if (!immediate) delay(1200)
            synchronized(this@ReadingProgress) { scheduled=null }
            try { flush() } catch (e:CancellationException) { throw e } catch (_:Exception) { /* Durable job retries. */ }
        }
    }

    suspend fun fetch(session:Session,key:ReadingCheckpointKey):RemoteReadingPosition = when(key.kind) {
        "epub" -> when(val response=session.api.readingEpubPosition(key.workId,key.sourceItemId)) {
            is HubResult.Ok -> {
                noticeReset(session.identity,key.workId,response.value.resetAt,listOf(key.sourceItemId))
                writers[key]=RemoteWriter(response.value.device,response.value.byThisDevice,response.value.timestamp)
                RemoteReadingPosition.Available(response.value.locator?.let { ReadingLocation(locator=it) },response.value.resetAt)
            }
            is HubResult.Failed -> RemoteReadingPosition.Unavailable
        }
        // The place as the hub reads it, never its clock (#19): the hub stamps every write itself.
        AudioPlace.KIND -> when(val response=session.api.readingAudioPosition(key.workId,key.sourceItemId)) {
            is HubResult.Ok -> {
                noticeReset(session.identity,key.workId,response.value.resetAt,listOf(key.sourceItemId))
                val position=response.value.position
                if (position==null) audioPositions.remove(key) else audioPositions[key]=position
                if (position!=null) writers[key]=RemoteWriter(response.value.device,response.value.byThisDevice,position.timestamp)
                RemoteReadingPosition.Available(position?.let(AudioPlace::fromServer)?.location(),response.value.resetAt)
            }
            is HubResult.Failed -> RemoteReadingPosition.Unavailable
        }
        else -> when(val response=session.api.readingPublication(key.workId,key.sourceItemId)) {
            is HubResult.Ok -> {
                noticeReset(session.identity,key.workId,response.value.resetAt,listOf(key.sourceItemId))
                // A series started over reads as page 0 here: its place is the hub's, which Kavita has cleared.
                RemoteReadingPosition.Available(ReadingLocation(pageIndex=response.value.currentPage),response.value.resetAt)
            }
            is HubResult.Failed -> RemoteReadingPosition.Unavailable
        }
    }

    /** Serial with flush so a late GET cannot roll the acknowledged baseline backwards. */
    suspend fun resume(session:Session,key:ReadingCheckpointKey):ReadingResume = withContext(Dispatchers.IO) {
        mutex.withLock { store.reconcile(key,fetch(session,key)) }
    }

    suspend fun flush():Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bound=session()
            val synchronizer=ReadingCheckpointSync(store,{ fetch(bound,it) },{ checkpoint ->
                if (session().identity != bound.identity) false
                else when(checkpoint.key.kind) {
                    "epub" -> sent(bound,checkpoint.key,bound.api.saveReadingEpubPosition(checkpoint.key.workId,checkpoint.key.sourceItemId,
                        EpubPositionBody(requireNotNull(checkpoint.local?.locator),checkpoint.updatedAt,
                            checkBase=true,expectedLocator=checkpoint.base?.locator,
                            resetSeen=resets.seen(bound.identity,checkpoint.key.workId))))
                    // Checked against the place last read (`expected`); a refusal is retried, and the
                    // next pass reads the place again and asks the person when it moved.
                    AudioPlace.KIND -> sent(bound,checkpoint.key,bound.api.saveReadingAudioPosition(checkpoint.key.workId,checkpoint.key.sourceItemId,
                        requireNotNull(AudioPlace.body(checkpoint,resetSeen=resets.seen(bound.identity,checkpoint.key.workId)))))
                    else -> sent(bound,checkpoint.key,bound.api.saveReadingPublicationCheckpoint(checkpoint.key.workId,checkpoint.key.sourceItemId,
                        ReadingPublicationProgressBody(requireNotNull(checkpoint.local?.pageIndex),checkpoint.base?.pageIndex,
                            resetSeen=resets.seen(bound.identity,checkpoint.key.workId))))
                }
            })
            var retry=false
            for (checkpoint in store.pending(bound.identity)) {
                if (session().identity != bound.identity) break
                if (synchronizer.sync(checkpoint.key)==CheckpointSyncResult.RETRY) retry=true
            }
            // Highlights and notes ride the same pass (#62): the same job, the same network rule, nothing of their own to schedule.
            if (session().identity == bound.identity && AnnotationRepository.get(context).flush(bound)) retry=true
            retry
        }
    }

    private fun scheduleBackground() {
        val scheduler=context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return
        scheduler.schedule(JobInfo.Builder(JOB_ID,ComponentName(context,ReadingSyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setMinimumLatency(5000).setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }

    companion object {
        /** The hub's code for a write made from a book since started over (#60). */
        const val RESET_CODE="reading_position_reset"
        private const val JOB_ID=8142
        @Volatile private var instance:ReadingProgress?=null
        fun get(context:Context):ReadingProgress=instance ?: synchronized(this) {
            instance ?: ReadingProgress(context.applicationContext).also { instance=it }
        }
    }
}

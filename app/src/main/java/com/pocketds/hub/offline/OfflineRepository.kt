package com.pocketds.hub.offline

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import com.pocketds.hub.model.OfflineManifest
import com.pocketds.hub.model.OfflineProgressEvent
import com.pocketds.hub.model.PlaybackItem
import com.pocketds.hub.model.PlaybackPrepareResponse
import com.pocketds.hub.model.PlaybackSource
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.OfflineSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

enum class OfflineState(val wire: String) {
    QUEUED("queued"), DOWNLOADING("downloading"), PAUSED("paused"),
    WAITING("waiting"), FAILED("failed"), COMPLETE("complete")
}

data class OfflineBatch(
    val id: String,
    val title: String,
    val seriesId: String,
    val userId: String,
    val paused: Boolean,
    val createdAt: Long,
    val jobs: List<OfflineDownload>
) {
    val completeCount get() = jobs.count { it.state == OfflineState.COMPLETE }
    val totalBytes get() = jobs.sumOf { it.totalBytes }
    val downloadedBytes get() = jobs.sumOf { it.bytesDownloaded.coerceAtMost(it.totalBytes) }
}

data class OfflineDownload(
    val id: String,
    val batchId: String,
    val userId: String,
    val manifest: OfflineManifest,
    val state: OfflineState,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val localPath: String,
    val error: String,
    val attempts: Int,
    val speedBytesPerSecond: Long,
    val sortOrder: Int,
    val updatedAt: Long
) {
    val progress: Float get() = if (totalBytes <= 0) 0f else
        (bytesDownloaded.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

/** Durable queue, local catalog, and offline watch-progress outbox. */
class OfflineRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val db = OfflineDatabase(app)

    init {
        // A process death interrupts a stream. Its verified partial file remains
        // useful, so put it back at the head of the resumable queue.
        db.writableDatabase.execSQL(
            "UPDATE downloads SET state=? WHERE state=?",
            arrayOf(OfflineState.QUEUED.wire, OfflineState.DOWNLOADING.wire)
        )
    }

    @Synchronized
    fun enqueue(title: String, seriesId: String, manifests: List<OfflineManifest>): Int {
        if (manifests.isEmpty()) return 0
        val root = OfflineSettings.selectedStorage(app)?.root ?: return 0
        val mediaDir = File(root, "media").apply { mkdirs() }
        File(root, "subtitles").mkdirs()
        File(root, "artwork").mkdirs()
        val userId = HubSettings.userId(app)
        val batchId = manifests.first().batchKey
        val now = System.currentTimeMillis()
        var inserted = 0
        db.writableDatabase.beginTransaction()
        try {
            db.writableDatabase.insertWithOnConflict(
                "batches", null, ContentValues().apply {
                    put("id", batchId); put("title", title); put("series_id", seriesId)
                    put("user_id", userId); put("paused", 0); put("created_at", now)
                }, SQLiteDatabase.CONFLICT_IGNORE
            )
            manifests.forEachIndexed { index, manifest ->
                val id = manifest.clientItemKey
                val path = File(mediaDir, mediaFileName(id, manifest)).absolutePath
                val result = db.writableDatabase.insertWithOnConflict(
                    "downloads", null, ContentValues().apply {
                        put("id", id); put("batch_id", batchId); put("user_id", userId)
                        put("item_id", manifest.item.id); put("source_id", manifest.source.id)
                        put("manifest_json", JSON.encodeToString(manifest))
                        put("state", OfflineState.QUEUED.wire); put("bytes_downloaded", 0)
                        put("total_bytes", manifest.source.sizeBytes); put("local_path", path)
                        put("error", ""); put("sort_order", index); put("updated_at", now)
                        put("attempts", 0)
                    }, SQLiteDatabase.CONFLICT_IGNORE
                )
                if (result != -1L) inserted++
            }
            db.writableDatabase.setTransactionSuccessful()
        } finally {
            db.writableDatabase.endTransaction()
        }
        changed()
        return inserted
    }

    @Synchronized
    fun batches(userId: String = HubSettings.userId(app)): List<OfflineBatch> {
        val rows = mutableListOf<OfflineBatch>()
        db.readableDatabase.rawQuery(
            "SELECT id,title,series_id,user_id,paused,created_at FROM batches " +
                "WHERE user_id=? ORDER BY created_at DESC", arrayOf(userId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.string("id")
                rows += OfflineBatch(
                    id, cursor.string("title"), cursor.string("series_id"), cursor.string("user_id"),
                    cursor.int("paused") != 0, cursor.long("created_at"), downloadsForBatch(id)
                )
            }
        }
        return rows
    }

    @Synchronized
    fun completed(userId: String = HubSettings.userId(app)): List<OfflineDownload> =
        queryDownloads("user_id=? AND state=?", arrayOf(userId, OfflineState.COMPLETE.wire),
            "updated_at DESC")

    @Synchronized
    fun completedForItem(itemId: String, userId: String = HubSettings.userId(app)): OfflineDownload? =
        queryDownloads("user_id=? AND item_id=? AND state=?", arrayOf(userId, itemId, OfflineState.COMPLETE.wire),
            "updated_at DESC", "1").firstOrNull()?.takeIf { verifyCompletedFile(it) }

    @Synchronized
    fun forItem(itemId: String, userId: String = HubSettings.userId(app)): OfflineDownload? =
        queryDownloads("user_id=? AND item_id=?", arrayOf(userId, itemId), "updated_at DESC", "1").firstOrNull()

    @Synchronized
    fun nextQueued(): OfflineDownload? {
        val userId = HubSettings.userId(app)
        return queryDownloads(
            "d.user_id=? AND d.state IN (?,?) AND b.paused=0",
            arrayOf(userId, OfflineState.QUEUED.wire, OfflineState.WAITING.wire),
            "b.created_at ASC,d.sort_order ASC", "1", joined = true
        ).firstOrNull()
    }

    @Synchronized
    fun download(id: String): OfflineDownload? =
        queryDownloads("id=?", arrayOf(id), "updated_at DESC", "1").firstOrNull()

    @Synchronized
    fun updateProgress(
        id: String,
        bytes: Long,
        state: OfflineState = OfflineState.DOWNLOADING,
        speedBytesPerSecond: Long = 0L
    ) {
        db.writableDatabase.update("downloads", ContentValues().apply {
            put("bytes_downloaded", bytes); put("state", state.wire); put("error", "")
            put("speed_bps", speedBytesPerSecond.coerceAtLeast(0L))
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id))
        changed()
    }

    @Synchronized
    fun updateManifest(id: String, manifest: OfflineManifest) {
        db.writableDatabase.update("downloads", ContentValues().apply {
            put("manifest_json", JSON.encodeToString(manifest)); put("total_bytes", manifest.source.sizeBytes)
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id))
        changed()
    }

    @Synchronized
    fun setState(id: String, state: OfflineState, error: String = "") {
        db.writableDatabase.update("downloads", ContentValues().apply {
            put("state", state.wire); put("error", error.take(300)); put("updated_at", System.currentTimeMillis())
            if (state != OfflineState.DOWNLOADING) put("speed_bps", 0L)
        }, "id=?", arrayOf(id))
        changed()
    }

    @Synchronized
    fun finish(id: String) {
        val row = download(id) ?: return
        if (!verifyFile(row.localPath, row.totalBytes)) {
            setState(id, OfflineState.FAILED, "Downloaded file did not match its expected size")
            return
        }
        db.writableDatabase.update("downloads", ContentValues().apply {
            put("state", OfflineState.COMPLETE.wire); put("bytes_downloaded", row.totalBytes)
            put("speed_bps", 0L); put("error", ""); put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id))
        changed()
    }

    @Synchronized
    fun setBatchPaused(batchId: String, paused: Boolean) {
        db.writableDatabase.update("batches", ContentValues().apply { put("paused", if (paused) 1 else 0) },
            "id=?", arrayOf(batchId))
        if (paused) db.writableDatabase.execSQL(
            "UPDATE downloads SET state=? WHERE batch_id=? AND state IN (?,?)",
            arrayOf(OfflineState.PAUSED.wire, batchId, OfflineState.QUEUED.wire, OfflineState.WAITING.wire)
        ) else db.writableDatabase.execSQL(
            "UPDATE downloads SET state=? WHERE batch_id=? AND state=?",
            arrayOf(OfflineState.QUEUED.wire, batchId, OfflineState.PAUSED.wire)
        )
        changed()
    }

    @Synchronized
    fun setItemPaused(id: String, paused: Boolean) {
        setState(id, if (paused) OfflineState.PAUSED else OfflineState.QUEUED)
    }

    @Synchronized
    fun retry(id: String) {
        db.writableDatabase.update("downloads", ContentValues().apply {
            put("state", OfflineState.QUEUED.wire); put("attempts", 0); put("error", "")
            put("speed_bps", 0L)
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id))
        changed()
    }

    @Synchronized
    fun recordFailure(id: String, message: String, maxRetries: Int) {
        val row = download(id) ?: return
        val attempts = row.attempts + 1
        db.writableDatabase.update("downloads", ContentValues().apply {
            put("attempts", attempts)
            put("state", if (attempts > maxRetries) OfflineState.FAILED.wire else OfflineState.WAITING.wire)
            put("speed_bps", 0L)
            put("error", message.take(300)); put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id))
        changed()
    }

    @Synchronized
    fun remove(id: String) {
        download(id)?.let {
            File(it.localPath).delete()
            subtitleFiles(it).forEach(File::delete)
            artworkFiles(it).forEach(File::delete)
            db.writableDatabase.delete("downloads", "id=?", arrayOf(id))
            removeEmptyBatch(it.batchId)
        }
        changed()
    }

    @Synchronized
    fun removeBatch(batchId: String) {
        downloadsForBatch(batchId).forEach {
            File(it.localPath).delete()
            subtitleFiles(it).forEach(File::delete)
            artworkFiles(it).forEach(File::delete)
        }
        db.writableDatabase.delete("downloads", "batch_id=?", arrayOf(batchId))
        db.writableDatabase.delete("batches", "id=?", arrayOf(batchId))
        changed()
    }

    @Synchronized
    fun cancelBatch(batchId: String, removeCompleted: Boolean) {
        downloadsForBatch(batchId).forEach { row ->
            if (removeCompleted || row.state != OfflineState.COMPLETE) {
                File(row.localPath).delete()
                subtitleFiles(row).forEach(File::delete)
                artworkFiles(row).forEach(File::delete)
                db.writableDatabase.delete("downloads", "id=?", arrayOf(row.id))
            }
        }
        removeEmptyBatch(batchId)
        changed()
    }

    fun mediaFile(row: OfflineDownload): File = File(row.localPath)

    fun subtitleFile(row: OfflineDownload, trackIndex: Int, codec: String): File =
        File(storageRoot(row), "subtitles/${safe(row.id)}-$trackIndex.${subtitleExtension(codec)}")

    fun artworkFile(row: OfflineDownload, kind: String): File =
        File(storageRoot(row), "artwork/${safe(row.id)}-${safe(kind)}.img")

    fun availableBytes(): Long = OfflineSettings.selectedStorage(app)?.availableBytes ?: 0L

    fun availableBytes(row: OfflineDownload): Long = storageRoot(row).let { root ->
        if (root.exists()) root.usableSpace else 0L
    }

    fun selectedStorageAvailable(): Boolean = OfflineSettings.selectedStorage(app) != null

    @Synchronized
    fun playbackPlan(itemId: String, startMode: String): PlaybackPrepareResponse? {
        val row = completedForItem(itemId) ?: return null
        val item = row.manifest.item
        val source = row.manifest.source
        val progress = progress(itemId)
        val embeddedSubtitles = source.tracks.filter { it.type.equals("Subtitle", true) && !it.external }
        val externalSubtitles = row.manifest.subtitles.mapNotNull { subtitle ->
            val file = subtitleFile(row, subtitle.track.index, subtitle.track.codec)
            subtitle.track.takeIf { file.isFile }?.copy(externalUrl = Uri.fromFile(file).toString())
        }
        val duration = (item.runtimeSeconds * 1_000L).coerceAtLeast(progress?.second ?: 0L)
        val remembered = progress?.first ?: item.positionSeconds * 1_000L
        val position = if (startMode == "restart") 0L else remembered
        val audio = source.tracks.filter { it.type.equals("Audio", true) }
        val subtitles = embeddedSubtitles + externalSubtitles
        val siblings = if (item.seriesId.isNotEmpty()) completed()
            .filter { it.manifest.item.seriesId == item.seriesId && verifyFile(it.localPath, it.totalBytes) }
            .sortedWith(compareBy({ it.manifest.item.seasonNumber }, { it.manifest.item.indexNumber }))
        else emptyList()
        val siblingIndex = siblings.indexOfFirst { it.manifest.item.id == item.id }
        val previous = siblings.getOrNull(siblingIndex - 1)?.manifest?.item?.let(::playbackItem)
        val next = siblings.getOrNull(siblingIndex + 1)?.manifest?.item?.let(::playbackItem)
        return PlaybackPrepareResponse(
            sessionId = "offline:${row.id}",
            item = playbackItem(item),
            positionMillis = position,
            durationMillis = duration,
            mediaUrl = Uri.fromFile(File(row.localPath)).toString(),
            mimeType = source.mimeType,
            playMethod = "Offline",
            bitrate = source.bitrate,
            sources = listOf(PlaybackSource(source.id, source.name, source.container, source.sizeBytes, source.bitrate)),
            audioTracks = audio,
            subtitleTracks = subtitles,
            selectedMediaSourceId = source.id,
            selectedAudioIndex = audio.firstOrNull { it.default }?.index ?: audio.firstOrNull()?.index,
            selectedSubtitleIndex = subtitles.firstOrNull { it.default }?.index,
            previousItem = previous,
            nextItem = next,
            offline = true,
            offlineDownloadId = row.id
        )
    }

    private fun playbackItem(item: com.pocketds.hub.model.LibraryItem) = PlaybackItem(
        id = item.id, type = item.type, title = item.title, seriesTitle = item.seriesTitle,
        seriesId = item.seriesId, seasonId = item.seasonId,
        seasonNumber = item.seasonNumber, episodeNumber = item.indexNumber
    )

    @Synchronized
    fun rememberPlayback(itemId: String, position: Long, duration: Long, completed: Boolean) {
        val userId = HubSettings.userId(app)
        val now = System.currentTimeMillis()
        db.writableDatabase.insertWithOnConflict("progress", null, ContentValues().apply {
            put("item_id", itemId); put("user_id", userId); put("position_ms", position)
            put("duration_ms", duration); put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        val event = OfflineProgressEvent(
            clientEventKey = "${itemId.take(12)}-$now-${UUID.randomUUID().toString().take(8)}",
            itemId = itemId, positionMillis = position, durationMillis = duration,
            completed = completed, occurredAt = now
        )
        db.writableDatabase.delete("outbox", "user_id=? AND item_id=?", arrayOf(userId, itemId))
        db.writableDatabase.insertOrThrow("outbox", null, ContentValues().apply {
            put("event_key", event.clientEventKey); put("user_id", userId); put("item_id", itemId)
            put("event_json", JSON.encodeToString(event)); put("created_at", now)
        })
        changed()
    }

    @Synchronized
    fun outbox(limit: Int = 50): List<OfflineProgressEvent> {
        val values = mutableListOf<OfflineProgressEvent>()
        db.readableDatabase.rawQuery(
            "SELECT event_json FROM outbox WHERE user_id=? ORDER BY created_at ASC LIMIT ?",
            arrayOf(HubSettings.userId(app), limit.toString())
        ).use { while (it.moveToNext()) values += JSON.decodeFromString<OfflineProgressEvent>(it.getString(0)) }
        return values
    }

    @Synchronized
    fun removeOutbox(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val placeholders = keys.joinToString(",") { "?" }
        db.writableDatabase.delete("outbox", "event_key IN ($placeholders)", keys.toTypedArray())
    }

    private fun progress(itemId: String): Pair<Long, Long>? {
        db.readableDatabase.rawQuery(
            "SELECT position_ms,duration_ms FROM progress WHERE user_id=? AND item_id=?",
            arrayOf(HubSettings.userId(app), itemId)
        ).use { if (it.moveToFirst()) return it.getLong(0) to it.getLong(1) }
        return null
    }

    private fun downloadsForBatch(batchId: String) = queryDownloads(
        "batch_id=?", arrayOf(batchId), "sort_order ASC"
    )

    private fun queryDownloads(
        where: String,
        args: Array<String>,
        order: String,
        limit: String? = null,
        joined: Boolean = false
    ): List<OfflineDownload> {
        val alias = if (joined) "d." else ""
        val table = if (joined) "downloads d JOIN batches b ON b.id=d.batch_id" else "downloads"
        val columns = listOf("id", "batch_id", "user_id", "manifest_json", "state", "bytes_downloaded",
            "total_bytes", "local_path", "error", "attempts", "speed_bps", "sort_order", "updated_at")
            .joinToString(",") { alias + it }
        val rows = mutableListOf<OfflineDownload>()
        db.readableDatabase.query(table, columns.split(',').toTypedArray(), where, args, null, null, order, limit)
            .use { cursor -> while (cursor.moveToNext()) rows += cursor.download() }
        return rows
    }

    private fun Cursor.download() = OfflineDownload(
        string("id"), string("batch_id"), string("user_id"),
        JSON.decodeFromString(string("manifest_json")),
        OfflineState.entries.firstOrNull { it.wire == string("state") } ?: OfflineState.FAILED,
        long("bytes_downloaded"), long("total_bytes"), string("local_path"), string("error"), int("attempts"),
        long("speed_bps"), int("sort_order"), long("updated_at")
    )

    private fun verifyCompletedFile(row: OfflineDownload): Boolean {
        if (verifyFile(row.localPath, row.totalBytes)) return true
        setState(row.id, OfflineState.FAILED, "The downloaded file is missing or incomplete")
        return false
    }

    private fun verifyFile(path: String, size: Long) = File(path).let { it.isFile && it.length() == size }

    private fun removeEmptyBatch(batchId: String) {
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM downloads WHERE batch_id=?", arrayOf(batchId)).use {
            if (it.moveToFirst() && it.getInt(0) == 0) db.writableDatabase.delete("batches", "id=?", arrayOf(batchId))
        }
    }

    private fun storageRoot(row: OfflineDownload): File =
        File(row.localPath).parentFile?.parentFile
            ?: File(app.getExternalFilesDir(null) ?: app.filesDir, "offline")

    private fun subtitleFiles(row: OfflineDownload): Array<File> =
        File(storageRoot(row), "subtitles")
            .listFiles { file -> file.name.startsWith(safe(row.id) + "-") } ?: emptyArray()
    private fun artworkFiles(row: OfflineDownload): Array<File> =
        File(storageRoot(row), "artwork")
            .listFiles { file -> file.name.startsWith(safe(row.id) + "-") } ?: emptyArray()

    private fun changed() {
        app.sendBroadcast(android.content.Intent(ACTION_CHANGED).setPackage(app.packageName))
    }

    private fun mediaFileName(id: String, manifest: OfflineManifest): String =
        safe(id) + "." + manifest.source.container.ifBlank { "media" }.lowercase()

    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
    private fun subtitleExtension(codec: String) = when (codec.lowercase()) {
        "srt", "subrip" -> "srt"
        "ass", "ssa" -> "ass"
        else -> "vtt"
    }

    companion object {
        const val ACTION_CHANGED = "com.pocketds.hub.offline.CHANGED"
        private val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
        @Volatile private var instance: OfflineRepository? = null
        fun get(context: Context): OfflineRepository = instance ?: synchronized(this) {
            instance ?: OfflineRepository(context).also { instance = it }
        }
    }
}

private class OfflineDatabase(context: Context) : SQLiteOpenHelper(context, "offline.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE batches(id TEXT PRIMARY KEY,title TEXT NOT NULL,series_id TEXT NOT NULL,user_id TEXT NOT NULL,paused INTEGER NOT NULL,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE downloads(id TEXT PRIMARY KEY,batch_id TEXT NOT NULL,user_id TEXT NOT NULL,item_id TEXT NOT NULL,source_id TEXT NOT NULL,manifest_json TEXT NOT NULL,state TEXT NOT NULL,bytes_downloaded INTEGER NOT NULL,total_bytes INTEGER NOT NULL,local_path TEXT NOT NULL,error TEXT NOT NULL,attempts INTEGER NOT NULL,speed_bps INTEGER NOT NULL DEFAULT 0,sort_order INTEGER NOT NULL,updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX downloads_media ON downloads(user_id,item_id,source_id)")
        db.execSQL("CREATE INDEX downloads_queue ON downloads(user_id,state,sort_order)")
        db.execSQL("CREATE TABLE progress(item_id TEXT NOT NULL,user_id TEXT NOT NULL,position_ms INTEGER NOT NULL,duration_ms INTEGER NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(item_id,user_id))")
        db.execSQL("CREATE TABLE outbox(event_key TEXT PRIMARY KEY,user_id TEXT NOT NULL,item_id TEXT NOT NULL,event_json TEXT NOT NULL,created_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN speed_bps INTEGER NOT NULL DEFAULT 0")
        }
    }
}

private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))

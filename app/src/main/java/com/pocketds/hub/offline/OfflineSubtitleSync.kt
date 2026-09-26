package com.pocketds.hub.offline

import com.pocketds.hub.model.OfflineManifest
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.net.FailureKind
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale

class SubtitleSyncBlocked(message: String): IOException(message)

/** Refreshes subtitle sidecars for an existing verified video; never requests its media URL. */
class OfflineSubtitleSync(private val repository: OfflineRepository, private val api: HubClient) {
    suspend fun sync(request: PendingSubtitleSync) {
        val row = repository.completedForRow(request.rowId) ?: return
        recoverInterruptedSwap(row)
        val renewal = api.renewOffline(row.manifest.grantId)
        val fresh = when (renewal) {
            is HubResult.Ok -> renewal.value
            is HubResult.Failed -> {
                if(renewal.kind==FailureKind.NOT_FOUND || renewal.kind==FailureKind.UNAUTHORIZED)
                    throw SubtitleSyncBlocked("Offline subtitle access needs attention: ${renewal.message}")
                throw IOException(renewal.message)
            }
        }
        validateSubtitleRefresh(row.manifest, fresh, row.totalBytes, request.expectedLanguage)

        val staged = mutableListOf<Pair<File, File>>()
        val backups = mutableListOf<Pair<File, File>>()
        val installed = mutableListOf<File>()
        var totalBytes = 0L
        try {
            if (fresh.subtitles.size > MAX_TRACKS) throw IOException("Too many subtitle tracks")
            for (subtitle in fresh.subtitles) {
                val target = repository.subtitleFile(row, subtitle.track.index, subtitle.track.codec)
                target.parentFile?.mkdirs()
                val temporary = File(target.absolutePath + ".sync-part")
                temporary.delete()
                staged += temporary to target
                api.offlineDownloadCall(subtitle.url, 0).execute().use { http ->
                    if (!http.isSuccessful) throw IOException("Subtitle HTTP ${http.code}")
                    val body = http.body ?: throw IOException("Empty subtitle response")
                    body.byteStream().use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                totalBytes += count
                                if (total > MAX_SUBTITLE_BYTES) throw IOException("Subtitle file is too large")
                                if (totalBytes > MAX_TOTAL_BYTES) throw IOException("Subtitle update is too large")
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                            if (total == 0L) throw IOException("Subtitle file was empty")
                        }
                    }
                }
            }
            for ((temporary, target) in staged) {
                if (target.exists()) {
                    val backup = File(target.absolutePath + ".sync-backup")
                    backup.delete()
                    target.copyTo(backup)
                    backups += backup to target
                }
                Files.move(temporary.toPath(),target.toPath(),REPLACE_EXISTING)
                installed += target
            }
            repository.updateManifest(row.id, fresh)
            val retained = staged.map { it.second.absolutePath }.toSet()
            repository.localSubtitleFiles(row).filter { it.absolutePath !in retained }.forEach(File::delete)
            backups.forEach { it.first.delete() }
        } catch (error: Exception) {
            staged.forEach { it.first.delete() }
            installed.forEach(File::delete)
            backups.forEach { (backup, target) ->
                if (backup.isFile) Files.move(backup.toPath(),target.toPath(),REPLACE_EXISTING)
            }
            throw error
        }
    }

    private fun recoverInterruptedSwap(row: OfflineDownload) {
        repository.localSubtitleFiles(row).forEach { file ->
            when {
                file.name.endsWith(".sync-part") -> file.delete()
                file.name.endsWith(".sync-backup") -> {
                    val target = File(file.absolutePath.removeSuffix(".sync-backup"))
                    if(target.isFile) file.delete()
                    else Files.move(file.toPath(),target.toPath(),REPLACE_EXISTING)
                }
            }
        }
    }

    companion object {
        private const val MAX_TRACKS = 32
        private const val MAX_SUBTITLE_BYTES = 8L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
    }
}

internal fun validateSubtitleRefresh(original: OfflineManifest, fresh: OfflineManifest,
                                     offlineSize: Long, expectedLanguage: String) {
    if (fresh.item.id != original.item.id || fresh.source.id != original.source.id ||
        fresh.source.sizeBytes != offlineSize
    ) throw SubtitleSyncBlocked("The online video differs from this download; its subtitles were not copied")
    val expected=expectedLanguage.split(',').filter { it.isNotBlank() }.map(::subtitleLanguage)
    val indexed=fresh.subtitles.map { subtitleLanguage(it.track.language) }.toSet()
    if (expected.any { it !in indexed })
        throw IOException("Waiting for Jellyfin to index the selected subtitle ($expectedLanguage; indexed: ${fresh.subtitles.map { it.track.language }})")
}

private fun subtitleLanguage(code: String): String = when (code.lowercase(Locale.ROOT)) {
    "he", "heb", "iw" -> "he"
    "en", "eng" -> "en"
    "es", "spa" -> "es"
    "fr", "fre", "fra" -> "fr"
    "hebrew" -> "he"
    "english" -> "en"
    "spanish" -> "es"
    "french" -> "fr"
    else -> Locale.forLanguageTag(code).language.lowercase(Locale.ROOT)
}

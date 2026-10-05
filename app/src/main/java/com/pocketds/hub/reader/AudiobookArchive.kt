package com.pocketds.hub.reader

import java.io.File
import java.util.zip.ZipFile

/**
 * A part of an audiobook on the reading-audio player: a track the hub streams
 * ([uri], its [trackId] and the [cacheKey] its bytes are kept under, #19), or a
 * [file] taken out of the whole book's ZIP when the hub cannot stream it.
 * [durationMs] and [bytes] are known up front for a track, from its manifest.
 */
data class AudiobookPart(
    val title: String,
    val file: File? = null,
    val uri: String = "",
    val durationMs: Long? = null,
    val bytes: Long? = null,
    val trackId: String = "",
    val cacheKey: String = ""
) {
    val streamed: Boolean get() = file == null && uri.isNotEmpty()
}

/** Storyteller returns audiobook parts as one ZIP; extract only known audio formats. */
object AudiobookArchive {
    private val audioExtensions = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "opus", "wav")
    private const val MAX_PART_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_BOOK_BYTES = 12L * 1024 * 1024 * 1024

    /** A part's name to show: its file name without the audio extension ("01 Opening.wav" is "01 Opening"). */
    fun partLabel(title: String): String {
        val suffix = title.substringAfterLast('.', "").lowercase()
        return if (suffix in audioExtensions && title.length > suffix.length + 1) title.substringBeforeLast('.') else title
    }

    fun hasPlayableAudio(file: File): Boolean = runCatching {
        ZipFile(file).use { zip -> zip.entries().asSequence().any(::isAudio) }
    }.getOrDefault(false)

    fun extract(file: File, directory: File, checkCancelled: () -> Unit = {}): List<AudiobookPart> {
        directory.mkdirs()
        return ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filter(::isAudio).sortedWith(PART_ORDER).toList()
            require(entries.isNotEmpty()) { "The audiobook archive contains no supported audio" }
            require(entries.size <= 500) { "Too many audiobook parts" }
            var total = 0L
            entries.map { entry ->
                checkCancelled()
                require(entry.size in 1..MAX_PART_BYTES) { "Invalid audiobook part" }
                total += entry.size
                require(total <= MAX_BOOK_BYTES) { "Audiobook is too large" }
                val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                val suffix = name.substringAfterLast('.').lowercase()
                val target = File(directory, ReadingCheckpointKey.digest(entry.name + ":" + entry.crc) + "." + suffix)
                if (target.length() != entry.size) {
                    require(directory.usableSpace > entry.size + (32L shl 20)) { "Not enough space for audiobook" }
                    val temporary = File(directory, target.name + ".part")
                    try {
                        zip.getInputStream(entry).use { input -> temporary.outputStream().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            var written = 0L
                            while (true) {
                                checkCancelled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                written += count
                                require(written <= entry.size)
                                output.write(buffer, 0, count)
                            }
                            require(written == entry.size)
                        } }
                        require(temporary.renameTo(target)) { "Could not save audiobook part" }
                    } finally { temporary.delete() }
                }
                AudiobookPart(name, target, bytes = entry.size)
            }
        }
    }

    /**
     * Each part's size in the order the ZIP's parts were played, read from its
     * directory alone: how a place this device kept by part number is found
     * among the hub's tracks, whose order may differ (#19: Dark Matter's
     * unsuffixed file sorted last here and is the first track by its tags).
     */
    fun partSizes(file: File): List<Long> = runCatching {
        ZipFile(file).use { zip -> zip.entries().asSequence().filter(::isAudio).sortedWith(PART_ORDER).map { it.size }.toList() }
    }.getOrDefault(emptyList())

    private val PART_ORDER = compareBy<java.util.zip.ZipEntry> { numericSortKey(it.name) }.thenBy { it.name.lowercase() }

    private fun isAudio(entry: java.util.zip.ZipEntry): Boolean =
        !entry.isDirectory && !entry.name.startsWith("/") &&
            entry.name.replace('\\', '/').split('/').none { it == ".." } &&
            entry.name.substringAfterLast('.').lowercase() in audioExtensions

    private fun numericSortKey(name: String): String = Regex("\\d+|\\D+").findAll(name.lowercase())
        .joinToString("") { part -> part.value.toLongOrNull()?.toString()?.padStart(12, '0') ?: part.value }
}

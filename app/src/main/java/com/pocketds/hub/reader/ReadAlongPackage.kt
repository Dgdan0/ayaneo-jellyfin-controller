package com.pocketds.hub.reader

import java.io.File
import java.net.URI
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

data class ReadAlongSegment(val textHref: String, val fragment: String, val audioHref: String, val beginMs: Long, val endMs: Long)
data class ReadAlongPosition(val track: Int, val offsetMs: Long)
data class ReadAlongTrack(val audioHref: String, val segments: List<ReadAlongSegment>) {
    val startMs get() = segments.first().beginMs
    val durationMs get() = segments.last().endMs - startMs
}
data class ReadAlongTimeline(val tracks: List<ReadAlongTrack>) {
    fun active(track: Int, offsetMs: Long): ReadAlongSegment? {
        val value = tracks.getOrNull(track) ?: return null
        val absolute = value.startMs + offsetMs
        // Word-level overlays can contain hundreds of thousands of segments.
        // Playback asks for the active segment on every position tick.
        var low = 0
        var high = value.segments.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (value.segments[middle].beginMs <= absolute) low = middle + 1
            else high = middle - 1
        }
        return value.segments.getOrNull(high)?.takeIf { absolute < it.endMs }
    }
    fun find(href: String, fragment: String): ReadAlongPosition? {
        tracks.forEachIndexed { index, track ->
            track.segments.firstOrNull { it.textHref == href && it.fragment == fragment }?.let {
                return ReadAlongPosition(index, it.beginMs - track.startMs)
            }
        }
        return null
    }
}

/** EPUB 3 media overlays. Only in-package resources are accepted; source files are never modified. */
object ReadAlongPackage {
    private const val XML_LIMIT = 4 * 1024 * 1024
    private const val AUDIO_LIMIT = 2L * 1024 * 1024 * 1024

    fun read(file: File): ReadAlongTimeline = ZipFile(file).use { zip ->
        val container = xml(zip, "META-INF/container.xml")
        val opf = container.children("rootfile").firstOrNull()?.getAttribute("full-path") ?: error("No EPUB package")
        val packagePath = resolve("", opf).first
        val doc = xml(zip, packagePath)
        val manifest = doc.children("item").associateBy { it.getAttribute("id") }
        val segments = mutableListOf<ReadAlongSegment>()
        for (ref in doc.children("itemref")) {
            val chapter = manifest[ref.getAttribute("idref")] ?: continue
            val overlayId = chapter.getAttribute("media-overlay").takeIf { it.isNotBlank() } ?: continue
            val overlay = manifest[overlayId] ?: error("Missing media overlay")
            val smilPath = resolve(packagePath, overlay.getAttribute("href")).first
            for (par in xml(zip, smilPath).children("par")) {
                val text = par.directChild("text") ?: continue
                val audio = par.directChild("audio") ?: continue
                val (href, fragment) = resolve(smilPath, text.getAttribute("src"))
                require(fragment.isNotBlank()) { "Narrated text needs a fragment" }
                val audioHref = resolve(smilPath, audio.getAttribute("src")).first
                require(zip.getEntry(href) != null && zip.getEntry(audioHref) != null) { "Missing narration resource" }
                val begin = clock(audio.getAttribute("clipBegin").ifBlank { "0s" })
                val end = clock(audio.getAttribute("clipEnd"))
                // Word alignment can legitimately emit a zero-length boundary
                // for an unmatched word. It has no audio to highlight; keep
                // the rest of the edition playable.
                if (end == begin) continue
                require(end > begin) { "Invalid narration interval" }
                segments += ReadAlongSegment(href, fragment, audioHref, begin, end)
                require(segments.size <= 200_000) { "Narration timeline is too large" }
            }
        }
        require(segments.isNotEmpty()) { "This edition has no aligned narration" }
        val tracks = mutableListOf<ReadAlongTrack>()
        var group = mutableListOf<ReadAlongSegment>()
        for (segment in segments) {
            val previous = group.lastOrNull()
            if (previous != null && (previous.audioHref != segment.audioHref || segment.beginMs < previous.endMs)) {
                tracks += ReadAlongTrack(previous.audioHref, group.toList())
                group = mutableListOf()
            }
            group += segment
        }
        if (group.isNotEmpty()) tracks += ReadAlongTrack(group.first().audioHref, group)
        ReadAlongTimeline(tracks)
    }

    fun extractAudio(file: File, timeline: ReadAlongTimeline, directory: File, checkCancelled: () -> Unit = {}): List<File> {
        directory.mkdirs()
        return ZipFile(file).use { zip ->
            val extracted = mutableMapOf<String, File>()
            timeline.tracks.map { track -> extracted.getOrPut(track.audioHref) {
                checkCancelled()
                val entry = zip.getEntry(track.audioHref) ?: error("Missing audio")
                require(entry.size in 1..AUDIO_LIMIT) { "Invalid audio size" }
                val target = File(directory, ReadingCheckpointKey.digest(track.audioHref + ":" + entry.crc) + ".audio")
                if (target.length() != entry.size) {
                    require(directory.usableSpace > entry.size + (32L shl 20)) { "Not enough space for narration" }
                    val partial = File(directory, target.name + ".part")
                    try {
                        zip.getInputStream(entry).use { input -> partial.outputStream().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            var total = 0L
                            while (true) {
                                checkCancelled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= entry.size && total <= AUDIO_LIMIT)
                                output.write(buffer, 0, count)
                            }
                            require(total == entry.size)
                        } }
                        require(partial.renameTo(target)) { "Could not save narration" }
                    } finally { partial.delete() }
                }
                target
            } }
        }
    }

    internal fun clock(raw: String): Long {
        val text = raw.trim().removePrefix("npt=")
        val seconds = when {
            ':' in text -> {
                val parts = text.split(':').map(String::toDouble)
                require(parts.size in 2..3 && parts.all { it >= 0 && it.isFinite() })
                parts.fold(0.0) { value, part -> value * 60 + part }
            }
            text.endsWith("ms") -> text.dropLast(2).toDouble() / 1000
            text.endsWith("min") -> text.dropLast(3).toDouble() * 60
            text.endsWith("h") -> text.dropLast(1).toDouble() * 3600
            else -> text.removeSuffix("s").toDouble()
        }
        require(seconds.isFinite() && seconds >= 0 && seconds < 365.0 * 24 * 3600)
        return (seconds * 1000).toLong()
    }

    private fun resolve(base: String, relative: String): Pair<String, String> {
        require(relative.isNotBlank() && '\\' !in relative)
        val uri = URI(relative.replace(" ", "%20"))
        require(!uri.isAbsolute && uri.rawAuthority == null && uri.query == null && !uri.path.startsWith('/')) { "External narration resource" }
        val resolved = URI(base.replace(" ", "%20")).resolve(uri).normalize()
        val path = resolved.path
        require(path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it == ".." || it == "." } && '\\' !in path)
        return path to resolved.fragment.orEmpty()
    }

    private fun xml(zip: ZipFile, path: String): Element {
        val entry = zip.getEntry(path) ?: error("Missing EPUB document")
        require(entry.size in 1..XML_LIMIT.toLong())
        val bytes = zip.getInputStream(entry).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= XML_LIMIT)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val text = bytes.toString(Charsets.UTF_8)
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "External XML entities are forbidden" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val parser = factory.newDocumentBuilder()
        parser.setEntityResolver { _, _ -> error("External XML is forbidden") }
        return parser.parse(InputSource(bytes.inputStream())).documentElement
    }
    private fun Element.children(name: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
    private fun Element.directChild(name: String): Element? = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }.firstOrNull { (it.localName ?: it.tagName) == name }
}

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

    /** A sentence with its place in the narration: which file, which one in it. */
    data class Located(val track: Int, val index: Int, val segment: ReadAlongSegment)

    /** Where [fragment] of [href] is narrated (the first sentence naming it), for the page's maths (#49). */
    fun locate(href: String, fragment: String): Located? {
        tracks.forEachIndexed { track, value ->
            val index = value.segments.indexOfFirst { it.textHref == href && it.fragment == fragment }
            if (index >= 0) return Located(track, index, value.segments[index])
        }
        return null
    }

    /** The sentence after [located], on into the next file at the end of one; null after the last. */
    fun after(located: Located): Located? {
        val value = tracks.getOrNull(located.track) ?: return null
        value.segments.getOrNull(located.index + 1)?.let { return Located(located.track, located.index + 1, it) }
        val next = tracks.getOrNull(located.track + 1)?.takeIf { it.segments.isNotEmpty() } ?: return null
        return Located(located.track + 1, 0, next.segments.first())
    }

    /** Where in its file [absoluteMs] of [track] is, as the player counts it. */
    fun positionAt(track: Int, absoluteMs: Long): ReadAlongPosition =
        ReadAlongPosition(track, absoluteMs - (tracks.getOrNull(track)?.startMs ?: 0L))

    /** Every element of [href] the narration names, in the order it reads them. */
    fun fragments(href: String): List<String> =
        tracks.flatMap { track -> track.segments.filter { it.textHref == href }.map { it.fragment } }.distinct()

    /**
     * L1 and R1 read along (#16, A5): where the sentence [delta] away from
     * [position] begins, across tracks. Back from more than [RESTART_MS] into
     * a sentence goes to its own start first, as a player's Previous does.
     * Null past either end.
     */
    fun step(position: ReadAlongPosition, delta: Int): ReadAlongPosition? {
        val all = tracks.flatMapIndexed { track, value -> value.segments.map { track to it } }
        if (all.isEmpty() || delta == 0) return null
        val track = tracks.getOrNull(position.track) ?: return null
        val absolute = track.startMs + position.offsetMs
        // The sentence playing, or the last one begun before a gap.
        val here = all.indexOfLast { (index, segment) -> index < position.track || (index == position.track && segment.beginMs <= absolute) }
        val target = when {
            here < 0 -> if (delta > 0) delta - 1 else return null
            delta < 0 && all[here].first == position.track && absolute - all[here].second.beginMs > RESTART_MS -> here + delta + 1
            else -> here + delta
        }
        val (index, segment) = all.getOrNull(target) ?: return null
        return ReadAlongPosition(index, segment.beginMs - tracks[index].startMs)
    }

    /** Some sentence of [href]'s text is narrated: the page can be followed. */
    fun narrates(href: String): Boolean = tracks.any { track -> track.segments.any { it.textHref == href } }

    companion object {
        /** Further into a sentence than this, back goes to its start rather than the sentence before. */
        const val RESTART_MS = 1_500L
    }
}

/**
 * What a read-along page says about the narration (#16, A5, #49): while it plays
 * the page and the voice move each other, so the page is always following, except
 * where this part of the book has no narration to follow.
 */
object ReadAlongFollow {
    fun label(narrated: Boolean): String = if (narrated) "Following" else "Alignment unavailable"
}

/** EPUB 3 media overlays. Only in-package resources are accepted; source files are never modified. */
object ReadAlongPackage {
    private const val XML_LIMIT = 4 * 1024 * 1024
    private const val AUDIO_LIMIT = 2L * 1024 * 1024 * 1024

    /**
     * The narration's timeline. [requireAudio] false reads the edition without
     * its audio (#19: the hub's slim edition), whose narration streams from the
     * audiobook's tracks: its SMIL still names the audio files, which are not
     * in the archive.
     */
    fun read(file: File, requireAudio: Boolean = true): ReadAlongTimeline = ZipFile(file).use { zip ->
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
                require(zip.getEntry(href) != null && (!requireAudio || zip.getEntry(audioHref) != null)) { "Missing narration resource" }
                val begin = clock(audio.getAttribute("clipBegin").ifBlank { "0s" })
                val end = clock(audio.getAttribute("clipEnd"))
                // Word alignment can emit a boundary of no length for a word
                // it did not match, and an older aligner a clip that ends
                // before it begins (the hub mends those it serves now, but an
                // edition kept on the device from before still has them).
                // Neither has audio to highlight: that one sentence is skipped
                // and the rest of the edition plays. Only an edition made of
                // nothing else has no narration (below).
                if (end <= begin) continue
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

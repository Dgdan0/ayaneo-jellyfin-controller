package com.pocketds.hub.reader

/**
 * The page each Contents entry starts on (#55), for one open book: the glue between the book's parts, the hub's
 * page count and what [AnchorShares] learns, kept in one place so Contents, the corners and the book's page all
 * count the same pages ([PageInfo.entryPage]). Plain values only, so it is tested without a screen.
 *
 * [sectionHrefs] are the book's parts in reading order, as the reader's locators spell them; [sectionSizes] and
 * [sectionStarts] are the reader's ([PageInfo.place], [PageInfo.sectionSpan]); [bookPages] is the hub's own page
 * count, 0 when it has none.
 *
 * An entry that names the file (`chapter.xhtml`) has its number at once. One that points into it
 * (`chapter.xhtml#part2`) has none until [learn] is given its anchor's share of the file, which the reader works
 * out in the background when the book opens; the list is never held up for it, and a row simply has no number yet.
 */
class ContentsPages(
    private val bookPages: Int,
    private val sectionHrefs: List<String>,
    private val sectionSizes: List<Int>,
    private val sectionStarts: List<Double?>
) {
    private val sectionByHref: Map<String, Int> = HashMap<String, Int>().also { map ->
        sectionHrefs.forEachIndexed { index, href -> map.putIfAbsent(href, index) }
    }

    /** Each anchor's share of its part: "section#anchor" to how far into the file it starts. Replaced whole, never edited. */
    @Volatile private var shares: Map<String, Double> = emptyMap()

    /**
     * Which anchors to look for in which files: the entries in [hrefs] that point into a part of the book, by that
     * part's index. Entries that name a whole file, or a file that is not a part of the book, need no reading.
     */
    fun wanted(hrefs: Iterable<String>): Map<Int, Set<String>> {
        val wanted = LinkedHashMap<Int, MutableSet<String>>()
        for (href in hrefs) {
            val anchor = AnchorShares.fragmentOf(href) ?: continue
            val section = sectionByHref[href.substringBefore('#')] ?: continue
            wanted.getOrPut(section) { LinkedHashSet() }.add(anchor)
        }
        return wanted
    }

    /** What was found: for each part, its anchors' shares ([AnchorShares.of]). Anchors not found simply have no entry. */
    fun learn(found: Map<Int, Map<String, Double>>) {
        val all = HashMap<String, Double>()
        found.forEach { (section, anchors) -> anchors.forEach { (anchor, share) -> all[key(section, anchor)] = share } }
        shares = all
    }

    /** The page the entry [href] starts on; null when it cannot be worked out yet (or at all), never a guess. */
    fun page(href: String): Int? {
        val section = sectionByHref[href.substringBefore('#')] ?: return null
        val anchor = AnchorShares.fragmentOf(href)
        val share = if (anchor == null) 0.0 else shares[key(section, anchor)]
        return PageInfo.entryPage(bookPages, sectionSizes, sectionStarts, section, share)
    }

    private fun key(section: Int, anchor: String) = "$section#$anchor"
}

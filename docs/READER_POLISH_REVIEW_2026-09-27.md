# Reader polish review — 27 September 2026

**Approved EPUB direction, updated after review:** Appearance remains global across all books. The user rejected per-book overrides. The approved implementation uses visible Paper/Sepia/Night/Blue samples, font samples, illustrated columns/margins/spacing, Font | Layout | Themes sections, and a one-page-per-screen checkbox. That checkbox selects one paginated column; reflowable EPUB cannot promise printed page boundaries. Navigation adds local passage search, return to the previous place, section-page entry and a book-percentage scrubber. These refinements are implemented in v0.3.10; validation is recorded in `../QUARTERMASTER_IMPLEMENTATION_STATUS.md`. The other reader proposals below remain suggestions and require the user's review before implementation.

The app is close to feature-complete for everyday browsing and playback. The readers should now get a focused finishing pass. My recommendation is to improve dependable reading and listening first, then add a small number of features that make finding and returning to a passage easier.

This review covers the production EPUB reader, comics/manga reader, audiobook player, and synchronized read-along mode. Reader Lab is a separate prototype, not the production reading engine. Findings below are grounded in the current implementation; suggested layouts are proposals, not screenshots of completed work. Device fixture results are recorded in the implementation status file. A long-session check with your own books remains necessary before calling the readers finished.

## What is already good, and what matters next

| Experience | Foundation worth keeping | Biggest finishing work |
|---|---|---|
| EPUB | Real Readium rendering, contents, bookmarks, offline English dictionary, extensive appearance controls, saved locators and conflict-aware progress sync | Controller navigation, clearer position/status, per-book appearance overrides, book search |
| Comics / manga | Tiled image decoding, zoom, RTL/LTR, fit-width, reading in thirds, page preview and bounded authenticated cache | Controller panning, top-aligned tall pages, saved view position and display preferences |
| Audiobooks | Real local audio playback, part navigation, seeking, scoped local resume, switching between reading/listening modes | Background playback, periodic resume saves, speed, timer, chapter list and clear errors |
| Read-along | Synchronized text/audio, sentence highlighting, speed, seek, return to narration, periodic progress saves | Clear following/browsing states, saved speed, less intrusive controls and explicit unavailable-alignment feedback |

## 1. EPUB: refine the reading experience

**Keep the current reader and appearance panel.** Text size, typeface, line spacing, margins, publisher styling, alignment, columns, scrolling, and light/sepia/dark/system themes already exist. Adding another settings system would make this harder to use.

**First: polish navigation and restoration.** Up/down currently participates in a flat control sequence, even when controls occupy different rows. Use spatial movement between rows, keep a visible ring around the complete focused control, and restore the exact button when a panel closes. Check that opening appearance or dictionary panels never loses the passage after text reflows.

**Second: explain the current position.** The footer currently shows a page count or percentage. Show the chapter name and overall progress together; keep the full title in the controls rather than over the text. Label reflow-dependent pages clearly so changing font size does not appear to lose progress. A small saved/sync-pending indicator belongs in the controls and should disappear during uninterrupted reading.

**Third: add search within the book.** Open a side panel containing a search field, chapter labels and short matching excerpts. Selecting a result jumps to its locator; offer “Return to previous place.” Index publication text locally and lazily, keep results tied to the edition, and avoid blocking page turns during indexing.

**Appearance:** retain global defaults and add “Use for this book” plus “Reset to defaults.” Current settings use one shared preference store. Scope overrides to the reading identity and edition; do not silently apply another profile’s adjustments.

**Later, if useful:** persistent highlights and notes using publication locators, with a clear export/sync policy. The existing narration highlight is temporary playback decoration, not saved annotation. Keep the existing English dictionary and label its language; additional dictionaries are optional.

Proposed controls, visible only when requested:

```text
[Back]  Book title                    [Contents] [Search] [Bookmark] [Aa] [⋯]

                    Uninterrupted book text

Chapter 6 · The Journey                 38% through book     Saved on device
```

Implementation anchors: `reader/EpubReaderScreen.kt` (control focus, navigator/bookmarks, preference persistence, position footer), `reader/EpubAppearancePanel.kt`, `reader/EpubBookmarkStore.kt`, `reader/ReadingCheckpointSync.kt`.

## 2. Comics and manga: make the controller as good as touch

**Highest priority: zoom must change what the D-pad does.** Currently left/right/up/down advances or retreats through pages or thirds even when zoomed. In zoom mode, the D-pad should pan. Keep page turns on explicit shoulder controls, show a short hint, and let Back exit zoom before leaving the reader. Avoid an accidental next page when the user only wanted to see the bottom of a panel.

**Fit width should begin at the top of a tall page.** The current viewport centers the full image vertically. For a long strip this can open halfway down. Start at the reading edge, move through the image smoothly, and make the end-of-page transition predictable.

**Resume the view, not just the page.** Production checkpoints currently record the page index. Save the reading mode and normalized viewport center/scale or thirds index locally per edition, restoring them after decode. Preserve a simple page locator for server compatibility until the shared checkpoint schema supports richer locations. RTL and fit preferences should persist per series with a book override.

**Next:** a thumbnail page grid and page bookmarks. The existing scrubber preview can be reused for thumbnails. Display actual page numbers and keep its focus stable when returning to the page. Reading in thirds is geometric navigation, not automatic comic-panel detection; keep that distinction clear.

**Optional:** continuous vertical scrolling for webtoons and a two-page spread mode for suitable publications. Add these after zoom and resume are reliable; content-aware panel detection is not necessary to finish this app.

```text
[Back]  Series · Volume 3                           [Pages] [Bookmark] [⋯]

                           Full-size page

Page 42 / 168         Fit width · RTL         D-pad: pan · Shoulders: pages
```

Implementation anchors: `reader/PagedImageReaderScreen.kt:438` (direction input), `:502` (viewport placement), `:124` (visible page checkpoint), `reader/ReaderPageRepository.kt` (cache and decoding input).

## 3. Audiobooks: the largest gap to a finished product

**Background listening comes first.** The current screen pauses audio when hidden or backgrounded and owns its player directly. Move audiobook playback into a lifecycle-independent media session service, with lock-screen/notification controls, headset buttons and audio-focus handling. Keep video/audio arbitration explicit so two players never speak together. Read-along may retain a different, explicit pause-on-leaving policy.

**Strengthen resume before adding decoration.** The player saves on pause, transitions, seeks and exit. Its half-second UI update does not checkpoint ongoing playback. Save periodically, such as every 10–15 seconds, and retain a distinct completed state instead of treating completion only as position zero. Integrate audiobook positions with the existing conflict-aware progress infrastructure using an audiobook location type and edition identity; today these positions are device-local preferences.

**Add the expected listening controls:** remembered playback speed, sleep timer including “end of chapter,” a chapter/part list, bookmarks, and both chapter and whole-book remaining time. If reliable chapter boundaries are unavailable, say “Part”; do not invent chapters from filenames. Show remaining time adjusted for playback speed.

**Make loading and failure recoverable.** Playback currently waits for a ZIP download and extraction into temporary cache. Show meaningful preparation states, a cancel/retry route, and download progress when available. Add explicit player-error handling with saved-position retry. Durable offline downloads should be a separate, complete, verified state. Streaming or downloading the first part before the rest is a later enhancement requiring backend support, not a simple UI switch.

**UI proposal:** a modest cover on the left, book/narrator/edition and current chapter on the right, one transport row, and a second row for speed, sleep and chapters. Constrain the cover to the available height; it must never push essential controls off the AYANEO screen. Use spatial controller navigation between these rows.

```text
┌──────────┐  Book title
│  Cover   │  Narrator · Edition
│          │  Chapter 8 · 12:34 / 28:10
└──────────┘  ━━━━━━━━━●━━━━━━━━━━━━━━━━━━
                  [−10s] [Play / Pause] [+10s]
             [1.25×] [Sleep: off] [Chapters] [⋯]
             Book: 3h 12m remaining · Saved on device
```

Implementation anchors: `reader/AudiobookScreen.kt:154` (lifecycle), `:189` (download/extraction), `:246` (UI update loop), `:265` (local position persistence); reuse the principles in `reader/ReadAlongPlayback.kt` and `reader/ReadingCheckpointSync.kt`.

## 4. Read-along: explain what the reader is doing

This already has real aligned narration, speed controls and “Return to narrated sentence.” Preserve those capabilities. Manual reading currently pauses narration and changes which location owns progress; that is a sensible default, but the transition needs visible explanation.

Use clear states: **Following narration**, **Reading — narration paused**, and **Alignment unavailable**. Show “Return to narration” only when it resolves a real mismatch. An optional follow switch could eventually allow browsing while listening, but should not silently change the current pause behavior.

Remember speed per book/profile. Offer the same sleep timer as audiobooks. Ensure the dock avoids the last line of text and scales at large text settings. The current dock is hidden with the full controls despite its “stay available” class comment; decide deliberately whether a small persistent play/pause control is wanted, rather than treating the comment as implemented behavior.

When alignment cannot be loaded, keep plain reading usable and show an actionable status with retry or reading-only mode. Do not imply ordinary audiobook audio can always be matched to a selected paragraph: that requires alignment data for the exact edition.

```text
                      Book text
         Highlighted narrated sentence in context

Following narration       [−10s] [Pause] [+10s]  [1.25×] [⋯]
```

Implementation anchors: `reader/EpubReaderScreen.kt:518` (dock), `:601` (highlight/follow), `:743` (manual reading transition), `:986` (dock visibility), `reader/ReadAlongPlayback.kt`, `reader/ReadAlongDock.kt`.

## Shared finishing rules

- **Offline must mean complete.** Differentiate temporary cached material from a verified full-book download. Cached comic pages do not guarantee the next unread page is available. Show readiness and storage management consistently for EPUB, image publications, audio and aligned packages.
- **Preserve existing sync protections.** EPUB/page/read-along checkpoints already have local storage, conflict handling and a retry path. Surface their state discreetly; do not replace them with last-write-wins behavior.
- **Confirm supported formats.** The production EPUB opening path disables its PDF factory. This review does not establish direct PDF rendering support. Audit actual catalog routes and offer an honest unsupported-format message or compatible edition rather than advertising a universal document reader.
- **One control language.** Consistent Back behavior, contents/chapters, appearance, bookmarks and More; visible focus rings and predictable return focus; no new sidebar redesign.

## Recommended delivery order

1. **Reliability and controller pass:** periodic audiobook saves, explicit playback errors, background audio infrastructure; comic pan/top-edge/resume; reader panel focus and clear offline/sync states. Test this before expanding features.
2. **Daily comfort:** audiobook speed, timer and chapters; EPUB search and per-book appearance; comic thumbnails/bookmarks; read-along state labels and remembered speed.
3. **Optional extras:** EPUB notes, extra dictionary languages, webtoon/spread modes. These should not delay a dependable release.

Before calling the readers finished, test each supported format with a real long publication: reopen after process death, lock/unlock, leave and return, disconnect/reconnect the Hub, switch profiles, change font size, cross chapters/pages, finish and replay, and use both touch and physical controls. Include RTL manga, a very tall comic page, a multi-part audiobook and an aligned EPUB with missing alignment. Verify the exact passage/viewport/audio timestamp after each interruption. No full reader redesign is needed to reach a polished result.

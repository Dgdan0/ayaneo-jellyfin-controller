# Reader improvements: a proposal

Written 2026-10-04 against `claude/consolidation` (e550804). Scope: the Android comic/manga, EPUB,
read-along and audiobook readers, the hub APIs behind them, and what the Apple Books side should
inherit. The first milestone is tracked in #16; the rest are proposals until the owner accepts them.

Method: I read the reader code, the plans and the hub's reading handlers, studied the Glass reader
prototypes, searched the web for what the best readers do and what users complain about, and checked
the screen arithmetic in a scratch script. I did not build, run the app, touch the handheld or call a
server. **Fact** = read in code or docs; **guess** = inferred, check first. Effort: **S** a day or two
with tests, **M** about a week, **L** several weeks across hub, app and device.

## Summary

The readers are deep (tiled page images, Readium, aligned narration, conflict-safe progress) but they
behave like four apps, forget what you chose, and use half the controller. Five moves would change
daily use most:

1. **Comics: Thirds on and remembered, steps computed from the page, a minimap, an end-of-issue
   card.** Spreads are probably cropped today (C1, C2, C6).
2. **Page turns without the blank, real thumbnails, reader traffic off the screen rate budget**
   (C3, C4, H1, H2).
3. **One controller language in every reader:** a shared keymap, the right stick and thumb clicks, an
   on-screen cheat sheet. The host hides the hint bar in readers, so nothing explains the buttons
   today (X1, X2).
4. **Audio that outlives the screen:** service-owned playback, speed, sleep timer, saved position;
   then hub-streamed tracks so Play is instant (A1, A2, then A3, A4).
5. **Comfort in long sessions on the OLED:** dim, warm tint, black page theme, screen on during
   read-along (X3, A5).

**Why Thirds is arithmetic, not taste.** The 7-inch 16:9 panel is about 6.1 x 3.4 in at 315 ppi. A US
comic page (6.6 x 10.25 in) at fit-width is 92% of print size but only 36% of its height fits; at
fit-height it is 33% of print, too small to read. The planner's three steps (12% overlap) each cover
36%, so Thirds is fit-width, stepped. A manga page is 122% of print at fit-width and also needs
three steps. A two-page spread at fit-width is 46% of print and shows 73% of its height: two steps.
An iPhone in landscape (about 6.3 x 2.9 in) behaves the same way.

## Owner's decisions (2026-10-04)

- **B differs by reader, on purpose.**
  - **Comics:** Ⓐ forward, Ⓑ back a page, as now and as in the comic reader the owner likes. You leave with Select.
  - **Books:** Ⓑ opens **menu mode**, where the page shrinks and the menu sits around it. Ⓑ again leaves the book.
  - **Audiobooks:** Ⓑ leaves, as now.
- **Comic zoom persists between pages.** Zoom in a little and turn the page: the next page opens at the same zoom and horizontal anchor, at its top. This is part of C1.
- **Controls over the page (Q3), decided 2026-10-05 after comparing both in the viewer:** comics get
  **glass bars over the page**, so the page keeps its size and the bars float over it. Books get **the page
  makes room**, so the page shrinks with the menu around it, matching Ⓑ's menu mode. This settles X7.
- **Everything is read here:** comics and manga, ebooks, audiobooks and read-along, and webtoons, PDFs and Hebrew (right-to-left) books. So the webtoon, PDF and RTL rows move from "later" into scope.
- **Second screen (X6):** not now. The Pocket is treated as single-display; revisit later.

## 1. Today

- **Comics and manga (Kavita)**, `reader/PagedImageReaderScreen.kt`, `PagedImageState.kt`: tiled image
  view; whole page, fit width or optional Thirds; A flows through a zoomed page; pinch, pan,
  double-tap; LTR/RTL; scrubber with preview; next and previous page prefetched into a 1 GB file LRU;
  next/previous issue and Kavita reading lists across series; page progress with a conflict check.
  Pad: A forward, B backward, X/Y whole page, L1/R1 and L2/R2 zoom, Start controls, Select leaves.
- **EPUB (Storyteller)**, `EpubReaderScreen.kt`: Readium; one global appearance; contents, device-only
  bookmarks, in-book search with a return point, offline English dictionary, section and percentage
  scrubber, live-preview side panels; locator checkpoints with a conflict sheet. Pad: A turns, X
  bookmark, Y contents, Select appearance, Start controls, L1/R1 pages, held L2/R2 chapter.
- **Read-along**, `ReadAlong*.kt`: the EPUB screen on an aligned book; SMIL timeline, Media3 audio,
  gold sentence highlight, speed, 10 s jumps, position saved every 10 s inside the text locator.
- **Audiobook**, `AudiobookScreen.kt`: downloads and extracts the whole ZIP, plays parts with ExoPlayer.
- **Shared**, `Reading*.kt`: local-first checkpoints, durable outbox, conflict choice; 20 JVM test
  files; Glass reaches the round controls only (bars are flat 92% ground).
- **Hub** (`reading` scope): manifests with page sizes, `isWide`, `doublePairs`, direction (RTL only for
  Manga libraries); page proxy; page progress with `expectedPage`; Storyteller file streams with Range
  (the audiobook is one ZIP); Readium-locator position with `expectedLocator`
  (`reading_publication.go`, `reading_epub.go`). **Absent:** thumbnails, audio tracks, chapters or
  position, annotations, page bookmarks, read-state writes.

`docs/READING_LISTS_AND_REMOVAL_2026-09-27.md` still shows the old comic pad map (commit 8487eaf
changed it that evening); `READING_CHECKPOINTS.md`, named in `CLAUDE.md`, is not in this checkout.

## 2. Gaps and pain points

**Comics and manga**
- **Forgotten view state.** Thirds, fit and direction are plain fields (`PagedImageReaderScreen.kt:87-107`),
  so every issue opens in whole-page mode, and a reading list builds a new screen per issue (`:571`).
  Direction comes only from the library type (`reading_publication.go:234`).
- **Spreads in Thirds are probably cropped (guess, `applyViewport` `:597`).** A wide page is stepped in
  thirds of its width; on a 16:9 screen that shows only the middle 25-39% of its height. Pages of
  aspect 0.75 and up lose 10-15% of their width. The step count is the constant 3.
- **Page turns probably flash (guess).** `loadPage` recycles the view and shows "Loading page N"
  (`:411`); prefetch fetches bytes and decodes nothing. Panels users report the same black flash.
- **Preview and budget.** The scrubber preview downloads the full page (`:645`, cap 128 MB); there is
  no thumbnail route or page grid. Reader pages spend the 90 rpm / 30 burst screen budget
  (`middleware.go:242`), so skimming may hit 429 (guess).
- **Plan items missing:** minimap (only "Region 1 of 3"), crop, rotation, brightness, page bookmarks,
  webtoon. `SpreadPlanner` and `doublePairs` are built and tested but unused. Touch has no tap zones or
  swipe (`:125`); forward on the last page jumps issue with no warning.

**Ebooks**
- Up/Down show the controls even in continuous scroll; the sticks cannot scroll (`EpubReaderScreen.kt:317`).
- No brightness, warmth or keep-awake; Readium offers about twice the type controls the panel shows.
- The dictionary is offline WordNet English only, so coined words and names (Allomancy, Sevro) likely
  get "No offline entry" (guess).
- No navigator listener (`:396`), so a footnote jump cannot be undone. No highlights, notes or PDF.

**Audiobooks and read-along**
- **Audio dies with the screen** (`AudiobookScreen.kt:155-170`, `HubActivity.onPause`): no screen-off
  listening, no browsing while listening.
- **First sound waits for the whole book:** ZIP download, extraction, ZIP kept, 12 GB cap (`:190`).
  Read-along does the same with the aligned EPUB. Neither cache has a budget (`EpubPackageCachePolicy`
  is unused).
- **Position is device-only,** saved on pause, seek and part change but not while playing, and
  finishing resets it (`:266`). The scrubber spans one part.
- No speed, sleep timer, chapters, bookmarks or cover; 10 s is hard-coded instead of
  `PlaybackSettings.seekSeconds`. L1/R1 fall through to the host's tab switch (`HubActivity.kt:735`), so
  a shoulder press probably leaves the player and pauses it (guess).
- Read-along: narration pauses when the screen sleeps; speed resets to 1x; the dock hides with the
  controls (`EpubReaderScreen.kt:1137`), so playing narration has no on-screen sign; no sleep timer.

**Across readers**
- **Invisible, inconsistent mapping.** The host empties the hint bar on immersive screens
  (`HubActivity.kt:1115`); B is backward in comics but controls-then-exit in EPUB; L1/R1 zoom in comics
  and turn pages in EPUB; the right stick, L3, R3 and Mode do nothing (`GamepadMap.kt`).
- **Server progress is per service account, not per Jellyfin profile** (one Kavita key, one Storyteller
  login; local checkpoints are per profile). Mark read, Want to Read, bookmarks and audio position never
  leave the device.
- No offline pinning for books; second screen unused; Glass for readers pending (opening the controls
  shrinks the page to about two-thirds, `ReaderPagePreviewController`; the prototype overlays frosted
  bars instead).

## 3. What other readers teach

- **Comic readers (Mihon, Kavita web, Komga, YACReader, Halftone):** per-series fit and direction, crop
  borders, split wide pages, tap-zone layouts, brightness override, a magnifier lens, pin-for-offline.
  Kavita's bug reports teach restraint: the next chapter fetched in full
  ([3168](https://github.com/Kareadita/Kavita/issues/3168)), a webtoon advancing when images lag
  ([3089](https://github.com/Kareadita/Kavita/issues/3089)).
- **Panel view (Panels, Halftone, Smart Comic Reader):** loved and the most complained about; zoom
  misbehaves when detection fails. Halftone falls back to the whole page when unsure.
- **Book readers (KOReader, Moon+, Readest, Apple Books):** hold-and-drag selection, highlights across
  pages, vocabulary lists, left-edge brightness, auto-night, a line guide; dense menus are the
  complaint.
- **Audio (BookPlayer, Smart AudioBook Player, Absorb, Libby):** smart rewind, sleep timer by minutes
  or chapter end with a fade, per-book speed, skip silence; users complain of timers that fail and a
  lost place after falling asleep.
- **Storyteller** syncs Readium locators about every 30 s (last update wins); a readaloud keeps a
  sentence locator while you listen, and clients convert track plus seconds through the SMIL timeline.
  Highlight sync is "coming soon".

## 4. Improvements

Rules followed: one owner per behaviour (named per row), pure logic with JVM tests, plain Views, no new
Activity, every new surface in Glass (`SidePanelView` and `ChoiceOverlay` for sheets,
`OverlayButtons.panel` and `GlassButtonBackground.overPicture` over a page, `FocusDecorator` for rings;
bars over a page stay near-solid, never live blur). "(plan)" marks items already in
`READER_DESIGN_PLAN.md`.

### 4.1 Comics and manga

| ID | What it is, and why it matters to the reader | Where | Effort / risk |
|---|---|---|---|
| **C1** | **Remember how you read.** Thirds on by default for portrait pages; Thirds, fit and direction remembered per series (global default in Display); the third you were on is restored; lists stop resetting. | App: pure `ComicViewPreferences` beside `DomainPreferences` | S / low |
| **C2** | **Steps from geometry.** Steps = ceil(1 + (page height at fit-width / screen height - 1) / 0.88, where 0.88 is 1 minus the overlap): 3 for comic and manga pages, 2 for spreads (or split a spread in two, as Kavita and Mihon allow). "Part 2 of 3" label and a tappable corner minimap. Shared vectors in `contract/` for Apple. (plan) | App: `ViewportStepPlanner`, `PagedImageState`, new `PageMapView` | M / med: tune on real spreads |
| **C3** | **Page turns without the blank.** Hold the next and previous page decoded in a second surface and swap. | App: `PageSurface` owner (two image views) | M / med: memory on 100 MB scans |
| **C4** | **Real thumbnails** for the scrubber, and a Pages grid. (plan) | Hub H1, H2; app `Artwork.loader`, Glass Pages sheet | S hub, M app / low |
| **C5** | **Trim margins.** Detect the uniform paper border on a 96 px luma thumbnail and treat the content as the page for fit and steps; per-series toggle (guess: 5-10% more scale; measure). (plan) | App: pure `PageBounds`, never over 12% | M / low |
| **C6** | **End-of-issue card:** "End of Fantastic Four #1 · Next: #2 (1962) · 14 of 457"; A continues, B stays; marks read. | App | S / low |
| **C7** | **Touch parity:** outer-third taps step, centre toggles chrome, swipe turns when not zoomed. (plan) | App | S / low |
| **C8** | **Gutter-snapped steps (experiment).** Nudge each boundary to the nearest horizontal gutter within 8% of the page height; uniform when unsure; debug overlay. Ship only if it lands on gutters on most of your own pages. | App: `PageGutters` beside `PageBounds` | M / high: bleeds, halftone, yellowed paper |

Later: page bookmarks (Kavita-native, H6); webtoon strips, only if you read them; double-page layout
for iPad (leave `SpreadPlanner` dormant on the Pocket, where a facing pair is about 46% of print);
hub-sized pages (re-encode to 1920 px; needs an image library, so a call on the hub's one-dependency
rule; measure real page sizes first); PDFs via Kavita's `extractPdf=true` images, if you have any.

### 4.2 Ebooks

| ID | What it is, and why it matters | Where | Effort / risk |
|---|---|---|---|
| **E1** | **Read with the sticks.** With Scroll on, Up/Down scroll instead of showing controls; the right stick scrolls smoothly (needs X2). (plan) | App: `EpubReaderScreen.onPad` | S / low |
| **E2** | **Type controls Readium already has:** weight (dark themes look thin), hyphenation, paragraph spacing and indent, letter and word spacing, image darkening, language and direction; optionally Literata and Atkinson Hyperlegible (OFL). Global only; per-book appearance was rejected. | App: `EpubReaderPreferences`, `EpubAppearancePanel`, `readiumPreferences` | M / low |
| **E3** | **Time left:** "12 min left in chapter · 4 h 10 min in book" from Readium positions and a self-calibrating pace; audio time on aligned books. | App: pure `ReadingPace` | S / low |
| **E4** | **Look-up for invented words:** "Find in book" on the selection card (first, next, previous mention via `EpubBookSearch`); a Wiktionary-derived offline pack with inflections and pronunciation behind `OfflineEnglishDictionary`; optional Wikipedia through the hub (online; your call). | App (+ asset size) | M / low-med |
| **E5** | **Footnotes and links:** a footnote card via Readium's `shouldFollowInternalLink`; `onJumpToLocator` feeds the existing "Return to previous place". | App | S / low |
| **E6** | **A word cursor for the pad:** hold A to enter, D-pad by word and line, A define, X highlight, B leave; injected JS plus Readium decorations. Prototype first. | App | L / high |
| **E7** | **Highlights and notes that sync:** grow `EpubBookmarkStore` into an annotation store; hub registry (H5) now, a seam for Storyteller's sync later. | Both | L / med |

Later: a line guide (Apple Books); Hebrew and other RTL books once you say you read them.

### 4.3 Audiobooks and read-along

| ID | What it is, and why it matters | Where | Effort / risk |
|---|---|---|---|
| **A1** | **A player that outlives the screen.** A reading-audio `MediaSessionService` (`PlaybackService` is bound to Jellyfin sessions): screen off, headset and lock-screen controls, resume; a top-bar mini player so you can browse while listening; one pure arbiter so video, narration and audiobook never overlap; position saved every 10-15 s; every key consumed. | App | M-L / med: service lifecycle, arbitration |
| **A2** | **Listening controls people expect:** per-book speed (0.75-3x); sleep timer by minutes or chapter end with a 30 s fade, any button extends, smart rewind when it fires; skip silence; chapter and book time left at the current speed; part list with `ChapterSeekBar` ticks; bookmarks; seek step from `PlaybackSettings`. Shared with read-along. (plan) | App: pure `SleepTimerPolicy`, `SmartRewind`; labels in `PlayerLabels` | M / low |
| **A3** | **Press play now.** The hub serves tracks one by one from the mapped media folders (H3): instant start, no 12 GB ZIP, no double storage. The app streams the current and next track and LRU-caches played ones. Read-along opens the text first and fetches narration lazily. | Both | L / med: needs the folder mapping |
| **A4** | **A position that follows you.** Audio position through the hub (H4); on aligned books it converts to and from Storyteller's sentence locator via the SMIL timeline, so Storyteller's apps and the text reader agree; a real "finished" state. `ReadingCheckpointStore` gets a kind `audio` (conflict sheet for free; `audiobook_positions` goes). | Both | M / med: spike against Storyteller first |
| **A5** | **Read-along you can leave alone:** screen on while narrating; a small "playing 1.25x" pill when controls are hidden; speed remembered per book; L1/R1 = previous/next sentence (the timeline has them); D-pad turns pages while narration continues, labelled Following, Reading, or Alignment unavailable; sleep timer from A2. (plan) | App | S-M / low |
| **A6** | **Audio screen in Glass:** square cover, chapter, remaining time, tinted by the cover; the dock from `ipad-read-along.jpg`. | App, with X7 | S / low |

### 4.4 Across readers

| ID | What it is, and why it matters | Where | Effort / risk |
|---|---|---|---|
| **X1** | **One keymap, visible.** A pure `ReaderPadMap` for all four readers (plan §8, adjusted by Q1); every PadAction consumed; a hint row inside the controls overlay and a "Controls" sheet. | App | S-M / low |
| **X2** | **Use the whole controller.** New `PadAction.Pan(dx, dy)` (analog, per frame) and `PadAction.Click(left or right)` in `GamepadMap` and `PadEventRouter`, tested like `AnalogRepeater`. Comics: right stick pans, holding L3 is a magnifier; EPUB scrolls; read-along L3 returns to the narrated sentence. | App: input owner | M / low-med |
| **X3** | **Comfort, one owner.** Generalise `PlayerBrightnessPolicy` into `ScreenComfort`: software dim, warm tint, pure-black page theme for the OLED, keep-screen-on while narrating, optional 60 Hz request on static pages (guess: saves battery; measure). One Glass sheet in every reader; left-edge vertical drag = brightness. (plan) | App | S-M / low |
| **X4** | **Sync the small things:** mark read/unread to Kavita and Storyteller (H6); Want to Read onto Kavita's list; per-profile server accounts if more than one person reads (H7); reader traffic on its own budget (H2). | Both | M / med |
| **X5** | **Pin for offline, budget the caches.** `OfflineRepository` learns reading assets (issue, volume, series, list, EPUB, audio tracks); LRU for the unbudgeted EPUB and audio caches; a Settings line. (plan M10) | Both | L / med |
| **X6** | **The bottom screen as a companion.** Comics: the whole page as a map and jump pad under the zoomed view. EPUB: contents, dictionary and notes, so nothing covers the text. Audio: transport and chapters. A `Presentation` from `ScreenHost.viewContext` with a display listener (CLAUDE.md: unstable ids, zombie windows). The keyboard panel owns that screen when expanded, so spike first; fallback: the same view as an inset. | App | L / high |
| **X7** | **Glass reader pass:** frosted, cover-tinted bars, Thirds lit, the glass read-along player, the sentence glowing in the accent. Decide overlay versus reserve (Q3). | App | M / low |
| **X8** | **Apple, later.** Same hub endpoints and `contract/` vectors. An iPad Pro 12.9" in landscape shows two facing pages at about 75% of print, where `SpreadPlanner` pays off; the Thirds planner serves an iPhone in landscape. Readium Swift shares locators with Android and Storyteller and has highlight decorations (check its Media Overlay support before promising read-along). Panels keeps working because progress stays in Kavita. | Apple | per item |

### 4.5 Hub changes in one place

All additive; older apps do not call them.

| ID | Endpoint and fields | Rules the hub decides | Android / Apple |
|---|---|---|---|
| **H1** | `GET /v1/reading/works/{workId}/publications/{sourceItemId}/pages/{page}/thumb?w=160` returns webp or jpeg, cached 30 days | Proxies Kavita's `/api/reader/thumbnail` (confirm on the installed version); `w` clamped 64-512; page-route checks | Scrubber, Pages grid / Pages grid |
| **H2** | No wire change: `limiterFor` sends reading `pages`, `thumb`, `file` and `audio` paths to a transport budget (about 1800 rpm / 150 burst, like artwork) | One place picks the budget | none / none |
| **H3** | `GET .../audio/{sourceItemId}` returns `{totalMs, narrator, aligned, tracks:[{index, title, durationMs, bytes}], chapters:[{title, startMs, track}]}`; `GET .../audio/{sourceItemId}/tracks/{n}` serves bytes with Range | Tracks from Storyteller's manifest, read through the media-root mapping (a read-only twin of `ResolveRemovalFile`); chapters from ffprobe on M4B, else none (never invented from file names) | Stream and pin / AVPlayer |
| **H4** | `GET`/`POST .../audio/{sourceItemId}/position` with `{track, offsetMs, completed, timestamp, expected?}`; 409 `reading_position_conflict` as for EPUB | Aligned books convert to and from the sentence locator via the SMIL timeline, cached per file; others stored per profile | Replaces `audiobook_positions` / same |
| **H5** | `GET`/`PUT .../publications/{sourceItemId}/annotations` with `{revision, items:[{id, type, locator, text, color, note, updatedAt, deleted}]}`; 409 on a stale revision | Last writer wins per id, with tombstones | Annotation store / Readium decorations |
| **H6** | `POST /v1/reading/works/{workId}/state` with `{read}`; `GET`/`POST`/`DELETE .../bookmarks` | Maps to Kavita mark-read and page bookmarks, and to Storyteller's status | Mark-read, page bookmarks / same |
| **H7** | Config only: `services.kavita.accounts.<jellyfinUserId>.api_key`, `services.storyteller.accounts.<id>.{username, password}` | Handlers pick by `X-Jellyfin-User`; the default account stays the fallback | none / none |

## 5. Recommended first milestone

App-only apart from H1 and H2 (one small hub deploy). The logic in each item is pure and gets JVM
tests first.

1. **X1 + X2: keymap, right stick, thumb clicks, cheat sheet.** First, because the rest builds on it;
   it also closes the stray-key fall-through. (S-M + M)
2. **C1 + C2 + C6: comics remember, steps from geometry, minimap, end-of-issue card.** (S + M + S)
3. **C3 + C4 + H1 + H2: page turns without the blank, real thumbnails.** (M + S hub)
4. **X3: comfort sheet** (dim, warm tint, black theme, keep-awake). (S-M)
5. **A1 + A2, core: audio that outlives the screen** with speed, sleep timer, seek step and periodic
   saves; chapters and bookmarks follow with A3. (M-L)

A5 is small enough to ride along with A2. Device checks use the `.uitest` package or a throwaway
title, because opening a book writes real progress.

**What should wait:** the second screen (X6) until C2's minimap exists as a reusable view; the word
cursor and annotations (E6, E7), bigger than they look; gutter snapping (C8) until C2 supplies the
data; webtoon and Pocket double-page; hub streaming and audio sync (A3, A4) as milestone two, since
they need a deploy and a Storyteller spike; offline pinning (X5); per-profile accounts (H7) unless
someone else reads; PDF; the Apple port. Next step: turn accepted rows into issues (`hub`, `android`,
`apple` labels) as `CLAUDE.md` asks.

## 6. Questions for the owner

1. **A and B.** Keep A forward and B backward in comics (the 27 Sept change) and make that the rule in
   EPUB and audio too, with Select and Start to exit? Or B means Back everywhere?
2. **Thirds.** On by default for every portrait page, manga included, or only when the page needs it?
3. **Chrome.** Reserve space for the bars (the page shrinks to about two-thirds; the bars take a quarter
   of the height) or overlay frosted bars as the prototype does, covering a third of the page while open?
4. **Guided reading.** Worth trying gutter snapping? Are your main comics regular Silver Age grids?
5. **What do you read?** Long strips, PDFs, magazines, Hebrew or other RTL books? That decides the
   "later" rows, PDF and E2's language work.
6. **Listening.** Screen off, and browsing while it plays? Fade the sleep timer, extend by any button?
   Skip silence?
7. **Who reads?** If Adirimo, Hadas or Horim read here (or on Apple), progress needs per-profile server
   accounts. Is the hub's Kavita key the same Kavita user Panels signs in as? If not, the Pocket and
   Panels never meet.
8. **The bottom screen.** Free while you read, or reserved for the keyboard and trackpad panel? Which
   job first: page map, contents and dictionary, or transport?
9. **Highlights and look-up.** Do you highlight at all? Proposal: a hub-owned store now, moved to
   Storyteller when it syncs. Is Wikipedia look-up through the hub acceptable, or strictly offline?
10. **Offline and comfort.** What do you pin before a trip: next issues of a list, a series, audiobooks?
    Literata and Atkinson Hyperlegible? A 60 Hz request while reading?

## Sources

[Mihon reader settings](https://mihon.app/docs/guides/reader-settings) ·
[Kavita comic and manga reader](https://wiki.kavitareader.com/guides/readers/comic-manga/) ·
[Kavita 0.5.4](https://github.com/Kareadita/Kavita/releases/tag/v0.5.4) ·
[Kavita reader API](https://deepwiki.com/Kareadita/Kavita/6.1-reader-api-and-services) ·
[Komga web reader](https://komga.org/docs/guides/webreader-divina/) ·
[YACReader](https://www.yacreader.com/features) ·
[Panels](https://www.panels.app/) and [its panel-view thread](https://community.panels.app/t/love-it-but-two-major-issues/198) ·
[Halftone](https://halftonereader.app/) ·
[Smart Comic Reader](https://smartcomicreader.com/) ·
[Chunky notes](https://www.avforums.com/threads/chunky-other-comic-readers-2015.1967638/) ·
[Kumiko](https://github.com/njean42/kumiko) ·
[KOReader guide](https://koreader.rocks/user_guide/) ·
[Moon+ Reader](https://play.google.com/store/apps/details?id=com.flyersoft.moonreader) ·
[Readest](https://readest.com/) ·
[Apple Books on iPhone](https://support.apple.com/guide/iphone/read-books-iphc1af7c57/15.0/ios/15.0) ·
[BookPlayer](https://github.com/TortugaPower/BookPlayer) ·
[Smart AudioBook Player review](https://teleread.com/app-review-smart-audiobook-player-android/index.html) ·
[Libby listening help](https://help.libbyapp.com/en-us/categories/listening-to-audiobooks.htm) ·
[Storyteller](https://storyteller-platform.dev/docs/welcome/) ·
[Storyteller v2 announcement](https://smoores.dev/post/announcing_storyteller_v2/) ·
[a client converting track and seconds to a locator](https://github.com/adryan30/infra/pull/188) ·
[Storyteller position sync notes](https://github.com/pkmetski/riffle/issues/37) ·
[Readium Kotlin preferences](https://readium.org/kotlin-toolkit/3.1.2/guides/navigator/preferences/) ·
[Readium `onJumpToLocator`](https://readium.org/kotlin-toolkit/2.4.0/readium/readium-navigator/readium-navigator/org.readium.r2.navigator/-navigator/-listener/on-jump-to-locator/) ·
[Readium Swift toolkit](https://github.com/readium/swift-toolkit) ·
[AYANEO Pocket DS specs](https://liliputing.com/ayaneo-pocket-ds-is-a-dual-screen-android-handheld-game-console-with-7-inch-and-5-inch-displays-and-snapdragon-g3x-gen-2/) ·
[Android multi-display and Presentation](https://source.android.com/docs/core/display/multi_display)

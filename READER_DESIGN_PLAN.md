# Pocket DS reader design plan

Date: 2026-09-21  
Branch: `feature/reading-library`  
Status: R0 reference audit, R1 shared shell, and the R2 real paged-image slice accepted on Pocket DS

## 1. Product boundary

The app will have one full-screen reader shell with three visual profiles:

1. **Comic** — paged images, left-to-right and spread-aware.
2. **Manga** — the same image engine, right-to-left by default, with a vertical/webtoon mode.
3. **Book** — reflowable EPUB plus a fixed document/PDF mode.

Audiobook and read-along are modes of the same logical book. They do not create
duplicate library entries. A work can expose **Read**, **Listen**, and **Read
along** according to its available Storyteller editions.

The reader uses the selected Hub/Storyteller/Kavita user. Local progress,
bookmarks, settings, temporary cache, and downloads are isolated by that user.

## 2. Evidence and reference behavior

The live audit ran on the Pocket DS at 1920×1080 in landscape on 2026-09-21.
The installed references were CDisplayEx 1.3.96, Kindle
8.156.0.100 (2.0.100996.0), and Storyteller 2.11.4. The audit used an existing
Kavita comic, an existing Kindle ebook with its Audible edition, and the local
Storyteller readaloud fixture. No purchase, download, annotation, or account
setting was changed.

CDisplayEx is the image-reader reference. Its official reader describes a
chromeless page, center-tap controls, progress and page thumbnails, reading
flow, screen/width/height fit, single page, cover-aware spreads, continuous
layouts, zoom/pan, bookmarks, crop, and controller support. Its settings also
support per-book RTL, vertical flow, adjustable step distance, nine touch
zones, and remote progress synchronization. See the official
[reader](https://www.cdisplayex.com/mobile/reader/) and
[settings](https://www.cdisplayex.com/mobile/settings/) pages.

Kindle is the reflowable-book reference. Amazon documents font, size, spacing,
margins, alignment, backgrounds, themes, paged/continuous reading, dictionary,
search, notes, highlights, reading ruler, and narration highlighting. Kindle's
Page Flip provides a useful model for browsing elsewhere without losing the
saved reading position. See Amazon's
[reading options](https://digprjsurvey.amazon.com/csad/help/node/TABlJ4ot69emTO8jJG),
[Kindle feature overview](https://read.amazon.com/landing), and
[Enhanced Typesetting](https://kdp.amazon.com/en_US/help/topic/G202087570).

Readium Kotlin fits the existing Android Views app and provides EPUB, PDF, CBZ,
audio and TTS navigators, stable locators, search, decorations, RTL, edge/key
navigation, and serializable reader preferences. The fragment-based stable
navigator will be evaluated first; the alpha Compose web navigators are outside
the first implementation. See its official
[navigator guide](https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/navigator/navigator.md)
and [preference guide](https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/navigator/preferences.md).

### Live Pocket DS findings

#### CDisplayEx

- The normal reading state is genuinely chromeless and gives the page almost
  the entire display. Center tap and the hardware Start key both reveal the
  controls without changing the page.
- The top toolbar groups Back/title, single-versus-spread layout, fit/resize,
  reading direction, a one-tap color-correction toggle, and overflow. Reading
  direction opens four large choices for horizontal and vertical progression.
- Resize exposes pan/fit, 1:1, page/spread, vertical-fit, and zoom actions as a
  compact icon palette. The bottom toolbar has transition/paging controls, a
  true page scrubber, current/total page count, previous/next, and a thumbnail
  mode.
- Overflow contains rotation lock, detailed color corrections, crop borders,
  page border, and export-current-page. Color corrections expose white
  balance, vibrance, gamma, and individual red/green/blue channels with Reset,
  Cancel, and Save.
- Its remote-service model is useful: a location can be Komga or Kavita, and a
  book opens directly into the reader. The inspected Kavita item rendered
  sharply and immediately.
- It supports gamepad input, and Start worked reliably in the live test. Its
  overlay still behaves like a touch toolbar: icon meaning is sometimes
  unclear, accessibility labels were absent from several image buttons, and
  D-pad focus did not produce a consistent, high-contrast path through every
  control. We should copy its reading behavior while replacing this weak
  controller interaction.

#### Kindle

- The landscape ebook opened as a clean two-column sepia spread with only page
  and percentage text visible. This is the right density for the Pocket DS.
- Kindle detected that a newer position existed on another device and showed
  the local page, remote page, source device, and update age. It required an
  explicit choice instead of silently taking the newest timestamp. Our sync
  conflict UI needs the same clarity.
- Center tap opened Page Flip: the current spread remained centered, adjacent
  spreads were visible, the scrubber showed both the preview and saved
  positions, and “Back to 165” made it clear that browsing had not committed a
  new reading position.
- Reading settings use Font, Layout, Themes, and More tabs. The inspected build
  exposes multiple serif/sans/dyslexia-friendly fonts, font size, spacing,
  white/sepia/mint/dark page colors, system-theme following, continuous
  scrolling, three margin presets, reusable themes, brightness/Auto, a reading
  ruler, configurable progress labels, and real-time narrated-word
  highlighting.
- The selected ebook had its Audible edition attached. The reader exposed a
  mini player, play/pause, 30-second rewind, speed, and remaining audiobook
  time. Real-time Text Highlighting explicitly applies to Audible or Assistive
  Reader narration.
- Long-press selection opened a side reference rail for Dictionary, Wikipedia,
  and Translation, plus Highlight, Note, Play, Copy, Pin, Share, and Report.
  The absent offline dictionary produced a direct language/download prompt.
- Kindle is not a controller reference. A D-pad press during the live audit did
  not reliably turn the page or reveal a clear focus target. Our ebook reader
  should reproduce its typography, Page Flip, study tools, and conflict model
  with the app's existing controller behavior.

#### Storyteller

- A work with both formats presents separate Read and Listen actions on the
  same card, including local/remote availability indicators. This matches the
  single-work/multiple-edition model already chosen for the Hub.
- Its readaloud view keeps text and audio in one screen. The live fixture
  showed column layout, appearance, sleep timer, speed, contents, bookmark,
  and audio-mode actions above a timeline with track count, remaining time,
  previous/play/next, and percentage.
- The installed fixture is only a zero-minute alignment sample, so it could
  prove the combined shell but not smooth word-by-word tracking. Native Media
  Overlay and generated-alignment fixtures remain required gates for R5.

### Reference matrix

| Capability | CDisplayEx | Kindle | Storyteller | Pocket DS target |
| --- | --- | --- | --- | --- |
| Distraction-free page | Excellent | Excellent | Good | Chromeless by default for every engine |
| Controller behavior | Partial | Weak | Touch-first | Complete focus path and consistent A/B/Start |
| Comic fit/direction/crop | Excellent | N/A | N/A | Match, with clearer labeled side sheets |
| Page preview | Thumbnail scrubber | Page Flip with non-committed preview | Timeline | Content-specific preview that commits on release |
| Ebook typography | N/A | Excellent | Basic | Kindle-class typography through Readium |
| Dictionary and study tools | N/A | Excellent | Limited | Offline-capable dictionary plus selection tools |
| Read/listen pairing | N/A | Audible pairing | Native work editions | One work with Read, Listen, and Read along |
| Narrated highlighting | N/A | Eligible books | Media Overlay/readaloud | Native overlays first, generated alignment later |
| Progress conflicts | Server sync | Explicit local/remote prompt | Server sync | Explicit choice for meaningful divergence |

## 3. Shared reader shell

`ReaderScreen` hides the app sidebar and hint bar and restores the originating
work, scroll position, and focus when it exits. Rendering engines sit behind
these tested interfaces:

- `ReaderSession`: user, work, edition, source, locator, chapter, completion,
  and revision;
- `ReaderSource`: pinned local file, valid temporary file/cache, or remote Hub
  manifest;
- `ReaderEngine`: open, close, go, next, previous, zoom/pan capability, visible
  locator, and preview;
- `ReaderProgressStore`: immediate local writes, debounced server writes,
  background/exit flush, and durable offline outbox;
- `ReaderPreferences`: global theme, per-profile typography/layout, and
  per-publication overrides;
- `ReaderOverlayState`: hidden, controls, navigator, appearance, dictionary,
  note, audio, or conflict;
- `ReaderCache`: bounded temporary LRU separate from explicitly downloaded
  assets.

The shell is chromeless while reading. A center tap or Start opens controls. A
slim top row contains close, title, bookmark, orientation lock, and reader-mode
icons. A bottom row contains position and a scrubber. Appearance and navigation
open as a side sheet on the Pocket DS so they do not consume vertical space;
the same sheet becomes a bottom sheet on narrow portrait screens. Every icon
has an accessible label, a controller focus treatment, and a short label in the
hint bar. The initial focus is the most likely action rather than Back.

Image books show real page thumbnails above the scrubber. Reflowable EPUBs show
chapter, stable position, percentage, and a short text excerpt because a
font-independent page thumbnail does not exist. Scrubbing previews a location
without changing the saved position until the user confirms or releases it.
EPUB preview also retains a visible marker and one-action return to the saved
position, following the useful Page Flip behavior observed on Kindle.

## 4. Comic and manga engine

The implementation begins with a benchmark between Readium's image navigator
and a native tiled View such as
[ZoomImage](https://github.com/panpf/zoomimage). The chosen engine must render a
20,000-pixel page without holding the full bitmap, retain sharp text while
zoomed, cancel off-screen tile work, and work with the existing Android Views
stack. Selection happens from hardware measurements, not library popularity.

Supported modes:

- single page;
- double page and double page with cover;
- continuous vertical;
- webtoon strip;
- left-to-right, right-to-left, and top-to-bottom progression;
- fit screen, fit width, fit height, and remembered manual zoom;
- 90-degree rotation, crop blank borders, page gap/background, and optional
  brightness/contrast/gamma filters;
- page thumbnail scrubber, page bookmarks, and automatic next issue/volume.

Fit/layout, reading direction, and color/crop are separate sheets. Direction is
never hidden inside fit, and changing the visual page direction does not
silently reverse the user's configured physical controls. Fit and layout can be
remembered separately for portrait and landscape. Image decode quality is
governed by a measured memory budget rather than a global low-quality switch.

Reading direction is stored per series and can be overridden per issue. It is
never inferred solely from the app language. A spread keeps the cover alone and
orders pages according to the selected progression.

### Viewport thirds

The requested “jump through thirds” behavior is deterministic and does not
claim to detect comic panels:

- when a page fits, advance changes page;
- when fit-width or zoom makes content taller than the viewport, advance moves
  top → middle → bottom with 12% overlap, then changes page;
- when a landscape spread is wider than the viewport, advance moves through
  left/centre/right for comics and right/centre/left for manga;
- mixed oversized pages use a small ordered grid, following the selected
  reading direction;
- a brief minimap shows the current viewport rectangle after every step;
- manual pan temporarily owns navigation; “Resume flow” snaps to the nearest
  deterministic step.

Real panel detection can be evaluated later as an optional, inspectable model.
The first release never changes reading order based on an opaque guess.

### Touch behavior

- center tap: show/hide controls;
- outer left/right thirds: previous/next logical step, reversed for RTL when
  “controls follow reading direction” is enabled;
- swipe: pan a zoomed page, otherwise turn/scroll in the swipe direction;
- pinch: continuous zoom around the fingers;
- double tap: toggle fit mode and a remembered reading zoom;
- left-edge vertical drag: brightness with a compact percentage bar;
- long press in the thumbnail strip: toggle page bookmark.

## 5. EPUB book reader

Use Readium's EPUB navigator, preserving the spine, table of contents,
footnotes, embedded semantics, RTL/vertical writing, and serializable locators.
Reader preferences update without reopening the book.

Appearance settings:

- publisher font or installed serif, sans, dyslexia-friendly, and user-added
  fonts where licensing permits;
- font size and weight;
- line, paragraph, word, and letter spacing;
- paragraph indent, margins, alignment, ligatures, and hyphenation;
- publisher styles on/off with a clear explanation that advanced spacing may
  require publisher styles off;
- white, sepia, mint, dark, and high-contrast themes, plus system following;
- paginated or continuous mode;
- one column, two columns, or Auto. Two columns apply only in paginated mode;
  Auto falls back to one column when font size or available width would make
  columns too narrow;
- portrait/landscape lock and keep-screen-on.

Pinch changes font size for reflowable text. A small transient label shows the
new size. Image pinch remains image zoom, so the same gesture always matches
the content type.

### Navigation and study tools

- table of contents, search results, bookmarks, highlights, and notes share one
  navigator sheet;
- text selection offers Highlight, Note, Copy, Define, Search in book,
  Translate, and Share according to availability;
- the first dictionary slice uses Android's installed text-processing apps and
  clearly reports when no offline dictionary exists;
- a later downloadable dictionary pack adds in-app offline lookup with language
  selection and SQLite full-text search;
- highlights use Readium decorations and store the exact locator, selected
  text, color, note, edition fingerprint, and revision;
- an optional reading ruler follows the current line without changing EPUB
  content.

Settings persist at three levels: theme and brightness per user, typography per
reader profile, and language/direction/publisher-style exceptions per book.

## 6. PDF and fixed-layout books

PDF uses a maintained native renderer with tile-based zoom. It shares the image
reader's fit, zoom, pan, crop, thumbnails, page bookmarks, viewport thirds, and
direction behavior. It adds text-layer search/selection where present and
clearly disables those tools for scan-only pages. OCR is a later opt-in server
job rather than a silent on-device requirement.

Fixed-layout EPUB selects Readium's fixed-layout behavior and exposes spreads
instead of reflow typography. It must not show inactive font controls.

## 7. Audiobook, handoff, and read-along

Plain audiobooks reuse Media3 and the existing playback service with an
audio-first overlay: cover, chapter, elapsed/remaining time, 0.75–3× speed,
sleep timer, bookmarks, previous/next chapter, and offline playback.

Handoff arrives before synchronized highlighting:

1. pair editions by a strong identifier or explicit user choice;
2. map the current named chapter and normalized chapter percentage;
3. offer **Continue in book** and **Continue in audio**;
4. allow per-chapter calibration when the editions differ.

Storyteller readaloud EPUBs use EPUB 3 Media Overlays. Those SMIL records bind a
text fragment to an audio clip, and the available granularity determines
whether highlighting is a phrase, sentence, or word. See the
[W3C Media Overlays specification](https://www.w3.org/publishing/epub32/epub-mediaoverlays.html)
and [Storyteller readaloud guide](https://storyteller-platform.dev/docs/reading/playing-readalouds/).

Readium Kotlin currently lists Media Overlays as planned, so Read Along needs a
tested `MediaOverlayController`: parse and validate SMIL, play the referenced
audio through Media3, navigate Readium to the text locator, and apply an active
decoration. Readium remains the renderer; this adapter owns only synchronized
timing and highlighting. Amazon's current Read & Listen behavior confirms the
target experience—position handoff plus highlighted narration—but no Amazon
API or Kindle progress is used. See
[Audible Read & Listen](https://help.audible.com/s/article/listen-with-whispersync-for-voice?language=en_US).

If no media overlay exists, the UI offers Read and Listen separately. A user
may start a Storyteller alignment job on the server. WhisperX remains an
optional later worker only if Storyteller's supported alignment fails objective
fixtures; it is never required to open a book.

## 8. Controller mapping

The mapping keeps A/B consistent with the rest of the Pocket DS app. Every
active mapping appears in the overlay hint row.

| Input | Controls open | Controls hidden — comic/manga/PDF | Controls hidden — EPUB |
| --- | --- | --- | --- |
| A | Activate focused control | Next logical viewport/page | Next page or viewport |
| B / Android Back | Close sheet → hide controls → exit reader | Exit reader | Exit reader |
| D-pad | Move focus; Left/Right adjust a value | Pan physically; at fit scale Left/Right turn pages | Previous/next page; Up/Down scroll in continuous mode |
| L1 / R1 | Previous/next tab where shown | Previous/next page | Previous/next page |
| L2 / R2 | Adjust focused slider | Zoom out/in | Previous/next chapter |
| X | Toggle bookmark | Toggle page bookmark | Toggle locator bookmark |
| Y | Open navigator | Page thumbnails/chapters | TOC/search/bookmarks/notes |
| Start | Hide controls | Open controls | Open controls |
| Select | Quick appearance | Fit/layout/direction sheet | Aa appearance sheet |
| Left stick | Same as D-pad with repeat threshold | Step or pan | Page/scroll |
| Right stick | No action unless a preview is open | Fine pan | Scroll continuously |

Android can report D-pad through key codes or hat axes and triggers through
motion axes, so the existing input abstraction must normalize both paths and
test dead zones/repeat rates. See Android's official
[controller input guide](https://developer.android.com/games/sdk/game-controller/controller-input).

All bindings become user-configurable after the default map passes hardware
acceptance. The initial release stores one global map plus optional comic and
book overrides.

## 9. Progress, offline, and conflicts

Every visible location writes locally immediately. The app sends progress after
a page/locator settles, every 10 seconds during audio, and immediately on
background, edition switch, exit, or completion. Failed writes enter the same
durable outbox used by offline media progress.

Progress events contain user, work, edition fingerprint, stable locator,
percentage, logical chapter/page, device revision, base server revision, and
timestamp. The Hub applies an event only to the same edition or a verified
paired edition.

Conflict rules:

- one device continuing from the acknowledged revision applies normally;
- close positions in the same chapter keep the later event;
- a large divergence or different chapter shows **Continue here**, **Use server
  position**, and **Keep both bookmarks**;
- completion is never inferred from a stale offline timestamp;
- server acceptance is relayed to Kavita or Storyteller for the selected user.

The conflict sheet displays the location, percentage/page, chapter, device,
and update time for both choices. Merely opening a preview or conflict sheet
does not acknowledge or overwrite either position.

Source preference is pinned download, then complete valid temporary asset,
then remote. Image pages prefetch the next two and previous one. An EPUB is
cached as a verified complete package before opening; audio and PDF use
resumable ranges. Temporary cache has an independent configurable LRU budget
and never appears in Offline. Explicit downloads are never evicted.

## 10. Test-first milestones

### R0 — hardware reference audit

- **Complete on 2026-09-21.** Connected over ADB and recorded the installed
  versions of CDisplayEx, Kindle, and Storyteller.
- Captured CDisplayEx chromeless reading, controls, resize/layout, four-way
  flow, page scrubber, color correction, crop/overflow, and Start/D-pad
  behavior against a live Kavita comic.
- Captured Kindle landscape typography, two columns, fonts, page colors,
  margins, continuous mode, themes, brightness, Page Flip, position conflict,
  selection references/actions, Audible mini player, and narrated-word option.
- Captured Storyteller's paired Read/Listen card and combined readaloud shell.
- Result: proceed to R1. CDisplayEx is the image behavior reference, Kindle is
  the ebook behavior reference, Storyteller supplies editions and alignment,
  and none is sufficient as the controller-interaction reference by itself.

### R1 — shared shell with fake engines

- First write pure tests for overlay transitions, Back order, controller
  normalization, focus return, locator persistence, conflict decisions, source
  fallback, cache eviction, and outbox replay.
- Add feature tests proving that Start opens controls without moving position,
  hidden and visible controls use different D-pad behavior, every control has a
  reachable focus path, focus decoration remains inside safe bounds, and Back
  closes a sheet then controls then reader.
- Add preview tests proving that scrub/page-flip movement does not write
  progress until confirmation, Cancel restores the saved locator, and network
  callbacks from an old preview/session cannot move the new session.
- Add conflict tests for local-newer, server-newer, close-same-chapter,
  divergent-chapter, completion-vs-stale, and Keep both bookmarks, including
  device/time/location presentation.
- Implement the full-screen shell and fake page/text engines.
- Gate: Pocket DS controller/touch acceptance with fake comic, reflowable book,
  and readaloud fixtures and no real publication parser.

R1 completed on 2026-09-21. Pure Kotlin coverage now exercises overlay and
Back transitions, hidden-versus-visible input, deterministic focus reachability,
preview commit/cancel, stale callback rejection, both sides of progress
reconciliation, source fallback, protected cache eviction, outbox compaction,
and all fake-engine profiles. The installed Pocket DS build passed controller
focus and safe-bound checks, Android Back and B cascades, scrollable fixture
selection, comic LTR and manga RTL rendering, book themes and one/two-column
layout, non-committing navigator preview, touch scrub preview/commit, saved
position restoration, conflict presentation, and read-along play/highlight.

### R2 — comics and manga

- First write tests for spread pairing, cover isolation, LTR/RTL order,
  viewport-third paths, crop bounds, zoom/pan state, issue continuation, and
  page progress.
- Benchmark tiled engines, select one, then implement remote, cached, and pinned
  pages.
- Gate: CBZ/PDF fixtures, very large scans, webtoon, rotation, network loss,
  and CDisplayEx comparison on Pocket DS.

R2's production paged-image slice passed on the Pocket DS on 2026-09-21. The
Hub now exposes authenticated Kavita manifests, bounded page resources, and
page-progress writes without exposing Kavita keys or raw upstream URLs. The
Android reader uses a tiled decoder, a 1 GB URL-scoped LRU cache, adjacent-page
prefetch, single-page LTR/RTL navigation, pinch/pan/double-tap zoom, optional
overlapping thirds, publication navigation, a real page scrubber, immediate
exit/background progress, and restored Continue reading state. Live acceptance
covered a 24-page comic and the 376-page Chainsaw Man manga, including first
archive extraction, controller-only open, progress save/resume, RTL metadata,
and removal of Kavita's `-100000` sentinel. The next image-reader enhancement
slice retains double-page rendering, webtoon/continuous mode, thumbnails,
crop/rotation/filters, and pinned offline publications.

### R3 — EPUB and PDF

- First write tests for locator serialization, preference scopes, inactive
  settings, one/two-column rules, search/highlight anchors, dictionary routing,
  and progress conflicts.
- Integrate the stable Readium fragment navigator and PDF renderer.
- Gate: EPUB 2/3, RTL, vertical text, footnotes, tables, embedded fonts,
  fixed-layout EPUB, text PDF, scan PDF, offline return, and Kindle comparison.

### R4 — audio and handoff

- First write tests for chapter mapping, calibration, speed/sleep/bookmarks,
  local/remote source switch, and bidirectional progress handoff.
- Extend Media3 audio mode and Storyteller edition pairing.
- Gate: M4B, multi-file audio, different chapter boundaries, and offline sync.

### R5 — synchronized read-along

- First write parser and timeline tests for valid, nested, partial, malformed,
  and missing SMIL; decoration tests; seeking; speed changes; chapter changes;
  and restart restoration.
- Implement the Media Overlay adapter and Storyteller alignment status UI.
- Gate: native overlay fixture, Storyteller aligned fixture, mismatched editions,
  word/phrase granularity disclosure, and Pocket DS performance.

### R6 — polish and release

- Accessibility labels, configurable mappings, performance/battery profiling,
  storage pressure, backup/restore, release notes, Hub-first deployment, and
  regression checks across existing media playback/downloads.

## 11. Decisions before implementation

The design recommends:

- CDisplayEx behavior as the comic/manga reference and Kindle behavior as the
  ebook reference;
- one image engine with comic, manga, and webtoon profiles;
- Readium's stable Android Views navigator for EPUB;
- a hardware benchmark before choosing the tiled image component;
- side sheets on Pocket DS and bottom sheets on narrow phones;
- a custom, narrow Media Overlay adapter because Readium Kotlin does not yet
  supply that layer;
- progress and preferences scoped to the selected Hub user;
- test-first delivery at every milestone, with reader code beginning only after
  R0 is observed on the actual device.

R0, R1, and the real single-page R2 slice are complete. The next implementation
slice is R3a: write the Readium locator, typography, columns, navigation,
search, dictionary-routing, caching, and progress tests, then connect real EPUB
publications. Mistborn is the end-to-end acquisition, series/author grouping,
cover, opening, and resume fixture for that slice. PDF follows as R3b; the
remaining advanced image modes and audiobook/read-along work retain their
separate gates.

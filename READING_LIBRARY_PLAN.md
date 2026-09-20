# Reading library, offline access, and audiobook/e-book sync

> **Platform decision update — 2026-09-20:**
> [READING_PLATFORM_REVIEW.md](READING_PLATFORM_REVIEW.md) supersedes the
> service choices in this document. Keep the product, download, reader, and
> test design below, but preserve the existing Komga/Panels workflow as the
> primary visual catalog, use Audiobookshelf for audiobooks, evaluate Bindery
> plus Kapowarr for focused acquisition, and retain Jellyfin-compatible folders
> as canonical storage. Kavita is a comparison candidate only; Chaptarr and
> Mylar3 remain alternatives.

## Product decision

Create one **Reading** destination in the Pocket DS app, backed by three reader experiences and
one audio experience:

| Content | Pocket DS experience | Primary server |
| --- | --- | --- |
| Comics | Fixed-layout comic reader, left-to-right | Komga |
| Manga | Fixed-layout reader, right-to-left by default | Komga |
| Books | EPUB reflow reader and PDF reader | Komga |
| Audiobooks | Audio-first player with chapters, speed, bookmarks, and sleep timer | Audiobookshelf |

The navigation starts with configured libraries: **Comics**, **Manga**, **Books**, and
**Audiobooks**. It then groups editions by an app-owned `Work` record. For example, opening
`Red Rising` shows every book in the series in reading order. Each book shows its available
editions: EPUB, PDF, audiobook, comic, and downloaded status. Opening an edition selects the
matching reader automatically.

Komga stays responsible for its own library scan, series, collections, readlists, artwork, and
visual-reading progress. Audiobookshelf owns audio chapters, playback progress, bookmarks, and
audiobook metadata. The Hub combines those read-only catalog records into the app's `Work` and
`Edition` model without exposing either server's URL or credential to the Pocket DS.

## Acquisition

### Books and audiobooks: Chaptarr

Do not expand the existing Readarr installation for new functionality. Readarr is retired and
its own maintainers recommend alternatives because its metadata source is no longer reliable.

The recommended acquisition candidate is **Chaptarr**:

- it is a Readarr-derived manager for both e-books and audiobooks in one instance;
- it supports multiple editions of the same title, narrators, M4B and multi-file audio;
- it integrates with standard *Arr indexers and download clients, so it can use the existing
  Prowlarr and qBittorrent pattern;
- it can organize e-books and audiobooks into separate roots while retaining their relationship.

Chaptarr is Docker-only and beta. It must first run with empty test libraries and a disposable
qBittorrent category. It does not replace the current Readarr service until metadata, import,
rename, failure handling, and a paired e-book/audiobook request have been tested.

Alternative: Librarr is an active Readarr continuation, but it needs separate instances when a
title should have both audiobook and e-book editions. It is a less natural match for paired
editions.

### Comics: Mylar3

Use **Mylar3** for comic acquisition. It manages watchlists, missing issues, weekly pull lists,
story arcs, CBR/CBZ processing, and qBittorrent/NZB clients. It should write to a dedicated
`Comics` import root that Komga scans.

### Manga

Do not add a separate manga downloader in the first release. Use a dedicated `Manga` import root
that Komga scans. Add a request/import adapter only after a real source and its metadata quality
are evaluated. Manga must not be forced through comic issue numbering or a book author model.

## Storage layout and scanning

Use stable host roots before deploying any new service:

```text
D:\Media\Reading\Books
D:\Media\Reading\Audiobooks
D:\Media\Reading\Comics
D:\Media\Reading\Manga
D:\Downloads\Books
D:\Downloads\Comics
```

Every Docker service sees the same paths under `/data`, for example
`D:\Media\Reading` -> `/data/reading` and `D:\Downloads` -> `/data/downloads`.
Chaptarr and Mylar import into the roots above; Komga reads Books, Comics, and Manga; AudioBookShelf
reads Audiobooks and may receive Books read-only for supplementary metadata. This avoids duplicate
copies and makes qBittorrent category paths predictable.

Each import triggers a server scan. The Hub's Manage screen later gets explicit **Scan Komga** and
**Scan Audiobookshelf** actions, matching the existing Jellyfin scan convention.

## Hub model and access rules

Add a `reading` bearer-token scope. It has no file-system path access and no administrative
library-edit action.

```text
ReadingLibrary
  id, source (komga|audiobookshelf), kind (comic|manga|book|audiobook), title, artwork

Work
  id, canonical title, authors, series id/number, identifiers, artwork

Edition
  id, work id, source item id, format, reader kind, duration/pages,
  chapters, availability (remote|temporary|downloaded), artwork

ReadingProgress
  user id, edition id, locator, percentage, completed, revision, updated at

EditionLink
  work id, ebook edition id, audiobook edition id, confidence, mapping status
```

`Work` matching uses ISBN first, then a conservative title/author/series/number match. Ambiguous
matches remain separate until the user selects **Link editions**. A false link is worse than
showing two copies of a title.

The Hub exposes read-only lists, details, covers, and server pagination. It proxies only approved
book resources through an opaque reader session:

- complete EPUB, CBZ, and CBR files go to the local temporary cache before opening;
- PDFs and audio support byte ranges for quick opening and seeking;
- a reader session is bound to the Pocket DS token and selected user;
- raw Komga and Audiobookshelf URLs and credentials never reach the device;
- cancellation closes remote audio sessions and releases temporary server state.

## Remote, temporary, and offline files

There are three clearly different states:

1. **Remote** — the item is hosted on Komga or Audiobookshelf and opens through the Hub.
2. **Temporary** — the app has fetched a cache copy to make the current session responsive. The
   cache has a storage budget, LRU eviction, resumable ranges, and never appears as an offline
   download.
3. **Downloaded** — the user explicitly pins an edition for a trip. The app verifies its complete
   file(s), cover, metadata, chapter map, and reader manifest before calling it available offline.

Source preference is downloaded, then a valid temporary cache, then remote. A downloaded edition
continues after Wi-Fi drops. Remote reading/listening continues while the connection holds.

Extend the existing Offline repository rather than creating a second queue. Introduce generic
`OfflineAsset` rows so video, EPUB, comic archives, PDFs, M4B, and multi-file audiobooks share
the existing queue, retry, storage-selection, LRU accounting, and deferred-progress outbox.

Download choices:

- one book/volume/issue;
- an audiobook as its chaptered M4B or all source files;
- selected books in a series;
- unread books in a series;
- a complete reading list.

## Readers

### Comic reader

Use page manifests from Komga and a native image pipeline. Include single/double page, continuous
scroll, fit width/height, zoom/pan, page scrub previews, bookmarks, and left-to-right progression.
Store page number plus image identifier for stable resume if the server rescans.

### Manga reader

Reuse the comic renderer but make reading direction an explicit per-series setting. Defaults:
right-to-left page progression, right-to-left double-page order, and optional vertical/webtoon
scroll. Never infer direction solely from the app language.

### Book reader

Use a standards-capable EPUB navigator and a native PDF renderer. EPUB progress is an EPUB locator
(CFI/Readium position) plus percentage; PDF progress is page plus within-page position. Support
font, margin, theme, line spacing, orientation, table of contents, bookmarks, search, and text
selection before adding annotation export.

### Audiobook player

Reuse the existing Media3 service and Hub session discipline, but give audiobooks an audio-first
screen: cover, chapter title, remaining chapter time, 0.75–3× speed, sleep timer, bookmarks,
previous/next chapter, and queue continuation. It uses Audiobookshelf chapter/progress data rather
than Jellyfin's video contract.

## Kindle-like progress and Whispersync

Pocket DS can sync its own progress through the Hub. It cannot claim to synchronize with Kindle or
Audible; those systems do not provide a compatible general-purpose progress and text-timing API.

### First release: reliable handoff

Pair an e-book and audiobook only after a confident identifier match or an explicit user link.
The user can choose **Continue in book** or **Continue in audio**. The Hub maps using named chapter
and normalized chapter progress. A user may calibrate an individual chapter when editions differ.
This provides useful cross-format handoff without pretending that it has word timings.

Progress is local-first. Every reader/audio update writes SQLite immediately, sends a revisioned
event when online, and uses a durable outbox when offline. The Hub preserves the newest compatible
position; a large divergent change presents a choice rather than silently discarding progress.
Komga and Audiobookshelf receive their own native progress updates after the Hub accepts one.

### Second release: synchronized highlighting

Exact phrase/word highlighting is possible when the EPUB contains EPUB 3 Media Overlays: SMIL
records bind EPUB text fragments to audio timestamps. Parse those overlays and store the text
locator/timing map locally. This is the only automatic exact-sync path in the first implementation.

For a normal EPUB plus M4B/MP3 audiobook, no usable word-timing relation exists in the files. An
optional later **PocketSync alignment** job can create a private phrase-level map using local
transcription/forced alignment. It must be opt-in, per book, resumable, inspectable, and clearly
labelled approximate. It is never required for ordinary reading or listening.

## Reading lists

Show Komga readlists as server-backed lists. Add Pocket Lists in the Hub for mixed content such as
`Red Rising re-read`, because a Komga list cannot represent an Audiobookshelf audiobook.

Pocket Lists support manual order, completed state, filters for downloaded items, and **Download
remaining**. They never alter a Komga list unless the user explicitly chooses to export a
compatible visual-only list later.

## Test-first delivery sequence

1. **Contract fixtures and pure matching tests**
   - Komga libraries/series/books/readlists/progression;
   - Audiobookshelf library/items/chapters/progress;
   - ISBN pairing, conservative fallback pairing, ambiguous-title rejection, and series sort;
   - reader-type selection and library classification.
2. **Read-only Reading browse**
   - Hub adapters and authenticated read-only endpoints;
   - Reading home, library grids, Red Rising-style series detail, editions, and readlists;
   - selected-user progress mapping and focus/scroll restoration.
3. **Native readers with online resources**
   - comic/manga pages, EPUB/PDF locators, audiobook chapters;
   - progress event conversion, cancellation, resuming, and user isolation.
4. **Temporary cache and pinned downloads**
   - generic OfflineAsset queue, ranges, retry/backoff, checksum/size verification, LRU eviction,
     storage migration, and offline progress reconciliation.
5. **Acquisition adapters**
   - Chaptarr test-stack spike with a disposable library;
   - Mylar3 test-stack spike;
   - request, manual search, queue status, import scan, and failure-state contracts.
6. **Edition handoff and Media Overlays**
   - chapter matching/calibration tests;
   - EPUB Media Overlay fixtures proving timestamp-to-locator highlighting;
   - no automatic alignment job until the handoff implementation is reliable on hardware.

Every milestone starts with focused contract/state tests, then implementation, full Android and Go
tests, and Pocket DS verification. Commits are feature-specific only after that evidence passes.

## Decisions before implementation

1. Run Chaptarr in a disposable Docker test stack and compare it with keeping Readarr only for
   existing content.
2. Confirm whether to install Audiobookshelf as the dedicated audio server.
3. Create test Komga libraries for Comics, Manga, and Books; do not point experimental scanners at
   the existing production folders.
4. Choose the first reader milestone: Comics/Manga is the lowest-risk start because Komga already
   supplies page-oriented data; EPUB and audio follow.

## References

- Komga read progress and reader API: https://komga.org/docs/guides/read-progress/ and
  https://komga.org/docs/openapi/web-pub-manifest/
- Komga series and readlists: https://komga.org/docs/openapi/series/ and
  https://komga.org/docs/openapi/readlists/
- Chaptarr: https://github.com/Chaptarr/chaptarr
- Mylar3: https://github.com/mylar3/mylar3
- Audiobookshelf: https://audiobookshelf.org/docs/documentation/introduction/
- EPUB 3 Media Overlays: https://www.w3.org/publishing/epub32/epub-mediaoverlays.html

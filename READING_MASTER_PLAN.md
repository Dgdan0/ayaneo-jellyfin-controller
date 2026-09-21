# Pocket DS Reading — master implementation and migration plan

Status: authoritative plan for `feature/reading-library`.

This document supersedes service-selection details in
`READING_LIBRARY_PLAN.md` and `READING_PLATFORM_REVIEW.md`. Those documents
remain useful research notes. No production service or media folder is changed
until the platform lab and migration gates below pass.

## 1. Outcome

Add a controller-first Reading section to Pocket DS that works with the same
self-hosted library from Pocket DS, iOS, Android, and the web.

The finished system supports:

- comics, manga, EPUB books, PDF documents, and audiobooks;
- browsing by library, series/work, volume/book, and chapter/issue;
- server streaming, temporary device caching, and pinned offline downloads;
- per-user progress that survives offline use and reconnects safely;
- acquisition requests and download status through bookkeeprr;
- controller-first comic, manga, EPUB, and PDF reading;
- audiobook playback using the existing Media3 playback foundation;
- paired ebook/audiobook handoff and synchronized narration when both editions
  exist;
- external use from Panels or another iOS client, Android clients, Storyteller
  mobile apps, and web readers;
- a reversible migration from Komga with no media deletion or in-place rename.

## 2. Selected architecture

No single service is mature at every job. Use a small stack with one clear
owner for each responsibility.

| Responsibility | Service | Authority |
| --- | --- | --- |
| Acquire, monitor, stage, name, and import reading media | bookkeeprr | Sole writer to canonical media roots |
| Comics, manga, visual library hierarchy, visual reading lists | Kavita candidate | Comic/manga catalog and page progress after lab acceptance |
| EPUB, audiobook, paired edition, synchronized narration | Storyteller | Book/audio/readaloud catalog and progress |
| Existing media fallback and ecosystem compatibility | Jellyfin | Read-only scanner; never reading-progress authority |
| App-facing authentication, aggregation, caching, and policy | Ayaneo Hub | Normalized API; never exposes upstream credentials |
| Pocket DS presentation and offline use | Android app | Local cache and pending-progress queue |

Komga remains running during the evaluation and parallel-run period. Kavita is
accepted only after Panels, the selected Android client, its API, page progress,
and the migration dry run all pass. If it fails, Komga remains the visual
catalog and the rest of this design is unchanged.

Storyteller is selected for books and audiobooks because it already:

- accepts EPUB, MP3, MP4/M4A/M4B, and matched ebook/audio pairs;
- merges both formats into one logical book;
- runs forced alignment using whisper.cpp;
- emits standard EPUB 3 Media Overlay readaloud files;
- provides iOS, Android, web, REST API, and OPDS access;
- preserves the reading position while switching between reading and listening.

WhisperX is not an initial dependency. It is evaluated only if controlled
Storyteller tests show an alignment-quality gap that its supported engines and
settings cannot fix.

## 3. Deployment model

### Docker Compose

Run Kavita, Storyteller, and bookkeeprr as a Docker Compose project on the
Ayaneo Media PC. They are container-first applications and benefit from:

- declarative versions, volumes, health checks, and restart policies;
- one isolated network and predictable service names;
- simple rollback to the previous image and database backup;
- no handcrafted Windows service wrapper for each runtime;
- optional GPU access for Storyteller transcription later.

Use exact version tags or image digests after the lab qualifies them. Never use
`latest` in production.

### FireDaemon

Keep the native Go Ayaneo Hub under FireDaemon. It already has a Windows
deployment path and does not need Docker to reach services through loopback.

Docker Desktop/WSL2 starts with Windows. Compose containers use
`restart: unless-stopped`. A scheduled health probe may notify the Hub when the
Docker engine is down, but it must not repeatedly restart a failing database.

### Network exposure

- Bind service ports to loopback or the Docker network where practical.
- Expose user-facing Kavita and Storyteller HTTPS through the existing
  Tailscale/Caddy route.
- Keep bookkeeprr administration tailnet-only.
- Do not add router port-forwarding.
- The Pocket DS app talks only to the Hub. External readers may talk to Kavita
  or Storyteller using per-user credentials over HTTPS.
- Store upstream API keys and passwords only in ignored secret files or Docker
  secrets. Never embed them in the APK or committed Compose file.

## 4. Canonical storage

There is one canonical copy of every source asset. Catalog services scan it;
they do not rename or reorganize it.

```text
D:\Media\Reading\
  Books\
    Author\Series\01 - Title\
      Title.epub
      metadata.opf
      cover.jpg
  Audiobooks\
    Author\Series\01 - Title\
      Title.m4b
      metadata.opf
      cover.jpg
  Comics\
    Publisher\Series (Year)\
      Series #001 (Year).cbz
      ComicInfo.xml
  Manga\
    Series\
      Series - v01.cbz
      ComicInfo.xml
  Staging\
  Quarantine\
```

This is compatible with Jellyfin's documented approach: media is separated by
type, every book has its own folder, and standard embedded or sidecar metadata
is retained. Manga gets its own operational root but is exposed to Jellyfin as
a Books/Comics library.

### Write ownership

- bookkeeprr alone writes or renames canonical source media.
- Kavita mounts Books, Comics, and Manga read-only.
- Jellyfin scans all relevant roots read-only.
- Storyteller imports canonical EPUB/audio read-only and stores its database,
  transcriptions, converted audio, and generated readaloud EPUBs in a separate
  writable data root.
- Storyteller metadata edits to canonical source files are disabled by policy.
- Generated readaloud EPUBs are derived assets. They live under
  `D:\Media\ReadingDerived\Storyteller`, may be large because they contain the
  narration, and can be regenerated after loss.

### Stable identities

The Hub creates a `work_id` independent of every server's record ID. Editions
link to it using, in order:

1. ISBN-13/ISBN-10;
2. ASIN or another source-specific edition identifier;
3. exact normalized title + author + series index;
4. an explicit user link.

Ambiguous matches are never auto-merged. Every edition stores source ID, media
type, file hash, language, edition/narrator, duration/page count, and canonical
relative path. Moving between services does not change the work identity.

## 5. Acquisition with bookkeeprr

bookkeeprr manages acquisition; it is not the progress authority or the only
reader.

### Initial safety settings

- Import existing roots in read-only mode.
- Disable one-time rename for existing files.
- Give bookkeeprr write access only to Staging, Quarantine, and the four
  canonical roots.
- Use dedicated qBittorrent categories for `reading-book`, `reading-audio`,
  `reading-comic`, and `reading-manga`.
- New downloads finish in Staging, pass validation, then import atomically.
- A failed/ambiguous import stays in Quarantine and appears in Manage; it never
  replaces an existing edition automatically.

### Post-import flow

1. Validate extension, nonzero size, archive integrity, and malware scan if
   available.
2. Extract embedded identifiers and metadata.
3. Choose a collision-free canonical destination.
4. Move within the same volume atomically; copy-plus-hash-verify across volumes.
5. Write standard sidecars and cover art without overwriting locked metadata.
6. Trigger Kavita, Storyteller, and Jellyfin scans for the applicable roots.
7. Ask the Hub index to reconcile the new editions.
8. Notify the app with success, rejection, or manual-match-needed status.

The Hub later exposes search, monitor, manual release selection, queue status,
retry, cancel, and import diagnostics using a bookkeeprr adapter. Existing
qBittorrent status remains available for transport-level detail.

## 6. Hub domain and API

### Normalized model

```text
ReadingLibrary
  id, source, kind, title, artwork, capabilities

ReadingWork
  id, title, sort_title, authors, series, series_index, overview,
  artwork, genres, year, languages, editions, progress, availability

ReadingEdition
  id, work_id, source, source_item_id, kind, format, identifiers,
  narrator, page_count, duration_ms, file_hash, availability

ReadingLocator
  kind(page|epub_cfi|pdf_page|audio_ms|media_overlay),
  chapter_id, position, percentage, updated_at, source_revision

ReadingProgress
  user_id, edition_id, locator, completed, updated_at, pending_sync

EditionLink
  work_id, ebook_edition_id, audio_edition_id, confidence,
  method(identifier|metadata|manual), alignment_status
```

### Hub endpoints

All endpoints require existing bearer authentication and a new `reading`
scope. Acquisition mutations additionally require `manage`.

```text
GET  /v1/reading/libraries
GET  /v1/reading/libraries/{libraryId}/items?page=&sort=&direction=
GET  /v1/reading/works/{workId}
GET  /v1/reading/works/{workId}/editions
GET  /v1/reading/works/{workId}/continue
GET  /v1/reading/editions/{editionId}/manifest
GET  /v1/reading/editions/{editionId}/resource/{resourceId}
POST /v1/reading/editions/{editionId}/progress
POST /v1/reading/progress/reconcile
GET  /v1/reading/lists
GET  /v1/reading/lists/{listId}

GET  /v1/reading/acquisition/search?q=&kind=
POST /v1/reading/acquisition/requests
GET  /v1/reading/acquisition/queue
POST /v1/reading/acquisition/jobs/{jobId}/retry
DELETE /v1/reading/acquisition/jobs/{jobId}

GET  /v1/reading/downloads/{editionId}/plan
```

Opaque Hub URLs proxy only resources approved in the edition manifest. Upstream
credentials, filesystem paths, arbitrary URLs, and Docker hostnames never leave
the Hub. Range requests and cancellation are forwarded incrementally.

### Progress ownership and reconciliation

- Kavita is authoritative for comic/manga page progress.
- Storyteller is authoritative for ebook, audiobook, and readaloud progress.
- Pocket DS writes through the Hub immediately while online.
- Offline updates enter an ordered local outbox with edition hash and logical
  position.
- On reconnect, the Hub compares upstream and device revisions. A clearly later
  monotonic position may merge automatically. Completion, large backward
  jumps, edition changes, or concurrent progress creates a choice instead of
  silently overwriting either side.
- Jellyfin reading state is display-only and never wins reconciliation.

## 7. Pocket DS information architecture

Retain the existing sidebar and put one persistent `Media | Books` switch at
the top of content screens. Home, Discover, Library, Offline, and Transfers
remember independent focus and scroll state for each mode. Notifications,
Manage, and Settings are shared system screens and do not change with the
switch.

Books Discover has a second filter row: All, Books, Audiobooks, Comics, Manga,
and Light novels. Offline groups local reading assets by their original source
library and recreates the online work/series hierarchy using only downloaded
editions. Transfer views keep media and reading queues distinct without adding
more sidebar destinations.

Cards retain the app's focus treatment but reserve scale padding so focused
items cannot leave the screen. A opens the work/detail page. X opens contextual
actions. Y searches consistently with other sections. B returns. L1/R1 changes
filters where appropriate.

The work page shows one logical work with all available editions. A Red Rising
entry can show EPUB, audiobook, and readaloud editions without duplicating the
book in the catalog. Series pages show books in series order.

## 8. Shared reader foundation

All readers use the same session shell:

- `ReaderSession`: source, edition, current locator, progress, download state;
- `ReaderSource`: remote manifest, temporary cache, or pinned local assets;
- `ReaderController`: open, close, next/previous, jump, focus, overlay state;
- `ReaderProgressStore`: debounced online writes and durable offline outbox;
- `ReaderCache`: bounded LRU cache plus pinned downloads;
- `ReaderOverlay`: title, position, chapter, settings, brightness, and exit;
- identical controller/touch accessibility behavior;
- restore originating screen, scroll, and focused work on exit.

Three rendering engines sit below that shell.

### A. Paged-image reader — comics and manga

Profiles change defaults without duplicating the engine:

- Comic: left-to-right, spread-aware.
- Manga: right-to-left, cover/spread-aware.
- Webtoon: continuous vertical strip.

Modes include single page, double page, fit width, fit height, continuous,
rotation, crop margins, brightness, and optional image filters.

Controller navigation:

- A advances; B reveals controls, then exits.
- D-pad pans a zoomed page or moves focus in the overlay.
- L1/R1 previous/next issue or chapter.
- Triggers zoom out/in.
- X opens reader settings; Y opens page/chapter navigation.

The requested large-page navigation is a deterministic viewport-step mode:
divide a fitted page into overlapping thirds and move through top/middle/bottom
or right/centre/left depending on reading direction. It does not pretend to
detect comic panels. Optional real panel detection is a later experiment.

### B. EPUB reader

Use Readium Kotlin rather than a custom WebView parser. Preserve EPUB spine,
styles, table of contents, accessibility, vertical writing, and stable EPUB CFI
locators.

Settings include font, size, line height, margins, columns, themes, publisher
style toggle, continuous/paginated mode, and screen orientation.

When Storyteller exposes a readaloud edition, show Read, Listen, and Read Along.
The active sentence is highlighted from the EPUB Media Overlay. Switching modes
keeps the same logical locator.

### C. PDF/document reader

Use a maintained PDF renderer with tile-based zoom. Support fit page/width,
continuous/single page, page thumbnails, search where a text layer exists,
bookmarks, and the same overlapping viewport-step navigation.

### Audiobook mode

Reuse Media3 and the existing player service with an audio-first overlay:
cover, chapter, elapsed/remaining time, speed, sleep timer, bookmarks, and
previous/next chapter. When paired, offer `Open book here` using Storyteller's
logical position.

## 9. Device downloads and caching

Server acquisition and device download are distinct operations.

- **Remote:** stream/page-fetch through the Hub.
- **Temporary:** cache enough for responsive reading; evict with an LRU policy.
- **Pinned:** retain the complete edition until the user deletes it.

CBZ/CBR and EPUB are downloaded and verified as complete files before offline
opening. PDF and audio support resumable range downloads. Readaloud EPUBs may be
large and display their size before download.

Download manager grouping:

- series/work first;
- editions underneath;
- ordered book/volume/issue queue;
- pause, resume, retry with backoff, cancel, remove local copy;
- checksum, free-space, and storage-location validation;
- SD-card destination through Android's Storage Access Framework;
- never discard a playable completed copy because metadata refresh failed.

Downloaded content mirrors the online hierarchy but hides unavailable
volumes/issues. If Continue points to a missing offline edition, explain that it
is not downloaded and offer the nearest available local item.

## 10. Komga migration

Migration is a parallel run, not a cutover command.

### Inventory

Export or record:

- Komga version, configuration, users, libraries, roots, permissions;
- series/book counts and per-file SHA-256;
- read progress, completed state, collections, and readlists;
- Panels URL/account and Android-client configuration;
- current backups and storage free space.

### Read-only qualification

1. Back up Komga database/config and canonical media.
2. Start Kavita with copied test fixtures.
3. Connect Panels and the selected Android client.
4. Verify browse, search, streaming, full-series download, offline return,
   progress writes, multiple users, HTTPS/Tailscale, and large libraries.
5. Mount the production media roots read-only and scan without moving files.
6. Compare library/series/item counts and random hashes to the Komga inventory.
7. Dry-run progress and list conversion; report unmappable records.

### Parallel run and cutover

- Keep Komga available for at least two weeks after Kavita passes qualification.
- Freeze collection/list edits briefly during the final progress export.
- Import progress with page-number bounds and completed-state checks.
- Change external clients one at a time and verify their first write-back.
- Keep the Komga database and config untouched for rollback.
- Remove Komga only after the user explicitly accepts the replacement on iOS,
  Android, Pocket DS, and web. Removing it is a separate milestone.

## 11. Backups, recovery, and observability

Back up daily:

- bookkeeprr, Kavita, Storyteller, and Hub databases/configuration;
- Docker Compose, pinned image list, and secret inventory (secrets encrypted);
- standard sidecars and cover art.

Canonical media follows the user's normal media backup policy. Derived
Storyteller audio/transcription/readaloud data may use a cheaper backup tier,
but the Storyteller database and user progress are protected.

Manage exposes read-only health, version, queue depth, last scan, last backup,
free space, failed imports, and alignment jobs. Restart and scan actions require
explicit focus/activation and report their result. Logs redact credentials,
URLs with tokens, and local file paths outside an administrator view.

## 12. Test strategy

Work remains test-first. Each milestone starts with focused failing tests and
ends with those tests plus the full relevant suite green.

### Fixtures

Use only generated, public-domain, or personally created fixtures:

- tiny EPUB with TOC and stable CFI targets;
- EPUB 3 Media Overlay with short generated speech;
- CBZ comic with spread metadata;
- right-to-left manga CBZ;
- PDF with and without a text layer;
- M4B/MP3 with chapters;
- matched and deliberately mismatched ebook/audio pairs;
- duplicate title with different ISBN/edition;
- corrupt archive, interrupted download, and missing cover.

### Automated layers

- Pure Go: normalized IDs, pairing, paths, locators, progress conflicts,
  manifests, resource allowlists, and migration mapping.
- Hub HTTP: auth/scope, pagination, upstream failures, cancellation, streaming,
  ranges, retries, late responses, and credential redaction.
- Kotlin unit: reader state, controller map, viewport thirds, direction,
  progress outbox, cache eviction, download ordering, and focus restoration.
- Android instrumentation: EPUB/PDF/image rendering, Media Overlay timing,
  rotation, lifecycle, Storage Access Framework, and accessibility.
- Compose contract: config validity, pinned images, read-only mounts, health
  endpoints, secret absence, backup/restore, and startup after reboot.
- Migration: counts, hashes, progress bounds, idempotence, and rollback.

### Hardware matrix

- Pocket DS controller/touch, sleep/resume, network loss, SD card, and storage
  pressure;
- Panels on iPhone/iPad and chosen Android client;
- Storyteller iOS/Android read/listen/readaloud and offline download;
- local Wi-Fi and Tailscale remote connection;
- CPU alignment and optional GPU worker;
- large comic, long audiobook, mismatched edition, and interrupted alignment.

## 13. Milestones and commits

Each milestone is one reviewable green commit on `feature/reading-library`.
Push the branch after the milestone passes its gate. Do not create a release for
internal scaffolding. User-facing milestones receive prerelease APK/Hub assets;
stable release follows hardware acceptance.

### M0 — Architecture and platform lab

- Commit authoritative plan, Compose lab, ignored secrets template, generated
  fixture manifest, storage-layout validator, and platform test checklist.
- Gate: Go tests, Compose configuration validation where Docker is available,
  no production paths/secrets, and clean rollback instructions.

### M1A — Automated service proof

- Start pinned Kavita, Storyteller, and bookkeeprr containers with fixtures.
- Test health, API/OPDS, catalog parsing, edition pairing, progress conflict
  handling, read-only mounts, source hashes, and restart persistence.
- Produce a reproducible evidence report. Do not select or deploy the visual
  catalog yet.
- Gate: all automated lab checks pass; no production media is mounted and no
  source-media hash changes.

### M1B — External-client qualification and catalog decision

- Test Panels, the selected Android client, and Storyteller iOS/Android clients
  against the isolated lab over local HTTPS and Tailscale.
- Verify browse, stream, download, offline return, page/audio progress,
  readaloud playback, multiple users, and backup/restore.
- Produce the final evidence report and select Komga or Kavita for the visual
  catalog.
- Gate: every external-client acceptance check passes. Until then Komga stays
  active and Kavita remains a candidate.

### M2 — Canonical storage and migration tooling

- Inventory Komga, compute hashes, validate sidecars, dry-run destinations, and
  export progress/lists without mutation.
- Gate: repeatable dry run and complete rollback bundle.

### M3 — bookkeeprr acquisition

- Configure qBittorrent categories, staging/quarantine, safe import, retries,
  scan notifications, Hub health and queue adapter.
- Gate: fixture acquisitions import correctly; failures never touch existing
  media; cancel/retry works.

Status on 2026-09-21: the isolated service slice proves category routing,
byte-identical import, and idempotent cancel. The Hub now exposes tested opaque
request options, single-book/whole-series creation, and normalized BookKeeprr
transfer status. Admin writes use BookKeeprr's mobile login/exchange flow;
personal-key reads remain separate. The Pocket DS has the request form and a
Books transfer view. Hub-owned Cancel and Retry use durable opaque transfer
capabilities; a failed replacement grab survives restart as an actionable
ticket, and an uncertain response is reconciled before another grab. Imported
content cannot be canceled from this view. Production paths and the deferred
Red Rising acceptance run remain open. Pause remains absent because BookKeeprr
1.1.1 cannot model it correctly. See
`deploy/reading/M3_ACQUISITION_EVIDENCE.md`.

### M4 — Hub reading catalog

- Add configuration, adapters, normalized domain, browse/detail/search/list
  endpoints, image/resource proxies, authentication, cache, and cancellation.
- Gate: adapter contract and API tests green against recorded fixtures.

Status on 2026-09-20: two green slices are complete. The first adds BookKeeprr
configuration/health, bearer-authenticated browse/category/search, the
`reading` token scope, normalized five-type discovery models, partial failure
handling, caching, and opaque registered cover proxying. The second adds
read-only Kavita and Storyteller adapters, renewable Storyteller bearer tokens,
normalized libraries and paged work browsing, canonical persistent Hub work
IDs, work details, available editions, reading progress, hierarchy/continue
data, and authenticated cover proxies. It passed the full Go suite and a live
lab run across three Kavita libraries and the Storyteller catalog. Lists,
reader manifests/resources, progress writes/reconciliation, and catalog search
remain in later M4 slices.

Status addendum on 2026-09-21: Storyteller series now collapse into one stable
collection entry while standalone books remain independent. Collection details
return their available books in series order with child work IDs. Library sort
semantics now include series, author, and true last-read time; Kavita advertises
only the sorts its API can honor.

### M5 — Pocket DS browsing

- Add the global content-mode switch, unified catalog, work/series details,
  edition chooser, lists, search, focus/scroll restoration, and states.
- Gate: Kotlin tests, debug build, and hardware navigation acceptance.

Status on 2026-09-21: Discover now has the persistent Media/Books switch,
reading-type filters, browse/category paging, search, separate mode state, and a
read-only discovery metadata page. Library shares that global switch and now
browses real Kavita/Storyteller libraries, paged and sortable normalized work
grids, progress, canonical work details, editions, Continue data, and available
sections. Reading discovery details now offer the tested BookKeeprr request
form, and Transfers shares the Media/Books switch. Kotlin tests and the debug
build pass. Series collections now open into a horizontal row of available
books, and sort menus expose Title, Series, Author, Date added, and Last read
according to each source's capabilities. Recent activity defaults to newest
first. Books transfers now expose the Hub-provided Retry and Cancel actions
with confirmation for cancellation. Pocket DS hardware acceptance and the
Home/Offline Books views remain open.

### M6 — Shared reader shell

- Add ReaderSession, source/cache/progress interfaces, overlay, controller map,
  lifecycle, focus return, and download hooks using fake renderers.
- Gate: pure state tests and hardware shell behavior.

### M7 — Comic and manga reader

- Add paged-image engine, profiles, spreads, RTL, webtoon, zoom/pan, viewport
  thirds, issue navigation, progress, and offline.
- Gate: image fixtures and Pocket DS/phone controller/touch acceptance.

### M8 — EPUB and PDF readers

- Integrate Readium EPUB and PDF engine, settings, TOC/search, stable locators,
  cache, progress, and offline.
- Gate: rendering/instrumentation tests plus representative hardware books.

### M9 — Audiobooks and edition pairing

- Extend Media3 audio mode, chapters, speed, sleep, bookmarks, pairing UI, and
  read/listen handoff.
- Gate: M4B/multi-MP3, offline, progress conflicts, and external-client checks.

### M10 — Device download manager

- Extend current offline system to reading assets, groups, SD-card selection,
  retries, checksums, local-first opening, and reconnect progress.
- Gate: interrupted/corrupt/low-space tests and travel-mode hardware test.

### M11 — Production migration and cutover

- Run read-only scan, import progress/lists, parallel run, per-client cutover,
  and backups. Komga remains rollback-ready.
- Gate: explicit user acceptance on every client. No deletion in this milestone.

### M12 — Storyteller readaloud integration

- Surface alignment status/jobs, readaloud edition, Media Overlay playback,
  sentence highlight, mode switching, storage estimates, and errors.
- Gate: native readaloud EPUB and generated match/mismatch fixtures on Pocket
  DS, Storyteller iOS/Android, and a standards-compatible external reader.

### M13 — Optional alignment improvements

- Benchmark Storyteller CPU/GPU settings. Only if quality remains inadequate,
  spike WhisperX or another forced aligner behind a replaceable worker API.
- Gate: objective alignment corpus improves without regressing supported
  languages or creating an unmaintainable fork.

### M14 — Cleanup and stable release

- Documentation, licenses, backup/restore drill, security review, performance,
  telemetry-free operation, accessibility, release notes, Hub-first deployment,
  APK release, and optional Komga retirement after separate approval.

## 14. Immediate implementation boundary

M0 and M1A are complete. M1B is still open for the iOS and client-download
isolation rows. M2 read-only inventory tooling may proceed in parallel because
it does not select the catalog or mutate media. It may add tests, scanners,
metadata readers, per-user Komga exports, ignored report bundles, and
documentation. It must not:

- copy, move, rename, or delete production media;
- run a production scan until `migration.local.yml` has been reviewed;
- import progress or lists into another service;
- connect bookkeeprr to qBittorrent;
- begin M3 acquisition work;
- remove Komga or select Kavita before the M1B decision gate;
- edit Jellyfin, Caddy, Tailscale, FireDaemon, or Docker Desktop for M2;
- deploy a Hub or APK;
- commit credentials or device-specific absolute paths.

## 15. Primary references

- Jellyfin books: https://jellyfin.org/docs/general/server/media/books/
- Kavita libraries: https://wiki.kavitareader.com/guides/admin-settings/libraries/
- Kavita and Panels: https://guides.panels.app/opds/kavita
- Storyteller: https://storyteller-platform.dev/docs/welcome/
- Storyteller self-hosting: https://storyteller-platform.dev/docs/installation/self-hosting/
- Storyteller alignment: https://storyteller-platform.dev/docs/managing/aligning/
- Storyteller OPDS: https://storyteller-platform.dev/docs/reading/opds/
- EPUB Media Overlays: https://www.w3.org/publishing/epub32/epub-mediaoverlays.html
- bookkeeprr: https://github.com/paulcsiki/bookkeeprr

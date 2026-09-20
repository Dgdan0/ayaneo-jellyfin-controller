# Reading platform review

Status: exploration only. No service, app, Hub, library, or download-client
configuration changes are authorised by this document.

> **Existing-client update — 2026-09-20:** The user already reads Komga in
> Panels on iOS and a separate Android client. Preserve Komga as the primary
> visual catalog. Kavita is an optional comparison candidate, not a migration
> target, unless it passes the same Panels and Android-client tests. A feature
> inside Pocket DS is insufficient if it makes progress or reading capability
> unavailable in those existing readers.

## Decision

Use a **hybrid file layout**, not a Jellyfin-only or a many-copy setup.

1. Keep one canonical reading-media tree, organised in Jellyfin's documented
   per-title folders. The files may be scanned by multiple read-only servers;
   they are not copied between them.
2. Continue to expose that tree to Jellyfin. It provides a familiar fallback,
   one server the current Pocket DS Hub already understands, library scanning,
   artwork, remote access, and audiobook playback reuse.
3. Keep **Komga** as the primary catalog for comics and manga, and any visual
   material already consumed through Panels and the Android reader. It already
   provides those clients with server-backed progress sync.
4. Evaluate **Kavita** only as a non-destructive comparison for text ebooks
   and cross-format reading lists. Do not migrate existing Komga libraries as
   part of that evaluation.
5. Use **Audiobookshelf** for audiobooks. It is purpose-built for audio
   chapters, user progress, bookmarks, and its public API. It may scan a book
   folder containing an ebook, but its own documentation calls ebook support
   limited; do not make it the visual-reader catalog.
6. The Pocket DS app remains the single controller-first experience. The Hub
   adapts Jellyfin, Kavita, and Audiobookshelf behind one authenticated API;
   the app never stores their credentials.

This keeps the good parts of Jellyfin without forcing it to become a reading
server it is not designed to be.

## What Jellyfin's book layout gives us

Jellyfin recommends separate Audiobooks, Books, and Comics roots and one folder
per title. EPUB files can contain their own metadata; OPF sidecars, ComicInfo,
cover/poster files, and conventional filenames supplement other formats.
This is an excellent, portable canonical disk layout. It also supports the
formats we expect to retain: EPUB/PDF, CBZ/CBR and other comic archives, plus
common audio formats.

Benefits for this project:

- One copy of every owned file, readable by the existing Jellyfin install.
- Existing Jellyfin user selection, Hub security model, image proxy and remote
  connectivity stay useful.
- The current Media3 player can serve as the base for audiobook playback.
- It is easy to recover from a future reader-server change because the files
  and standard sidecars remain usable without that server's database.

Limits:

- Jellyfin explicitly does not support read-along audiobooks.
- Its documentation does not define native book-series grouping, rich reading
  lists, a dedicated ebook/comic reader, or ebook/audiobook handoff. The app
  and Hub would have to build those features from scratch.
- A local offline copy, background cache, and EPUB/CBZ rendering are Pocket DS
  features either way; selecting Jellyfin does not eliminate that work.

## Candidate catalog services

| Role | Candidate | Recommendation | Why |
| --- | --- | --- | --- |
| Comics/manga and existing Panels workflow | Komga | Keep as primary | Panels supports Komga, preserves its hierarchy, and writes page progress back to Komga. |
| Text ebooks and cross-format reading lists | Kavita | Optional comparison | Native library types, per-user reading progress, EPUB reader, manga/comic support, and ordered lists that can cross reader types. It must prove Panels/Android compatibility before any migration decision. |
| Audiobooks | Audiobookshelf | Use | The strongest fit for chapters, bookmarks, playback progress, multi-user audio libraries, and an explicit API. |
| General fallback | Jellyfin | Keep | Useful for portable storage, the current Hub, and shared media playback. Not the primary reading experience. |

### Canonical folders

```text
D:\Media\Reading\
  Books\Author\Series\01 - Title\Title.epub
  Audiobooks\Author\Series\01 - Title\Title.m4b
  Comics\Publisher\Series (Year)\Series #001.cbz
  Manga\Series\Volume 01.cbz
```

Each service gets read-only access to its relevant roots after the acquisition
manager has completed an import. Exactly one service owns file naming/import;
catalog servers never reorganise files.

## Acquisition-manager candidates

These managers organise material the user has lawfully obtained or imported.
They should first be tested against a disposable library and an existing
qBittorrent category. No production root or existing library is changed during
evaluation.

| Scope | Candidate | Assessment |
| --- | --- | --- |
| Ebooks + audiobooks | Bindery | Best initial evaluation. Active, single Go service, multiple metadata sources, separate ebook/audiobook roots, narrator metadata, and import diagnostics. |
| Ebooks + audiobooks | Chaptarr | Viable second candidate. It supports both formats and editions in one instance, but describes itself as beta. Test it before trusting it with the primary library. |
| Ebooks + audiobooks + comics/magazines | LazyLibrarian | Mature broad alternative, but the interface and metadata path are older. It is not a strong manga solution. |
| Comics | Kapowarr | Strong focused candidate with a familiar *arr-style workflow, existing-library import and manual/monitored searches. |
| Comics | Mylar3 | Keep as an alternative if its issue/watchlist workflow better fits the comic collection. |
| Ebooks + audiobooks + comics + manga | bookkeeprr | The only current all-in-one candidate found. It claims first-class handling for all five reading media types, but it is new enough to require a disposable proof-of-concept. Do not make it the only copy of the library until it passes that test. |
| Ebooks + audiobooks + manga | Librarr | Interesting single-binary candidate, but not a complete comics solution and should remain an evaluation candidate. |

Readarr itself is retired and must not be used for new work.

### bookkeeprr compatibility constraint

bookkeeprr is promising rather than broken: version 1.1.1 advertises one
container, five content types, qBittorrent automation, a built-in reader,
progress and offline downloads. However, its published documentation currently
does not describe an OPDS endpoint. That makes Panels and generic external
reader compatibility unproven, and it must not replace Komga in this setup.
It can still be evaluated as an acquisition/import manager with its existing
library-import mode set read-only.

### Evaluation order

1. Create four tiny test roots containing public-domain or personally created
   fixtures. Do not point a candidate at the production media tree.
2. Run Kavita and Audiobookshelf against read-only fixtures. Verify scanning,
   series order, covers, user progress, reading-list behavior, and their APIs.
3. Run Bindery and Kapowarr against separate disposable import roots. Verify
   metadata, a deliberate import, correct qBittorrent categorisation, no
   duplicate or cross-root moves, and a clean rollback.
4. Only then decide whether a single broad manager such as bookkeeprr is worth
   replacing the focused pair. It must demonstrate the same test cases plus
   safe separation of all media types.

## Read-along ebook and audiobook sync

### Interoperability rule

An alignment map held only by the Pocket DS Hub is useful only to Pocket DS.
Existing third-party readers cannot discover or render it. The interoperable
form is a derived EPUB 3 Media Overlay package: keep the original EPUB
unchanged, generate a separately identified read-along edition containing SMIL
timing data, and offer it only to clients that support Media Overlays.

Panels is a comic reader: it supports image-based comic EPUB, not reflowable
text EPUB/light novels, and does not act as an ebook-plus-audiobook read-along
client. It will continue to work well for Komga comics and manga, but cannot be
the consumer for this feature. Any future word-highlight experience therefore
requires a reader that explicitly supports Media Overlays or the compatible
alignment protocol. Test a candidate such as Enve separately; it claims
StoryAlign on iOS and Komga/Audiobookshelf connectivity, but its Android app is
still in open testing and cannot yet be a required dependency.

### What an EPUB can know

Normal EPUB files are text and layout. Normal M4B/MP3 files are audio and
chapters. They usually have no shared timing data, so neither file inherently
knows which word is being spoken at a given second.

An EPUB 3 *Media Overlay* is the special case. It contains a SMIL timing map
that links a text fragment to an audio clip. A publisher can make the fragment
a phrase, sentence, or word. When the same edition includes a valid overlay,
the app can highlight that precise text without AI. This is the closest
standards-based form of read-along. Jellyfin does not support it.

### Our staged design

1. **Progress handoff** — match an ebook and audiobook by ISBN first, then a
   conservative title/author/series match. Save chapter + relative position.
   "Continue reading" seeks to the corresponding ebook chapter; "listen from
   here" seeks the corresponding audio chapter. Let the user calibrate a
   chapter offset. This is reliable and inexpensive but does not highlight
   words.
2. **Media Overlay support** — when a valid EPUB Media Overlay is present,
   use its exact timings. This is the only version promised to be exact.
3. **Private alignment job, optional** — for a verified matching pair without
   an overlay, the Hub can run a local alignment worker. It takes ebook text
   and audiobook audio one chapter at a time, obtains speech timing with a
   local transcription/alignment model, aligns it to the ebook's actual text,
   saves phrase/word time ranges, and lets the reader highlight the current
   phrase or word.

WhisperX and Montreal Forced Aligner are suitable technologies to spike. This
is not a generic "clip" operation: it is **forced alignment** between known
text and the spoken recording. It must run chapter-by-chapter, attach every
result to hashes of the exact EPUB and audio edition, retain confidence scores,
and offer an alignment correction/disable control. Narrated editions can omit
introductions, change wording, include dramatization or music, and drift over a
long recording. Therefore the app never claims an AI map is perfectly exact.

The job stays on the user's own server: no book text or audiobook needs to be
sent to a cloud provider. It is compute-heavy, should be queued only at the
user's request, and must never block reading or listening.

## Feature tests before implementation

Before writing Android or Hub production code, create fixture-driven tests for:

- the four canonical directory layouts and metadata sidecars;
- a Red Rising-like work containing ordered ebooks and audiobooks;
- normal EPUB versus EPUB with a Media Overlay;
- one audiobook with matching chapter structure and one deliberate mismatch;
- a comic, manga, and EPUB reading-list sequence;
- downloaded-first, temporary-cache, remote-stream, offline-progress-queue and
  reconnect-sync behavior;
- pairing confidence, manual linking, and refusal to auto-link ambiguous
  editions;
- alignment-map validation, low-confidence suppression, and correction.

Only after those contract tests are red should an implementation phase start.

## Sources

- https://jellyfin.org/docs/general/server/media/books/
- https://wiki.kavitareader.com/guides/admin-settings/libraries/
- https://wiki.kavitareader.com/guides/features/readinglists/
- https://audiobookshelf.org/docs/
- https://github.com/vavallee/bindery
- https://github.com/Chaptarr/chaptarr
- https://github.com/Casvt/Kapowarr
- https://github.com/paulcsiki/bookkeeprr
- https://www.w3.org/publishing/epub32/epub-mediaoverlays.html
- https://github.com/m-bain/whisperX

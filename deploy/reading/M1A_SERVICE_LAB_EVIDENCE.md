# M1A reading-service lab evidence

Date: 2026-09-20
Branch: `feature/reading-library`

## Scope and safety

This run used only the generated media in `deploy/reading/lab-media`. No
production media, Komga database, Jellyfin library, router rule, or external
client was changed.

The lab ran these immutable images:

| Service | Observed version | Image digest |
| --- | --- | --- |
| Kavita | 0.9.1.4 | `sha256:31181a32f0dda73cae68721867028a7253d57881b58bea5754cd9e578e75421a` |
| Storyteller | 2.14.21 | `sha256:f063fcd838ffd9723d581d7a190135069c58c595e6ce9ac41b5e2f8bec090db7` |
| bookkeeprr | 1.1.1 | `sha256:27efbf26e8b37d339ecba16b0fb12a80fe481897bc2c3f451613626a508f8b27` |

All published ports were bound to `127.0.0.1`. Secrets and service databases
remained below ignored lab directories.

## Fixture coverage

`go run ./cmd/reading-fixtures` generated eight deterministic assets:

- EPUB 3 ebook with TOC;
- EPUB 3 Media Overlay readaloud with embedded generated audio;
- matching MP3 audiobook with ID3 title, album, and author metadata;
- two-page CBZ comic with `ComicInfo.xml`;
- two-page right-to-left manga CBZ;
- searchable PDF;
- image-only PDF;
- deliberately corrupt CBZ in Staging.

The fixture tests require the EPUB mimetype entry to be first, uncompressed,
and free of ZIP extra fields. They also require ID3 pairing metadata to match
the ebook.

## Results

| Check | Result | Evidence |
| --- | --- | --- |
| Compose model | Pass | Three pinned services, loopback ports, restart policy, file-backed Storyteller secret |
| Health | Pass | All three containers reached `healthy` |
| Runtime mount policy | Pass | Kavita `/reading` and Storyteller `/library` rejected write probes; bookkeeprr could create and remove one controlled Staging probe |
| Source integrity | Pass | All 8 manifest SHA-256 values still matched after scan, progress, merge, and restart |
| Kavita parsing | Pass | 6 catalog series: 2 EPUB, 2 PDF, 1 comic, 1 manga |
| Kavita API and OPDS | Pass | Authenticated REST query returned the catalog; OPDS root returned HTTP 200 and an Atom navigation feed |
| Kavita progress | Pass | Comic page 1 saved and remained page 1 after container restart |
| Storyteller parsing | Pass | Media Overlay EPUB became a readaloud edition; ebook and audiobook became a paired logical book after merge |
| Storyteller API and OPDS | Pass | Bearer-token API and Basic-auth OPDS root returned successfully |
| Storyteller progress | Pass | 50% Readium locator saved; an older write returned 409; 50% remained after restart |
| Restart persistence | Pass | Accounts, six Kavita entries, two Storyteller logical books, pairing, and both progress values survived restart |
| bookkeeprr smoke test | Pass | `/api/health` reported healthy and the worker heartbeat was present |

Repository validation also passed:

- `go test ./...`
- `go vet ./...`
- `gradlew.bat testDebugUnitTest assembleDebug`
- `docker-compose --env-file deploy/reading/.env -f deploy/reading/compose.yaml config --quiet`

## Compatibility findings

Kavita's `enableMetadata` library option controls embedded OPF and ComicInfo
parsing as well as metadata features. With it disabled, the pinned Kavita build
skipped valid EPUB fixtures. The lab therefore enables embedded metadata while
keeping external metadata matching disabled.

Storyteller 2.14.21 does not automatically combine matching files discovered
under separate ebook and audiobook roots. Its multi-path `POST /api/v2/books`
also failed the second candidate with `UNIQUE constraint failed: book.uuid`.
The supported `POST /api/v2/books/merge` route successfully combined the two
scanned editions and retained the result through restart. The acquisition
adapter must use scan-then-merge and make that operation idempotent.

## Decision and remaining M1B gate

The automated service proof passes. Kavita remains a candidate rather than the
selected replacement for Komga. M1B still requires real-client checks in
Panels, the chosen Android comic client, and Storyteller iOS/Android, plus an
isolated backup/restore exercise and local/Tailscale HTTPS verification.

Komga remains untouched and available throughout M1B.

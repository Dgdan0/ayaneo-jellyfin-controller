# Production reading cutover evidence

Date: 2026-09-21
Branch: `feature/reading-library`
Status: passed; real-title acquisition remains deferred

## Cutover

The stopped lab state was copied into `D:\Apps\PocketDS\Reading`. The source,
production copy, and ignored rollback snapshot were compared by relative path,
size, and SHA-256 before production started. Kavita, Storyteller, BookKeeprr,
the qBittorrent 5 compatibility proxy, and qBittorrent then started from the
pinned production Compose model and all five reported healthy.

The lab Compose project remains stopped because it uses the same loopback
ports. Docker restart policies keep the production containers active across
Docker restarts. The FireDaemon-managed Ayaneo Hub remained running and its
`/v1/health/live` endpoint returned HTTP 200 throughout the cutover.

## Storage and service checks

| Check | Result |
| --- | --- |
| Production Compose model | Pass; all images use qualified digests and all published administration ports bind to `127.0.0.1` |
| Existing library isolation | Pass; `D:\Bookshelf` is read-only and visible only to Kavita and Storyteller |
| Managed library isolation | Pass; BookKeeprr is the only acquisition service with write access to `D:\Media\Reading` |
| Download isolation | Pass; BookKeeprr and qBittorrent share only `E:\Downloads\Reading` |
| qBittorrent paths | Pass; save path `/downloads`, temp path `/downloads/incomplete`, and `temp_path_enabled=true` |
| BookKeeprr connection | Pass; Docker host `qbittorrent:18080`, healthy worker, and tracked M3 fixture present under `/media/books` |
| Storyteller watches | Pass; three ebook roots use `copy` plus `backup-and-convert`, and two audiobook roots use `reference` |
| Hub adapters | Pass; existing BookKeeprr, Kavita, and Storyteller base URLs already target the production loopback ports |

Nine qualified fixture files totaling 27,947 bytes were copied into the empty
managed roots. Every destination matched its source SHA-256. Their cleanup
manifest is `D:\Media\Reading\.pocketds-qualification-fixtures.json`. The one
copied qBittorrent fixture entry referenced missing acquisition data, so it was
removed with `deleteFiles=false`; the imported EPUB and BookKeeprr history were
retained.

## Reader scans

Kavita's first production scan completed successfully:

| Library | Files | Series |
| --- | ---: | ---: |
| Books | 7 | 6 |
| Comics | 9,605 | 4,920 |
| Manga | 39 | 2 |

The accepted scan window contained no error or fatal lines. Kavita could not
parse 1,276 unique legacy archives: 712 CBR and 564 CBZ files. The exact
container paths are stored in
`D:\Apps\PocketDS\Reading\kavita\scan-reports\production-initial-20260921.txt`.
Komga remains the authority for the legacy collection while those files are
audited; no source archive was modified.

Storyteller completed its full scan with no recurring read-only or EPUB
conversion errors. The resulting catalog contains nine logical books, four
ebook editions, two audiobook editions, and four read-aloud editions.

## Remaining acceptance

The production state, path, isolation, scanner, and Hub integration gates are
closed. The next acquisition milestone is the previously deferred Red Rising
end-to-end test through the real indexer and BookKeeprr workflow. Reader UI
implementation starts only after its interaction design is reviewed for
ebooks, manga, comics, and Kindle-like behavior.

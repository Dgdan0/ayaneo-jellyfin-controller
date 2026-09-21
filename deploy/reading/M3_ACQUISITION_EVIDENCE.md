# M3 BookKeeprr acquisition evidence

Date: 2026-09-21
Branch: `feature/reading-library`
Status: request/status/retry/cancel/import-scan integration implemented and automated; production storage gate passed

## Scope and safety

This run used only generated files below `deploy/reading/lab-acquisition` and
`deploy/reading/lab-acquisition-source`. BookKeeprr and its dedicated
qBittorrent backend did not mount production reading media, the M1 reader
fixtures, or the production movie/TV qBittorrent data. No indexer, tracker,
production library, Jellyfin server, Caddy route, router rule, Hub process, or
Pocket DS application was changed.

All host ports remained bound to `127.0.0.1`. Credentials, databases, torrent
state, and generated source files remained under ignored lab directories.

| Service | Observed version | Image digest |
| --- | --- | --- |
| BookKeeprr | 1.1.1 | `sha256:27efbf26e8b37d339ecba16b0fb12a80fe481897bc2c3f451613626a508f8b27` |
| qBittorrent | 5.2.3 | `sha256:1a4641fa759dee784708ed277ece10adbbc5810ebb8bb9fdfe1cf00031f5ab2b` |
| Internal compatibility proxy | Caddy 2.11.4 | `sha256:de23def33b17fb5d1290b0f6c2add1d70780e52341896c00a4c8a2a2fe9d355e` |

The proxy has no published host port. It forwards to the dedicated backend and
rewrites only `/api/v2/torrents/pause` to `/api/v2/torrents/stop` and
`/api/v2/torrents/resume` to `/api/v2/torrents/start`.

## Test-first fixture

`BuildSingleFileWebSeedTorrent` and its tests were written before the fixture
generator. The builder creates a deterministic BitTorrent v1 metadata file for
one generated local asset, validates its filename, URL, payload, and piece
length, emits correct SHA-1 piece hashes, and returns the info hash. It uses an
HTTP web seed and no public tracker or third-party content.

The successful EPUB bytes were staged directly in the isolated incomplete
directory to emulate a completed local transfer. A second fixture intentionally
had no available payload so controls and failure isolation could be observed.

## Results

| Check | Result | Evidence |
| --- | --- | --- |
| Compose isolation | Pass | Five pinned services; all published ports loopback-only; reader fixtures and production roots absent from both writable acquisition mounts |
| BookKeeprr first run | Pass | Isolated administrator, quality profile, storage roots, and qBittorrent connection retained in ignored lab state |
| qBittorrent connection | Pass | BookKeeprr connection test through Docker host `qbittorrent:18080` returned `200 {"ok":true}` |
| Category routing | Pass | Manual ebook grab appeared in `bookkeeprr-ebook` |
| Successful import | Pass | Download moved through queued/completed/imported and landed at `books/M3 Fixture Book/M3 Fixture Book - v01.epub` |
| Import integrity | Pass | Source and imported EPUB were both 2,625 bytes and had the same SHA-256 hash |
| Existing-media isolation | Pass | BookKeeprr and qBittorrent could see only the disposable acquisition root; a failed or cancelled fixture had no path to existing media |
| Cancel | Pass | First cancel returned 200, removed the qBittorrent transfer and BookKeeprr row; a repeated cancel also returned 200 |
| qBittorrent 5 route compatibility | Pass with shim | Direct old pause/resume routes returned 404; direct stop/start returned 200; the internal proxy made BookKeeprr pause/resume calls return 200 |
| Pause semantics | Not accepted | BookKeeprr keeps no paused download state. Its watcher maps an incomplete stopped/paused torrent to `downloading` and can classify it as stalled after five minutes |
| Native retry contract | Not supplied upstream | BookKeeprr 1.1.1 has no failed-download retry endpoint. The Hub now implements retry as an authenticated cancel followed by a re-grab of the same release, with a durable recovery ticket. |
| Personal API key controls | Not accepted | The admin personal bearer key read authenticated data, but admin download controls returned 401; those routes independently accept a browser session or mobile bearer token instead |

## Compatibility findings

qBittorrent 5 renamed the Web API operations from pause/resume to stop/start.
BookKeeprr 1.1.1 still calls the qBittorrent 4 route names in its packaged
client. The internal proxy preserves the otherwise-compatible current
qBittorrent backend without modifying BookKeeprr or downgrading the downloader.

The proxy fixes the request names only. It does not invent a pause state in
BookKeeprr or suppress its stall detector. Pause remains hidden. Retry is owned
by the Hub and does not depend on a fictional upstream paused state.

BookKeeprr's request middleware recognizes personal API keys, but its admin
download routes call a second authorization helper that validates session or
mobile tokens. This mismatch is why a valid admin personal key receives 401 on
those writes. The Hub must not store a browser session as an accidental
workaround.

## Hub and Pocket DS integration

The selected boundary is option 1 below. BookKeeprr owns metadata search,
request creation, release search, and import. The Hub exposes opaque request
keys, normalized quality profiles, single-book/whole-series choices, and
sanitized transfer status. It does not expose qBittorrent hashes, indexer GUIDs,
filesystem paths, upstream URLs, or BookKeeprr credentials.

BookKeeprr search parsing now accepts the installed 1.1.1 error object as well
as legacy array and null forms. Acquisition writes use a dedicated admin mobile
bearer obtained through the documented login/exchange flow and renew once after
401. The Pocket DS reading detail page has an explicit controller form, and
Transfers now has a Media/Books switch with BookKeeprr status rows. A scoped
token receives Cancel on active transfers and Retry/Cancel on failed transfers.
Imported rows remain read-only.

The Hub stores random public transfer IDs in `reading-transfers.json`. The
private registry binds each ID to its BookKeeprr row, release, and transport
hash. It also records the exact `importedAt` value successfully scanned by each
applicable reader. A retry ticket is written before the failed row is removed. If the new
grab fails, or the Hub restarts while the response is uncertain, the ticket
continues to appear as `retry_pending`. Before another grab the Hub reconciles
the live BookKeeprr list so a successful request with a lost response is not
submitted twice. Neither the private release ID nor qBittorrent hash appears in
the Android contract.

The background reconciler polls BookKeeprr once per minute. A newly imported
ebook triggers both Kavita and Storyteller; comics, manga, and light novels
trigger Kavita; audiobooks trigger Storyteller. Successful readers receive a
durable receipt, so a Hub restart does not repeat their scan. A failed reader
stays pending and is retried without repeating readers that succeeded. Manage
also exposes scoped manual Kavita and Storyteller scan actions. The Android app
receives only the result and never sees a canonical filesystem path.

The production Compose contract now keeps the existing `D:\Bookshelf` catalog
read-only at `/legacy`, gives BookKeeprr exclusive write access to the new
`D:\Media\Reading` managed root, and gives BookKeeprr plus its dedicated
qBittorrent only the `E:\Downloads\Reading` acquisition workspace. Contract
tests reject reader write access, any legacy mount in BookKeeprr, and any
library mount in qBittorrent. The expanded Compose model validates, all six host
roots have been prepared, and the existing 9,638 readable legacy assets remain
untouched. Consistent service-state promotion, reader paths, qBittorrent paths,
and initial production scans passed. See `PRODUCTION_CUTOVER_EVIDENCE.md`.

Automated adapter/API tests cover the installed search shape, read bearer,
mobile exchange, one-time renewal, missing admin credentials, scope isolation,
opaque candidate expiry, series payload mapping, quality-profile validation,
transport-identity redaction, retry ordering, durable failed-grab recovery,
active cancellation, unknown capabilities, imported-row protection, installed
reader scan routes, per-content reader routing, partial retry, and scan receipts
across Hub restarts.
Kotlin tests cover endpoints, wire models, action parsing, and
single-versus-series form state.

The production reading roots and dedicated reading download client are now
active. No real title, production movie/TV downloader, indexer configuration,
or legacy source media was changed. The full Red Rising run remains deferred.

## Remaining gate

The service-side acquisition slice and Hub control contract are reproducible:
local fixture acquisition, category routing, import naming, byte integrity,
idempotent upstream cancel, scoped Hub cancel, and durable Hub retry all have
automated coverage. Production path cutover is complete. The complete M3 gate
remains open only for the deferred Red Rising acceptance run. Pause is outside
the gate because the pinned BookKeeprr API cannot represent that state
accurately.

The ownership alternatives considered were:

1. keep BookKeeprr for search/request/import and let the Hub model transport
   controls and retry using the dedicated qBittorrent plus a BookKeeprr
   reconciliation contract;
2. maintain a small tested BookKeeprr compatibility fork that adds qBittorrent
   5 state handling, personal-key authorization, and retry; or
3. initially expose request, queue, diagnostics, and cancel only, then add
   pause/retry after upstream support exists.

Option 1 is implemented for request creation, status, cancel, durable retry,
uncertain-response reconciliation, and idempotent reader scans. Production
BookKeeprr destinations and Kavita/Storyteller watched folders now point at the
same canonical roots. The full Red Rising acceptance run remains open. Pause is
still intentionally absent because the pinned BookKeeprr version cannot
represent its state accurately.

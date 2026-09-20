# M3 BookKeeprr acquisition evidence

Date: 2026-09-20
Branch: `feature/reading-library`
Status: service-side qualification complete; Hub integration not started

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
| Retry contract | Not accepted | BookKeeprr 1.1.1 exposes pause, resume, cancel, queue, and manual grab, but no failed-download retry endpoint |
| Personal API key controls | Not accepted | The admin personal bearer key read authenticated data, but admin download controls returned 401; those routes independently accept a browser session or mobile bearer token instead |

## Compatibility findings

qBittorrent 5 renamed the Web API operations from pause/resume to stop/start.
BookKeeprr 1.1.1 still calls the qBittorrent 4 route names in its packaged
client. The internal proxy preserves the otherwise-compatible current
qBittorrent backend without modifying BookKeeprr or downgrading the downloader.

The proxy fixes the request names only. It does not invent a pause state in
BookKeeprr, suppress its stall detector, or provide retry. Presenting pause or
retry in the Pocket DS before those state rules are designed would claim
behavior the service does not provide.

BookKeeprr's request middleware recognizes personal API keys, but its admin
download routes call a second authorization helper that validates session or
mobile tokens. This mismatch is why a valid admin personal key receives 401 on
those writes. The Hub must not store a browser session as an accidental
workaround.

## Gate and next decision

The service-side acquisition slice is reproducible and safe enough to proceed
to adapter design: local fixture acquisition, category routing, import naming,
byte integrity, and idempotent cancel work. The complete M3 gate remains open
because retry and durable pause semantics are not supplied by the pinned
BookKeeprr API.

Before any Ayaneo Hub code is written, choose the ownership boundary together:

1. keep BookKeeprr for search/request/import and let the Hub model transport
   controls and retry using the dedicated qBittorrent plus a BookKeeprr
   reconciliation contract;
2. maintain a small tested BookKeeprr compatibility fork that adds qBittorrent
   5 state handling, personal-key authorization, and retry; or
3. initially expose request, queue, diagnostics, and cancel only, then add
   pause/retry after upstream support exists.

No Hub configuration, adapter, endpoint, or Android UI was added in this slice.

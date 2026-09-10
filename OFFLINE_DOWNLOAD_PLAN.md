# Pocket DS offline downloads

## Goal

Let the active Jellyfin user choose a movie, episode, season, or series and keep it on the Pocket DS
for reliable offline playback. A completed local copy must take priority over streaming, retain every
audio and subtitle track available in the chosen media source, update progress locally without a
network, and reconcile that progress with Jellyfin when the Hub becomes reachable again.

## Implementation status

Delivered and deployed on 2026-09-10. The Hub contract, durable grants, app selection flow,
persistent queue, foreground transfer service, grouped manager, local catalog, local-first Media3
playback and progress outbox are implemented. The production probe mapped 88 episodes, preserved two
audio and three subtitle tracks, returned an exact 1,024-byte HTTP 206 range and renewed the grant.
A Pocket DS run downloaded and validated a 545 MB episode, displayed live speed/ETA, played it from
local storage without a playback-network request, restored focus and deleted it cleanly.

The Offline landing view is the persistent local catalog: one alphabetized poster per downloaded
movie or series, without Jellyfin library folders. Opening a downloaded series shows only seasons
and episodes actually stored on the device. The Download manager is the secondary tab. Repository
broadcasts update it when state changes; the old 750 ms full-screen rebuild loop has been removed.

New downloads can target any currently mounted app-private Android storage volume. Settings lists
internal storage and removable SD cards with free space. Changing the location applies to new jobs;
existing downloads keep their absolute paths and are neither copied nor orphaned. If a selected SD
card is removed, new jobs wait for that location instead of silently filling internal storage.

Pocket DS firmware does not expose an AC-3/E-AC-3 platform decoder. The app therefore includes the
official Media3 1.4.1 FFmpeg audio extension as a minimal arm64 LGPL build with only AC-3/E-AC-3
enabled. Original MKV downloads retain all streams while these tracks decode to PCM locally. Exact
sources, license, configuration, and rebuild steps are in `app/third_party/media3-ffmpeg/README.md`.

The Android transfer engine uses a sequential original-file worker with SQLite and exact-length
validation rather than Media3 `DownloadManager`/`SimpleCache`. A complete MKV/MP4 is the intended
artifact here, so direct files make restart ranges, sidecars, storage accounting, corruption checks
and deletion explicit while Media3 remains the playback engine.

## Product shape

### Item actions

- Movie and episode details get **Download** or **Remove download**.
- Season details get **Download season**, **Download unwatched**, **Next to watch**, **Next X
  episodes**, and **Choose episodes**.
- Series details get **Download all**, **Download unwatched**, **Next to watch**, **Next X
  episodes**, and **Choose episodes**.
- **Next to watch** uses the selected user's Jellyfin play target. It includes a partly watched
  episode before advancing to the next episode.
- **Next X episodes** opens a numeric stepper, starts at that play target, and follows season then
  episode order. Missing and unaired episodes are skipped.
- Already completed downloads remain visibly selected and do not create duplicate queue entries.

### Episode selection screen

The manual picker reuses the series/season visual language already in Library:

- one horizontal row per season, ordered Specials, Season 1, Season 2, and so on;
- the season title and season poster at the start of its row;
- one landscape card per available episode, showing number, title, still, runtime, watched progress,
  estimated size, and local/queued state;
- A or tap toggles an episode; Y toggles the focused season; Start toggles all available episodes;
- X confirms; B returns without modifying the queue;
- a fixed top-right counter shows **N selected · estimated size** and has a touch-accessible
  **Download** action;
- confirmation lists the item count, app-private storage destination, estimated total, current free
  space, and the Original media source before work is enqueued.

Selections are Jellyfin item IDs. TMDB metadata is decorative and can never block an offline copy.

### Offline section

Add **Offline** as a primary side-rail destination with a down-arrow-into-device icon. It contains
two local tabs:

1. **Downloaded** — the default local catalog, grouped into movie/series posters and independent of
   Jellyfin libraries.
2. **Download manager** — active, waiting, paused, and failed jobs.

The existing **Transfers** section continues to describe Sonarr/Radarr/qBittorrent activity on the
media PC. Calling the new area **Offline** keeps those two different jobs understandable.

The eight rail entries use compact 40 dp rows and a 42 dp header so all remain visible above the
Pocket DS hint bar in both collapsed and expanded modes.

### Download manager

- One episode transfers at a time by default, ordered by season number then episode number.
- Movies retain explicit queue order; newly confirmed batches append as a group.
- Each series batch shows aggregate bytes, episode count, current episode, speed, remaining time,
  and a progress bar. Expanding it shows each episode's state and progress.
- Pause/resume/cancel work for a batch or episode. Cancel offers **Keep completed episodes** and
  **Remove this batch's completed episodes**.
- Failed downloads keep completed content and expose Retry. HTTP range resume continues from the
  last verified byte.
- Queue state survives app/process/device restarts. A foreground service and Android notification
  keep an active transfer alive when the app is backgrounded.
- Settings provide Wi-Fi-only, charging-only, automatic retry count, and minimum free space. The
  delivered release stores Original files in app-private external storage. Selectable storage,
  compatible offline encodes, and a per-batch metered override remain later options.

## Media and storage contract

### What is downloaded

The first release downloads one complete Jellyfin media source, not every alternative encode:

- **Original** stores the original MKV/MP4 and therefore retains all embedded audio and subtitle
  streams in that source.
- Every external subtitle belonging to that source is downloaded as a sidecar with language,
  forced/SDH/default flags and codec metadata.
- The item, series and season metadata plus poster, backdrop, episode still and resume state are
  stored for offline browsing.
- The confirmation screen exposes the source/version and size. A later **Compatible** quality can
  ask Jellyfin to create a Pocket-DS encode, but it must not silently discard alternate audio or
  subtitles.

Media3 playback of the downloaded MKV was verified on the Pocket DS. Broader release testing is still
needed for embedded/external SRT, WebVTT, ASS/SSA, PGS and DVB combinations. If that testing shows a
real Media3 gap, add a measured mpv/libass fallback; pre-burning one full video per subtitle track is
rejected because of its storage cost.

### On-device layout

Use app-scoped external storage so normal media/gallery apps cannot index the files and uninstalling
the app removes them cleanly:

```text
Android/data/com.pocketds.hub/files/offline/
  media/                        complete original files, no automatic eviction
  subtitles/                    external subtitle sidecars keyed by download and track
  artwork/                      still/poster/backdrop files keyed by download

internal app database:
  offline.db                    catalog, queue, user state, speed and sync outbox
```

Use a foreground serial transfer service and a dedicated long-lived OkHttp client. A small SQLite
catalog maps opaque grants and local filenames to Jellyfin hierarchy. Intentional downloads are
never evicted automatically; raw Jellyfin paths, API keys and upstream URLs are never persisted.

Each asset records expected content length, ETag/Last-Modified when available, received bytes and a
completion marker. Playback treats only a completed, validated asset as offline. If a ranged resume
finds that the source changed, it restarts that episode and leaves the rest of the batch intact.

Before enqueueing, reserve the estimated batch size plus a configurable safety margin. Stop starting
new episodes when free space falls below that margin; do not delete older downloads automatically.

## Hub contract

Add an explicit `download` token scope and these authenticated endpoints:

| Endpoint | Purpose |
|---|---|
| `GET /v1/offline/series/{seriesId}/selection` | All available seasons/episodes, watch state, file/source availability, track metadata and size estimates for the selected Jellyfin user. |
| `POST /v1/offline/prepare` | Validate a batch of item IDs and return durable per-item download manifests and grants. |
| `GET /v1/offline/grants/{grantId}/media` | Range-capable proxy for the approved original source. |
| `GET /v1/offline/grants/{grantId}/subtitles/{trackId}` | Proxy one approved external subtitle sidecar. |
| `POST /v1/offline/grants/{grantId}/renew` | Renew an expired grant after revalidating source identity and ownership. |
| `POST /v1/offline/progress/sync` | Reconcile queued offline positions/completions with the selected Jellyfin user. |

Download grants are bound to bearer-token label, Jellyfin user, item and media-source ID. They allow
only the source and subtitle resources returned during preparation, support cancellation and Range,
and never expose filesystem paths, Jellyfin credentials or upstream URLs. Unlike playback sessions,
grants must survive a Hub/FireDaemon restart and remain renewable during a long trip. Persist the
minimal registry under `C:\ProgramData\AyaneoHub` with restrictive ACLs and idempotent cleanup.

The prepare request is idempotent by client batch/item key. It rejects missing/deleted items, virtual
episodes, sources without a known length, insufficient scope, another user's grant and IDs outside
the selected series. The response describes the exact source and every embedded/external track so
the app can prove what will be available offline.

## Offline playback and progress

Before preparing a network stream, playback asks `OfflineRepository` for the active item ID:

1. If a complete local asset and manifest exist, construct the Media3 item from the offline cache,
   attach local external subtitles, and show a small **Offline** indicator.
2. If the local asset is corrupt, mark it for repair and use Hub playback when reachable.
3. Partial downloads are not selected for playback in the first release.
4. Playback never switches source midway through a session; a stream that began online remains that
   session, while the next open can choose the completed local asset.

All audio/subtitle/version sheets use the stored manifest while offline. Subtitle timing remains the
same local dynamic control delivered in the player and therefore needs no Hub connection.

Progress is written locally on the same 10-second cadence and immediately on pause, seek, exit and
completion. The outbox is keyed by Jellyfin user and item and collapses repeated progress to the
newest state. On app start, network recovery, profile selection and manual Sync:

- compare the offline event time with Jellyfin's latest user-data time;
- keep a newer server playback made on another client;
- otherwise send the latest local position/completion through the Hub and let Jellyfin apply its
  watched threshold;
- mark an outbox entry complete only after the Hub acknowledges it;
- make retries idempotent so reconnect loops cannot increment play counts repeatedly.

Media assets can be shared between local profiles, but watch position, played state, audio/subtitle
preference and sync outbox remain separate per Jellyfin user.

## Visual language

Item actions use a symbol plus a short label so meaning remains obvious without relying on text
alone:

| Action | Symbol/state |
|---|---|
| Play / Resume / Start series | play triangle |
| Start over | counter-clockwise restart arrow |
| Playback options | three sliders |
| Watched | eye |
| Unwatched | eye with slash |
| Favourite | filled star |
| Not favourite | outlined star |
| Download | down arrow into a tray/device |
| Queued | clock plus download arrow |
| Downloading | animated/progress-ring download arrow |
| Downloaded / Offline | check mark on a device |
| Remove download | device/tray with a small minus |

The label stays for detail-page actions and accessibility. Dense episode cards use the symbol plus a
state badge. Focus changes the outline/fill around the whole control; it does not change the symbol's
meaning.

## Delivery phases

1. **Contract probe:** verify Jellyfin original-download Range behavior, source lengths/validators,
   all representative audio/subtitle formats, and available storage on the Pocket DS.
2. **Durable Hub gateway:** scopes, batch preparation, persisted grants, media/subtitle Range proxy,
   renewal, ownership and progress-sync contract.
3. **On-device foundation:** offline database, app-scoped cache, DownloadService, constraints,
   ordered queue, restart/range resume, free-space checks and notifications.
4. **Selection UI:** episode picker and movie/episode/season/series actions with size estimates and
   batch confirmation.
5. **Offline section:** grouped queue manager, downloaded catalog, delete/retry controls, series and
   season navigation without the Hub.
6. **Playback routing:** local-first resolver, all local tracks, dynamic subtitle timing, corruption
   fallback and focus restoration.
7. **Progress reconciliation:** per-user local state, durable outbox, conflict rules, automatic and
   manual synchronization.
8. **Hardening:** interrupted power/network, Hub restart, app kill/reboot, low storage, changed
   source, deleted Jellyfin item, user switch, 4+ GB media, every subtitle/audio format, and a full
   phone-hotspot trip simulation.

## Release gates

- A selected season/series produces exactly the visible available episodes in season/episode order.
- Pausing, restarting the app and rebooting the Pocket DS resume a large download without corruption.
- Completed local playback makes zero media requests and continues through network loss.
- Every advertised audio and subtitle track remains selectable offline.
- Offline progress updates the correct Jellyfin user once and never overwrites newer server progress.
- Deleting a batch cannot remove Jellyfin/server media or another local user's shared asset.
- Storage estimates, free-space failures and partial/failed states are clear before and during work.

Defaults: **Offline** side section; one concurrent episode; original source; all embedded audio and
subtitles plus all external subtitle sidecars; season/episode queue order; app-scoped external
storage; no automatic eviction; Wi-Fi-only enabled; local media preferred whenever a complete copy
exists.

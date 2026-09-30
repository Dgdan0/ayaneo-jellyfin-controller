# Pocket DS Hub functionality audit — 2026-09-20

## Evidence and current result

This audit covers the Android and Hub source, their automated tests, the live Hub configuration,
and the active Windows/Docker deployment. It does not claim a fresh Pocket DS hardware pass: no ADB
device is connected for this audit.

| Check | Result |
| --- | --- |
| Android unit tests and debug APK build | Passed after the Offline, player and Manage additions |
| Hub Go tests | Passed (`go test ./...`) |
| Hub static analysis | Passed (`go vet ./...`) |
| Production Hub config check | Valid; eight services configured |
| Production Hub health | `200 ok` on loopback |
| Current-source Hub doctor | All configured services answered |

The latest Offline/player/Manage work is deliberately **not released yet**. It has focused
test-first coverage and successful Android/Hub builds, but needs a Pocket DS hardware pass before
its feature-specific commit, APK version bump, and release.

## Product coverage

| Area | Delivered now | Follow-up that is still worthwhile |
| --- | --- | --- |
| Home | Selected-user Continue Watching, Next Up, recently added titles, favourites, progress refresh, and episode-specific cards | Device regression check after every playback/progress change |
| Discover and acquisition | Jellyseerr search/discovery, trailers, season/episode release selection, request options, and Sonarr/Radarr/qBittorrent transfer state/control | Keep CleanUparr read-only until its activity model and deletion safety are explicitly designed |
| Library | Adaptive grids, daily fallback library art, sorting, favourites, user watch state, details, Specials, horizontal seasons and episodes, focus restoration | Combine sort key and direction into one simpler controller sheet; optionally show expanded episode descriptions |
| Player | Media3 with hub-negotiated direct play/remux/transcode, quality/source/audio/subtitle selection, dynamic subtitle offset, appearance presets, timeline previews, gestures, chapters, segment skip prompts, speed, aspect, touch lock, next episode, PiP, and Jellyfin progress reporting | Device-test the new player controls; later add automatic PiP and richer notification actions |
| Offline | Persistent download queue, series/season/episode selection, resume ranges, validation, local-first playback, sidecar subtitles, storage selection, deferred Jellyfin progress sync, and a local rich series snapshot | Device-test online-target fallback and the fully-offline series header |
| Services and notifications | Health, dashboards, official icons, service notifications and unread state for the current stack; CleanUparr status support is built | Run the administrator deployment script to enable the card; add destructive cleanup only with a review screen |
| Settings | Theme, notifications, connection, player and Offline preferences exist | Consolidate the scattered preferences into a clearer central Settings structure as more player controls are added |

## Offline series detail: current work

The new local implementation is intentionally shaped like the online series detail:

- a selected user's current server target is resolved when the Hub is reachable;
- if that episode is stored locally, Resume/Next plays it locally;
- if it is not stored, the screen names the episode and says that it is not downloaded rather than
  pretending it can play;
- only downloaded seasons and episodes are shown below;
- without a Hub, it falls back to the best local resume/next target;
- each newly prepared download carries the selected series metadata, while the app caches the
  most recent online series metadata per Jellyfin user for existing downloads.

The mapper has focused tests for local resume, an unavailable server target, local fallback,
remembered metadata precedence, and the rule that only locally available Specials/seasons appear.
The remaining step is hardware verification.

## Live deployment audit

The current Hub doctor reached every configured upstream successfully:

| Service | Version reported by Hub | Current management |
| --- | --- | --- |
| Jellyfin | 10.11.8 | Native Windows process |
| Jellyseerr | 2.7.3 | Docker Compose (`C:\jellyseerr\docker-compose.yml`) |
| Prowlarr | 2.6.5.5623 | Native Windows service |
| Radarr | 6.4.4.10685 | Native Windows service |
| Sonarr | 4.0.20.3014 | Native Windows process |
| Readarr | 0.4.18.2805 | Native Windows service |
| Bazarr | 1.6.1 | Native Windows service |
| qBittorrent | 5.0.4 | Native Windows process |

FireDaemon currently owns Ayaneo Hub, Caddy, and Komga. Docker currently runs Jellyseerr,
CleanUparr, and FlareSolverr. CleanUparr and FlareSolverr have `unless-stopped` restart policies
but are not labelled as Compose projects; Jellyseerr is Compose-managed. Docker is working, but
its Windows service is manual/stopped, so reboot persistence should be confirmed before it becomes
the primary home for more services.

CleanUparr now has a health-only Hub probe at its unauthenticated loopback `/health` endpoint and
an official logo in Manage. Its dedicated deployment script is
`hub/deploy/windows/enable-cleanuparr-health.ps1`. It preserves a config backup and refuses to
change a live FireDaemon service unless invoked from an Administrator PowerShell session. The
source and script checks pass; the live service remains on its prior valid configuration until
that final administrator step is run.

## Recommended stack direction

Use a deliberate hybrid, then reduce it to two understandable layers:

1. Keep **FireDaemon** for Ayaneo Hub and Caddy. Those are host-local Windows services whose
   failure behaviour, logs, and restart controls already live there.
2. Keep **Jellyfin and qBittorrent native for now**. They are working, have existing media/download
   paths and, in qBittorrent's case, peer-facing networking. Moving them first creates the most
   disruption for the least benefit.
3. Move the automation stack to **one Docker Compose project**: Prowlarr, Sonarr, Radarr, Bazarr,
   Readarr, Jellyseerr, FlareSolverr, and CleanUparr. Compose makes mounts, ports, restart policies,
   upgrades, and service dependencies reviewable in one file. Pin released image versions and keep
   configuration/database folders as host bind mounts.
4. Keep **Komga in FireDaemon** until the reading feature is stable. It already works there and is
   a good fit for comics, manga, EPUB, and PDFs. Moving it to Docker can be a later independent
   maintenance change.

Do not migrate all services at once. First prepare a Compose project without starting it, map every
media and download path identically across all *Arr containers, back up each configuration database,
then migrate one low-risk service at a time: Prowlarr, Readarr, Bazarr, Sonarr, Radarr, then the
existing Jellyseerr/FlareSolverr/CleanUparr containers. Verify the Hub, a real import, and one
manual request after every cutover. Stop the old service only after its replacement is healthy.

## Reading roadmap

Komga is the right first reader backend for the requested comics, manga, and e-books. It supports
CBZ, CBR, EPUB, and PDF, and it is already running on this PC. Readarr should remain the acquisition
manager; it is not the reading experience.

Recommended phases:

1. Add Komga to Hub as a read-only managed service: health, dashboard opening, and a safe library
   summary. No library edits or deletion controls.
2. Add a **Reading** destination in the Pocket DS app: Komga libraries, series/books, covers,
   page/issue state, and an option to open the existing Komga reader while native reading is built.
3. Build a native CBZ/CBR image reader and EPUB/PDF reader with controller page turning, progress,
   brightness, fit/zoom, and offline files. Treat manga right-to-left direction as a per-series
   preference.
4. Add Audiobookshelf only if audiobooks or podcasts become a goal. It is a better separate backend
   for that category than stretching the comics/e-book reader.

Jellyfin can catalogue book media, but the Pocket DS app currently exposes movie/TV navigation and
video playback only. A reader feature should therefore use Komga deliberately instead of presenting
Jellyfin book files through a video-first interface.

## Recommended next order

1. Hardware-test the Offline series screen and new player controls, then install/release the APK.
2. Run the CleanUparr administrator deployment script and verify its Manage card.
3. Produce a Compose migration inventory and a dry-run `media-stack` definition; do not stop or
   move a live service yet.
4. Start the Komga/Reading foundation.

## References

- Docker Compose production guidance: <https://docs.docker.com/compose/how-tos/production/>
- Komga formats and Docker deployment: <https://komga.org/docs/introduction/> and <https://komga.org/docs/installation/docker/>
- Jellyfin book-library formats: <https://jellyfin.org/docs/general/server/media/books/>
- Audiobookshelf Docker deployment: <https://audiobookshelf.org/docs/documentation/install/docker/>

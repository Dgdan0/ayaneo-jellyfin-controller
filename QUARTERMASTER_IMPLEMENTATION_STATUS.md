# Quartermaster feature checkpoint — 26 September 2026

## Active source

Feature worktree: `C:/Users/Dgdan/.codex/worktrees/quartermaster-features/Ayaneo Jellyfin Controler`, branch `codex/quartermaster-features`.

`04b7c1f` snapshots the newer reading/UI checkout used by the installed app. The original project root and `.worktrees/reading-library` were not changed by this feature work. Do not install a build from the older original root.

## Installed and verified

- Upcoming: Discover > Upcoming; real posters and titles grouped by every date; episode batches; right-side details; contiguous Monday–Sunday weeks; local episode times and civil movie dates; title links; partial-source errors. Commits `b0f70a5`, `2590792`.
- Download diagnostics: Transfers > Needs attention; explanation, source evidence and next step; optional scoped resume. Failed title pipelines link to the attention list. Commits `bca0d1a`, `3d8b2dd`.
- Discover cached-pagination crash fixed and device regression covered. Commits `77956a6`, `727973d`.

At the initial checkpoint, production Hub was `0.3.1-monitor` and the daily Android app was v0.3.1/code16. The FireDaemon `AyaneoHub` service was running on port 8791 after that combined deployment; its installed binary hash matched the tested ready binary (`E6C9E73286589A8693E2951ADE090532185D474F7FEDDF60BD0A46A62C68C981`). The AYANEO daily app was updated in place with `adb install -r` on `100.97.20.86:42603`, preserving app data. Current versions and the later subtitle deployment are recorded below.

## Unified online/offline subtitle update — 26 September

The daily AYANEO app is now v0.3.5/code20. An offline movie or episode opens the same Subtitles screen as its online library item through the Y controller action or touch UI. The screen shows local tracks alongside Bazarr's installed tracks, download history, match score and the user's local quality rating. Selecting a new provider result writes through Bazarr, asks Jellyfin to refresh that one title and queues an update for any completed offline copy. The explicit “Prepare online subtitles and refresh this AYANEO” action also works while an earlier subtitle update is pending. If the Hub is unreachable, saved tracks remain visible and the screen offers a retry.

The app persists a subtitle-only retry queue per profile. Its worker renews an offline grant, checks the item, media source and size against the existing video, fetches bounded sidecars, swaps them with recoverable backups and updates the local playback manifest. It never requests the video media URL during this update. A separate worker allows this to proceed while video downloads are queued.

Live Dune: Part Two exposed a real cross-service naming mismatch: Bazarr had English and Hebrew `.srt` files beside the movie, but their basenames did not match the video stem, so Jellyfin initially indexed zero subtitle streams. The Hub now verifies that Bazarr and Jellyfin refer to the same existing video, validates same-folder sidecars and language codes, writes bounded `pocketds` copies under the movie stem without changing Bazarr originals, then refreshes only that Jellyfin item. The download endpoint applies the same preparation after a successful Bazarr write. A failure after that write is returned as a warning so the app does not submit the provider selection again.

After the live refresh, Jellyfin indexed two subtitle streams. The AYANEO fetched `/subtitles/0` and `/subtitles/1` through the existing offline grant and its Dune screen showed **2 local tracks: English SDH and Hebrew SDH**. The offline player opened Dune and listed both tracks as selectable. The final app suppresses the internal `pocketds` filename marker in the player label. The prior video file remained in place and the device's subtitle sync made no media download request. Screenshots: gitignored `.local-backups/dune-after-subtitle-sync.png` and `.local-backups/dune-player-final.png` (final player UI). Dune was closed after the check and its resume point returned to about 2:26, near its original 2:23.

The FireDaemon service now runs Hub `0.3.3-offline-subs`; its installed binary hash matches `.local-backups/hub-offline-subtitle-naming-ready.exe` (`CDD13783077BBCB513922DBE0592DD2FDB8B87BDE43272345790F3A4FEFB2FC3`). `.local-backups/deploy-subtitle-naming.ps1` backed up the prior binary, checked configuration, replaced the service binary and verified service status, liveness and hash. The user ran this script in Administrator PowerShell after automatic approval review blocked the assistant's UAC launch. The daily APK was installed with `adb install -r`; no app data was cleared. The immediately previous v0.3.2 APK is saved as `.local-backups/daily-before-offline-subtitle-naming.apk`.

Validation: `go test ./...` and `go vet ./...` passed, including path, alias update, ticket and refresh tests. `gradlew.bat testDebugUnitTest assembleDebug assembleUitest assembleUitestAndroidTest` passed; the smaller final UI changes passed `testDebugUnitTest assembleDebug` and the uitest artifacts were rebuilt. `OfflineSubtitleSyncDeviceTest` passed on the AYANEO: it exercised actual sidecar transfer and verified no media URL request or video mutation. A live provider subtitle replacement was not performed because it would replace the user's chosen subtitle; the two already-installed Bazarr files provided the end-to-end refresh check.

Live calendar posters, week navigation and title open/back passed. Production transfers are currently empty, so failed-transfer UI is tested with deterministic fixtures in the isolated `.uitest` app. No daily app data was cleared.

## Installed and live-checked on 26 September

### Bandwidth and queue priority (`007bc6c`)

Transfers > Bandwidth reads normal/alternative limits, current mode, scheduler and queueing state. Explicit mode selection, KiB/s editing with byte-preserving conversion, scope enforcement and readback verification. Supported priority actions appear for queued torrents; disabled queueing is not automatically enabled.

Installed qBittorrent is 5.0.4. Live candidate test changed only inactive alternative limits, verified them, and restored the exact original values (10 KiB/s each). Normal mode, unlimited normal limits, scheduler off and queueing off remain unchanged. Mode switching and priority rejection/adapter behavior are fixture-tested; no live priority change was possible with queueing disabled.

### Subtitles

Movie/episode > More actions > Subtitles. Installed tracks and history, recorded Bazarr match scores, explicit provider search, language filtering, match/mismatch evidence and reviewed download selection. Personal Good / Out of sync / Wrong translation ratings and up to 100 provenance records per title are saved on this device, partitioned by Hub URL and profile. Cached history never asserts that a subtitle remains installed. Match score is separate from translation quality.

Hub resolves the selected Jellyfin item through TMDB/TVDB to an actual downloaded Arr/Bazarr file. Search results are bounded and retained only in short-lived Hub tickets bound to token, profile, item and file identity. The app never supplies serialized provider objects. Download requires control scope, revalidates file identity, consumes the ticket before writing and does not automatically replay uncertain results.

Live reads at that checkpoint: 100 English/Hebrew candidates for 10 Things I Hate About You; The Avengers installed Hebrew subtitle correctly joined its recorded 91.11% match score. No real subtitle was replaced for testing. Movie/episode wire contracts, forged/expired/profile-mismatched/duplicate tickets and changed paths are tested. Jellyfin item refresh and offline sidecar sync were subsequently verified with Dune, as recorded above; a provider replacement remains untested on live media.

### Server monitoring

Manage > Server monitor shows a short Windows CPU sample, physical memory use, uptime and fixed-drive space available to the Hub account. Docker CLI is invoked read-only with fixed arguments and bounded time/output; only name, image, state and status are returned. Current Jellyfin playback is filtered to the selected profile. Unknown/failed sources remain explicit, and a refresh error labels old values stale. The UI refreshes every 15 seconds and preserves focused cards.

Live production returned five fixed volumes, 18 containers and no active playback for the selected profile. At the latest check C: had about 3.4 GiB free (0.7%) and E: 70.3 GiB (2%). Nothing was deleted or restarted. Host CPU math, malformed Docker output and cross-profile session filtering have regression coverage. Device monitor test passed for missing data, low disk highlighting and focus restoration.

## Validation

- `go test ./...` and `go vet ./...` passed after subtitle implementation; additional episode contract test passed afterward.
- `gradlew.bat testDebugUnitTest assembleDebug assembleUitest assembleUitestAndroidTest` passed; 548 JVM tests.
- Six isolated device scenarios passed: calendar, diagnostics, synchronous Discover pagination, bandwidth unit/mode preservation, subtitle rating/history retention and explicit candidate selection, and server-monitor missing data/low-space/focus restoration.
- Subtitle screenshot capture was corrected to wait for a rendered frame; the focused test passed again and the screenshot was visually inspected.
- Screenshots in gitignored `.local-backups/`: `upcoming-next.png` (real calendar), `diagnostics-fixture.png`, `bandwidth-fixture.png`, `subtitles-fixture.png`, `monitor-fixture.png` (synthetic UI fixtures).

## Deployment record

The user authorized reopening Windows UAC. `.local-backups/deploy-monitor.ps1` backed up the prior binary, replaced the FireDaemon service binary, verified liveness and hash, and reported success. Ready Hub binary: `.local-backups/hub-monitor-ready.exe`. The daily APK at `app/build/outputs/apk/debug/app-debug.apk` installed successfully on the AYANEO. Previous device APK is backed up at `.local-backups/daily-before-monitor.apk`.

The actual daily app showed `v0.3.1-monitor` and 12 services running; the live Server monitor displayed Windows/Docker data. Transfers > Bandwidth displayed the production normal mode, unlimited normal rates, 10 KiB/s alternative rates and queueing off. Library > The Avengers > More actions > Subtitles displayed three installed tracks, including the Hebrew `ktuvit` track with its recorded 91.11% match score. Home, Library search and Offline also loaded. No subtitle was downloaded or replaced during this read-only UI verification. Screenshots: `.local-backups/daily-monitor-live.png`, `daily-bandwidth-live.png`, `daily-subtitles-live.png`.

Candidate Hub processes used: bandwidth PID 153804 on 8793; subtitles PID 145848 on 8794; monitor PID 151516 on 8795. All three candidate processes were stopped after executable-path verification at the final checkpoint. Production remains running on 8791. Candidate configuration and secrets, backups and deployment scripts are inside `.local-backups/`; never commit or share that directory.

Automatic approval review previously rejected a combined command that would switch the test app to the candidate Hub through ADB reverse. That route was not retried. Direct local API validation, isolated device fixtures and real daily-app navigation all succeeded.

## Next slices

1. Verify a user-selected real subtitle acquisition and player selection of a newly synced offline track.
2. Narrowly configured restarts with review, audit and health verification. Windows services observed include AyaneoHub, Bazarr, Prowlarr and Radarr; Docker is installed at its standard Program Files path. Actual management mechanisms and permissions must be verified for every supported target; unmanaged processes must not advertise restart.
3. Actionable alerts with persistent deduplication and exact destinations, followed by background delivery and quiet hours.
4. Home shortcuts and configurable shelves.
5. Verified local/remote routing for the same Hub identity, preserving sessions and authentication/cache isolation.

These remaining slices are approved backlog, not completed features. No family mode, new reading scope or multi-Arr instances have been added.

Known first-slice limits: Upcoming includes dated monitored calendar releases, with no undated inventory; Arr file availability is labeled Downloaded, not asserted Jellyfin availability. A failed title pipeline opens the attention list rather than an exact selected transfer. Subtitle feedback is local to the device; live provider replacement and player track selection remain unverified.

Final verification: the two affected device tests (subtitle history/selection and monitor focus/missing data) passed again against the final APK. Production configuration validation passed for the ready Hub binary. Commits: `7e55a8b` subtitles, `2b46036` history provenance, `5f5409b` monitoring. The daily app was updated without clearing its data. Actual production UI and read-only API checks passed after deployment.

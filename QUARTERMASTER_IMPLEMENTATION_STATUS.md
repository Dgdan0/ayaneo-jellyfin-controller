# Quartermaster feature checkpoint — 25 September 2026

## Active source

Feature worktree: `C:/Users/Dgdan/.codex/worktrees/quartermaster-features/Ayaneo Jellyfin Controler`, branch `codex/quartermaster-features`.

`04b7c1f` snapshots the newer reading/UI checkout used by the installed app. The original project root and `.worktrees/reading-library` were not changed by this feature work. Do not install a build from the older original root.

## Installed and verified

- Upcoming: Discover > Upcoming; real posters and titles grouped by every date; episode batches; right-side details; contiguous Monday–Sunday weeks; local episode times and civil movie dates; title links; partial-source errors. Commits `b0f70a5`, `2590792`.
- Download diagnostics: Transfers > Needs attention; explanation, source evidence and next step; optional scoped resume. Failed title pipelines link to the attention list. Commits `bca0d1a`, `3d8b2dd`.
- Discover cached-pagination crash fixed and device regression covered. Commits `77956a6`, `727973d`.

Production Hub is `0.3.1-diagnostics`; daily Android app v0.3.1/code16 includes the above. Diagnostics deployment passed service health and binary hash verification after explicit authorization to reopen UAC. AYANEO ADB: `100.97.20.86:36813`.

Live calendar posters, week navigation and title open/back passed. Production transfers are currently empty, so failed-transfer UI is tested with deterministic fixtures in the isolated `.uitest` app. No daily app data was cleared.

## Built and tested, deployment pending

### Bandwidth and queue priority (`007bc6c`)

Transfers > Bandwidth reads normal/alternative limits, current mode, scheduler and queueing state. Explicit mode selection, KiB/s editing with byte-preserving conversion, scope enforcement and readback verification. Supported priority actions appear for queued torrents; disabled queueing is not automatically enabled.

Installed qBittorrent is 5.0.4. Live candidate test changed only inactive alternative limits, verified them, and restored the exact original values (10 KiB/s each). Normal mode, unlimited normal limits, scheduler off and queueing off remain unchanged. Mode switching and priority rejection/adapter behavior are fixture-tested; no live priority change was possible with queueing disabled.

### Subtitles

Movie/episode > More actions > Subtitles. Installed tracks and history, recorded Bazarr match scores, explicit provider search, language filtering, match/mismatch evidence and reviewed download selection. Personal Good / Out of sync / Wrong translation ratings and up to 100 provenance records per title are saved on this device, partitioned by Hub URL and profile. Cached history never asserts that a subtitle remains installed. Match score is separate from translation quality.

Hub resolves the selected Jellyfin item through TMDB/TVDB to an actual downloaded Arr/Bazarr file. Search results are bounded and retained only in short-lived Hub tickets bound to token, profile, item and file identity. The app never supplies serialized provider objects. Download requires control scope, revalidates file identity, consumes the ticket before writing and does not automatically replay uncertain results.

Live reads: 100 English/Hebrew candidates for 10 Things I Hate About You; The Avengers installed Hebrew subtitle correctly joins its recorded 91.11% match score. No real subtitle was replaced for testing. Movie/episode wire contracts, forged/expired/profile-mismatched/duplicate tickets and changed paths are tested. Real acquisition and Jellyfin/player refresh still need a selected title/language; they are not claimed as live-verified.

### Server monitoring

Manage > Server monitor shows a short Windows CPU sample, physical memory use, uptime and fixed-drive space available to the Hub account. Docker CLI is invoked read-only with fixed arguments and bounded time/output; only name, image, state and status are returned. Current Jellyfin playback is filtered to the selected profile. Unknown/failed sources remain explicit, and a refresh error labels old values stale. The UI refreshes every 15 seconds and preserves focused cards.

Live candidate returned 95.7 GiB total RAM (matching Windows CIM), five fixed volumes, 18 containers and no active playback for the selected profile. At the check C: had about 5.5 GiB free (1.2%) and E: 74.2 GiB (2%). Nothing was deleted or restarted. Host CPU math, malformed Docker output and cross-profile session filtering have regression coverage. Device monitor test passed for missing data, low disk highlighting and focus restoration.

## Validation

- `go test ./...` and `go vet ./...` passed after subtitle implementation; additional episode contract test passed afterward.
- `gradlew.bat testDebugUnitTest assembleDebug assembleUitest assembleUitestAndroidTest` passed; 548 JVM tests.
- Six isolated device scenarios passed: calendar, diagnostics, synchronous Discover pagination, bandwidth unit/mode preservation, subtitle rating/history retention and explicit candidate selection, and server-monitor missing data/low-space/focus restoration.
- Subtitle screenshot capture was corrected to wait for a rendered frame; the focused test passed again and the screenshot was visually inspected.
- Screenshots in gitignored `.local-backups/`: `upcoming-next.png` (real calendar), `diagnostics-fixture.png`, `bandwidth-fixture.png`, `subtitles-fixture.png`, `monitor-fixture.png` (synthetic UI fixtures).

## Deployment state and pending approval

Windows canceled the bandwidth UAC prompt. It was not silently retried. A new asynchronous question requests permission to reopen UAC for reopening UAC. While waiting, the read-only monitor was also completed. Latest combined script `.local-backups/deploy-monitor.ps1` validates configuration, backs up the current binary, replaces the service binary, verifies health/hash and rolls back on failure.

Latest ready Hub binary: `.local-backups/hub-monitor-ready.exe`, version `0.3.1-monitor`, with production config validation passed. Pending daily APK: `app/build/outputs/apk/debug/app-debug.apk`. Install only with `adb install -r`, after the matching Hub deployment. The isolated test app already includes the new features.

Candidate Hub processes used: bandwidth PID 153804 on 8793; subtitles PID 145848 on 8794; monitor PID 151516 on 8795. Stop only after verifying each executable path. Production runs on 8791. Candidate configuration and secrets, backups and deployment scripts are inside `.local-backups/`; never commit or share that directory.

Automatic approval review rejected a combined command that would switch the test app to the candidate Hub through ADB reverse. That route was not retried. Direct local API validation and isolated device fixtures succeeded. A real daily-app navigation check remains after approved deployment.

## Next slices

1. Finish the pending combined deployment and daily-app checks. Verify a user-selected real subtitle acquisition and subsequent player track refresh.
2. Narrowly configured restarts with review, audit and health verification. Windows services observed include AyaneoHub, Bazarr, Prowlarr and Radarr; Docker is installed at its standard Program Files path. Actual management mechanisms and permissions must be verified for every supported target; unmanaged processes must not advertise restart.
3. Actionable alerts with persistent deduplication and exact destinations, followed by background delivery and quiet hours.
4. Home shortcuts and configurable shelves.
5. Verified local/remote routing for the same Hub identity, preserving sessions and authentication/cache isolation.

These remaining slices are approved backlog, not completed features. No family mode, new reading scope or multi-Arr instances have been added.

Known first-slice limits: Upcoming includes dated monitored calendar releases, with no undated inventory; Arr file availability is labeled Downloaded, not asserted Jellyfin availability. A failed title pipeline opens the attention list rather than an exact selected transfer. Subtitle feedback is local to the device; live replacement and player refresh are still unverified.

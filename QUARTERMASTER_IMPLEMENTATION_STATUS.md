# Quartermaster feature implementation — 25 September 2026

## Active source

Work in `C:/Users/Dgdan/.codex/worktrees/quartermaster-features/Ayaneo Jellyfin Controler`, branch `codex/quartermaster-features`.

The original project root was older than the installed reading-enabled app. Commit `04b7c1f` snapshots the current source from `.worktrees/reading-library` (including its uncommitted reading/UI work) so the feature builds preserve that functionality. Neither original checkout was modified by implementation. Do not build the old root and install it over the daily APK.

## Implemented

- `b0f70a5`: Upcoming under Media > Discover; authenticated Sonarr/Radarr calendar and poster routes, chronological date groups, series/season episode batches, right-side detail, local episode times, civil movie dates, partial-source errors, weekly navigation, title links.
- `2590792`: complete Monday–Sunday ranges, contiguous in both directions; prefer today's or the next release for initial selection.
- `bca0d1a`: Hub-owned transfer diagnoses from observed queue/client evidence; Needs attention filter; explanation, evidence and next step in the existing side panel; scoped resume; no automated repair or deletion.
- `3d8b2dd`: failed download/import stages link to Transfers needing attention.

Diagnostics distinguish missing files, client errors, import/queue problems, unmatched queues, client outage, paused, stalled data, queued, checking/moving, importing, downloading and completed transfers. Client completion is not presented as Jellyfin availability. A missing client response does not establish that a transfer disappeared. Old Hub responses retain existing warning behavior.

## Validation and deployment

- `go test ./...` and `go vet ./...` passed; targeted diagnostics tests passed after adding scoped-action integration coverage.
- Final `gradlew.bat testDebugUnitTest assembleDebug` passed: **548 tests, zero failures/errors**.
- Live production calendar returned five releases for 25 Sep–2 Oct. Poster proxy returned HTTP 200, `image/jpeg`, 129,980 bytes.
- Initial calendar was opened and captured on the AYANEO with real posters and date/detail panels. Screenshot: `.local-backups/hub-upcoming.png` (before final spacing changes).
- Upcoming APK v0.3.1/code16 was installed with `adb install -r`; reading/media Home still loaded. A second Upcoming build removing the duplicate heading/excess bottom padding was installed successfully. **The later backward-week correction and diagnostics are built but not installed.**
- Production Hub was updated to `0.3.1-upcoming` with binary hash and service health verification. Its config and credentials were retained.
- Diagnostics candidate on loopback8793 returned HTTP200 with an empty queue, matching production. This verifies connectivity, not a live failed-download diagnosis; those cases are fixture-tested.
- The Windows administrator prompt for the diagnostics production update was canceled. **Diagnostics are not deployed to production.** No retry of that elevation was attempted.
- ADB `100.97.20.86:39705` disconnected and now refuses connections. An asynchronous question asks for the current Wireless debugging IP:port. No current mDNS endpoint was discovered.
- Temporary candidate Hub processes were stopped; production remains the Upcoming build.

Backups, candidate configs and deployment scripts are gitignored under `.local-backups/`. That directory contains secrets copied for local candidate execution; never commit or share it. Prior daily APK: `installed-before-features.apk`. Prior Hub: `hub-before-upcoming-deploy.exe`. Diagnostics deployment script: `deploy-diagnostics.ps1` with config validation, backup, health/hash checks and rollback. Final pending APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Required next steps

1. Reconnect to the user-provided current ADB endpoint. Do not uninstall or clear daily app data. Install the pending APK with `-r`.
2. Complete diagnostics Hub deployment when the Windows administrator prompt can be approved. Its script is ready; the prior canceled prompt did not deploy it.
3. Inspect final Upcoming layout on the real display; test previous/next week, dates at boundaries, title open/back, focus restoration, touch and controller navigation. ADB key events are not a physical-stick test.
4. Inspect diagnostics UI and Needs attention filter. Use deterministic fixtures when the live queue is empty; do not create broken real downloads for testing. Check modal scrolling/focus, refresh, read-only permissions, and preservation of existing transfer confirmations.
5. Run a short regression of Media/Books navigation and existing playback/download flows. Device testing so far was silent and did not start playback.
6. Only after this first milestone is verified, continue subtitles with retained match scores and personal feedback, then bandwidth/priority. Remaining approved backlog: server metrics and supported restarts, actionable alerts, Home customization, connection routing. These have **not** been implemented yet.

Known first-slice limits: Upcoming covers scheduled monitored releases from Arr calendars; no separate undated-library inventory. Its downloaded state is the Arr file flag, not a fresh Jellyfin availability join. The diagnostics title link opens the Needs attention list, not a preselected exact transfer. Alerts/deep links will be expanded in their own milestone.

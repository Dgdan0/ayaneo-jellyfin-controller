# Quartermaster feature checkpoint — 26 September 2026

## Current update: v0.3.8 / code 23

Installed in place on the AYANEO at `100.97.20.86:39751` on 26 September, with the final shelf-heading correction installed at 20:39 device time. Package version and final APK SHA-256 were verified (`94438BF5CE24452899254FF15AA3F26AE63BF06613B0E0CDFF086D8E2287F997`). The previous daily APK is retained on the device at `/data/local/tmp/hub-before-v038.apk`; the new APK is also saved locally as `.local-backups/app-v0.3.8-daily.apk`. No app data was cleared. This is an Android-only update; the existing FireDaemon `AyaneoHub` service remains running.

Scope follows the user's accepted next steps 1–5 and 7. **Home customization (item 6) is excluded**, including rearranging/hiding shelves and pinned shortcuts. The existing sidebar and section order are unchanged.

- Discover now treats the featured card and each poster shelf as separate directional stops. Down/Up are reversible across recycled rows, horizontal title selection is remembered, and returning from details restores the same title. The featured outline draws above the artwork. Search and Upcoming share one toolbar row; poster cards expose meaningful accessibility labels.
- Movie and episode details have a direct Subtitles action beside Play/Resume. Offline availability and saved subtitle count are visible with the watch state. Existing secondary actions remain in More.
- Subtitles distinguish library installation from this AYANEO's saved tracks, pending copy and failed copy. Completion messages reflect device sync, retries retain the video, and refreshes preserve the focused action. Provider match scores remain separate from personal ratings.
- Failed title pipelines filter Transfers using exact provider IDs. Transfer cards show the diagnosis with the title; “Why isn't it working?” opens the existing explanation, evidence and allowed repair actions.
- Notifications contains persistent download/subtitle alert history. Local completion and terminal failures link directly to the affected download or subtitle screen. Server transfer checks link to the exact transfer, including finished items and when launched from Books mode. History is isolated by Hub/profile, repeated events are suppressed, resolved failures are replaced, and a later subtitle update is treated as a new event. The Android alert toggle preserves in-app history.

Validation: 553 JVM tests passed; 15 isolated AYANEO tests passed. After the final shelf-heading correction, all four affected navigation/layout tests passed again, including full heading and caption visibility at a 360dp content height. Device coverage includes Discover navigation through six shelves and back, horizontal selection and return, artwork outlines/caption visibility, series focus restoration, calendar continuity, diagnostics, subtitle selection/rating history, offline tracks without Hub connectivity, exact transfer alert routing, profile isolation/deduplication, and real sidecar HTTP transfer with **zero video requests and unchanged video bytes**, including a truncated subtitle recovery. The normal daily app was updated only after these checks passed. Live daily-app inspection confirmed the featured outline above artwork, reversible shelf navigation, visible poster captions, and the direct Subtitles button beside Resume. Screenshots are saved in `.local-backups/hub-feature-v038.png`, `hub-discover-ready.png` and `hub-title-ready.png`.

Delivery limits: local download/subtitle workers publish completion alerts immediately when running. Remote transfer checks use an Android periodic job (15-minute minimum; Android may defer it), plus checks while Transfers is open. This is polling, so a transfer removed between observations may have no completion event. Server completion means downloaded on the server; it does not assert Jellyfin import. Quiet hours and server push are not part of this update. Physical controller key presses were simulated through ADB/instrumentation, not pressed by a person.

## Active source

Feature worktree: `C:/Users/Dgdan/.codex/worktrees/quartermaster-features/Ayaneo Jellyfin Controler`, branch `codex/quartermaster-features`.

`04b7c1f` snapshots the newer reading/UI checkout used by the installed app. The original project root and `.worktrees/reading-library` were not changed by this feature work. Do not install a build from the older original root.

## Installed and verified

- Upcoming: Discover > Upcoming; real posters and titles grouped by every date; episode batches; right-side details; contiguous Monday–Sunday weeks; local episode times and civil movie dates; title links; partial-source errors. Commits `b0f70a5`, `2590792`.
- Download diagnostics: Transfers > Needs attention; explanation, source evidence and next step; optional scoped resume. Failed title pipelines link to the attention list. Commits `bca0d1a`, `3d8b2dd`.
- Discover cached-pagination crash fixed and device regression covered. Commits `77956a6`, `727973d`.

At the initial checkpoint, production Hub was `0.3.1-monitor` and the daily Android app was v0.3.1/code16. The FireDaemon `AyaneoHub` service was running on port 8791 after that combined deployment; its installed binary hash matched the tested ready binary (`E6C9E73286589A8693E2951ADE090532185D474F7FEDDF60BD0A46A62C68C981`). The AYANEO daily app was updated in place with `adb install -r` on `100.97.20.86:42603`, preserving app data. Current versions and the later subtitle deployment are recorded below.

## Unified online/offline subtitle update — 26 September

This subtitle deployment used v0.3.5/code20; the subsequent browsing audit below advances the app to v0.3.7/code22. An offline movie or episode opens the same Subtitles screen as its online library item through the Y controller action or touch UI. The screen shows local tracks alongside Bazarr's installed tracks, download history, match score and the user's local quality rating. Selecting a new provider result writes through Bazarr, asks Jellyfin to refresh that one title and queues an update for any completed offline copy. The explicit “Prepare online subtitles and refresh this AYANEO” action also works while an earlier subtitle update is pending. If the Hub is unreachable, saved tracks remain visible and the screen offers a retry.

The app persists a subtitle-only retry queue per profile. Its worker renews an offline grant, checks the item, media source and size against the existing video, fetches bounded sidecars, swaps them with recoverable backups and updates the local playback manifest. It never requests the video media URL during this update. A separate worker allows this to proceed while video downloads are queued.

Live Dune: Part Two exposed a real cross-service naming mismatch: Bazarr had English and Hebrew `.srt` files beside the movie, but their basenames did not match the video stem, so Jellyfin initially indexed zero subtitle streams. The Hub now verifies that Bazarr and Jellyfin refer to the same existing video, validates same-folder sidecars and language codes, writes bounded `pocketds` copies under the movie stem without changing Bazarr originals, then refreshes only that Jellyfin item. The download endpoint applies the same preparation after a successful Bazarr write. A failure after that write is returned as a warning so the app does not submit the provider selection again.

After the live refresh, Jellyfin indexed two subtitle streams. The AYANEO fetched `/subtitles/0` and `/subtitles/1` through the existing offline grant and its Dune screen showed **2 local tracks: English SDH and Hebrew SDH**. The offline player opened Dune and listed both tracks as selectable. The final app suppresses the internal `pocketds` filename marker in the player label. The prior video file remained in place and the device's subtitle sync made no media download request. Screenshots: gitignored `.local-backups/dune-after-subtitle-sync.png` and `.local-backups/dune-player-final.png` (final player UI). Dune was closed after the check and its resume point returned to about 2:26, near its original 2:23.

The FireDaemon service now runs Hub `0.3.3-offline-subs`; its installed binary hash matches `.local-backups/hub-offline-subtitle-naming-ready.exe` (`CDD13783077BBCB513922DBE0592DD2FDB8B87BDE43272345790F3A4FEFB2FC3`). `.local-backups/deploy-subtitle-naming.ps1` backed up the prior binary, checked configuration, replaced the service binary and verified service status, liveness and hash. The user ran this script in Administrator PowerShell after automatic approval review blocked the assistant's UAC launch. The daily APK was installed with `adb install -r`; no app data was cleared. The immediately previous v0.3.2 APK is saved as `.local-backups/daily-before-offline-subtitle-naming.apk`.

Validation: `go test ./...` and `go vet ./...` passed, including path, alias update, ticket and refresh tests. `gradlew.bat testDebugUnitTest assembleDebug assembleUitest assembleUitestAndroidTest` passed; the smaller final UI changes passed `testDebugUnitTest assembleDebug` and the uitest artifacts were rebuilt. `OfflineSubtitleSyncDeviceTest` passed on the AYANEO: it exercised actual sidecar transfer and verified no media URL request or video mutation. A live provider subtitle replacement was not performed because it would replace the user's chosen subtitle; the two already-installed Bazarr files provided the end-to-end refresh check.

Live calendar posters, week navigation and title open/back passed. Production transfers are currently empty, so failed-transfer UI is tested with deterministic fixtures in the isolated `.uitest` app. No daily app data was cleared.

## Subtitle and browsing audit — v0.3.7/code22

The Offline grid now assigns widths from the available columns, so one or three titles keep the same compact poster size as a full row. Shared poster focus outlines follow the artwork, titles reserve two lines, and focus reveals the complete card with scale clearance. Nested horizontal shelves explicitly reveal their selected card in the outer vertical list; the shelf heading stays visible too when the row fits. Discover focus callbacks now apply that behavior, and person filmographies use responsive columns. Downloaded episode cards grow with their text and reserve clearance around the first focus border.

The audit also fixed two subtitle defects: incomplete HTTP transfers now remove their staged `.sync-part` files while preserving existing subtitles, and A on the Offline series Subtitles button opens Subtitles instead of starting playback. The button has its own focus identity, restores focus on return and has proper spacing from Play.

Validation: 552 JVM tests and all debug/uitest builds passed. Five device tests passed in the isolated `.uitest` app: sparse grids and up/down caption visibility; nested shelf navigation; interrupted subtitle transfer and retry without a video request or mutation; local subtitle visibility when the Hub is unavailable; and controller activation/focus restoration on the series subtitle action. The two browsing tests passed again after the final shelf-heading refinement. Hub subtitle tests passed; production FireDaemon remained Running and `/v1/health/live` returned 200. A read-only Dune check still found two indexed English/Hebrew streams matching the same video managed by Bazarr.

Live walkthrough covered media Home/Discover/Library, Offline movie cards, series/seasons/episodes, and Books Home/library cards. Before/after screenshots and test evidence are under gitignored `.local-backups/audit-*.png`. This is targeted landscape/controller coverage on the AYANEO, not a claim of every font size, theme, title or provider being tested. No provider subtitle was replaced during the audit.

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
4. Home shortcuts and configurable shelves — excluded by the user's latest instruction.
5. Verified local/remote routing for the same Hub identity, preserving sessions and authentication/cache isolation.

These remaining slices are approved backlog, not completed features. No family mode, new reading scope or multi-Arr instances have been added.

Known first-slice limits: Upcoming includes dated monitored calendar releases, with no undated inventory; Arr file availability is labeled Downloaded, not asserted Jellyfin availability. A failed title pipeline opens the attention list rather than an exact selected transfer. Subtitle feedback is local to the device; live provider replacement and player track selection remain unverified.

Final verification: the two affected device tests (subtitle history/selection and monitor focus/missing data) passed again against the final APK. Production configuration validation passed for the ready Hub binary. Commits: `7e55a8b` subtitles, `2b46036` history provenance, `5f5409b` monitoring. The daily app was updated without clearing its data. Actual production UI and read-only API checks passed after deployment.


## Subtitle menu refinement and reader review — v0.3.9/code24

Installed on AYANEO 100.97.20.86:39751 on 27 September 2026. APK SHA-256: `dbd0f356fb48f26ebc3b314876be7b5b28e8d7ade5b245dea0284050b4e87f17`. The FireDaemon AyaneoHub service remains Running and its live health endpoint returns 200; this change requires no Hub deployment.

Removed the standalone subtitle action from online details. More actions now separates Audio & subtitles (existing track selection before playback) from Find / update subtitles (provider search, scores, ratings and saved-copy updates). Offline movies, series, seasons and completed-download menus expose the same distinction. Offline track selection now uses the saved playback plan without contacting the Hub and does not start playback until Play/Resume is chosen. Returning from subtitle fetching restores the offline series More button focus.

Validation: debug and isolated uitest APKs built; JVM suite reports 553 tests, 0 failures/errors, 2 skipped. All 17 targeted AYANEO instrumentation tests passed, covering offline selection without network/autoplay, subtitle fetching menu/focus, local subtitle availability, provider score/rating behavior, detail navigation, EPUB appearance, real Readium narration highlights, real read-along audio, reader preview and reading-library integration. Production movie More, Audio & subtitles and Find / update subtitles were visually checked. A brief movie playback occurred during navigation and was stopped; no provider subtitle was downloaded or replaced. Daily user data was preserved. Device screenshots are in gitignored `.local-backups/v039-*.png`.

Reader analysis and proposed layouts: `docs/READER_POLISH_REVIEW_2026-09-27.md`. Covers production EPUB, comics/manga, audiobooks and read-along, distinguishes Reader Lab from production, and prioritizes reliability/controller work before comfort features. No reader upgrades were implemented in this change. Source review and targeted fixtures do not replace long-session testing with real publications.

## Approved book-reader polish — v0.3.10/code25

Installed on AYANEO 100.97.20.86:39751 on 27 September 2026 without clearing daily app data. Package manager confirms version 0.3.10/code25. APK SHA-256: `b124d7154706b848188cea50631855323de7e420f194d646b7079cdaf97a9cea`. This Android-only change requires no Hub/FireDaemon deployment.

Appearance now shows simultaneous Paper/Sepia/Night/Blue samples, Publisher/Serif/Sans samples, illustrated one/two columns, margin widths and line spacing. Font | Layout | Themes use subtle separators. Changes save immediately to the existing global preference store and apply to subsequent books. No per-book override was added. One full page per screen selects one column and disables continuous scrolling; changing to another layout clears that mode. Reflowable EPUB page breaks still depend on font and screen size, so this is not physical/printed-page matching or an iPad implementation.

The quiet toolbar adds local passage search; results retain exact Readium locators and provide Return to previous place. Search is cancellable, limited to 100 matches and a 30-second timeout. Navigation now follows the controls' screen positions. Chapter shortcuts move between actual reading-order sections, replacing the previous four-screen skip. The footer shows section title, section screen page and whole-book percentage, with page-number entry and a percentage scrubber. Short resources use interpolation within their position range to avoid a stuck percentage bar. Existing saved locators, checkpoint conflict handling, narration ownership and rendering remain intact.

Validation: debug/uitest/test APK builds succeed. JVM suite: 554 tests, 552 passed, 2 skipped, no failures/errors. All eight final AYANEO instrumentation checks pass: production reader opening a fixture EPUB over HTTP, actual Readium blue CSS, one-column/non-scrolling renderer settings, global persistence into a second book, spatial D-pad focus, section-page entry, whole-book seek/return, local search/return, section navigation, appearance saves, real read-along highlights/audio, page preview geometry, modal focus restoration, and durable/conflict-aware Android checkpoints. Native Font/Layout/Themes and toolbar screenshots were inspected; layout options scroll within the panel. Screenshots are gitignored `.local-backups/reader-polish-*.png`. Device fixtures run in the separate `.uitest` package and restore its settings. Long-session testing and iPad rendering are not claimed.

Daily-app verification: Home and Books & Audiobooks loaded from the live Hub. Opened the existing lab sample The Clockwork Island through its normal detail screen, then inspected Font and Themes in the production reader. The installed user's Night theme, publisher font and 110% size were retained. No daily appearance setting was changed. Screenshots: `.local-backups/reader-v0310-reading.png`, `reader-v0310-appearance.png`, `reader-v0310-themes.png`.

The next reader improvement is a proposal only: comic/manga controller panning with top-aligned tall pages and saved zoom/position. Wait for the user's feedback before implementing it.

## Reader button refinement — v0.3.11/code26 prepared, device validation pending

User approved B showing reader controls, then exiting on a second press; Navigator keeps its name; chapter jumps require holding L2/R2. Implemented those changes only. Appearance/Navigator panels still consume B to close themselves first, and Start still toggles reader controls. Android edge-back retains its existing behavior. A held B cannot auto-repeat into an exit.

EPUB and read-along trigger actions now require 600 ms and fire once per pull. Early release, a different input, a panel/screen change or backgrounding cancels a pending hold. The shared input router preserves digital/analog source deduplication; neutral axes and duplicate releases cannot claim/cancel another source's trigger. Other screens retain immediate trigger actions. The reading-position panel explains L1/R1 versus held L2/R2.

Validation: debug, isolated uitest and instrumentation APKs build. JVM suite: 562 tests, 560 passed, 2 skipped, no failures/errors. Eight new router tests cover quick release, threshold timing, rearming, repeat suppression, source deduplication, context cancellation, background reset and unchanged immediate actions elsewhere. The real-reader device test now checks both B presses, Navigator naming, modal hold cancellation policy and a held trigger through the production router, but this updated device test has **not run yet**.

Installation is pending: `172.27.12.79:35535` and the prior Tailscale IP at the new port `100.97.20.86:35535` timed out. Tailscale reports Pocket DS offline. Asked the user for connectivity/current address. Last confirmed installed version remains v0.3.10/code25. Ready daily APK: `.local-backups/app-v0.3.11-daily.apk`, SHA-256 `2f5d9997d0a09e29955359dbbaea8484fbdfa4365bb12d41cb5e78b35fae363e`; isolated build `.local-backups/app-v0.3.11-uitest.apk`. Resume by installing the isolated app and test APK, running the reader/input device checks, then installing and verifying the daily package. No Hub deployment is needed. Do not propose/implement another feature before finishing this validation.

# Apple clients: iPad, iPhone and Mac

Decided on 2026-10-03. This file holds the design. **Status lives in GitHub Issues** (label
`apple`, see "Tickets" in `CLAUDE.md`), not here. The Apple work is done **from the Windows
media PC**, alongside the hub and Android. The MacBook is a build machine that the PC drives over
SSH with `scripts/mac-remote.sh`, the way `scripts/dev.sh` drives the Pocket DS over adb.

Read `CLAUDE.md` first. Its hub sections (architecture, upstream quirks, caching, badges,
wording, the download join) apply unchanged. Its gamepad, focus and Pocket DS sections describe
the Android client and can be skimmed.

---

## What was decided

- **A real native app**, not a web app or a PWA: Swift and SwiftUI, one multiplatform app for
  the user's **iPad Pro 12.9"**, **iPad mini**, **iPhone** and **MacBook**. The native app gives
  AVPlayer playback, Picture in Picture, AirPlay, game controllers and offline downloads, which a
  web app cannot do well on iPadOS.
- **It talks only to the hub**, over HTTPS with a bearer token, exactly as `app/` does. No
  service API keys ever reach an Apple device. This is why the hub exists, and it carries over
  unchanged.
- **Distribution is internal TestFlight only**, under the user's Apple Developer Program account
  (Individual, $99 a year). It is not published on the App Store: it is a personal app, and App
  Review would probably reject a client that controls qBittorrent. Internal testers need no
  review. **Every TestFlight build expires after 90 days**, so upload a fresh one at least that
  often. Family members (the Jellyfin profiles Adirimo, Hadas, Horim) can be added as internal
  testers on the App Store Connect team.
- **One session, on the PC, does all three parts.** Apple's toolchain (Xcode, signing,
  simulators, device tools) is macOS-only, so the Mac builds and runs what the PC edits. The
  first Apple work (#2, #3) was done by a Claude session on the Mac. It was then moved to the PC
  (#7), so that one session could see the hub, Android and Apple together. A session on the Mac
  is still allowed, but never at the same time as the PC; the issue's "Starting" comment says
  who has the work.

## Where things are

| Path | What |
|---|---|
| `app/` | The Android client. **The reference implementation**: behaviour, wording, ordering and rules. When unsure what a screen should do, read the Kotlin |
| `app/src/main/java/com/pocketds/hub/model` | Response models for every hub endpoint (`Library.kt`, `Playback.kt`, `Offline.kt`, `Reading.kt`, ...) |
| `app/src/main/java/com/pocketds/hub/net` | `HubClient`, `CredentialGate` (one 401 stops all traffic), `RetryPolicy`, failure mapping |
| `hub/internal/api/router.go` | Every hub route (about 90), with its rate-limit budget |
| `hub/internal/api/*.go` | Response shapes, one file per area, each with tests |
| `PLAYER_PLAN.md`, `OFFLINE_DOWNLOAD_PLAN.md`, `READING_*.md` | How playback, offline and books were designed |
| `apple/` | **New.** The Apple app goes here, beside `app/` and `hub/` |

## Reaching the hub

1. **Tailscale** on the Mac and on every iPad and iPhone, signed into the same tailnet. The hub's
   private address is the Tailscale Serve URL in `CLAUDE.md`, "Reaching the hub from the handheld".
2. **One token per device**, issued on the Windows PC:
   `hubctl.exe token new --label mac-dev` (then `ipad-pro`, `ipad-mini`, `iphone`). The hash goes
   into `C:\ProgramData\AyaneoHub\hub.yaml` and the `AyaneoHub` service is restarted from an
   administrator PowerShell. **The user does that on the PC**; this session cannot.
3. Keep the development token in **`apple/dev.env`** (gitignored, like `scripts/dev.env`).
   **This repository is public**: never commit a token, an App Store Connect key (`*.p8`), a
   signing certificate or a provisioning profile. In the app, store the token in the Keychain.

Hub behaviour the client has to respect (all in `CLAUDE.md`, "What H0 actually enforces"):

- Each token label has its own rate limits: 90 rpm / 30 burst for screens, 1800 rpm for
  `/v1/img/`, 3600 rpm for playback transport.
- **Five wrong tokens ban the source for 15 minutes**, and retrying through the ban extends it.
  After one 401, stop all traffic until the token changes (the Android `CredentialGate` rule).
  A 403 is a missing scope, not a bad token.
- Send `X-Jellyfin-User` for the chosen Jellyfin profile. Home and Library vary by it.

**The hub is production.** Requests, deletes, watched/favourite toggles and playback all change
the user's real stack. Playback writes watch positions to the real Jellyfin profile. When
testing, prefer titles that are already watched, always end a playback session
(`DELETE /v1/playback/sessions/{id}`), and tell the user about anything that changed.

## Hub changes the Apple client needs

These are Go changes in `hub/`, tests first, in the style of the existing handlers. **Deploying
the hub happens on the Windows PC** (the user runs the installer in an administrator
PowerShell), so push them and say what needs deploying. Any deployed hub must also contain the
`claude/consolidation` history; an older one silently stops saving watch positions.

1. **A playback profile for AVPlayer** (#2). `buildDeviceProfile` in `hub/internal/api/playback.go`
   is written for Media3: it offers **MKV and WebM direct play**, and names itself
   "Pocket DS Media3". AVPlayer cannot open MKV or WebM. Add a container list (or a client kind)
   to `PlaybackCapabilities`, defaulting to today's behaviour so the Android app is unaffected.
   For Apple, offer direct play for mp4/m4v/mov only. MKV then streams as HLS. When the codecs
   already fit, Jellyfin remuxes rather than re-encodes. **HEVC in HLS must use fMP4 segments,
   not TS**, so the Apple transcoding profile needs an fMP4 option. AVPlayer decodes AAC, AC-3
   and E-AC-3 natively, so the Android FFmpeg extension has no Apple equivalent to port.
2. **Subtitles.** The hub already delivers text subtitles as external tracks
   (`/v1/playback/sessions/{id}/subtitles/{trackId}`), and burns in only picture formats
   (PGS, DVD). Android parses SRT/WebVTT once into a cue timeline and draws them itself, which is
   also what makes its live subtitle-delay control instant. Do the same on Apple. ASS can start
   as text with its styling dropped.
3. **Offline** (#5). Offline downloads are the original files, usually MKV, which AVPlayer cannot
   play. Choose when offline work starts:
   - a hub-side remux to MP4 (`ffmpeg -c copy`; the hub already runs the server's ffmpeg for
     trickplay previews),
   - Apple's own HLS offline download (`AVAssetDownloadURLSession`), or
   - embedding VLCKit or MPVKit.

   The remux keeps one player and is the recommended start.

## One behaviour, one implementation

The user's standing rule. Shared behaviour has one owner, and a fix goes into that owner. A
second client is the biggest threat to that rule, because every label, grouping and threshold
now lives in Kotlin (see `CLAUDE.md`, "Shared building blocks"): `ResumeRules`, `EpisodeLabel`,
`StatusText`, `Fmt`, `PlayerLabels`, `SeriesBookLabels`, `ReadingShelves`, ...

- **Prefer moving a rule into the hub response** when both clients need it (a ready-made label,
  a resolved resume decision), with a hub test.
- When a rule must stay client-side, port it **with its tests**: translate the Kotlin JVM test
  cases into XCTest so both clients are held to the same cases.
- Shared example responses in `contract/`, which the hub, Kotlin and Swift tests all read, catch a
  renamed field before a device does (#4).

## Glass on Apple (#12)

The look is `GLASS_PLAN.md`. Its pieces in this app, so each screen uses one owner:

| Behaviour | Owner |
|---|---|
| An artwork's colours: batched asks, the 3/10/30 s retries, the file | HubKit `ArtworkColorStore` (rules in `ArtworkColorBook`); the app's copy is `Glass/ArtworkColors` (`palette(for:)`, `want`) |
| The page behind everything | `Glass/AmbientBackground` (the hub's `HubEndpoints.smallest` picture, decoded at 64 px); pages report their picture with `.ambientArtwork(path)`; which one shows is HubKit `AmbientStack` |
| A panel, the white and glass pills, a round toggle | `.glassPanel(shape)`, `PrimaryPillStyle`, `GlassPillStyle`, `GlassRoundButton` in `Glass/GlassStyle` |
| A card's ring and lift, its marks | `Glass/GlassCards`: put the card in a `GlassCardStyle` button and give its artwork `.litArtwork(corner:)` (`.litRing` for a row card); `ArtworkProgress`, `WatchBadge`, `UpNextTag`, `PlayDisc`, `CardCaption` |
| Sizes: margins, tiles, posters, episodes, type | `GlassMetrics` (`\.glassMetrics`), set by the shell: phone sizes (`small`) where the width class is compact or the window is short (`ShellLayout.isShort`, a phone turned sideways, about 400 points tall: heroes fit the height, the tab bar is compact); a hero's words centred only on a phone held upright (`centred`) |
| A filter or season pill, a capsule of places, a control, tabs, a page heading | `Glass/GlassControls`: `ChoicePill`, `GlassCapsulePicker`, `GlassControlStyle`, `UnderlineTabs`, `PageHeading`, `GlassLabel` |
| Artwork fading into the page | `FadedArtwork.hero` / `.title` (`Media/Redesign`): a mask with the words' shade inside it, as Android's `FadedImageView` |
| Panel, sheet and ink colours, accents | HubKit `GlassColors` (`panel`, `sheet`, `ink`, `badge`), `AccentPreset.defaultFor(side)` |
| The bars, the sections and Media/Books | `Shell/` (`MainView`, `WideBar`, `PhoneBar`, `ShellTabBar`); top capsule or bottom tab bar by width, `ShellLayout.isWide` |
| Pushing a page, or swapping the top one | `NavigationLink(value: AppRoute…)` or `@Environment(\.openRoute)` (`push`, `replace`); the shell owns every stack and its back pill |
| A profile's avatar and colour | HubKit `Profiles` (Android's `ProfileAvatar`, held to its test cases) |
| A library tile's three fanned posters | HubKit `LibraryFan.posters` (one function, so the choice can move into a hub field) |
| The libraries' order (#15): moving one, saving, sliding back on a refusal | HubKit `LibraryOrder` and `LibraryOrderQueue`; the app's `Library/LibraryOrderEditor` (one per side), drawn by `Library/LibraryArrange` (`GripMark`, `.jiggle`, `.arrangeable`) on the Library page and in Settings › Libraries |
| A library's own search (#14), and the Library page's | `GridSource.search(_:viewId:library:)` in `Library/LibraryView` (`HubEndpoints.librarySearch(viewId:)`): the round search on a library's page keeps to it and its count line names it ("2 matches in Anime"); on Favourites and on the Library page it looks everywhere |
| The demo hub (`-demo`) for UI tests and previews | HubKit `Demo/`: `DemoLibrary` answers the library as the hub does (a search kept to one library, Favourites, title pages, watched and favourite that stay for the run, a change that is not exactly one of the two refused) |

Debug builds also take `HUB_SIDE=books` and `HUB_SHEET=profiles` (the avatar's sheet) for
screenshots, beside `HUB_SECTION` and `HUB_OPEN` (a Home row's first title, or with
`HUB_SECTION=library` a library by name, `HUB_OPEN=Anime`). `scripts/mac.sh uitest` runs the UI
tests on the iPhone simulator against `-demo`, and `scripts/mac.sh transparency reduce|normal`
turns the simulators' Reduce transparency on and off.

**Every screen works both ways up.** Each one works in portrait and in landscape on the iPad and
the iPhone, and at any size of Mac window, and none is reported done until it has been checked
both ways on the three devices below. `scripts/mac.sh turn landscape` turns the simulators
themselves (an iPad app that shares the screen cannot turn itself), `shot` then names its
pictures `<state>-<device>-landscape.png`, and `turn portrait` turns them back. With Xcode 27 the
iPhone simulator comes back upright as soon as the turning test ends, so `shot` also hands the
turn to the app as `HUB_ORIENT` and a Debug build on a phone turns its own window. An iPad once
turned sideways stays sideways through a later test that turns it upright, so `turn portrait`
restarts the simulators instead: they boot upright. Look for words
cut off behind the bars or the notch, anything squeezed to "…", scrolling that stops, a turn
that loses your place, and heights that suit a phone held sideways (about 400 points tall).
An iPad's Split View widths are checked with `HUB_WIDTH=375` (a third), `639` (two thirds
upright), `678` (half sideways) and `981` (two thirds sideways): debug builds lay the app out in a
window that wide, compact below 660 as Apple's table has it. The Mac's windows are checked with
`scripts/mac.sh mac-shot 760x560` and `1440x860`: the app opens a fresh window of that size, draws
it into its container (a screenshot over SSH needs Screen Recording, which stays off) and quits.
The Mac's Debug build is `com.dgdan.jellyhub.debug`: the TestFlight copy in `/Applications` owns
the plain id's container, and macOS keeps another app's container from an SSH session.

## Discover, search and requests (#17)

Discover is the Media side's second section: Discover and Upcoming in a glass capsule, the search
beside it (Upcoming puts its weeks there instead), then a featured title and Jellyseerr's rows.

| Behaviour | Owner |
|---|---|
| Where a card leads | `MediaHit.route`: a title in the library (`jellyfinItemId`) opens its library page, with Play, as the prototype's cards do; one you do not have opens `MediaTitleView` on its pipeline. Android opens the request side for both |
| The availability chip's words and colours | HubKit `Availability` (the Pocket's Glass chip colours); the view is `Discover/DiscoverParts` `AvailabilityChip` on `DiscoverPoster` |
| The featured title, the facts, the pipeline's chips and summary, polling | HubKit `DiscoverFeature`, `TitleFacts`, `PipelineTone` / `PipelineLines` (`nextPoll`: 4 s while a stage moves, 5/15/30 s after failures) |
| Paging a row or the search grid | HubKit `PagedLoadState` / `RowPaging`, held to Android's tests |
| Titles requested this session | HubKit `RequestedTitles`, kept in `AppModel.requested` |
| The request form's defaults and body | HubKit `RequestDraft` (the server's default profile and folder; every season until some are ticked) in `Discover/RequestSheet` |
| A release search's lines, Grab and Grab anyway | HubKit `ReleaseLines` and `ReleaseTargetLines`, in `Discover/ReleasesView` |
| The calendar: weeks, days, groups, states, times | HubKit `UpcomingPresentation` and `ReleaseState`, in `Discover/UpcomingView` |

Differences from Android, on purpose:

- The featured card shows on every width; on a phone its words go under the picture.
- Search runs once typing pauses for 0.4 s, from two characters, instead of on Enter.
- The request form is a glass sheet with menus for the profile and the folder. If the options
  cannot be read, it says so and Request still works with the server's defaults; Android sends
  that request at once without asking.
- A series' Find release opens one page with its seasons as pills, the whole season and each
  aired episode; Android has a season chooser before it. A series always names its season to the
  hub, Specials included (Android leaves `season=0` out, which the hub reads as Specials anyway).
- The trailer opens in YouTube or the browser.
- Upcoming's preview sits beside the days on an iPad and a Mac; a phone opens the title instead.

Testing against the real hub never sends a request or grabs a release: the form is opened and
closed, and a release search (which asks every indexer) runs at most once per change. The demo
hub (`-demo`) answers every one of these calls, for the UI tests.

## Playback on Apple (#2)

| Behaviour | Owner |
|---|---|
| Playing something from a page | `@Environment(\.play)` with a `PlayRequest` (item, resume or restart, or a series whose episode the hub picks) |
| The player, its AVPlayer and its hub session | `Playback/PlayerModel`, one per window, shown by the shell over everything (`Playback/PlayerView`) |
| What the hub is told, and when | HubKit `PlaybackReporter` (Android `PlaybackService`'s rules); sent in order by `PlaybackOutbox` |
| What AVPlayer can open | HubKit `PlaybackProfile` (mp4/m4v/mov files, fMP4 HLS), filled in for this device by `Playback/PlaybackDeviceInfo` |
| Resume or start over | `DetailLines.startMode` / `offersStartOver` (Android's `canResume`); the hub judges the position |
| Player wording, the up-next card, seeking and the end | HubKit `PlayerLabels`, `UpNext`, `PlaybackRules` (Android's, with their tests); the card is `UpNextCardView` |
| Pages reading their progress again after playback | `@Environment(\.playbackClosed)`, which changes once the stop and the close have reached the hub |
| The panels (Audio & subtitles, This video, Chapters and their pages) | `Playback/PlayerPanels`: `PlayerSheet` with `SheetGroup`, `SheetRow`, `SheetLabel`, `SheetNote`; opened by pills, round icons or one menu as `PlayerLayout.panelButtons` decides |
| Another track, quality or version | `PlayerModel.change` through `select`; it plays on when `PlaybackChoices.sameStream`, else reopens where it was |
| The choice kept per profile and series or film, the subtitle look for every video | HubKit `PlaybackChoices` (Android's `PlaybackPreferences`), stored by `Playback/PlaybackMemory` |
| Subtitles the app draws | HubKit `SubtitleParser` (SRT, WebVTT, ASS as text; a broken block is skipped) and `SubtitleTimeline`; drawn by `SubtitleOverlay`; the delay is `SubtitleTimingPolicy` |
| Chapters, Skip intro, where subtitles sit | HubKit `PlaybackEnhancements` (Android's, with its tests); the notches are `ChapterNotches` |
| Picture in picture, AirPlay | `PlayerModel.attach` (`AVPictureInPictureController` on the surface's layer), `RoutePicker` under the round Cast icon |

A session is prepared, then streamed, reported (started, paused and unpaused, seeks, progress
every ten seconds of play, stopped) and deleted. Leaving is the only way out, and Back, the next
episode, the app going to the background and quitting all take it. AVPlayer is given the
session's grant addresses (`POST /v1/playback/sessions/{id}/cast-grant`, the ones a TV gets)
rather than the session's own: it fetches its media and every HLS segment itself, it has no
supported way to add the bearer token, and a request without one counts towards the hub's ban
on the device's address. The grant needs no token, the session's `DELETE` revokes it, and
AirPlay will need it too.

`HUB_PLAY=<item id>` opens the player at launch (with `-demo`, `demo-e5` plays Apple's public
HLS test stream as Bleach S1E5), `HUB_PLAY_EXIT=<seconds>` leaves it through Back's own path,
`HUB_PLAY_CHROME=pinned` holds the controls up for a screenshot, and with `-demo`
`HUB_PLAY_FROM_END=<seconds>` starts near the end for the up-next card. `SHOT_SIMS` limits
`sims` and `shot` to some simulators, and `scripts/mac.sh capture` takes screenshots without
relaunching. Against the real hub, play only a title that is unwatched and at 0:00, for under
30 seconds, and leave through `HUB_PLAY_EXIT`: under Jellyfin's 5% nothing is kept, though the
stop still sets the item's `lastPlayedAt`. Then `POST /v1/library/items/{id}/state
{"played": false}` clears it; read the title before and after, and report both reads.

A session left open by a crash, a forced quit or a relaunch is closed at the next launch: each
session is written down when the hub opens it and crossed off when its `DELETE` succeeds
(HubKit `OpenSessions`, kept by `Playback/PlaybackMemory`), and once a launch, before anything
plays, `PlaybackLeftovers` closes what an earlier launch left, each for its own profile. Checked
against the real hub with a made-up leftover: the launch sent its `DELETE`, the hub answered 404,
and the entry was gone.

The player follows the device. Turned, the picture fills the screen; held upright it is a band
across the middle, the title takes its own line under the buttons, drawn subtitles sit under the
picture and the panels come up from the bottom. A narrow window never loses a function: the
three pills become round icons, and where those do not fit either, one round menu. Over video
nothing is white but the Play disc: every button, pill, panel and the up-next card is dark glass
in the playing title's colours.

On an iPhone or iPad, leaving the app ends playback unless picture in picture carries it on (it
starts by itself when the system's setting allows); closing that small window in the background
ends it too. The Mac plays on with its window minimised or the app hidden, as QuickTime does;
closing the window or quitting ends it. The iPhone simulator has no picture in picture, so the
button is not offered there; the iPad simulator has it.

SwiftUI lays out some animation frames on a thread of its own (`com.apple.SwiftUI.AsyncRenderer`),
and a `ForEach` or `Group(subviews:)` row built there runs main-actor code off the main thread,
which Swift 6 stops with a trap. It crashed the player twice on 2026-10-04. So in the player
nothing that holds rows moves while they are first laid out (the sheet appears in place, then
slides), nothing re-lays them out four times a second (the model sets a property only when it
changed, and the sheet does not read the position), and what changes while the chrome fades has
no `ForEach` (the subtitles are one text, the chapter notches a `Shape`).

`HUB_PLAY_TOUR=1` opens the panels in turn, 4 s apart (Audio & subtitles, its timing page, This
video, Chapters), and `HUB_PLAY_SUBTITLE=eng` turns those subtitles on as the title opens.
`SHOT_STATE` names a run's screenshots and `SHOT_TIMES="8 12 16"` takes several, that many
seconds after launch. With `-demo` the stream has two audio tracks, subtitles in SRT, WebVTT and
ASS, two versions, chapters with frames, an intro to skip and the credits.

## Working on the Mac

### From the PC

`scripts/mac-remote.sh <command>` (Git Bash on the PC) copies `apple/` and `scripts/mac.sh` to
a plain build copy at `~/Builds/ayaneo-jellyfin-controller` on the Mac and runs
`scripts/mac.sh <command>` there. After the run it brings `shots/apple/` back to the PC. The
commands are `test`, `build`, `sims [-demo]`, `shot`, `capture`, `uitest`, `turn`, `transparency`,
`mac` and `logs`. It reaches the Mac through
the `mac` entry in `~/.ssh/config` (key login over the tailnet) and the System32 OpenSSH client,
which uses the 1Password agent. The hub token stays in the Mac checkout's `apple/dev.env`; the
build copy points at it through `HUB_DEV_ENV`. Measured on 2026-10-04: the tests take 16 s, and
the three simulators run against the real hub in 39 s.

The Mac must be awake. It never sleeps on the charger (`pmset -c sleep 0`), but on battery it
sleeps after a minute.

### Tools that replace adb

| Pocket DS (Windows) | Apple (on this Mac) |
|---|---|
| `adb install -r` | `xcrun devicectl device install app` (device), `xcrun simctl install` (simulator) |
| `am start` + `dev.sh seed` | `devicectl device process launch` / `simctl launch` with arguments, or a URL scheme |
| `logcat`, `dev.sh trace` | `xcrun simctl spawn booted log stream`; `pymobiledevice3 syslog live` for a device |
| `dev.sh shot` | `xcrun simctl io booted screenshot`; `pymobiledevice3` for a device |
| `.uitest` + instrumentation | an XCUITest target |
| wireless ADB pairing | connect each device by cable once; afterwards it works over Wi-Fi |
| USB debugging | Developer Mode on each device (Settings > Privacy & Security, shown after first connecting to Xcode) |

The user's devices, confirmed on 2026-10-04, and the simulators every check and screenshot uses:

| Device | Points | Simulator |
|---|---|---|
| iPad Pro 12.9" (4th generation, 2020, A12Z) | 1024 × 1366 | `iPad Pro (12.9-inch) (4th generation)`, made by `scripts/mac.sh` from its device type with the iOS 26.3 runtime when the Mac has none |
| iPad mini (A17 Pro) | 744 × 1133 | `iPad mini (A17 Pro)` |
| iPhone 17 Pro Max | 440 × 956 | `iPhone 17 Pro Max` |

`SIMS` in `scripts/mac.sh` names these three; do not drift back to Xcode's default iPad Pro 13" or
iPhone 17 Pro. Check each both ways up, and the Mac at a small and a large window. Real devices are
still needed for controller feel, playback performance and offline downloads.

### Setup

- **Keep the Xcode project as text.** Nobody clicks through Xcode, so the project is generated
  from `apple/project.yml` with XcodeGen and the generated `.xcodeproj` is not committed.
- Done: Xcode 26.3, Homebrew, XcodeGen and `gh` on the Mac; `scripts/mac.sh` and
  `scripts/mac-remote.sh`.
- TestFlight (#6) is described below under Shipping.

## Shipping through TestFlight (#6)

The app is **JellyHub** (`com.dgdan.jellyhub`, one universal App Store Connect record for iOS and
macOS; the UI tests are `com.dgdan.jellyhub.uitests`). `scripts/mac-remote.sh testflight` is the
one step:

1. it checks that the signing keychain (below) holds both distribution identities and that App
   Store Connect has a current App Store profile for each platform, and installs the profiles;
2. it archives for iOS (iPhone and iPad) and for macOS, numbering the build with the minute of
   the upload in UTC (`yyMMddHHmm`, always increasing; `BUILD_NUMBER` overrides it) and taking
   the version from `MARKETING_VERSION` in `apple/project.yml`;
3. it exports each with `method app-store-connect`, `destination upload` and manual signing,
   which signs it and uploads it with the App Store Connect API key;
4. `apple/Tools/asc.swift` follows the builds through App Store Connect's processing until both
   are `VALID`, and lists the TestFlight group's builds.

When the Mac upload fails after the iOS one, `BUILD_NUMBER=<that build> TESTFLIGHT_PLATFORMS=macOS`
sends the Mac build alone, under the same number. `asc.swift builds`, `certificates` and
`profiles` show what App Store Connect holds.

The key, its ids, the team and the app id stay on the Mac in `~/.appstoreconnect/jellyhub.env`
(`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_PATH`, `APPLE_TEAM_ID`, `ASC_APP_ID`, `TESTFLIGHT_GROUP`),
never in the repo.

**Signing.** The key's role, App Manager, may not use Apple's cloud-managed distribution
certificates, so the Mac keeps its own in `~/.appstoreconnect/jellyhub-signing.keychain-db`, whose
random password is in `jellyhub-signing.pass` (600) beside it. The first run (2026-10-04) made the
key pairs there and asked App Store Connect (`asc.swift cert`) for an **Apple Distribution**
certificate, which signs both apps, and a **Mac Installer Distribution** one ("3rd Party Mac
Developer Installer"), which signs the Mac package; both expire on 2027-10-04. `asc.swift profile`
keeps one App Store profile per platform for that certificate, **JellyHub iOS App Store** and
**JellyHub Mac App Store**: it is made once and then reused, and a profile of that name made for
another certificate is replaced. For the run only, the keychain is unlocked and put first in the
search list; when the run ends, however it ends, the list is put back and the keychain locked. The
login keychain is never opened, which an SSH session could not do anyway. To start over (a
certificate revoked or expired), delete the keychain and its password file, and the next run asks
for new certificates.

The iOS archive is unsigned and the Mac one signed ad hoc so it carries its sandbox; the export
signs both. The macOS sandbox entitlements apply to the Mac only (an iOS build signed with them is
refused, ITMS-90046), `ITSAppUsesNonExemptEncryption` is false, `PrivacyInfo.xcprivacy` declares
the app's one required-reason API (UserDefaults, CA92.1), and the icon set has the iOS 1024 and
every Mac size.

A new device starts with the hub's tailnet address in its Address field; it only pastes its own
token (`hubctl.exe token new --label ipad-pro`, `ipad-mini`, `iphone`).

## Design direction

- **Sizes**: a sidebar (`NavigationSplitView`) on iPad and Mac, tabs on iPhone. Lay out by
  available width, not by device model. The iPad mini in portrait is closer to a phone than to the
  12.9".
- **Input**: touch first, then a hardware keyboard on iPad and Mac, then a game controller
  through SwiftUI focus and the GameController framework. The Android rule that every action is
  reachable every way carries over, with touch, keyboard and controller as the three ways.
- **Look**: follow the Android app's 2026-10-02 redesign (top tabs, Home hero, detail page,
  player panels). Ask the user for current screenshots from the Pocket DS rather than guessing.
- **Wording**: as `CLAUDE.md` says. Sentence case everywhere; "On the way", not
  "Processing"; no `…` on buttons.

## Suggested order

1. **Connect** (#3): hub address and token (Keychain), the `CredentialGate` rules, then the
   Manage health screen as the first proof that every service is reachable. TestFlight is #6.
2. **Home, Library and detail**: the Jellyfin profile picker, artwork through `/v1/img/`, watched
   and favourite actions.
3. **Playback**: hub change 1, then AVPlayer with resume / start over, tracks, versions, quality,
   progress events, next episode, PiP and AirPlay.
4. **Search, Discover and requests**: the request form and the release picker.
5. **Downloads and notifications.**
6. **Offline**, after hub change 3.
7. **Books** (Kavita / Storyteller reading).

## Conventions carried over

- Tests first. Logic in plain Swift types that take values and never UIKit/SwiftUI types, tested
  with XCTest, as the Kotlin logic is kept free of Android types.
- Comments explain *why*, usually citing what was observed.
- Commit subjects are lowercase prose, without conventional-commit prefixes. Commit as the same
  author as the history (`git log -1 --format='%an <%ae>'`).
- LF line endings (`.gitattributes`).
- **Branch:** work on `apple/client`. The Windows sessions use `claude/consolidation`. Merges
  between the two are coordinated through the user.

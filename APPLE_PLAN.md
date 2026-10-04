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
| The page behind everything | `Glass/AmbientBackground`; pages report their picture with `.ambientArtwork(path)`; which one shows is HubKit `AmbientStack` |
| A panel, the white and glass pills, a round toggle | `.glassPanel(shape)`, `PrimaryPillStyle`, `GlassPillStyle`, `GlassRoundButton` in `Glass/GlassStyle` |
| Panel, sheet and ink colours, accents | HubKit `GlassColors` (`panel`, `sheet`, `ink`, `badge`), `AccentPreset.defaultFor(side)` |
| The bars, the sections and Media/Books | `Shell/` (`MainView`, `WideBar`, `PhoneBar`, `ShellTabBar`); top capsule or bottom tab bar by width, `ShellLayout.isWide` |
| Pushing a page | `NavigationLink(value: AppRoute…)` or `@Environment(\.openRoute)`; the shell owns every stack and its back pill |
| A profile's avatar and colour | HubKit `Profiles` |

Debug builds also take `HUB_SIDE=books` and `HUB_SHEET=profiles` (the avatar's sheet) for
screenshots, beside `HUB_SECTION` and `HUB_OPEN`.

## Working on the Mac

### From the PC

`scripts/mac-remote.sh <command>` (Git Bash on the PC) copies `apple/` and `scripts/mac.sh` to
a plain build copy at `~/Builds/ayaneo-jellyfin-controller` on the Mac and runs
`scripts/mac.sh <command>` there. After the run it brings `shots/apple/` back to the PC. The
commands are `test`, `build`, `sims [-demo]`, `shot`, `mac` and `logs`. It reaches the Mac through
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

Simulators cover every screen size the user owns: the iPad Pro 12.9"/13", the iPad mini and an
iPhone. Boot all three and screenshot each for layout work. Real devices are still needed for
controller feel, playback performance and offline downloads.

### Setup

- **Keep the Xcode project as text.** Nobody clicks through Xcode, so the project is generated
  from `apple/project.yml` with XcodeGen and the generated `.xcodeproj` is not committed.
- Done: Xcode 26.3, Homebrew, XcodeGen and `gh` on the Mac; `scripts/mac.sh` and
  `scripts/mac-remote.sh`.
- Still to do (#6): Apple Developer Program enrollment, done by the user in the Apple Developer
  app. After approval, an **App Store Connect API key** lets `xcodebuild` sign automatically
  (`-allowProvisioningUpdates -authenticationKeyPath ...`) and upload builds without the Xcode
  window. Keep the key outside the repo. **Signing over SSH needs a one-time keychain step on
  the Mac**, because SSH sessions cannot open the login keychain (the same reason the Claude CLI
  needed its own login there). Add `device` and `testflight` to `scripts/mac.sh` then.

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

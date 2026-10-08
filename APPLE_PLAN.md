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
| An artwork's colours: batched asks, the 3/10/30 s retries, the file | HubKit `ArtworkColorStore` (rules in `ArtworkColorBook`); the app's copy is `Glass/ArtworkColors` (`palette(for:)`, `want`). All three keep a picture once whatever width it is shown at: `ArtworkColorKey`, the hub's own `artworkColorKey` (a Jellyfin image by item, type and tag, a TMDB image by its file) |
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
`HUB_SECTION=library` a library by name, `HUB_OPEN=Anime`). With a library open,
`HUB_SHEET=search:the` opens its own search with those words and `HUB_SHEET=first` its first
title. `scripts/mac.sh uitest` runs the UI tests on the iPhone simulator against `-demo`, and
`scripts/mac.sh transparency reduce|normal` turns the simulators' Reduce transparency on and off.
A run of `scripts/mac-remote.sh` copies back only the screenshots it made.

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
A sheet open on the window is drawn over it where it sits, since on the Mac it is a window of its
own; `-demo` goes after AppKit's own arguments, or the app opens a stray "YES" as a document.
The Mac's Debug build is `com.dgdan.jellyhub.debug`: the TestFlight copy in `/Applications` owns
the plain id's container, and macOS keeps another app's container from an SSH session. The Mac's
disk is small: `scripts/mac.sh shots-prune` deletes the build copy's pictures older than two hours,
which every run has already copied back.

## Discover, search and requests (#17)

Discover is the Media side's second section: Discover and Upcoming in a glass capsule, the search
beside it (Upcoming puts its weeks there instead), then a featured title and Jellyseerr's rows.

| Behaviour | Owner |
|---|---|
| A Library title's cast | A portrait the hub names on TMDB (`people[].tmdbId`, #27) opens `PersonView`, the filmography the request side opens; one it cannot name opens nothing |
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
| What AVPlayer can open | HubKit `PlaybackProfile` (mp4/m4v/mov files, fMP4 HLS), filled in for this device by `Playback/PlaybackDeviceInfo`; the demo hub refuses a prepare without it, so every UI test that plays proves the app sends it (#2) |
| Resume or start over | `DetailLines.startMode` / `offersStartOver` (Android's `canResume`); the hub judges the position |
| Player wording, the up-next card, seeking and the end | HubKit `PlayerLabels`, `UpNext`, `PlaybackRules` (Android's, with their tests); the card is `UpNextCardView` |
| Pages reading their progress again after playback | `@Environment(\.playbackClosed)`, which changes once the stop and the close have reached the hub |
| The panels (Audio & subtitles, This video, Chapters and their pages) | `Playback/PlayerPanels`: `PlayerSheet` with `SheetGroup`, `SheetRow`, `SheetLabel`, `SheetNote`; opened by pills, round icons or one menu as `PlayerLayout.panelButtons` decides |
| Another track, quality or version | `PlayerModel.change` through `select`; it plays on when `PlaybackChoices.sameStream`, else reopens where it was. Every select body comes from HubKit `PlaybackRules.selection`, which names the version playing: Jellyfin applies a track only with its source (#24), and the demo hub refuses one without it |
| The picture's gestures: a double tap's step, the scrub across, the brightness and volume drags | HubKit `PlayerGestures` (sides, the step, `drag`: the first movement decides across or up-and-down, `scrubTarget`: the whole width is a third of the video from two to twenty minutes, `scrubCardCenter`, the level a drag sets); drawn by `Playback/PlayerTouch` (`PlayerSeekBubble`, `PlayerLevelBar`, `PlayerScrubPreview`); the level drags on iOS only, the screen's brightness put back when the player closes; the scrub on the Mac too, as a click-drag (#24) |
| The frame a scrub or a chapter shows | The session's `previewUrl` on the hub's five-second grid (HubKit `PlaybackEnhancements.frameMillis`, `chapterFrameMillis`); a scrub asks once the drag rests 180 ms and keeps the last frame until the next comes |
| The choice kept per profile and series or film, the subtitle look for every video | HubKit `PlaybackChoices` (Android's `PlaybackPreferences`), stored by `Playback/PlaybackMemory` |
| Subtitles the app draws | HubKit `SubtitleParser` (SRT, WebVTT, ASS as text; a broken block is skipped) and `SubtitleTimeline`; drawn by `SubtitleOverlay`; the delay is `SubtitleTimingPolicy` |
| Chapters, Skip intro, where subtitles sit | HubKit `PlaybackEnhancements` (Android's, with its tests); the notches are `ChapterNotches` |
| Picture in picture, AirPlay | `PlayerModel.attach` (`AVPictureInPictureController` on the surface's layer), `RoutePicker` under the round Cast icon |
| Google Cast to the TV (#44; iPhone and iPad, the Mac keeps AirPlay): the address the TV reaches, the TV's session, its subtitles, the button's states | HubKit `CastAddress`, `CastPlan`, `CastPresentation` (`Rules/Cast`); `CastCenter` (the SDK, or the stand-in TV with `HUB_CAST=standin`), `CastPlayback` (the TV's session and its reports, through `PlaybackReporter`), `CastButton`; the player is the remote while `PlayerModel.casting` |

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
`HUB_PLAY_CHROME=pinned` holds the controls up for a screenshot, `HUB_PLAY_SCRUB=<seconds>` holds a
drag across the picture that far on (the preview over the timeline), and with `-demo`
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

## Books (#25)

The Books side of the app: Kavita's comics and manga, Storyteller's ebooks, audiobooks and
read-along editions, and BookKeeprr's requests. Every route it needs is on the hub already (the
ones the Pocket uses, plus #19's streamed audio and listening place), so no hub change is planned;
one that turns out to be needed gets its own ticket first. Android is the reference for behaviour,
wording and which route a screen asks (`screens/home/ReadingHomeView`, `screens/library/
ReadingLibraryScreens`, `ReadingAuthorScreen`, `screens/discover/ReadingDetailScreen`,
`reader/AudiobookScreen`, `ReadingAudio`). The look is `GLASS_PLAN.md`'s Books screens.

It comes in four phases, each checked both ways up on the three simulators and on the Mac at two
window sizes before it is called done: 1 browse, 2 listen, 3 comics and manga, 4 ebooks and read
along. Nothing in a test opens a real book or writes a place to the real hub: reading writes the
owner's Kavita and Storyteller progress. Tests and screenshots of anything that writes use the
demo hub (`-demo`) and generated fixtures; the real hub is only read.

### The screens and the routes they ask

| Screen | What it shows | Hub routes |
|---|---|---|
| Media \| Books | The side picker in the bars (already built for #12). On Books the five sections are Home, Discover, Library, Downloads (offline books, later, with #5) and Activity | none |
| Books Home | The book read last at full cover size with Resume reading (gold) and Details; "Also reading" as glass rows with format icons; "Your series" as fans of covers; then Next in series, Comics and manga, Want to Read, the person's own lists and Recently added | `GET /v1/reading/libraries`; per library `…/libraries/{id}/items?sort=last_read&direction=desc` (Storyteller's with `view=collections`) and `sort=added` where the library has `sort:added`; `GET /v1/reading/works/{id}` for a series being read and for list entries |
| Library | "Your reading libraries" as tiles in the hub's order (#15), arranged as the media side's are | `GET /v1/reading/libraries`, `PUT /v1/library/order` |
| A library | Its name, Series \| Authors \| Books where it can sort by author, Sort and its direction, the count line, a grid of covers (round portraits for Authors), 60 a page | `…/libraries/{id}/items?page=&sort=&direction=&view=`, `…/libraries/{id}/authors?page=&direction=` |
| A book | The cover (square for an audiobook), the eyebrow ("Book 6 · Red Rising"), the facts, the formats as glass chips with missing ones dimmed, how far through, the overview, Read, Listen or Read along (gold) with Change format, the author and series as links, Want to Read, and "More in" its series | `GET /v1/reading/works/{id}`, the series' own work for its strip |
| A series | Its fan, "Series · Pierce Brown", Continue · Book 6, the continue card, its books in reading order | `GET /v1/reading/works/{id}` |
| A comic run | Its cover, "Comic · My Marvelous Year", Continue · Issue 51, volume chips and each volume's issues | `GET /v1/reading/works/{id}`, `/v1/img/reading/kavita-chapter/{id}` covers |
| An author | A round portrait in a white ring, the shelf ("2 series · 6 books"), each series as a glass pill over its books, then the books outside a series | `…/libraries/{id}/authors?authorId=` (every page) |
| A missing book | The dimmed cover and Find this book, which searches editions to request | `GET /v1/reading/search?type=ebook` |
| Books Discover | The kinds as a glass capsule (All, Ebooks, Audiobooks, Comics, Manga, Light novels) beside the search, BookKeeprr's rows (`ReadingDiscoverRows.shown`), each paging on; search shows close matches first and broader ones on request | `GET /v1/reading/discover?type=`, `…/discover/{row}?type=&page=`, `GET /v1/reading/search?q=&type=` |
| A book to request | "EBOOK · NOT IN YOUR LIBRARY", Find a download (gold) opening the request sheet (what to download and the quality), the series' books to tick when the mode asks for them, then the release picker; the transfer's state while it runs. A title the library already has opens its book page instead | `GET /v1/reading/resolve`, `…/requests/options`, `…/requests/series-preview`, `POST /v1/reading/requests`, `…/requests/{seriesId}/releases` and `/search`, `POST …/grab`, `GET /v1/reading/downloads` |
| Activity (Books) | BookKeeprr's transfers grouped by kind, the state as a chip, the bar while it moves, Retry and Cancel with a confirmation | `GET /v1/reading/downloads`, `POST …/downloads/{id}/retry`, `DELETE …/downloads/{id}` |
| The audiobook (phase 2) | The square cover large beside the eyebrow, title and time left; the glass dock with the line, the part steps, the jumps (the seek step from Settings) and the white Play; Parts, Speed, Sleep and Stop as glass pills. A mini player in the shell brings it back while you browse | `…/publications/{id}/audio`, `…/audio/tracks/{n}?rev=`, `GET`/`POST …/audio/position` |
| The readers (phases 3 and 4) | Shown over the whole window by `ReaderHost` (below): an issue or a volume in the comic reader, Read and Read along in the ebook reader | none |

### HubKit: the models and rules to port, with Android's test cases

| HubKit owner | Android original | What it decides |
|---|---|---|
| `Model/Reading.swift` | `model/Reading.kt` | Every reading response, field for field, every field defaulted |
| `Net/ReadingEndpoints.swift` | `net/HubEndpoints` (reading part) | Each `/v1/reading/…` path and query, as Android builds it (Storyteller's library asks for `view=collections` unless a view is named) |
| `Rules/ReadingFacts.swift` | `ReadingBookFacts`, `SeriesBookLabels`, `AuthorLabels`, `Fmt.readingPercent`, `ReadingWork.cardSubtitle` | Every line a book, series, issue or author says ("Book 6 of Red Rising · 2023 · 735 pages", "49% · page 363 of 735", "On #6 · 1 of 6 finished", "#2 · 40% · Audio") |
| `Rules/ReadingShelves.swift` | `ReadingShelves`, `ReadingListsState` | Books Home's rows: the books being read one per series and newest first (Storyteller's zone-less times), the series being read, the next book after one finished, Want to Read and the person's own lists (kept on the device per hub and profile, as on Android) |
| `Rules/ReadingPresentation.swift` | `ReadingWorkPresentation`, `ReadingEntryChoice`, `ReadingFormatMenu`, `ReadingFormatStatus`, `ReadingSortFields`, `ReadingDiscoverRows`, `ReadingSearchPresentation`, `ReadingRequestForm`, `ReadingSeriesSelectionModel`, `ReadingAcquisitionState`, `ReadingReleasePickerPolicy`, `ReadingTransferSummary` | What a book opens as and what the button says, the formats, the sort fields a library offers, Discover's row names, the request form, a request's state, a transfer's words |
| `Rules/Listening.swift` (phase 2) | `Listening`, `SleepTimer`, `SmartRewind`, `SyncThrottle`, `AudiobookContents`, `AudiobookStream`, `AudioPlace`, `AudioArbiter`, `PlayerLabels.rate` / `timeLeft` / `sleep` / `sleepChoice` | Speeds, time left at the speed playing, the sleep timer's fade and the step back after it, chapters or parts and the steps through them, a manifest as parts, the place as the hub keeps it, one sound at a time |
| `Rules/ReadingCheckpoints.swift` (phase 2) | `ReadingCheckpointStore`, `ReadingCheckpointSync` | The place kept on the device first and sent through a durable outbox, based on the place last read (`expected`); another device's move becomes a question, never an overwrite |
| `Demo/DemoReading.swift` | the Pocket's stand-in hub in its instrumentation tests | The demo hub's Books: libraries, works, authors, Discover, requests and transfers, an audiobook's manifest and a listening place that refuses a stale `expected` with 409 |

### Listening on Apple (phase 2)

- **Streaming.** The manifest is read through `HubClient`, so the credential gate decides first. Its
  tracks go to an `AVQueuePlayer` as `AVURLAsset`s with the bearer in
  `AVURLAssetHTTPHeaderFieldsKey` (the hub takes no token in a URL); the hub serves each track with
  `Content-Length`, Range and its real `Content-Type`. The current track and the next are queued,
  so a change of part plays on. A 412 (`audio_changed`) reads the manifest again and plays on at the
  same place, at most twice in a row. A 409 (`audio_not_streamable`) says the book cannot be
  streamed: Apple has no whole-ZIP fallback, which Android keeps for that case.
- **The place.** `ReadingCheckpointStore` keeps one file per book under Application Support, per hub
  and profile. The player keeps the place every 15 seconds while it plays and on every pause, seek
  and change of part, and sends it no faster than every 15 seconds (the last always goes); finishing
  writes `completed: true`. Each write names the place last read as `expected`; the hub stamps the
  time itself, so no timestamp is sent or compared. A place another device moved is a choice
  ("Continue on this device" or "Use server position"), and a place the hub only worked out from a
  reader's page (`exact: false`) is asked about before the player goes there.
- **The device.** Background audio (`UIBackgroundModes` already has `audio`) with the audio session
  in `.playback` and `.spokenAudio`; Now Playing with the cover, the book, the author and the part;
  the lock screen's play, pause, the jumps (the seek step), the part steps and the scrubber. An
  interruption pauses it. On the Mac the same centre answers the media keys.
- **One sound at a time.** HubKit `AudioArbiter`: the shell pauses the audiobook when a video starts
  and pauses the video when the audiobook starts.
- **Settings.** The speed is kept per book. The seek step (5, 10, 15 or 30 seconds, 10 by default,
  Android's `PlaybackSettings.seekSeconds`) is Settings › Playback, and the video player takes it
  too: its ± buttons, the arrow keys, a double tap on the picture and VoiceOver's swipes on the
  timeline (#33). The sleep timer offers 5 to 60 minutes or the end of the part, fades over its last 30
  seconds, and steps back over what faded; any control while it fades carries on.
- **The demo hub** plays generated tones from files the app writes on the device, so the UI tests
  and screenshots need no network and never touch a real book.

### The readers (phases 3 and 4)

**Where a book page meets a reader.** Phase 3 is built by the other Apple session on
`apple/client`; this is the seam between the two. No book page pushes a reader: it asks
`@Environment(\.read)` with a `ReadRequest` (`Hub/Books/ReaderEntry.swift`), and the shell shows
`ReaderHost` over the whole window and its bars, as it shows the video player.

- `ReadRequest.pages(work:publication:)` is a comic issue or a manga volume: the `ReadingWork` the
  page loaded (its volumes and their issues, so the reader goes on to the next issue without asking
  the hub again) and the issue as a `ReadingSectionItem` (`sourceItemId`, `title`, `number`, `kind`,
  `pageCount`, `progress`). Phase 3's entry point is **`ComicReaderView(work: ReadingWork,
  publication: ReadingSectionItem)`**. It takes the place of the coming-next page in `ReaderHost`'s
  `.pages` case, a one-line change.
- `ReadRequest.ebook(work:sourceItemId:readAlong:)` is phase 4's: an EPUB alone, or read along.
  Phase 4's entry point is **`BookReaderView(work: ReadingWork, sourceItemId: String, readAlong:
  Bool)`** ("The ebook reader", below), for `ReaderHost`'s `.ebook` case.
- Which one Read opens is HubKit's `ReadingWorkPresentation.opensPages(_:source:)` (Android's
  `openPublication`: a Storyteller edition, a book or an ebook opens the ebook reader; a Kavita comic
  or manga opens the pages), and the issue for an id is `ReadingWorkPresentation.publication(_:sourceItemId:)`.
- A reader closes with `read.close()`. The shell then changes `\.readerClosed`, and the book, series
  and comic pages and Books Home read their progress again, as `\.playbackClosed` does for video.
- HubKit's reading models and endpoints are on `apple/books` from 9dd3a39. Phase 3 merges that
  branch first and puts its own routes, models and rules in new files (`Net/ReaderEndpoints.swift`,
  `Model/ReadingPages.swift`, `Rules/Comic*.swift`), so the two branches never edit the same file.
  Phase 2 brings `Rules/ReadingCheckpoints.swift`, the place kept on the device and its outbox; a
  comic's page goes through it once both branches are merged.

- **Comics and manga (phase 3).** The hub's pages (`…/pages/{n}`) and thumbnails (`…/thumb?w=`),
  Android's `ComicView` rules kept per series (fit, Thirds, the zoom and its anchor, trimmed
  margins), `ViewportStepPlanner` for the steps, the next page ready before the turn, the Pages
  grid, two pages side by side on an iPad held sideways (`SpreadPlanner`), and the page sent to
  Kavita through `POST …/progress` with `expectedPage`, through the same outbox.
- **Ebooks and read along (phase 4).** The EPUB through the hub's `…/file`, the place through
  `GET`/`POST …/position` with `checkBase` and `expectedLocator` and the same choose-which sheet;
  read along opens the slim edition (`file?format=readaloud&audio=omit`) and plays its narration
  from the audiobook's tracks through `alignment`, its sentence lit in the accent.

**Readium's Swift toolkit for phase 4: recommended for iPad and iPhone, with a decision needed for
the Mac.** Checked on 2026-10-05 (github.com/readium/swift-toolkit):

- Licence BSD-3-Clause. Added with Swift Package Manager (`https://github.com/readium/
  swift-toolkit.git`; CocoaPods also works), and only the products used: `ReadiumShared`,
  `ReadiumStreamer`, `ReadiumNavigator`. `ReadiumLCP` and its SQLite adapter are for DRM and are
  not needed.
- The 3.x line needs iOS 15, Swift 6.0 and Xcode 16.4; the `develop` line (4.0, in alpha) needs Swift
  6.2 and Xcode 26.4. The Mac now has Xcode 27.0 and Swift 6.4, and the app targets iOS 18, so
  either builds. Pin the newest 3.x release exactly and move to 4.0 once it is stable.
- Its `Package.swift` declares iOS only, and the navigator is UIKit, so it does not build for the
  native Mac app. Choose at phase 4: the Mac gets its own small WKWebView reader on the same Readium
  locators (whether `ReadiumShared` and `ReadiumStreamer` build for macOS is the first thing to
  try), or ebooks reach the Mac after the iPad and the iPhone.
- Media overlays are only planned in Readium, so read along needs our own SMIL timeline (the hub's
  `alignment` already maps each narrated file onto a track) with Readium's decorations for the
  sentence.
- Readium is also what the Pocket reads with (Kotlin), so a locator written on one device is read
  as the same place on the other and by Storyteller's own apps.
- Its dependencies need their licences recorded in `THIRD_PARTY_SOFTWARE.md` before it is added:
  CryptoSwift, Zip, DifferenceKit, Fuzi, GCDWebServer, ZIPFoundation, SwiftSoup (and SQLite.swift
  only with LCP).

### Order

1. This plan.
2. HubKit: the models and endpoints, then the facts, shelves and presentation rules, test first
   from Android's cases, against real responses saved from the hub (read only).
3. The demo hub's Books.
4. Phase 1's screens: the side's roots, Home, Library, the book, series, comic, author and missing
   book pages, Discover and requests, Activity.
5. Phase 2: the listening rules and the outbox in HubKit with tests, then the player, its screen,
   the mini player, Now Playing and Settings › Playback.
6. UI tests against `-demo`, then both ways up on the three simulators and the Mac at two sizes,
   once the simulators are free.
7. Phase 3 (comics and manga), by the other session on `apple/client`, through `ReaderHost`; then
   phase 4.

Differences from Android, on purpose, for now: no whole-ZIP audiobook, no Kavita server reading
lists, no mark-read or offline copy on a book's page, and list actions on a card's context menu
rather than Ⓨ.

## The comic reader (#25, phase 3)

`ComicReaderView(work: ReadingWork, publication: ReadingSectionItem)` (`Hub/Reader`) is what a comic
run's issue or a manga volume opens, over the whole window. It leaves through
`@Environment(\.closeReader)`, which its host sets; `ReaderHost` hands it `read.close()`.

| Behaviour | Owner |
|---|---|
| The steps down a page, spreads, the place in an issue, panning | HubKit `ViewportStepPlanner`, `SpreadPlanner`, `PagedImageState`, `ComicPanPolicy` (`Rules/ComicPages`), Android's with its test cases |
| How a series reads (fit, direction, Trim margins), the third you were on, the zoom kept, an issue's cover | HubKit `ComicView`, `ComicPlace`, `ComicZoom`, `IssueCover` (`Rules/ComicView`); stored per work by `ComicReaderSettings` in Android's encodings |
| The heading, "Issue 51 · Page 2 of 24", "Part 2 of 3", the end card's words | HubKit `ReaderTitleFormatter`, `EndOfIssue` (`Rules/ComicWords`) |
| Pages decoded ahead, the Pages grid's cursor, the paper round a page | HubKit `PageSlots`, `PageGrid`, `PageBounds` (`content(ofThumbnail:)` reads the hub's 96-wide thumbnail) |
| A tap, a swipe | HubKit `ComicTouch` |
| Every key in every reader, and a keyboard's | HubKit `ReaderPadMap`, `ReaderKeyboard`; a game controller through the app's `App/PadRouter` (GameController), claimed by each reader while open (`PadClaim`) |
| Two pages side by side, where the page goes on screen | HubKit `ComicSpreads` (a window 980 wide or more and 1.25 times as wide as tall), `ComicUnits`, `ComicUnitLayout`, `ComicFrame` and `ComicCamera`: the fit, thirds, kept zoom, pans, pinches and double taps Android keeps in its screen |
| The issue, its pages and thumbnails, the place sent | HubKit `ReadingPublicationManifest` (`Model/ReadingPages`), `HubEndpoints.readingPublication…` (`Net/ReaderEndpoints`), `ComicProgressOutbox` (`Rules/ComicProgress`) |
| The reader on screen | `ComicReaderModel` (state, opening, moving, zoom, the place; `ComicReaderPages` the pictures, `ComicReaderInput` the keys), `ComicPageCanvas`, `ComicReaderBars`, `ComicPagesGrid`, `ComicReaderSheetView`, `ComicReaderOverlays` |

As on the Pocket: Thirds unless a series or every series is set otherwise; a zoom of your own is
kept for the next page and the next issue, at the same place across; the paper round a page is left
out of the fit and the steps; the pages either side stay decoded, so a turn never shows black, and a
jump keeps the page shown until the next is ready; the last page ends on a card naming the next
issue (Continue, Stay, Leave). The bars float over the page, which never resizes, and go three
seconds after it opens; a tap in the middle brings them back. In a wide window held sideways (an
iPad, most Mac windows) two pages stand side by side where `SpreadPlanner` pairs them: the cover and a
wide page alone, manga right to left. Kavita's place goes through the hub once the reading has moved
(opening an issue, even on a spread, writes nothing), three seconds after a page rests and at once on
leaving, at the end of an issue and between issues, each save naming the page the hub last had; a 409
stops the saves for that issue and says so. When phase 2's checkpoints reach this branch, a comic's
page goes through them.

Keys: Ⓐ forward, Ⓑ back, Ⓧ Ⓨ a whole page, L1 R1 L2 R2 zoom, the D-pad across the page, the right
stick pans, L3 held looks closer, R3 the Keys sheet, Start the controls, Select leaves; with the
controls open the D-pad moves a ring between them and Ⓐ presses one. A keyboard: Space and Return,
Delete, the arrows, Page Up and Page Down, − and =, and Escape, which closes what is open, then
leaves. Touch: the outer thirds read on and back, the middle shows the controls, a swipe across
turns the page while not zoomed, a pinch or a double tap zooms, a drag moves the page.

Debug builds take `HUB_READ=<work id>/<issue id>` with `-demo` only (the scripts and the app both
refuse it otherwise, since reading writes the place): `rw_demo_ff/rw_demo_ff-51` is Fantastic Four
at issue 51, `rw_demo_csm/rw_demo_csm-1` manga. `HUB_READ_CHROME=pinned` keeps the controls up,
`HUB_READ_PAGE=<n>` opens a page, and `HUB_READ_SHEET=display|keys|pages|end` opens a sheet, the
Pages grid or the end card. The demo hub draws every page (`DemoComics`), with paper margins and a
spread in the middle of each issue. `scripts/mac.sh build-tests` compiles the UI tests without a
simulator.

### The page curl (#32)

On the iPhone and the iPad a page turns like paper, as in Apple Books: `UIPageViewController` with
`.pageCurl`, double-sided, under the reader's own page (`Reader/ComicCurlView`, the model's side in
`ComicReaderCurl`, the rules in HubKit `ComicCurl`). The canvas stays on top and keeps every touch
but the outer edges, an eighth of the page's width (44 to 80 points), while a curl may start there:
reading, nothing over the page, at its normal size, in thirds only from the last step going on or
the first going back, and only with a decoded page on that side. A zoomed page pans instead, and past
the first and last page the edges are the canvas's again, so a swipe still reaches the end card. The
curl's own tap is off; taps at the edges read on and back as `ComicTouch` says, and the system's
edge swipes are deferred while reading. A curl hides the canvas until it ends; a finished one turns
the reading as a swipe would, and the keys and a controller play the same curl for a turn to the
page next door. The back of a page is the page itself, mirrored and faint on paper.

Two pages side by side turn as a book, the spine in the middle (`.mid`), each spread's halves a leaf.
A curl knows only left to right (it ignores the view's direction, and its right-hand spine `.max`
turned a left-edge drag to the page before the first, which stops the app), so right to left the
book is drawn mirrored and each leaf mirrored back inside it: a manga's page lifts at the left edge
and turns over to the right. The book's drag must also start moving in from its edge, since a drag
that finds no page to turn to throws. The Mac keeps its own turn.

Checked on the simulators with pictures of a curl held half way: a page alone on the iPhone, a
manga page from the left, and spreads on the iPad Pro sideways both ways. Just after a turn, until
the next page is decoded, the edge is the canvas's, so a quick second drag turns as a swipe does.

A controller walks Reading options as Android's D-pad does (HubKit `SheetWalk`, the lines in
`ComicDisplayLine`): up and down from line to line, Ⓐ presses one, left and right change Comfort's
brightness and warmth a step; Keys goes a part at a time. The ring shows only while a controller is
in use (`readerRing`). A keyboard's arrows and Space do the same (Return does not reach a sheet).

Differences from Android, for now: two pages side by side and the page curl are new. Comfort and
Kavita's reading lists came with #37.

## The ebook reader (#25, phase 4)

`BookReaderView(work: ReadingWork, sourceItemId: String, readAlong: Bool = false)` (`Hub/Reader`) is
what a book's EPUB opens, over the whole window: the view for `ReadRequest.ebook(work:sourceItemId:
readAlong:)`. It leaves through `@Environment(\.closeReader)`, as the comic reader does, and
`ReaderHost`'s `.ebook` case opens it for every book since its simulator pass (2026-10-07). Read
along comes after phase 2 (below).

**Readium**: the Swift toolkit 3.11.0, pinned exactly in `project.yml` (`ReadiumShared`,
`ReadiumStreamer`, `ReadiumNavigator`), linked on iOS only through `destinationFilters`. Its
`ReadiumShared` links UIKit and its navigator is a UIKit view controller, so the Mac does not build
it: `BookReaderView` there says ebooks open on the iPad and the iPhone for now (the choice made at
phase 4; a Mac reader of its own on the same locators is later work). Licences:
`THIRD_PARTY_SOFTWARE.md` and `Hub/Resources/Licenses`. `Reader/BookNavigator` is the one file that
speaks Readium (`@preconcurrency` imports; the EPUB opened off the main actor and handed back as
`sending`), so SwiftUI's `Color`, `Link` and `TextAlignment` never meet Readium's.

| Behaviour | Owner |
|---|---|
| How fast you read and the time left ("12 min left in chapter · 4h 10m in book") | HubKit `ReadingPace`, `ReadingPace.Tracker`, `ReadingPaceStore`, `TimeLeft` (`Rules/ReadingPace`), Android's with its test cases |
| The book's parts and positions, how far through it, the slider's place, the menu's line | HubKit `BookSections` (`Rules/BookSections`), the arithmetic Android keeps in its screen |
| Scrolling with the D-pad, the arrows, Space and the right stick, on into the next part | HubKit `BookScroll` |
| The page shrunk, never laid out again, for the menu or beside a sheet | HubKit `ReaderPagePreview` |
| A footnote's words for its card | HubKit `FootnoteText` |
| How a book looks: theme, typeface, size, spacing, margins, columns, scrolling | HubKit `EpubReaderPreferences`, `EpubLayoutPolicy`, `EpubChromePolicy`, `EpubPreferenceState`, `EpubPagePalette`, `EpubRendering`, `EpubAppearance`, `EpubAppearanceStore` (`Rules/EpubAppearance`) |
| A place as Readium's locator JSON: its label, its anchor, the same place | HubKit `BookLocator` |
| Kindle's corners while reading: the clock, where you are (the hub's pages, else Readium's positions; the time left), the percentage; which show, kept on the device; a tap or L3 for the next (#42) | HubKit `PageInfo`, `PageInfoPlace`, `PageInfoPreferences`, `PageInfoStore` (`Rules/PageInfo`); drawn by `BookReaderCorners` in the strips `PageInfo.strip` keeps |
| Reset text style in Appearance › Layout: a look kept from before gets the defaults' typography in one press (justified, hyphenated, 1.5 spacing, the book's styling off), its size, typeface, theme, margins, columns and Page info left as they are; a line the controller walks to (#42) | HubKit `EpubAppearance.resetTextStyle` and `resetTextStyleDetail` (from `EpubReaderPreferences()`'s defaults), `BookAppearanceLine.resetTextStyle` |
| The EPUB kept on the device, a partial download never opened | HubKit `EpubPackageCache` |
| A kept EPUB or read-along edition checked with the hub as it opens: its ETag kept beside it, `If-None-Match`, 304 opens it, 200 replaces it, an outage opens it at once (#41) | HubKit `EpubPackageCache.open` with the rule `EpubFreshness` and `HubClient.file` |
| Bookmarks per hub, profile and edition | HubKit `EpubBookmarks` (named as phase 2's `ReadingCheckpointKey` names records) |
| The routes: the EPUB, the place read and sent | HubKit `HubEndpoints.readingEpubFile`, `readingEpubPosition`, `saveReadingEpubPosition`, `EpubPosition`, `EpubPositionBody` (`Net/BookEndpoints`) |
| Where a book opens and where it was left | HubKit `BookPlaceKeeper`, kept by `CheckpointBookPlaces` through phase 2's reading outbox (`ListeningStore.shared`, `ReadingCheckpointKey` kind `epub`) |
| The reader on screen | `BookReaderModel` (opening, the place, the pace, keys and pad, scrolling, bookmarks, appearance), `BookNavigator`, `BookReaderScreen`, `BookReaderBars`, `BookReaderSheetView` with `BookAppearanceSheet`, `FootnoteCard`, `BookReaderStatus` |
| A reader's sheet and its rows of keys, the keys' hint row | `ReaderSheetFrame`, `ReaderKeyLines`, `ReaderHintRow`: both readers. A book's sheet sits beside the page wherever the page keeps at least half the window (`keepsPage`), and Appearance from the bottom leaves the page above it undimmed (`previews`) |
| The keyboard while Readium holds it | `BookKeysController`: Readium's navigator inside a controller that takes the first responder after it |

As on the Pocket: Ⓑ opens the menu and the page shrinks inside it, round with the cover's glass;
Ⓑ again leaves the book. The top bar has Close, the title over the time left, Contents, Bookmark,
Appearance and Keys; the lower bar the pages either side of where you are ("Four · Page 3 of 12 in
chapter · 49% of book"), Return to previous place when a link or a jump left one, and the book's
slider. A note's number opens the note as a card over the page, which stays where it was; Go to the
note follows it and leaves the way back. A link to another part of the book is followed, leaving
"Return to previous place" in the menu; a link out of the book is not opened. The pace is learnt from
the reading itself, per edition, leaning on the pace over every book until enough has been read.

Keys (`ReaderPadMap`'s book table): Ⓐ reads on, Ⓧ bookmarks, Ⓨ the contents, L1 R1 a page, L2 R2 a
chapter, the D-pad's sides turn pages and its ends scroll a third of a screen while scrolling, the
right stick glides, Start the menu, Select Appearance, R3 Keys; with the menu open the D-pad moves a
ring between its controls (the slider takes a percent at a time, Ⓐ goes there). A keyboard: Space
and Return read on (a screen at a time while scrolling), the arrows, Delete, Page Up and Page Down,
and Escape, which closes a note or a sheet, then leaves. Touch: the middle shows or hides the menu,
a swipe turns the page; Readium's own gestures otherwise.

The keys reach the reader in two ways. Until the page is on screen the reader's own view holds them
(`.focusable()` with `onKeyPress`); then Readium makes its navigator the first responder and reads
every press itself, passing on no Delete (its press reading has no case for that key), and key
commands on a parent never fire because the navigator handles the presses first (the simulator,
2026-10-07). So `BookKeysController`, the navigator's parent, takes the first responder back once
the navigator has appeared and whenever the app becomes active, reads each key the reader uses as a
`ReaderKey` (Delete arrives as the system's `delete:` action, not as a press), repeats a held arrow,
and passes every other press on. A page that takes the keyboard back (selecting text) sends its keys
through Readium's script, which `BookNavigator` hears too. XCUITest's `typeKey(.delete)` reaches no
view without text input, so the UI tests open the menu with ↑.

The place: the reader asks its `BookPlaceKeeper` where to open and hands it the place reached once
the reading has moved (opening a book writes nothing), two seconds after the reading pauses, and on
leaving or going to the background. `CheckpointBookPlaces` keeps it as the listening place is kept:
on this device first, in the reading outbox under kind `epub`, then sent through `…/position` with
`checkBase` and the place last read (`expectedLocator`). A place another device moved since is a
question when the book opens (Continue on this device, or Use server position), never an overwrite;
a hub that cannot be asked offers the beginning, which writes nothing until the reading moves.

**Read along (after phase 2, as on the Pocket, #21)**: the slim edition (`readingEpubFile(format:
"readaloud", omitAudio: true)`) with its narration streamed from the audiobook's tracks; the
narration's dock stands in the lower bar's place (`BookLowerBar`), the sentence read lit through
Readium's decorations, and the time left the narration's own.

Debug builds take `HUB_BOOK=<work id>/<edition id>` with `-demo` only (the scripts and the app both
refuse it otherwise, since reading writes the place): `rw_demo_recursion/demo-rw_demo_recursion` is a
book not started, `rw_demo_rr6/rr6` Light Bringer half read. `HUB_BOOK_CHROME=pinned` opens the menu,
`HUB_BOOK_AT=<percent>` goes that far in, `HUB_BOOK_SHEET=menu|contents|bookmarks|appearance|keys`
opens the menu or a sheet, and `HUB_BOOK_SCROLL=1|0` turns continuous scrolling on or off, kept as
Appearance keeps it (every UI test launches with 0, so a test cut short leaves no scrolling behind). The demo hub writes a real EPUB 3 for every Books demo work with an ebook
(`DemoEpub`): made-up words, eight chapters of 9 to 18 KB, a footnote in One, a link on to Five in
Two, an endnote in Three, a link out of the book in Four and a second part in Eight's contents.

A controller walks Appearance (`BookAppearanceLine`, `SheetWalk`): the tabs first, where left and
right change tab (as do L1 and R1), then each tab's lines, the tiles across, the size and Comfort's
values changed with left and right; Keys a part at a time.

**No reader on the Mac**, by the owner's choice (2026-10-08, #25): Readium's navigator is UIKit, and
a reader of our own waits until the rest is finished. Read and Read along open a card that says to
read on the iPad, iPhone or Pocket (`BookReaderUnavailable`), its one button Close; the audiobook
plays on the Mac.

Differences from Android, for now: the edges of the page turn nothing on a tap (Android's too).
Search, Look Up and Comfort came with #37.

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
   are `VALID`, gives both their notes, and lists the TestFlight group's builds.

**Every build says what it is about** in the TestFlight app: its "What to Test", the en-US
`betaBuildLocalizations` `whatsNew` of the iOS and the Mac build, made or changed and then read
back (`asc.swift notes`). `testflight` takes them as `NOTES="…"` or `NOTES_FILE=<path on the PC>`
and starts nothing without them; the text reaches the build copy through ssh's input, so no
quoting changes it. They are written for the owner, not for developers: a few short lines of what
is new, what to try, and anything else worth knowing, in plain words ("New: audiobooks. Open one
from Books and press Listen…" / "Please check: …" / "Also: …"). `testflight-notes <build>` gives a
build already up its notes, or shows what it says without them.

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
7. **Books** (Kavita / Storyteller reading): "Books (#25)" above.

## Conventions carried over

- Tests first. Logic in plain Swift types that take values and never UIKit/SwiftUI types, tested
  with XCTest, as the Kotlin logic is kept free of Android types.
- Comments explain *why*, usually citing what was observed.
- Commit subjects are lowercase prose, without conventional-commit prefixes. Commit as the same
  author as the history (`git log -1 --format='%an <%ae>'`).
- LF line endings (`.gitattributes`).
- **Branch:** work on `apple/client`. The Windows sessions use `claude/consolidation`. Merges
  between the two are coordinated through the user.

## Notifications, the server monitor, Settings and Home's rows (#36, #38, #35)

| Behaviour | Owner |
|---|---|
| What has been seen of the services' notifications, the bell's count and the page's dots | HubKit `NotificationReadReducer` / `NotificationReadStore` (Android's `NotificationReadState`, with its test cases); the app's one `NotificationsModel`, owned by `ShellModel` (so the bell and the page are one answer) |
| The Notifications page's columns, words, times and summary line | HubKit `NotificationsPresentation` (`column`, `summary`, `time`, `humanizeHealthTitle`); drawn by `Notifications/NotificationsView` |
| How many entries each service's column loads | HubKit `NotificationSettings` / `NotificationLimits`, Settings › Notifications |
| The server monitor's figures, containers, sessions and status line | HubKit `ServerMonitorPresentation`, drawn by `Services/ServerMonitorView`; `DashboardTone` is the one colour of a status dot (`StatusDot`, `StatCard` in `Glass/DashboardParts`) |
| Each side's accent, per hub and profile | HubKit `AccentSettings` / `PreferenceScope`; the app's one `AccentModel.shared` (the shell, the side picker and the readers read it) |
| How subtitles look, in Settings and in the player's sheet | HubKit `SubtitleLookWords` (words, placement, size); the stored look is `PlaybackMemory.look`; the picture is `SubtitleLine` |
| The licences the app ships | HubKit `Licences`, held by a test to `Hub/Resources/Licenses` |
| What a game controller says | HubKit `ControllerProbe`, read by `Settings/ControllerSettings` by polling (readers set handlers on the same controls) |
| The last answer Home and Discover open with | HubKit `AnswerKeeper` (`HubClient.fetchKept` / `lastAnswer`, `LastAnswer` words), kept in the caches folder under a hash of hub, token and profile; `AppModel.answers` |
| Home's rows: order, hidden, Coming up, a library's newest | HubKit `HomeRows`, `HomeLayout` / `HomeLayoutEditor` / `HomeRowSettings`; the app's one `HomeLayoutModel.shared`, edited in `Home/HomeSettings` |

Debug launches also take `HUB_OPEN=pane:<name>` (Settings), `HUB_SHEET=licence:<id>`, `HUB_HERO=<row id>` (Home's
hero on that row's first card), `HUB_SEEN_DWELL_MS`, `HUB_DEMO_DELAY_MS` and `HUB_FORGET_ANSWERS=1`. The demo hub keeps accents, Home's
layout and the notifications' seen list for the run only, so a test never leaves them chosen.

Differences from Android, on purpose: the bell and Notifications count what is unread on every service while a side shows its
own three columns; a column shows its newest eight and Show all opens the rest; Settings › Home moves a row with arrows, not
with X and Y; there is no Classic look or theme (Glass is always dark); local alerts for finished downloads and new subtitles
are later.

## Library upkeep: subtitles, release searches and deleting from the server (#34)

| Behaviour | Owner |
|---|---|
| Which pages offer subtitles, a release search, deleting; a series' release key; an episode's heading | HubKit `LibraryUpkeep` (`offersSubtitles`, `offersReleases`, `releaseKey`, `offersDeleting`, `pageTitle`, `episodeHeading`); `LibraryItem.mediaKey` is the hub's TMDB key for a film or series |
| The subtitle page's words (a language in English, flags, a candidate's line, a record's line, what a download says) | HubKit `SubtitleLines`, drawn by `Library/SubtitlesView` (`SubtitlesRoute`) |
| The deletion page's words, the alert's title and message | HubKit `RemovalLines`, drawn by `Library/RemovalView` (`RemovalRoute`); the preview and the one-use ticket are `HubEndpoints.removalPreview` / `removeMedia`, each sent once |
| A season's or an episode's release search from a Library series | `ReleaseTargetsRoute` (`startSeason`) and `ReleasesRoute`, the pages the request side already uses; entered from the title page's More menu, a season pill's menu and an episode's menu |
| Going back past a page that finished its work | `OpenRouteAction.pop(count)` (a deletion pops its own page and the title's) |
| Pages that list titles reading again after a deletion | `AppModel.libraryChanges` / `libraryChanged()`, read by `LibraryGrid`, Home, a title's page and the Books grids |

The demo hub's subtitles and deletions (`DemoUpkeep`, `DemoLibrary.remove`) keep the hub's rules: a ticket per search
candidate that a download spends (a second use is 409), a same-language-and-type subtitle replaces the old one, a deletion
is previewed first and confirmed by a ticket good once, and a confirmed one only takes the title out of the demo for the
run. A subtitle downloaded for an item is in the tracks its player lists. Debug launches also take `HUB_TITLE=<item id>`,
which opens that library title on the first section's stack.

Differences from Android, on purpose: no per-subtitle rating kept on the device and no offline subtitle copy (those wait for
Downloads' subtitle sync); a result opens in place under its row rather than in a side panel; the deletion's confirmation is
a native alert whose Cancel has the cancel role (iOS 26 places it last), with Cancel first on the page itself.

## Downloads for watching away from the hub (#5)

The hub repackages each title as an MP4 for this device (`"format": "apple"` on prepare; the
contract is in #5), so AVPlayer plays every download and there is one player.

| Behaviour | Owner |
|---|---|
| What is kept, coming and watched offline, per profile | HubKit `OfflineStore` (files, not SQLite), the app's one `Downloads/OfflineLibrary.shared` with its background `URLSession` (`OfflineDownloader`) |
| A download's next step, and what a failed request means (409 `offline_preparing` waits on the PC) | HubKit `OfflineTransfer` |
| The Downloads tab: one poster per film or series under its library, the queue a batch at a time | `Downloads/DownloadsView`; words in HubKit `OfflineCatalog`, `OfflineQueueLabels` ("Preparing on the PC · 40%", "Next on the PC", "2nd in line on the PC") and `OfflineAppleNotes` (what takes longer, what is left out) |
| A downloaded title's page, played from its files | `Downloads/OfflineTitleView`; seasons from the episodes that arrived (`OfflineCatalog.seasons`), the one to go on with from `playTarget` |
| A series' episodes to download | `Downloads/OfflinePickerView` on `GET /v1/offline/series/{id}/selection?format=apple`; quick choices and words in HubKit `OfflineSelection` |
| The Download button on a title page and its ring | `Downloads/DownloadButton`, its state and words in HubKit `OfflineTitleState`; an episode's menu has Download episode |
| Asking before a download leaves the device | `offlineRemoval` (`OfflineRemoval`): Keep in the cancel role, Remove |
| A download played | `PlayerModel` takes `OfflineLibrary.localPlan` before the hub; the file's audio is chosen by place when that is its language (two English dubs are told apart), its text subtitles by place with the language checked |
| A watch made offline | kept on the device and sent with `POST /v1/offline/progress/sync` as the profile that made it |
| A download's subtitles kept beside it as WebVTT and refreshed (#45): as it arrives, when Downloads opens and before it plays (1.5 s at most, the rest as it plays); fetched when the signature differs, let go when gone from the list, kept on any error | HubKit `OfflineSubtitleSync` (`plan`, `merged`, `failure`, `refresh`, and `tracks`: the kept files drawn by the app first, the MP4's own options for the rest), files in `OfflineStore` (`keptSubtitleFile`, removed with the download); the app's `OfflineLibrary.refreshSubtitles` |

Each item's key is the batch's and the whole Jellyfin id (`OfflineSelection.itemKey`), not its
first twelve characters as on Android: ids that start alike were one key, and only one of a series'
episodes was queued.

Checked against the live hub on 2026-10-08 with Vinland Saga S1E1 (unwatched, at 0:00): the PC
made a 627 MB MP4 in under a minute and a half (picture and sound copied); the player listed its
three audio tracks (two English dubs and Japanese) and two text subtitles, the two picture
subtitles left out, and AVPlayer's own options matched. Played for under 30 seconds, the episode
stayed unwatched on the server; the download was then removed.

Debug launches take `HUB_DOWNLOAD=<item id>` (downloads it at launch) and
`HUB_DOWNLOAD=remove:<item id>`. The demo hub makes each MP4 in a few seconds, The Matrix's
(converted) in eight; Dune fails once until it is retried, and Inception has a French picture
subtitle that is left out.

The Books side's Downloads tab (#43) has Books first: the books, audiobooks and comics kept on the
device (#37's caches), newest first, from HubKit's `ReadingKeptShelf` (a small JSON file beside the
caches, recorded as the ebook reader opens an edition, the player takes an audiobook and the comic
reader opens an issue), each with what is kept and its room, a book's page on a tap and Remove
offline copy on a hold (`Downloads/KeptBooksView`); a book the system has cleared from the caches
leaves the list. Films and TV and the queue are the tabs beside it.

A season's menu on a series page has Download season (#43): the picker opens with that season's
episodes ticked (`OfflinePickerRoute.seasonId`).

A download that finishes or fails says so in a notification (#43, `Downloads/DownloadAlerts`; the
outcomes and words are HubKit's `OfflineAlerts`): one per download and outcome, a batch's grouped,
shown while the app is open too. A tap on one opens Downloads (`DownloadAlertTaps`): the queue for a
failure, which says why and has Retry (it asks the hub to make the MP4 again, then fetches it), and
what is on the device for one that finished. Permission is asked when the first download starts, never
at launch; the demo hub neither asks nor posts without `HUB_ALERTS=1`, so a permission given in one UI
test never puts a banner over the next.

Differences from Android, on purpose: the hub's MP4, not the original file; no storage location to
choose (the app's own Application Support, kept out of backups); no alerts for the server's own
transfers and subtitles.

## A controller on every page: the input, Ⓑ, L1/R1 and the player (#46, parts A and B)

One listener for the whole app, `App/PadRouter` (`shared`): GameController's handlers on every
controller, the D-pad and the left stick repeating while held, the right stick panning, each press a
HubKit `PadAction`. The screen on top claims the presses with a `PadClaim` (`start` when it opens,
`stop` when it goes) and only the latest claim is sent them: the shell claims first and keeps its
claim, the player and each reader claim theirs over it while open. HubKit's `PadInput` (#46's
engine: whether the ring shows) is another thing.

| Behaviour | Owner |
|---|---|
| Ⓑ closes the profile picker, else goes back a page; L1 and R1 go round Home, Discover, Library, Downloads and Activity, from Notifications, Services or Settings to the first or the last (the Pocket's `switchWithin`) | HubKit `ShellPadMap`, `SectionCycle`; `Shell.padPressed`, which leaves the page alone while a screen's own sheet or alert is up (`App/PresentedOver`) |
| The player with a controller: Ⓐ play/pause, ←/→ the seek step, ↑/↓ volume, Ⓧ subtitles on/off, Ⓨ Audio & subtitles, Ⓑ closes a panel and then leaves, L1/R1 the episode before and after, L2/R2 slower and faster, Menu the controls shown or hidden, Options skips the intro or credits. A panel open takes Ⓑ only; the lock is against touches, so presses go on through it | HubKit `PlayerPadMap`; `PlayerView.padPressed` with the keys' own `press` |
| The UI tests' controller | `HUB_PAD="R1,B"` (Debug builds) presses those, one a second, `HUB_PAD_DELAY` seconds after launch (`PadScript`) |

Escape is not a way back on the iPad yet: a SwiftUI shortcut for it never arrives, since iPadOS keeps
the key for its own focus system. The keyboard joins through key commands that take priority over
the system's (`wantsPriorityOverSystemBehavior`), with #46's engine.

## The reader's Kindle look: colours, margins, corners, typefaces and the menu (#47)

| Behaviour | Owner |
|---|---|
| The page colours (Paper, Sepia `#FCF0D9`/`#5A4931`, Dim = the old `DARK` with ink `#C8C8C2`, Dark = `BLACK` `#000000`/`#AFAFAF`, Blue) and system colours at night | HubKit `EpubPagePalette` / `EpubRendering` (`EpubTheme.black`); a device that had Comfort's black page moves to Dark in `EpubAppearanceStore.migrate` (Comfort's `ComfortStore.takeBlackPage`) |
| What a new device starts with (Literata, 130% on a tablet and 120% on a phone, spacing 1.5) and the one-time switch of a kept look to Literata | `EpubReaderPreferences` (`tabletScale`, `phoneScale`), `EpubAppearanceStore.load(startingScale:)` / `migrate`; Reset text style sets the typeface too |
| The typefaces (Original, Literata, Charter, Georgia, Iowan, Atkinson Hyperlegible), their CSS names and the bundled files | HubKit `EpubTypefaces`; the files are `Hub/Resources/Fonts` (in `UIAppFonts`), declared to Readium by `BookNavigator.fontFamilies`; their licences are in `Licenses` and `Licences` |
| Where the text sits: the gutter Readium keeps (half the gap), the outer margin, the inset of the page view | HubKit `EpubGeometry` (iPad 90 pt and a 48 pt gap, iPhone 24 pt and 24); `BookNavigator.gutter` is given with the navigator, `BookKeysController.sideInset` paints the margin and turns the page on a tap or swipe in it (`BookReaderModel.insetTapped`) |
| The strips and the corners' baselines | `PageInfo.strip(compactHeight:tablet:)` (84 and 96 on an iPad), `PageInfo.baselines`, `PageInfo.title`; drawn by `BookReaderCorners` (13 pt, the page's full ink, the title in small capitals top centre, the time top right) |
| The size slider (14 marks), Spacing (a page of Font's: line spacing 1.3/1.5/1.8 and margins) and brightness at the foot of every page | `EpubAppearance.sizeMarks`, `BookAppearancePage.spacing` / `BookAppearanceLine.spacingPage` / `.back`, `BookAppearanceLine.brightness`; `SizeSlider`, `BrightnessBar` and `ReaderSheetFrame.footer`; the model's `appearancePage` and `leaveSpacing` |

Debug launches also take `HUB_BOOK_THEME`, `HUB_BOOK_COLUMNS`, `HUB_BOOK_FONT`, `HUB_BOOK_SIZE` (the ebook's look, kept as Appearance
keeps it), `HUB_BOOK_SHEET=spacing`, and `HUB_BOOK_TABLET=1`, which lays a phone's reader out with an iPad's margins and strips.

The ebook reader is iOS only, so the iPad is measured on a phone simulator: its margins and gap are the unit-tested arithmetic
of `EpubGeometry` and, in the screenshots, 90 points outside and 48 between two columns.

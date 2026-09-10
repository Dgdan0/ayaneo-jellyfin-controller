# Findroid device audit and feature comparison

Audit date: 2026-09-08

Device: AYANEO Pocket DS

Findroid build: 1.1.0 (version code 33)
Compared with: the current Ayaneo Jellyfin Controller app and hub in this workspace

## Scope and evidence

This audit combines direct use of the installed Findroid app on the AYANEO with Findroid's
official project documentation. Every screen listed below was opened on the device. The player
was exercised with real media, including its audio and subtitle selectors. Offline mode was
enabled briefly to verify the downloaded-media navigation and was turned off again afterward.

The device firmware crashes Android's `uiautomator dump` command while parsing a system
property, so the interaction audit used screenshots and manual taps rather than an accessibility
tree export. Fresh-server login, a download in progress, a download failure, and casting could
not be safely exercised against the user's live library. Those cases are marked rather than
inferred.

Findroid's official README describes its initial player as direct-play only and lists offline
downloads, picture-in-picture, chapters, trickplay, and media-segment support. Its current
roadmap lists Android TV, SyncPlay, and Chromecast as planned features. Version 1.1.0 also adds
an optional mpv player and editable mpv configuration.

- [Findroid project and feature list](https://github.com/jarnedemeulemeester/findroid)
- [Findroid releases](https://github.com/jarnedemeulemeester/findroid/releases)

## Screenshot index

The useful device captures are kept in [`shots/findroid-audit`](shots/findroid-audit/).

| Area | Captures |
|---|---|
| Home and primary navigation | [Home](shots/findroid-audit/01-home.png), [My media](shots/findroid-audit/02-my-media.png), [library cards](shots/findroid-audit/03-my-media-libraries.png) |
| Library grid and sorting | [series grid](shots/findroid-audit/04-library-shows.png), [sort dialog](shots/findroid-audit/05-library-sort.png) |
| Series and seasons | [series hero](shots/findroid-audit/07-series-detail.png), [series continuation](shots/findroid-audit/08-series-seasons.png), [season posters](shots/findroid-audit/09-series-season-posters.png), [season detail](shots/findroid-audit/10-season-detail.png) |
| Episodes and movies | [episode](shots/findroid-audit/11-episode-detail.png), [episode media information](shots/findroid-audit/12-episode-detail-lower.png), [movie](shots/findroid-audit/13-home-card-result.png) |
| Player | [playing](shots/findroid-audit/14-player.png), [controls](shots/findroid-audit/15-player-controls.png), [audio](shots/findroid-audit/16-player-audio.png), [subtitles](shots/findroid-audit/17-player-subtitles.png), [back behavior](shots/findroid-audit/19-player-back.png) |
| Offline downloads | [downloads root](shots/findroid-audit/20-downloads.png), [downloaded series](shots/findroid-audit/21-download-series.png), [downloaded seasons](shots/findroid-audit/23-download-series-seasons.png), [downloaded episodes](shots/findroid-audit/24-download-season.png) |
| Accounts, search, and settings | [settings](shots/findroid-audit/25-profile.png), [users](shots/findroid-audit/27-users.png), [player engine](shots/findroid-audit/28-player-settings.png), [gestures](shots/findroid-audit/29-player-settings-gestures.png), [player options](shots/findroid-audit/30-player-settings-more.png), [trickplay and next episode](shots/findroid-audit/33-player-settings-trickplay.png), [favourites](shots/findroid-audit/35-favorites.png), [search](shots/findroid-audit/36-search.png), [download settings](shots/findroid-audit/37-download-settings.png) |

The app was left online after the audit; [the final Home capture](shots/findroid-audit/39-offline-restored.png)
confirms that normal library rows were restored.

## A. What differs

### Navigation and overall structure

| Area | Ayaneo Controller | Findroid | Decision |
|---|---|---|---|
| Primary navigation | Collapsible left rail with Home, Discover, Library, Transfers, Manage, and Pad. It defaults to a 68dp icon strip, expands to 190dp with Start/app-mark tap, remembers the choice, and retains L1/R1 plus bottom controller hints. | Persistent left rail with Home, My media, and Downloads; search/profile actions at the top. Detail and player routes hide the rail. | Delivered a rail adapted to the Pocket DS: compact by default so landscape content keeps its width, with gamepad shortcuts still visible in the hint bar. |
| Focus and movement | Explicit focus decoration, A/B/X/Y actions, L1/R1 section switching, remembered scroll and focus, and Android system-back handling. | Touch-first Material layout. Basic focus may be provided by Android, but the UI does not expose a controller legend or visible section shortcuts. | Keep ours. Validate every new surface with D-pad and touch. |
| Back behavior | B and the Android back gesture unwind overlays, playback, details, and sections. Player restores the originating item. | Floating back/home buttons on detail pages and a back button in the player. | Keep our system-back integration and focus restoration; adopt the visually clear close affordance in the player's top bar. |
| Service management | Manage shows the hub plus Jellyfin, Jellyseerr, Sonarr, Radarr, Bazarr, and qBittorrent health, latency, version, uptime, official project logos, and an open-dashboard action. | No media-stack operations dashboard. Server and user settings concern Jellyfin connections only. | This is a core advantage of our app. |

### Home, My media, and search

| Area | Ayaneo Controller | Findroid | Decision |
|---|---|---|---|
| Continue Watching | Dedicated 16:9 episode/movie cards with episode still, series title, `SxxEyy · title`, progress, and per-series exclusivity. | Large 16:9 cards with title, episode label, badges, and a thin progress line. | Delivered in the Findroid shape while keeping our membership rule. |
| Next Up | Concrete episode cards use the same landscape identity, without a progress line for completed content. | Separate large landscape row. | Delivered. A partly watched series stays out of Next Up until its active episode completes. |
| Recently added | Movie/series posters; A opens title details rather than starting playback. | Not visible in the observed Home viewport. Content is reachable through sorted libraries. | Keep ours as a title-browsing row. |
| Favourites | Home keeps its Favourites row, and Library has a dedicated paged Favourites destination with a clear empty state. | Dedicated full-width Favourites entry under My media; the tested account's page was empty and showed no explanatory empty state. | Delivered. |
| Global Jellyfin search | Library search returns Jellyfin movies and series by item ID with watched/favourite/unwatched state. Discover search remains separate for TMDB/Jellyseerr acquisition. | Top-level search returns existing Jellyfin movies and series and shows unwatched counts. | Delivered while preserving the distinction between owned-media search and discovery. |
| User identity | Home profile selector lists hub-visible Jellyfin users and invalidates user-specific caches when switched. | Profile chip plus Users settings; observed screen lists a current server and user and offers Add user. | Add explicit server/account management only if the handheld should connect without the hub. For the current architecture, improve the profile picker and show the active server. |

### Library selection and grids

| Area | Ayaneo Controller | Findroid | Decision |
|---|---|---|---|
| Library selector | Large square cards. Explicit collection art is preserved; otherwise the hub chooses a deterministic daily poster from that library, so it changes once per day without flickering on every return. Cards clamp to the available screen width. | Huge two-column 16:9 artwork tiles with the library name centred over the image; the Marvel library uses explicit art. | Our daily fallback meets the requested behavior. Consider an optional wide-card layout, but keep square cards as the controller-dense default. |
| Grid density | Seven poster columns with the compact rail, six when expanded, 60-item pages, end-of-page prefetch. | Four large poster columns on this display. | Delivered adaptive spans so the cards keep their size instead of being squeezed by the rail. |
| Paging and state | Paged loading; keeps loaded pages and visible content on later errors; restores scroll and focus. | Appears as one continuous lazy grid. Persistence after process death was not tested. | Keep ours. |
| Card state | Progress plus compact watched, favourite, and unwatched-count badges; completed items never show a progress line and Library never shows the redundant “Partial/In library” banner. | Purple check badge for watched items and numbered badge for remaining/unwatched items. | Delivered. |
| Sort controls | Server-side title, release date, date added, date played, parental rating, community rating, critic rating, and runtime; ascending/descending is selected separately. | One modal combines Ascending/Descending with Title, IMDb Rating, Parental Rating, Date Added, Date Played, and Release Date. | Parental rating is delivered. Combining key and direction into one sheet remains a small interaction polish item. |
| Missing/deleted content | Item-ID navigation, loading/empty/error handling, stale-response rejection, retries, and focus fallback. | Normal Jellyfin navigation; error and deleted-item cases were not induced. | Keep ours. |

### Series, seasons, episodes, and movies

| Area | Ayaneo Controller | Findroid | Decision |
|---|---|---|---|
| Series primary action | Resolves the selected user's Resume SxEx, Play next episode, or Start series through the hub. | Generic Play action plus a large Next Up episode lower on the page. | Ours states the actual action more clearly. Keep it. |
| Series metadata | Backdrop, title/original title, year, certification, ratings, genres, overview, watched/unwatched state, studios, writers/directors, cast/crew, media data, and seasons. | Backdrop hero, title and original title, years, rating, runtime, overview, genres, writers, cast and crew. | Delivered. |
| Series actions | Play/resume/next/start plus selected-user mark watched/unwatched and favourite/unfavourite, with optimistic UI and rollback. | Play, trailer, mark watched/unwatched, and favourite buttons. | Delivered; trailers remain in Discover when a provider match exists. |
| Seasons | Horizontal season posters, including Specials, using season artwork, selection state, and unwatched counts. | Horizontal poster row with season art and unwatched-count badges. | Aligned. |
| Episodes in a season | Horizontal 16:9 episode strip, by explicit user preference, with episode number/title/runtime and watch state. | Vertical rows with a landscape still, number/title, and the full overview. | Keep ours horizontally. Add an optional expanded episode description/details pane rather than converting to a vertical list. |
| Episode detail | Date/runtime/ratings, Play/Resume, Start over, Playback options, watched/favourite actions, overview, studios/people, file size, and normalized video/audio/subtitle stream summaries. | Date/runtime/rating, codec badges, Play, watched, favourite, download, overview, file size, detailed video/audio/subtitle streams, and cast/crew. | Core metadata and actions are delivered. Local Download belongs to the Offline milestone. |
| Movie detail | Artwork, original title, certification/ratings, overview, progress, Play/Resume, Start over, Playback options, watched/favourite actions, studios/people, and stream information. | Original title, certification/rating, codec badges, remaining-time Play button, Start over, trailer, watched, favourite, download, stream information, and cast/crew. | Core metadata and actions are delivered. Keep source/quality choices in Playback options. |
| Specials | Included and populated from Jellyfin. | Shown as a season when present. | Aligned. |
| Missing TMDB data | Jellyfin item IDs drive Library navigation, so provider metadata cannot block it. | Native Jellyfin items likewise remain browsable. | Aligned. |

### Playback

| Area | Ayaneo Controller | Findroid | Decision |
|---|---|---|---|
| Playback pipeline | Media3 client plus hub PlaybackInfo negotiation, source selection, direct play/remux/transcode, range/HLS/subtitle proxy, scoped sessions, progress events, and transcode cleanup. | Official documentation describes direct play only; unsupported media may require a compatible client decoder. Optional ExoPlayer and mpv engines are selectable. | Keep the hub-negotiated fallback. Consider mpv only if hardware tests prove a Media3 gap that Jellyfin transcoding cannot solve well. |
| Overlay layout | Controller-first overlay with box-free outlined top symbols, focusable bottom controls, a timeline-anchored preview, and contextual sheets. Tapping unused space hides controls. | Top row has back, title, screen lock, aspect, speed, audio, and subtitles. Centre row has previous, rewind, play/pause, forward, and next. Bottom has elapsed/total and timeline. | Add lock, aspect, and speed. Keep our top-bar back behavior tied to Android back as well. |
| Skip controls | User-selectable symmetric 5/10/15/30-second interval; side double-tap uses the selected value; L2/R2 and buttons use symbolic controls. | Separate configured rewind and forward values; observed defaults were 5 seconds back and 15 seconds forward. | Allow independent back/forward values. Keep a simple linked default. |
| Seek gestures | Horizontal drag moves the visible timeline thumb and shows target time, signed delta, and a small image above that point. Timeline scrubbing omits the delta. | Horizontal seek gesture with optional trickplay preview. | Aligned; ours gives more explicit delta feedback. |
| Brightness/volume | Left vertical drag changes brightness; right vertical drag changes media volume, with compact moving percentage bars. Player is held silent until the audio route initializes. | Same left-brightness/right-volume gestures, with an option to remember brightness. | Add remember-brightness. |
| Audio/subtitles | Pre-play and in-player track sheets, semantic preference memory by user/series, external text subtitles, burn-in fallback, and subtitle offset. | Radio-list audio and subtitle dialogs with codec/language labels. | Ours is functionally broader. Improve track labels and add subtitle appearance controls. |
| Quality and versions | Original first, manual bitrate caps, media versions, active stream diagnostics, and Jellyfin fallback. | Direct-play engine selection; no equivalent server quality negotiation was observed. | Keep ours. |
| Trickplay | Hub prefers approved Jellyfin trickplay tiles and extracts a small session-bound frame with local ffmpeg when the library has no generated tiles. | Optional trickplay on scrub and seek gestures. | Aligned, including media without pre-generated trickplay. |
| Chapters | No chapter markers or chapter actions yet. Previous/next selects adjacent episodes. | Chapter markers can appear on the timeline; a setting can make long presses jump chapters. | Add chapter markers and a chapter sheet. Do not overload previous/next episode buttons. |
| Media segments | Next-episode countdown exists. No intro/recap/outro/credits skip yet. | Configurable skip button and optional auto-skip for Jellyfin media segments such as Intro and Outro. | Add skip-segment prompts using Jellyfin segment data; default auto-skip off. |
| Speed | Not present. | Playback speed action is present in the top bar. | Add 0.5–2.0× choices and remember per-user default only when explicitly selected. |
| Aspect/zoom | Fixed sensible video fit; no user aspect menu. | Aspect/maximize button, optional start maximized, and pinch-to-zoom. | Add Fit, Fill, Zoom, and Original aspect choices. |
| Screen lock | Not present. | Lock action prevents accidental touches. | Add a touch lock that leaves Android back available after an unlock gesture. |
| PiP | Native Android PiP action in the player top panel; dismissing the window stops playback and closes the Jellyfin session. | PiP can be entered on the Home gesture when enabled. | Ours has the explicit action requested. Add optional automatic PiP later. |
| Lifecycle | Pauses on foreground loss, retains session for return, reports position, and restores content focus on exit. | PiP/offline behavior is mature; exact event cadence was not inspected. | Keep ours and add notification/headset controls only with background playback. |

### Downloads and acquisition

| Area | Ayaneo Controller | Findroid | Decision |
|---|---|---|---|
| Meaning of Downloads | The remote acquisition view is now **Transfers**. It groups qBittorrent/Sonarr/Radarr episode packs and series, distinguishes grabbed/downloading/importing/stuck, and can stop, start, remove, delete files, or remove failed queue records according to token scope. | Local Jellyfin media downloaded onto the Android device for offline playback. It groups TV shows, seasons, and episodes using normal library detail views. | Naming is fixed. Future device downloads get a separate Offline destination. |
| Series/season selection | Discover request flow supports explicit seasons and manual release selection; pack safety rejects releases outside the selected scope. | Download action lives on movie/episode details; downloaded series and seasons are organized afterward. Exact season-batch selection behavior was not proven during the audit. | Add local movie and per-episode download first, then season batch selection with size and storage confirmation. |
| Download state | qBittorrent is authoritative for transfer progress; Sonarr/Radarr supply queue/import context. Completed quiet torrents are hidden so live work remains visible. | Local downloaded/not-downloaded badges; Offline Mode shows only local content. | Keep transfer state. Add a separate persistent local-download database for file, tracks, checksum, progress, and retry state. |
| Offline playback | Not present. | Present, including an Offline Mode that removes unavailable online library navigation. | Add after item actions/player parity. Store selected audio/subtitle metadata and reconcile playback progress with Jellyfin when connectivity returns. |
| Download settings | Remote control permissions are bearer-token scopes and server policy. | Toggles for mobile-data and roaming downloads. | Add network and storage policy when local downloads exist; no disabled placeholder button is needed before then. |
| Discovery/requesting | TMDB/Jellyseerr Discover, request options, manual releases, pipeline status, and service diagnostics. | Browses and downloads existing Jellyfin media; it is not a Sonarr/Radarr/Jellyseerr acquisition controller. | This is a core advantage of our app. |

### Settings, diagnostics, and polish

| Area | Ayaneo Controller | Findroid | Decision |
|---|---|---|---|
| Settings breadth | Hub connection/profile, theme, player skip interval and related app preferences are distributed through current screens. | Central Settings screen: Offline Mode, Language, Interface, Player, Users, Servers, Downloads, Network, Cache, and About. | Add a central Settings destination once the next set of options lands. |
| Player settings | Skip interval, track preferences, and current session controls; more behavior is fixed to safe defaults. | Engine, editable mpv files, gestures, brightness memory, maximize, independent seek values, chapters, segments, next-episode threshold, trickplay, and PiP behavior. | Add only user-facing choices that materially change behavior. Keep protocol/session internals out of the UI. |
| Service visibility | Manage exposes live health and opens each configured dashboard without putting API credentials on the handheld. | No equivalent. | Keep ours and later add safe maintenance actions through scoped hub endpoints. |
| Empty/error states | Explicit loading, empty, partial, stale-data, page-failure, and retry handling across hub screens. | The empty Favourites page observed on device was blank. Other failures were not induced. | Keep ours. |
| Visual density | Designed for the Pocket DS landscape display and D-pad, generally showing more content at once. | Large, polished touch targets and cinematic artwork, often with only two to four items visible. | Borrow its hierarchy, artwork, and state badges while preserving our density and focus visibility. |

## B. Features worth adding

### Delivered milestone: library actions and richer item details

Completed on 2026-09-08 without changing the proven playback transport.

1. Added hub endpoints for favourite and watched state with the selected Jellyfin user bound at
   request time. Support mark played/unplayed for movies, series, seasons, and episodes, plus
   favourite/unfavourite where Jellyfin supports it.
2. Added compact watched checks and unwatched-count badges to library, season, Home, and search
   cards. Never show a progress line for a completed item.
3. Added original title, certification, genres, directors/writers, studios, cast and crew, file
   size, and normalized video/audio/subtitle summaries to movie and episode detail.
4. Added dedicated Library search and Favourites screens. They navigate by Jellyfin item
   ID and reuse the existing details and focus restoration.
5. Added parental rating sorting. Merging sort key and direction into one controller/touch sheet
   remains optional interaction polish.

### Player milestone: navigation and viewing controls

1. Chapters: timeline markers, chapter list, previous/next chapter actions, and trickplay preview.
2. Jellyfin media segments: Skip Intro/Recap/Outro/Credits prompts, with optional per-user
   auto-skip kept off by default.
3. Playback speed, screen lock, and Fit/Fill/Zoom/Original aspect choices.
4. Subtitle appearance: size, vertical position, text/background color, edge style, and saved
   semantic preferences alongside the existing subtitle offset.
5. Independent rewind/forward intervals and remembered brightness.
6. Media notification, headset controls, and optional automatic PiP only when background/PiP
   lifecycle behavior is implemented end to end.

### Offline milestone: separate Offline from Transfers

1. Rename the existing qBittorrent/Sonarr/Radarr page to **Transfers**.
2. Add local movie and per-episode downloads with resumable work, storage checks, network policy,
   deletion, retry, and clear failure messages.
3. Group downloaded episodes under series and seasons; show local state badges on normal details.
4. Save the chosen media version plus audio/subtitle tracks and verify files before declaring
   them available offline.
5. Add Offline Mode and sync playback progress back to Jellyfin after reconnection, resolving
   conflicts by event time rather than overwriting blindly.

## Choices to retain from this app

- Controller focus, A/B/X/Y actions, L1/R1 section switching, hint bar, and reliable focus return.
- Horizontal season and episode browsing, because that is the requested Pocket DS interaction.
- Hub-negotiated direct play, remux, and transcode fallback with manual quality caps.
- Distinct Continue Watching and Next Up membership.
- Discover, requests, release selection, transfer control, and service health/dashboard access.
- Daily library fallback artwork while preserving explicit server artwork.

The strongest next implementation is the player milestone above. The Library actions and state
surfaces now provide the foundation for local Download actions later.

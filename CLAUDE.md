# Ayaneo Jellyfin Controller

A single control seat for a self-hosted media stack — Jellyfin, Jellyseerr, Radarr, Sonarr,
Bazarr, qBittorrent — driven from an AYANEO Pocket DS handheld with the gamepad.

Two components, built in parallel:

- **`app/`** — `com.pocketds.hub`, Kotlin Android. Talks **only** to the hub, over HTTPS with
  a bearer token. Holds no service API keys.
- **`hub/`** — a Go service on the Windows media PC. Holds every API key, fans out to all six
  services, and does the cross-service join.
- **`apple/`** — the SwiftUI app for iPad, iPhone and Mac. It is edited on the PC and built on
  the MacBook over SSH (`scripts/mac-remote.sh`), and talks only to the same hub. See
  `APPLE_PLAN.md`.

The full plan lives at `~/.claude/plans/hey-claude-i-know-shiny-boole.md`.

---

## Tickets: every change has one

All work is tracked in **GitHub Issues** on `Dgdan0/ayaneo-jellyfin-controller`. That includes
work by Claude on the PC or the Mac, by Codex, or by hand. Sessions on different machines share
no conversation or memory, and the issues are what they all see. Use the `gh` CLI, signed in as
`Dgdan0`. The PC session owns the hub, Android and Apple; for Apple it drives the Mac as a build
machine with `scripts/mac-remote.sh`. A Claude session on the Mac may also work on Apple, but
never at the same time as the PC. Hub deploys happen only on the media PC, by the user, and only from
`claude/consolidation`. When a hub change is made on another branch, push it, and the PC session
brings it onto that branch.

1. **Find or open the issue before changing anything.** Search with
   `gh issue list --state all --search "<words>"`. Otherwise create one with the sections of
   `.github/ISSUE_TEMPLATE/feature.md` or `bug.md`, leaving out the front matter, and fill in every
   section. Small related fixes may share one issue.
2. **Labels show the work still to do.** An issue carries one label per part with work left:
   `hub`, `android` or `apple`. When your part is done and verified, remove your label.
   `needs-deploy` means a hub change is pushed and waiting for the user to deploy it. After the
   deploy, the PC session checks the running `hub.exe` and removes the label. `enhancement` and
   `bug` give the kind.
3. **A hub change says what each app must do.** The *Hub* section lists the endpoints, request
   and response fields with an example, the rules the hub now decides, and what an older app sees.
   The *Android* and *Apple* sections each say what that app must do, or "Not needed: <reason>",
   never left blank. A response change that an app reads gets both the `android` and `apple`
   labels. Changes are additive: never remove or rename a field an installed app still reads.
4. **Comment as you go.** Before starting, read the comments, then post
   "Starting <part> on <branch> (<PC or Mac>)" so two agents never take the same part. When done,
   comment with what changed, the commits, how it was verified (tests, device, simulator) and
   anything the next part needs to know. Then tick its line under *Progress*.
5. **Commits name their issue.** Add ` (#12)` to the end of the subject. Do not write
   "fixes #12": closing keywords act only on `master`, which lags behind. Close the issue by hand
   once no `hub`, `android`, `apple` or `needs-deploy` label is left.
6. **Plans describe and issues track.** A `*_PLAN.md` holds the design; its issue links to it.
   Status lives only in the issue.

---

## Status

| Phase | State |
|---|---|
| **A0** app skeleton + input probe | **Done**, verified on the hardware |
| **A1** input + navigation | **Done.** Tabs across the top plus L1/R1 (the left rail was retired in the 2026-10 redesign), compact controller hints and reliable focus; shell drivable on the device |
| **A2** networking spine | **Done.** HubClient, models, retry, failure mapping |
| **A3** search screen | **Done.** Real posters and availability on the device |
| **H0** hub skeleton + auth | **Done.** Running, serving six service states |
| **H1** Jellyseerr adapter | **Done.** search, detail + pipeline, requests, image proxy |
| **H3** downloads | **Done.** qBittorrent + Radarr + Sonarr adapters, `/v1/activity`, pack grouping, stop/start/delete/queue-remove |
| **A5** downloads screen | **Done.** One row per client transfer, episode-pack counts, action menu and confirmations. Verified against the real stack |
| **H4a** request options + interactive search | **Done.** `/v1/requests/options`, visual `/release-targets`, season/episode `/releases`, scoped `/grab` |
| **A6a** request dialog + release picker | **Done.** Quality, folder, visual season and aired-episode targets; 100-release picker with rejection reasons |
| **H5** discover rows | **Done.** `/v1/discover` returns four rows in one call (99ms), `/v1/discover/{row}?page=` pages each |
| **A7** Discover as poster rows | **Done.** Findroid shape, endless rows, search switches to a dense grid |
| **H2** Jellyfin adapter + index | **Done.** Home, Library, item/season/episode reads, image proxy, provider-id index |
| **A4** Library screen | **Done.** Square folder art, server-side sorting, adaptive seven/six-column paging, search/Favourites, watched/favourite actions and rich Jellyfin-native details, seasons and episodes |
| **A8** Home screen | **Done.** Selectable Jellyfin user; landscape episode/movie Continue/Next cards with season context; title-level Recently Added; progress and focus restoration |
| **H6 / A9** native playback | **Done.** Session-bound Jellyfin range/HLS/subtitle gateway plus full-screen Media3 playback, resume/start-over, tracks, versions, quality, progress events and next episode; verified on hardware |
| **A10** Manage health | **Done.** Official project logos, live state/version/latency/uptime, explicit vertical focus, dashboard launching, and Jellyfin library scanning |
| **H7 / A12** notifications | **Done.** `/v1/notifications` combines Sonarr, Radarr and Bazarr history with current health warnings; stable IDs and local seen state drive per-service and rail unread badges, focus-to-seen, and mark-all-seen |
| **A13** settings + themes | **Done.** A colour per side (one dark look since #20), notification history limits, playback seek distance and controller test |
| **H8 / A14** offline downloads | **Done.** Durable scoped Hub grants, range-resumable original files, series/season picker, persistent foreground queue, grouped manager, local-first playback and per-user deferred progress sync; live device path verified |

The 2026-09-08 Findroid 1.1.0 hardware audit, screenshot index, side-by-side feature matrix,
and recommended next milestones are in `FINDROID_COMPARISON.md`.

The shared detail/focus redesign and remaining visual milestones are in `VISUAL_POLISH.md`.
The Glass look chosen on 2026-10-04 for the Pocket DS and the Apple apps, screen by screen, is in
`GLASS_PLAN.md` (issues #10, #11 and #12).
The durable reader checkpoint candidate and acceptance status are in `READING_CHECKPOINTS.md`.
`IMPLEMENTATION_HANDOFF.md` breaks the remaining product features into tests-first tasks.
Native Apple clients (iPad, iPhone, Mac; SwiftUI, TestFlight) are planned in `APPLE_PLAN.md`;
that work happens on branch `apple/client`, edited here and built on the MacBook with
`scripts/mac-remote.sh`, and it talks to this same hub.
Native UI tests run only against the isolated `.uitest` application with `scripts/dev.sh test-ui`.
Gradle uninstalls instrumentation targets after testing: never target the user's normal app,
uninstall it, or clear its data as part of device verification. Use `adb install -r` for updates.

Offline opens on the durable downloaded catalog, grouped as one alphabetized poster per movie or
series across all libraries. A local series screen derives its season and episode rows only from
completed files. The manager is a secondary tab, and repository broadcasts replaced its former
750 ms polling rebuild. Offline Settings enumerates app-private roots returned by
`getExternalFilesDirs`: the chosen internal/SD location is used only for new jobs and stored absolute
paths keep earlier files valid. The Pocket DS has no platform AC-3/E-AC-3 decoder, so Original MKVs
with AC-3 audio use a minimal arm64 build of the official Media3 1.4.1 FFmpeg extension. Keep its
exact-source and LGPL material in `app/third_party/media3-ffmpeg` and `assets/licenses` with the AAR.

The Offline manager updates live byte/speed labels and progress bars in place rather than replacing
the queue tree. It coalesces a transfer's closely spaced state/progress/artwork notifications for
180 ms and preserves the selected row or scroll position for a structural update.
`OfflineRepository.setState` suppresses identical waiting/paused updates, so a failed stream's retry
loop cannot broadcast a false visual refresh.

On Glass (#22) the downloaded pages take their title's colours by its backdrop (`PageArtwork.backdrop`,
the player's string, so they are likely known offline). A downloaded series is a book page: the cover
beside the words, Play or Resume as the white pill naming the episode, More as a round glass toggle, the
next episode as a continue card, notices as quiet chips and the seasons under a glass heading. A season
has its own heading over a quiet line. The download picker names the series with Download as the white
pill, each season under a glass heading, its choices on the side sheet. A queue row is a transfer's:
the state as a chip by the title, the figures on one quiet line, a failure in red.

Fresh installs from v0.1.1 onward expose both Hub address and masked bearer-token fields under
Manage > Ayaneo Hub. Uninstalling removes these app-private values. The screen points Jump Desktop
users to the gitignored `scripts/dev.env`; no token is embedded in the APK. The activity and every
service share one `HubClient.shared` instance, and `net/CredentialGate` sits in its interceptor, so
images, reader pages and offline transfers obey it too. One 401 stops all traffic for that token
(verified on the device: six section switches and a refresh reached the Hub as exactly one
rejected credential); a 429 with a long `Retry-After` holds everything until the ban ends; a token
not yet accepted sends one request at a time; and the offline queue waits instead of failing. A 403
is a missing scope, not a bad token (`FailureKind.FORBIDDEN`). Editing the token re-enables one
connection test.

## Reaching the hub from the handheld

The preferred address is now **`https://ayaneo-media-pc.tail737e96.ts.net`**. Pocket DS and the
media PC are members of the same tailnet, and Tailscale Serve proxies this private HTTPS origin to
the loopback-only Hub on `127.0.0.1:8791`. Dashboard listeners on ports 8920, 5055, 7878, 8989,
6767, 8080, Kavita 5000, Storyteller 8001, Prowlarr 9696, Readarr 8787 and BookKeeprr 3000 are also
tailnet-only; `services.<name>.web_url` contains those HTTPS addresses (all eleven since 2026-10-03).
The private live endpoint returns `200 ok`, and all dashboard listeners answer. ADB updated the
Pocket DS app base while preserving its token; users, Home, Library, activity, health, and artwork
return 200. The Jellyfin Manage card reaches its tailnet sign-in page without a certificate warning.

The previous `https://myjellydan.duckdns.org:55886` route remains a public fallback: the Sagemcom
router forwards WAN TCP 55886 to `10.100.102.8:443`, where Caddy proxies `/v1/*` to the Hub. Do not
remove that rule until the Tailscale route passes a full phone-hotspot test. Selecting Ayaneo Hub in
Manage preserves the bearer token, and the row remains available when health cannot load. Reserve
`10.100.102.8` for Wi-Fi MAC `04-EC-D8-46-3C-EB` while the fallback exists.

During development, **`adb reverse tcp:8791 tcp:8791`** can make the hub appear on the
device's own 127.0.0.1. It is an ephemeral fallback: every device or wireless-ADB reconnect
can discard the mapping. Do not seed a normal installed build with the loopback URL and then
interpret a FireDaemon Running state as end-to-end health. `network_security_config.xml`
permits cleartext for 127.0.0.1 and localhost only; everything else is HTTPS-or-nothing.
`scripts/dev.sh tunnel` reconnects wireless ADB and restores development mappings.

`scripts/dev.sh seed` pushes the URL and token in as intent extras. **`onNewIntent` must
handle it too**: launchMode is `singleTask`, so a second `am start` on a running app never
reaches `onCreate`, and seeding silently did nothing until that was fixed.

## The hub

Go 1.27, in `hub/`. Two single-file binaries, no runtime to install: `hub.exe` (11.6 MB) and
`hubctl.exe` (10.8 MB). Router is stdlib `http.ServeMux` — since Go 1.22 its patterns carry
the method, which is all this API needs, so there is no router dependency. The only external
module is `gopkg.in/yaml.v3`.

```
cd hub
go test ./...                              # config + auth
go build -o hub.exe ./cmd/hub
hub.exe --check --config <path>            # validate without starting
hubctl.exe token new --label pocketds      # issue a token, printed once
hubctl.exe doctor --config <path>          # probe every service
```

**The hub listens on 8791, not 8787.** 8787 is Readarr's default and Readarr is running on
this machine. Prowlarr holds 9696. Neither is in `KnownServices` yet; both are candidates for
later adapters.

### The Jellyfin index, and why it is not SQLite

The plan called for a SQLite index of ProviderIds, because **Jellyfin has no
provider-id query** — the public API exposes `hasTmdbId` and friends as *booleans*
only, so there is no way to ask which item carries tmdb 27205. That part is true and
the index exists.

The database is not needed. Measured on this library:

| | |
|---|---|
| Library size | **250 items** — 179 movies, 71 series |
| Full sweep | **362 ms**, 436 KB, one request |
| TMDB coverage | **99%** of movies (178/179), **98%** of series (70/71) |
| TVDB coverage | 67 of 71 series — the Sonarr join |
| Items with no ids at all | **1**, a mis-scanned file named `ETRG` after a release group |

SQLite existed to avoid re-sweeping a large library and to survive a restart without
blinding the app for fifteen minutes. At 362 ms neither concern survives contact with
the numbers, so `internal/index` is a plain map rebuilt every 5 minutes — which also
keeps the hub's only external module `gopkg.in/yaml.v3`. If a library ever grows to
where the sweep costs 30s, the answer is paging plus persistence and that type is the
seam for it.

The coverage number is the one the plan said to report up front, and it is the
opposite of the feared case: a messy library would make the app look broken through no
fault of the hub. This one is essentially perfect.

**"Not in the index" is not "not in the library."** `Index.Ready()` distinguishes the
two, and before the first sweep every lookup reports unknown rather than absent.

### Jellyfin quirks worth remembering

- **`/Items/Latest` answers a bare array**, where every other paged query answers an
  object with `Items` and `TotalRecordCount`. Decoding it as a page yields an empty
  list and no error.
- **PlaybackInfo applies `AudioStreamIndex` and `SubtitleStreamIndex` only with the matching
  `MediaSourceId`.** Without one it converts the default track: choosing English on a film whose
  default is Russian kept the Russian conversion until the app named the source (#24).
- **Ticks, not seconds.** `RunTimeTicks / 10_000_000`. Forgetting the divide produces
  numbers around ten million that look like bytes.
- **The season number on an episode is `ParentIndexNumber`**, which is not a name
  anyone guesses. `IndexNumber` is the episode.
- **An episode's own Primary image is a still from that episode** — usually a dark
  frame in a poster-shaped card. `SeriesPrimaryImageTag` is the show's poster and is
  what a Continue-watching card wants.
- **Jellyfin re-encodes images at quality 90.** A 360px-wide poster came back at
  **150 KB**, four times TMDB's equivalent; `quality=82` brings it to **44 KB** with no
  visible difference at this size. A 24-poster row went from 3.6 MB to 1 MB.
- **Favourites is empty on this install**, so that row hides itself rather than showing
  a blank labelled strip. It stays empty until something is starred in Jellyfin.
- Library views are read from the server rather than assumed: there are **five**, and
  two of them (`Marvel Movies`, `Marvel TV`) are collections rather than the whole
  library.

### Measured on this machine (2026-09-07)

All six services run on **localhost** — this PC is the media server.

| Service | Finding |
|---|---|
| Jellyfin | **10.11.8** with four enabled profiles: Adirimo, Dgdan, Hadas and Horim. **Forces HTTPS**: `http://…:8096` returns `307 → https://…:8920` with a self-signed cert, so configure it as `https://127.0.0.1:8920` with `insecure_skip_verify: true`. Its `/System/Info` embeds every plugin's full changelog and runs to hundreds of KB — read caps must allow for that (a "Jellyfin Enhanced" plugin is installed) |
| Jellyseerr | **2.7.3**. Its `/api/v1/status` needs no credential, so it is a free liveness probe |
| qBittorrent | **v5.0.4** — so it is the `torrents/start` / `torrents/stop` vocabulary with `stoppedUP`/`stoppedDL` states, **not** `pause`/`resume`. That was an open unknown; it is settled |
| Bazarr | **1.6.0**. `/api/system/ping` is unauthenticated. Its status nests the version under `data.bazarr_version`, unlike the *arr apps |
| Radarr | **6.3.0.10514** |
| Sonarr | **4.0.19.2979** |

### Where the credentials live

**Not environment variables.** A Windows service does not inherit a user's
environment, so env vars would have to be machine-wide -- which puts the API keys in the
registry for every process to read. `${env:}` expansion still works, but the supported path is
`hub.secrets.yaml` beside `hub.yaml`, deep-merged at load and ACL'd to SYSTEM, Administrators
and the owner.

`deploy/windows/collect-secrets.ps1` reads the keys straight out of each service's own config
and writes that file without printing anything:

| Service | Source |
|---|---|
| Radarr / Sonarr | `%ProgramData%\<app>\config.xml`, `<ApiKey>` |
| Bazarr | `%ProgramData%\Bazarr\config\config.yaml`, `apikey:` |
| Jellyseerr | `C:\jellyseerr\config\settings.json` (Docker bind mount), `main.apiKey` |
| qBittorrent | none needed -- see below |
| Jellyfin | **must be created by hand**: Dashboard -> API Keys. Its keys live in `jellyfin.db` |
| Hardcover | **by hand, and optional**: hardcover.app > Settings > API, as `services.hardcover.api_key` in `hub.secrets.yaml` (with or without its `Bearer `). With no key the hub makes no request to it (#39) |

**qBittorrent needs no credentials here.** `WebUI\LocalHostAuth=false`, so a loopback
connection is unauthenticated -- confirmed by the API answering without a key. Validation
allows a credential-free qBittorrent **only** when the host is loopback or private.

> Trap worth remembering: if qBittorrent is ever put behind a reverse proxy on the same
> machine, the proxy's connection looks like localhost and `LocalHostAuth=false` would make
> it world-accessible with no password. It is safe today only because port 8080 is
> forwarded directly, and remote requests do get a 403.

`insecure_skip_verify` exists **only** because Jellyfin forces a self-signed cert on loopback.
Validation refuses it for any host that is not loopback or RFC1918 — on loopback there is no
position from which to intercept the connection, so verification buys nothing there and
everything over a real network.

### Badges and card geometry

| State | Label | Colour | Means |
|---|---|---|---|
| `available` | In library | green | you have it |
| `partially_available` | Partial | **Ultra Violet** `#5B1E96` / `#7B34C4` | some of it. Not a shade of green, because a series missing half its episodes is not "you have this". Violet was chosen over the indigo and near-black in the same palette because a badge sits *on top of a poster*: indigo is too close to the card surface to register, and the darkest violet reads as a hole punched in the artwork |
| `requested` | Requested | amber | asked for, awaiting approval |
| `processing` | **On the way** | amber | approved, an *arr is working on it. "Processing" described the software rather than the thing you asked for |
| `downloading` | Downloading | amber | bytes actually moving; the card also draws a progress bar |
| `blocked` / `deleted` | Blocked / Deleted | red | |

Discover rows use a **150dp** poster in a **104dp** card; the search grid uses 150dp across
7 columns. Derived, not guessed: the usable area is 853x456dp, and after the navigation rail, hint bar,
search field and status line there are ~340dp left, so a row has to fit inside ~200dp for the
next one to peek below it.

### Search ranking

TMDB orders results by popularity-weighted relevance, which occasionally put a documentary
called *The Mentalists* above the series you typed. `rankSearchHits` re-tiers them —
exact title, prefix, whole-word, substring, no match — and sorts **stably**, so within a tier
TMDB's own ranking still decides. Nothing is filtered: a near-miss is sometimes exactly what
someone meant, and hiding it is worse than showing it second.

`normalizeTitle` drops a leading article, so "The Mentalist" and "Mentalist" both count as
exact answers to "the mentalist" and both sort above "The Mentalists".

### Wording

Sentence case everywhere — *Show all*, *Hide done*, *Find release*, *Search again*. Title Case
on one chip makes it the odd one out.

No `…` on the Request button. The ellipsis is a desktop convention for "this opens a dialog";
on a console-style UI nothing else uses it, so it is noise.

`processing` is labelled **On the way**, not "Processing" — the old label described the
software rather than the thing you asked for.

### Detail screen layout

The pipeline runs **horizontally**, not as a stacked list. Five vertical rows ate the space
the overview and cast need, and on a 7-inch landscape screen horizontal is the axis there is
plenty of. Each stage is a chip — glyph, short label, colour — and only the *active* stage
carries a percentage; the rest of the detail lives in the summary line beneath, so the strip
stays a glance rather than a wall.

Stages carry both `label` (full, for a list) and `short` (one or two words, for the strip).

Cast is a horizontal row of focusable cards below the overview, capped at 12 in billing order.
Each opens that performer's filmography.

**The actions are real focusable buttons**, not only hint-bar chips — "Request…" and "Find
release", under the stage strip. The hint bar tells a pad user what A/B/X/Y do, which is
necessary but invisible to anyone driving the trackpad, and it left the screen's main actions
with nothing on it you could point at. `requestInitialFocus` targets the first button rather
than the first cast card, which is below the fold.

**The pipeline summary is hidden unless a stage is active, failed or stuck.** With nothing in
motion it read "Not requested" directly under a strip of five pending chips that already said
exactly that.

A title page is the prototype's (#11): the backdrop across the top of the page, 330dp
tall and fading through `FadedImageView.TITLE` under the tabs; the words at the left (the
Bricolage title, the facts, the watch line in the accent, the overview); a white Play or Resume,
Start over as a glass pill on a film or episode you can resume, and round glass toggles that
turn white while on; underline tabs with the accent underline; seasons as glass chips; episodes
as tiles with UP NEXT; cast as portraits; Details as words. A title you don't have reads "NOT IN
YOUR LIBRARY" (or where it has got to, in the accent) over its title, with the pipeline as glass
chips under the facts and the stage under way pulsing amber; Request opens the request form as a
glass side sheet and the release picker's rows are glass.

### The floating trailer window

A trailer plays in a window you can move and resize while the app stays usable underneath.
Hosted by **HubActivity**, not by a screen, so backing out of a detail page does not stop it.

**Not Android's system PiP.** That shrinks *this whole app* into a corner of the launcher, which
is the opposite of what was wanted.

**Corners, not free dragging, for the gamepad.** Four snap positions are one press each; nudging
a window pixel by pixel with a D-pad is miserable. A pointer still drags freely and snaps to the
nearest corner on release, so both input paths get what suits them from one piece of state.
`state/FloatingWindow` is pure and tested — clamping, the 16:9 shape, what "down" means when you
are already at the bottom.

The floating shell has one compact 48dp toolbar above a true 16:9 WebView viewport. It uses
rounded corners, a shadow, and an accent outline only while trailer control mode is active.
Fullscreen, open-in-YouTube, and close remain real toolbar controls with accessible labels. The
window stays 12dp inside the content area, clear of the navigation rail and bottom hint bar; size and corner
changes animate for 180ms without replacing or reloading the WebView. Title dragging begins only
after touch slop, excludes the toolbar controls, clamps to the content area, and snaps to the
nearest corner. The duplicate instruction footer was removed because the app hint bar already
shows the gamepad controls.

**An explicit control mode, not a focus target.** The window lives in the chrome layer, outside
the content that the focus guard confines movement to, and letting directional search cross that
boundary is exactly the class of bug that had the selection escaping into app chrome. Opening a
trailer takes control (D-pad moves, Ⓐ fullscreen, Ⓨ size, Ⓑ close, Ⓧ back to the app); **Start**
takes control again later.

#### Getting YouTube to play at all — three attempts, two dead ends

Worth recording, because the errors are not self-explanatory and the obvious approaches fail:

| Approach | Result |
|---|---|
| `loadUrl("https://www.youtube.com/embed/KEY")` | **Error 153**, "Video player configuration error" — no page origin at all |
| A hand-written `<iframe src=…>` inside `loadDataWithBaseURL` | **Error 152** for *every* video, official studio uploads included. The base URL sets the page origin, but WebView sends no Referer for the iframe's own request |
| The IFrame Player API (`iframe_api` + `YT.Player`) | **Still 152 on this device**, with and without an `origin` param, and with `; wv` stripped from the user agent |

So the embed is refused by this WebView outright. The player asks `Bridge.onPlayerError`, and the
answer to any refusal is the same: fall back to **`m.youtube.com/watch?v=KEY`**, which is an
ordinary website visit and plays fine. Measured: refusal at +700ms, playing immediately after.

The embed attempt is kept rather than deleted, because it is the nicer player wherever it does
work — but a "Loading trailer…" panel covers the WebView until either the player reports ready or
the fallback has loaded, so YouTube's error card never flashes up. An 8s timer uncovers it
regardless, so a wedged load shows YouTube's own message rather than our placeholder forever.

**A Trailer button, only when there is a trailer.** `Detail.Trailer()` prefers TMDB's
`relatedVideos` entry of type `Trailer`, then `Teaser`, YouTube only — behind-the-scenes and
featurettes are deliberately not offered, because a button labelled "Trailer" that plays a
costume-design interview is worse than no button. Measured: Gran Torino has one, The Mentalist
has nothing but behind-the-scenes clips. Launched with a plain `ACTION_VIEW`, so a browser is a
fine fallback when the YouTube app is absent.

The toolbar's **Open in YouTube** action is also the path to system PiP: start playback in
YouTube, then leave YouTube. The installed YouTube app has PiP allowed on this device. Android
does not let this app force another app's Activity into PiP or choose that PiP window's size.

**`focusOnShow` is true here now, and the buttons claim focus once the detail lands.** It was
false because the only focusable thing used to be the cast row, below the fold — focusing it
scrolled the title out of view. The buttons sit beside the poster, so focusing them moves
nothing. `showCurrent` still asks a frame after the push, which is before the data arrives, so
`buildActions` takes focus itself if nothing else has it.

### Filmographies

`GET /v1/person/{id}` returns a performer and their credits as ordinary `SearchHit`s, so the
app reuses the poster grid unchanged. Two pieces of cleanup make it usable:

- **Self-appearances are dropped.** TMDB's combined credits are full of chat-show guest spots,
  and those carry the *show's* popularity. Measured on one real actor: 67 credits in, 39 out.
- **Duplicates are collapsed** — a recurring show arrives once per credited episode block.

Sorted **newest-first by default**, not by popularity, and that is measured rather than
assumed: sorting Timothée Chalamet by popularity yields *The Tonight Show, Law & Order, The
Late Show, Late Night, SNL* before any film he acted in. `?sort=popularity` is still there and
the app toggles it with Ⓧ.

**Availability is `unknown` on these, never guessed.** Combined credits carry no `mediaInfo`,
so the hub genuinely does not know whether a title is in the library — and a wrong green badge
is worse than no badge. The app renders no badge for `unknown`.

### The download join

`/v1/activity` merges three services into one list. Verified live on this stack: **1/1 join
rate**, Sonarr's uppercase `downloadId` matching a lower-case qBittorrent hash exactly as its
source said it would.

- **Torrents are indexed by all three hashes** (`hash`, `infohash_v1`, `infohash_v2`), because
  a v2 or hybrid torrent has two and which one an *arr recorded depends on how it was added.
- **Sonarr queue rows sharing one `downloadId` are one transfer.** A series pack can produce
  one queue row per episode (23 live rows for The Mentalist all named the same 40.8 GB torrent).
  `/v1/activity` folds those back into one series row, reports the episode count, and uses the
  `qbit:{hash}` identity accepted by Start/Stop/Delete while retaining the Sonarr queue metadata.
  Control choices use a separate `clientStage`, so an import-stuck row still switches from Stop
  to Start when its qBittorrent job is stopped.
- **Detail pipeline transfer state comes from the live join.** Jellyseerr's tracker can remain
  on Grab after qBittorrent is already moving bytes. Media detail matches activity by the
  Radarr/Sonarr id, or TMDB/TVDB for titles added outside Jellyseerr, and the app polls every
  four seconds while a pipeline stage is active.
- **qBittorrent is v5.0.4 / API 2.11.2** here, detected at startup by reading the major
  version rather than sniffing state names — which only tell you about the torrents that
  happen to exist. Live states seen: `stoppedUP`, `stalledDL`, `missingFiles`, `uploading`;
  **no `paused*` names at all**. Both vocabularies are still handled.
- **Finished torrents are hidden by default** (`?all=true` shows them). This stack has 30
  torrents and 1 queue row; burying the live one under 29 seeders makes the screen useless.
  A *broken* torrent is never hidden, however complete it is.
- **Problems sort first**, then downloading, importing, queued, stopped, seeding. Someone
  opening this screen is asking "what is wrong" or "how long".
- `etaSeconds` is **-1** when unknown. qBittorrent reports `8640000` (100 days) when it has
  nothing to estimate from, and rendering that next to a stalled torrent is worse than
  silence.

### Upstream quirks worth remembering

- **Jellyseerr answers 500, not 404,** for a TMDB id it cannot find, with the body
  `{"message":"Unable to retrieve movie."}`. The hub translates that to a 404 — passing it
  through tells the app a service is broken when the title simply does not exist.
- **Jellyseerr does not reject a duplicate request.** Asking again for something already in
  the library creates a new, auto-approved request rather than returning a conflict. The
  duplicate/quota/blocklist mapping in `writeRequestError` is still right for the cases that
  *do* fail, but "you already have this" is not one of them — the app relies on `actions` not
  offering Request in the first place.
- **`POST /api/v1/request` takes the TMDB id**, not Jellyseerr's internal media id. The
  classic mistake, and it yields a confusing 500 rather than a validation error.
- **qBittorrent does not flip a torrent's state synchronously with the reply.** Measured on
  this stack: `POST /torrents/stop` answered in 11ms, and a read 24ms later still reported
  `queuedDL`. So invalidating the hub's cache and refetching immediately is *not* enough --
  it faithfully reads back the old state. `PollSchedule.SETTLE_MS` keeps the downloads screen
  polling fast for 8s after any action, which is the difference between a stop button that
  works and one that appears to do nothing for ten seconds.
- **Jellyseerr rejects `+` as a space in a query string** with a bare 400. `url.Values.Encode`
  in Go writes spaces that way, so **every multi-word search failed** while every single-word
  one worked — "gran torino" 400, "dune" 200. `httpx.encodeQuery` substitutes `%20`, which is
  safe rather than approximate: `QueryEscape` writes a literal plus as `%2B`, so any `+` left
  in the output is always an encoded space.
- **A Radarr release's `downloadUrl` embeds the Prowlarr API key in clear text** — 40 of 40 in
  one measured search. The hub therefore never forwards a release's `guid` or `downloadUrl`;
  the app gets `sha256(indexerId + guid)[:8]` and grabs by that. Beyond the leak, handing over
  a URL would let any token make the hub fetch an arbitrary address.
- **`mediaInfo.externalServiceId` is null for anything not added *through* Jellyseerr** — true
  even for a series plainly in the library. So the *arr id is resolved by asking Radarr
  (`/api/v3/movie?tmdbId=`) or Sonarr (`/api/v3/series?tvdbId=`) directly, which is the only
  authority on what they hold.
- **Radarr refuses everything once a title is complete**: an interactive search on Gran Torino
  returned 100 releases, 99 of them rejected with `Existing file meets cutoff: Bluray-1080p []`
  — trailing empty brackets and all, which is Radarr's own text and is shown verbatim.
- **Sonarr and Radarr disagree about `indexerFlags`.** Radarr sends an array of strings
  (`["G_Freeleech"]`), **Sonarr sends an integer bitfield** (`1`) — same API version, same field
  name, same concept. Decoding Sonarr's number into a `[]string` failed and the hub reported it
  as "sonarr is not responding", so a healthy service looked down on the very screen meant to
  tell you which service is misbehaving. `arr.IndexerFlags` accepts both, and a decode failure
  now says `upstream_unreadable` — "the hub could not read Sonarr's response" — because that is
  the hub's bug, not the service's.
- **A per-service timeout is a hard cap applied *inside* every call**, so a generous handler
  deadline cannot loosen it. Interactive search is slow when everything is healthy, so it uses
  `httpx.Base.WithTimeout` for its own 110s budget rather than the 8s default.
- **Go's `ServeMux` percent-decodes wildcard path segments**, so `/v1/downloads/{id}` hands
  back `qbit:abc` from `qbit%3Aabc` — verified against the live hub with both forms. It does
  *not* sanitise them: `qbit%3A..%2F..%2Fetc%2Fpasswd` arrives as `../../etc/passwd`, and only
  `parseActivityID`'s strict hex check rejects it.

### Caching

`internal/cache` is a TTL store with three behaviours beyond a map, each earning its keep:
**singleflight** (ten concurrent misses for one key make one upstream call),
**stale-while-revalidate** (past the TTL, serve immediately and refresh behind, so a slow
service never blocks a screen), and **stale-if-error** (when an upstream is down, something
from ten minutes ago plus a warning beats an empty screen). The clock is injected, so the
tests advance a variable instead of sleeping.

Measured: a cold `/v1/search?q=interstellar` is **222ms**, the same query cached is **2ms**.

`policy.go` is the one place TTLs live. The numbers come from how fast each thing can
actually change:

| Data | Fresh | Why |
|---|---|---|
| TMDB metadata | 24h | A 2021 film will not change its runtime |
| Availability / progress | 20s | "63% done" at ten seconds old is fine, at ten minutes it is a lie |
| Search | 60s | People re-run the same query while deciding |
| Trending | 30m | Changes on TMDB's schedule, not ours |
| Library page | 60s | |
| Watched state / resume | 15s | Changes when *you* watch something |
| **Transfers** | **3s, never stale** | A torrent list from two minutes ago is not out of date, it is wrong — it shows finished downloads as running. Showing nothing is better, because nothing does not mislead |
| Images | 30 days | A TMDB poster path is immutable; the path changes when the image does |

**The subtlety that makes naive caching wrong**: Jellyseerr returns stable TMDB metadata and
volatile `mediaInfo` in the same object. Cache the whole thing for an hour and a title you
requested this morning still reads "not in library"; cache it for 20 seconds and you re-fetch
TMDB constantly for data that has not changed in years. They get separate TTLs and are
recombined.

Responses carry a `cache` block (`hit`, `ageSeconds`, `stale`, `degraded`) so the app can say
"showing results from 4 minutes ago" rather than silently lying. The **raw upstream response**
is what gets cached, not the rendered one — `actions` depend on the caller's scopes, so a
read-only token must never be served a response computed for one that can request things.

### What H0 actually enforces

- Refuses to start (exit **78**, `EX_CONFIG`, so a service manager stops retrying) on: no
  tokens, a placeholder token *or the hash of one*, a token under 32 chars or below 3.5
  bits/char of entropy, a non-loopback bind without a declared TLS terminator, an unknown
  service name, a missing `base_url` or key. Every message names the setting and the fix.
- Stores only the **SHA-256** of each token; `Verify` is constant-time and does not stop at
  the first match, since returning early leaks their order.
- The interactive API rate limit is keyed on the **token label**, not the IP, so one busy
  device cannot starve another behind the same NAT. Authenticated, session-owned playback
  routes have a separate 3600 rpm / 240 burst transport budget; HLS segments, byte ranges,
  subtitles and progress events therefore cannot consume the 90 rpm / 30 burst screen budget.
  Artwork (`/v1/img/`) has its own 1800 rpm / 150 burst budget: a 60-title Library page asks for
  60 posters at once, and on the screen budget the overflow came back 429 and stayed blank.
  `Server.limiterFor` is the one place a path picks its budget.
- Auth failures ban the source, and hammering through a ban **extends** it rather than
  resetting the clock. `X-Forwarded-For` is honoured only from a declared trusted proxy —
  taking it from anyone lets a guesser spoof a fresh source per attempt.
- `Secret` renders as `[redacted]` through `%s`, `%v`, `%+v`, `%#v`, JSON and YAML. The only
  way out is `.Reveal()`, which is trivial to audit.

Verified end to end against the live stack: no token → 401; five wrong tokens → 429 with
`Retry-After: 900`; and **the correct token is then refused too**, which is the point — a
guesser who eventually lands on it still gets nothing. Bans are in-memory, so restarting the
service clears them; that is also how you rescue yourself if you lock yourself out.

Neither the `/v1/health` response nor the logs contain any service key or the bearer token —
checked by grepping both for the real values.

### Credential locations on this machine

- `C:\ProgramData\AyaneoHub\hub.yaml` — config, no secrets, safe to read
- `C:\ProgramData\AyaneoHub\hub.secrets.yaml` — the five API keys, ACL'd to SYSTEM /
  Administrators / owner
- `scripts/dev.env` — `HUB_URL` and `HUB_TOKEN` for `dev.sh seed`. **Gitignored.** Update
  `HUB_URL` to the DuckDNS address once Caddy is fronting the hub

Re-running `collect-secrets.ps1` on an already-locked file cannot re-apply the ACL without
elevation, so it now detects that the permissions are already correct and leaves them alone
rather than failing.

A1 ends where it was meant to: six sections, grid and list navigation, focus ring, collapsible side rail,
hint bar and status strip, all driven by gamepad or trackpad, with **no network code at all**.
A2 swapped the placeholder grid's adapter for real data behind `HubApi`/`FakeHubApi` (the grid itself went in #22).

## Exposure audit (2026-09-07)

`myjellydan.duckdns.org` = 89.139.208.26. Reachable from the internet:

| Port | What | Auth | Action |
|---|---|---|---|
| 443 | Caddy | — | Keep. The hub goes behind it as a new site block |
| 8989 | Sonarr | 401 on API | **Un-forward.** Reach it through the hub instead |
| 8080 | qBittorrent WebUI | 403 on API | **Un-forward.** Highest risk of the three |

Everything else checked was closed (80, 8096, 8920, 5055, 7878, 6767, 9117, 9696, 32400, 22,
3389, 445). Neither exposed service is open — both reject unauthenticated API calls — but a
login page on the public internet is brute-forceable, and qBittorrent's WebUI can set the save
path and run a program on torrent completion, which makes any future CVE in it code execution
on the machine that holds every API key.

---

## Shared building blocks

**One behaviour, one implementation.** Before writing a loop, a card, a label or a rule, use the
owner below; if it does not fit, change the owner (with a test) so every screen gets the fix.
Most of these exist because several screens had drifted copies of the same thing.

| Behaviour | Owner |
|---|---|
| Talking to the hub | `HubClient.shared(context)` -- one per process. `net/CredentialGate` in its interceptor: one 401 stops all traffic, a ban's long `Retry-After` holds it, 403 is a scope (`FailureKind.FORBIDDEN`). A failure keeps the hub's own `code` and `reason` (`HubResult.Failed`), for a caller that branches on them; `audioHttp` is the audiobook tracks' pool (#19) |
| One request at a time, busy flag that cannot stick | `state/JobSlot` |
| Asking again on a timer | `state/Poller` + `PollCadence` (backoff, settle window, hidden stops) |
| Status line wording and tone | `state/StatusText` + `TextView.showStatus`. A line shows only with news (`StatusText.shows`: data a minute or more old, partial, degraded or failed, or a `StatusText.notice` such as why a list is empty), as a quiet glass chip round its words; Home's sits in the hero's top corner, clear of its words. A page's own line under its heading ("94 unread notifications", "All 12 running") is `TextView.showSummary`: always shown, plain words |
| Numbers, times, sizes | `state/Fmt` (`Fmt.version`: one "v" before a service's version, however it writes it) |
| How far through a book, as a percent | `Fmt.readingPercent` / `readingPercentLabel`: the floor, 1 to 99 while under way and 100 only when finished, so a book's facts, its Resume button, its card and the reader agree (#16; the facts once said 1% where Resume said 2%) |
| Resume point / finished / not started | `playback/ResumeRules` (mirrors the hub's `decideWatchPosition`) |
| Watched tick vs progress bar | `ResumeRules.showsWatched` / `watchLabel`: a saved position wins, because watched + position is a rewatch |
| Which watch is newer, local or server | `OfflineCatalogProgress.newer` / `fromServer` |
| Titles requested this session | `state/RequestedTitles` (applied at card bind) |
| Patching cards already on screen | `state/HitRefresh` |
| Loading any hub image | `ui/Artwork.loader` / `bind` / `bindHub` (always cancels a recycled view's old request) |
| The size of a hub picture to ask for | `HubEndpoints.sized(path, width)` for artwork shown large; `smallest(path)` for artwork drawn tiny or blurred (TMDB w92, Jellyfin w=180, a reading cover as it is). A Jellyfin item's picture by id: `HubEndpoints.jellyfinImage(id, type)`, so one picture is one string in the colour caches |
| Watch progress | `ui/ArtworkProgressView` on artwork, `ui/ProgressLine` under titles |
| An episode | `ui/EpisodeCardView`; names via `EpisodeLabel.of` / `code` / `season` ("S1E4 · Title", "Specials") |
| A season or poster-shaped detail card | `ui/DetailArtworkCardView` |
| A value menu / a destructive confirm | `ChoiceOverlay.pickValue` / `confirm` (harmless answer first); `ask` for a question with a few answers. On a side sheet a question still opens as the centred card, beside it; `isOpen`, `onPad` and `dismiss` answer for both |
| A side panel's look and parts | `ui/SidePanelView`: a full-height sheet at the right edge (centred card for `confirm`), heading and small round close; `choice` and `setting` (label, value, chevron) rows share a raised card until a `section`, `startGroup`, `note` or hand-added view starts the next; `choice(leading =)` puts a picture before the words; tabs are a `BlobSegmentedView`. `group()`, `reveal(view)` and `resetBody(keepScroll = true)` for rows a caller draws itself (the Glass request form). On a page that draws under the top bar a side sheet starts below it (`ui/TopChrome.overlap`; HubActivity registers its bar) |
| Anything drawn over video | `Theme.onVideo` (the dark palette with the media accent, whatever the app theme) |
| A dashboard's pieces: status dot and its colour, a disk with its bar, a figure card, a focusable row, a state chip | `ui/DashboardParts` (`dot`, `stateColor`, `disk`, `stat`, `row`; `chip(context, label, Tone)`: Soon, Aired, In library, Downloading, Stuck, and `releaseChip` for the calendar; the prototype's `.st`, whose colours `glassFill` / `glassInk` give anything else saying the same, such as the release picker's tiles): Activity, Server monitor, Services, Notifications, Upcoming, the transfers, the Offline manager's rows (`offline/OfflineQueueLabels`: a download's state as a chip, and its line of figures) |
| A service's logo | `ServiceLogo.resource(service)` (and `bind`): one set, drawn for the dark page |
| A torrent's release name as words ("Dark Matter (2024) · S2E6 · 1080p") | `model/ReleaseNames.readable`; `ActivityItem.headline` uses it when the *arr has no title |
| How long the PC has been up ("1 day 4 hours") | `Fmt.uptime` |
| A pill that is a filter, lit while on | `PillButton.setPrimary` (a new background otherwise resets the padding); a Glass pill turns white in place (`GlassButtonBackground.lit`) |
| A pushed page's header | `HubActivity.pageTitle`: a round back mark and the screen's `title` in the heading face; a page with its own heading sets `showsOwnTitle` |
| A menu's cursor after a submenu | `SidePanelView` remembers the row last chosen per menu and tab; pass no start row and Back lands where you were |
| On/off action icons (watched, favourite, downloaded) | `MediaActionIconDrawable.of` (`MediaActionIcon.isOn`): filled accent when on, outline when off; `DOWNLOADING` is a progress ring. On a glass toggle `onGlass`: dark on its white face while on, white on glass while off |
| Reacting to offline downloads changing | `offline/OfflineChanges` (start in onShow, stop in onHide) |
| Where to carry on, as a card of its own (the prototype's `.cont`) | `ui/ContinuationCardView` (`portrait` for a cover, `side` for the play disc's face): glass with 18dp corners and the ring hugging it, a bar in the accent; a Books series' book being read and a downloaded series' next episode. `downTo(view)` says where Down goes: the book being read (else the first) or the first season, not the item under the card's middle (#23, #26) |
| Sorting a library | `ui/LibrarySortControls` (field button + one-press direction, two control buttons); wording from `SortPreference.directionLabel` |
| A series' books as cards / their labels | `screens/library/SeriesBookStrip` + `SeriesBookLabels` ("#2 · 40% · Audio"); series page, author page and a book's own page |
| A book's facts line | `ReadingBookFacts.line` ("Book 6 of Red Rising · 2023 · 735 pages"); how far through: `ReadingBookFacts.progress` ("49% · page 363 of 735"); `eyebrow` over a page's title ("Book 6 · Red Rising", "Series · Pierce Brown", "Comic · My Marvelous Year", the library from `ReadingLibraryNames`), `miniLine` under a book also being read ("Blake Crouch · 3%"), `formats` (ebook, audiobook, read along, always in that order), `continueLine` on a series' continue card ("Book 6 · 49% · page 363 of 735") |
| A book's place in its series on a card | `ReadingWork.cardSubtitle` ("Red Rising #6") and `seriesNumber` |
| A book's own page under the owner's layout "1" (#39), and what it writes to the hub | `DetailHeaderView.reading` (set for `ReadingBookPage.isBook`: a book that is not a series' page, a comic or a manga; the others keep their page). Right of the cover: eyebrow, title, author, `ReadingBookPage.facts` ("2006 · 541 pages · 24h 38m · 4.5 from readers", `community`), `ui/ReadingFormatRowView` over `ReadingFormatChips.of` (Audiobook, Ebook, Read along as icon and name: the accent and a control that opens that format at your place, grey and no stop for the pad when the book has none, a tap on it says why), Resume (`ReadingResumeLabel.of`: "Resume · Chapter 14 · 32%", "Read book", "Read again"; the chapter is the saved text place's `title`, never for a listening place, and a narration picked from the menu is named until the book is started) with a round ⋯ and `ReadingBookPage.genres` on one quiet line. Under the cover: `ui/StarRatingView` (Ⓐ rates with the cursor, Left and Right move it, a finger rates the star under it; the star you already gave takes the rating away, `ReadingStars`), `ReadingBookPage.finished` ("Finished Sep 2025 · 2nd time", "· rate it?" when finished and unrated) and `shelves` in the accent. The ⋯ is `ReadingMoreMenu` (Finished, Mark unread when finished, Choose narration when there are several readers, Want to read, Add to a list, Remove offline copy, Delete from server…). Finished is `FormOverlay(centred = true)` over `ReadingFinished` ("When did you finish?": Month and Year as ‹ value › rows preset to this month, or to the month kept when the book is already finished, Cancel and Mark finished, which left and right walk between). Every write is `HubApi.updateReadingYou` with a `ReadingYouPatch` (a body built by `ReadingYouEdits`: a key present sets, `YouEdit.Clear` sends null, absent keeps; never a hand-written JSON); the page changes at once and `ReadingWorkScreen.saveYou` sends them one at a time in order, on a scope that outlives the page, and on a failure says so and reads the page again. Mark finished also marks the book read on this device (`ReadingCompletionRepository`, as the page's read toggle always did: the hub has no route that marks it read in Kavita or Storyteller); Mark unread undoes the date and count only when this visit set them |
| Books Library's view: Series, Authors, or every book on its own | `ReadingLibraryGridScreen` (`view`; Books asks the hub for `view=works`); each view's order in `DomainPreferences` (`sort`, `bookSort`, `readingView`) |
| A person as a round portrait, the ring round it | `DetailArtworkCardView.portrait` (an author, a title's cast row in `CastRowView`) |
| A cast portrait's filmography, on either title page | `PersonScreen.open(host, api, person, ringVisible)`: TMDB's credits for a person the hub knows there (`CastRowView.Person.tmdbId`, a Library person's from the hub, #27), else it says why (#26) |
| Books Home's top: the book being read, the series being read | `screens/home/ContinueReadingView` (Resume reading opens `ReadingWorkScreen(openReader = true)`; no card, the cover at full size with the words beside its foot, Resume reading gold), `SeriesStackView` fed by `ReadingShelves.yourSeries` |
| A series as a fan of covers (Books Home, the series page) | `ui/CoverFanView`; which covers from `ReadingShelves.fanCovers` (up to four), the book being read from `onNumber`; "On #6 · 1 of 6 finished" from `ReadingBookFacts.seriesProgress`. It is the prototype's fan: 64dp covers leaning about their feet, the book being read on top at the right, the outer two leaning further with focus (`spread`); at a page's edge it stands in by `LEAN_DP`, how far its outer cover leans, or the screen cuts it |
| Books Home rows | `ReadingShelves`: `onePerSeries`, `nextInSeries`, `BUILT_IN` (rows the app fills; list actions only on the person's own) |
| Reading times from the hub | `ReadingShelves.timestamp` (Storyteller writes `2026-09-27 03:16:47`, UTC with no zone) |
| Hub: an author page id | `readingAuthorRef` (Authors view and a book's author link) |
| Hub: a Storyteller title | `reconcileStorytellerBook` (file-name titles, `withoutSeriesNote`) and `storytellerPeople` (writers, not narrators, as First Last) |
| Activating a focusable on the first tap | `activateOnTap`, never `setOnClickListener` on a focusable |
| Pick one of a few (tabs, seasons, a setting's value, Media/Books) | `ui/BlobSegmentedView` (`PILL`, `ACCENT`, `UNDERLINE`, `CHIPS`: Glass seasons, each its own glass pill with the chosen one white; `useGlassTrack()` for a glass capsule; `followFocus` for tabs that switch on focus); geometry in `SegmentGeometry` (`gap` between pills) |
| A line mixing a Hebrew title with English facts ("… פרק 6 · 11 min") | `ui/Bidi.join` / `isolateParts` (each part isolated); captions under cards align to the view's start, not the text's |
| Title and heading type | `ui/Type` (`typeRole(Type.Role.HERO …)`); body text is Figtree from the theme, never set per view |
| An accent and the ink drawn on it | `AccentPreset.color` / `ink` via `Theme.colors`; Books default to gold (`AccentPreset.defaultFor`) |
| A controller button drawn in the hint bar | `ui/KeyGlyphDrawable` |
| A small glass button in a row of controls (Favourites, Sort, Mark all seen, a round search) | `PillButton.control(view, colors, icon, round)`: the prototype's 32dp `.cbtn` |
| A rounded action (Play, Details, Continue reading) | `ui/PillButton` (the ring keeps a gap). As a detail page's first action it takes `marginStart = -RING_DP`; `DetailHeaderView`'s action row leaves that room, so the pill lines up with the title and its ring is not clipped. The main action (`primary`) takes its side's face (`side`; `mainFace`/`mainInk`): white with dark words on Media, the Books accent (gold) with its ink on Books -- Resume reading, a book's Read or Resume, a series' Continue and its card's disc, Find a download and the request sheet's foot; the others glass that follows the page, 11dp corners |
| A Glass button's face: glass, or white while lit | `ui/glass/GlassButtonBackground` (`attach(view, colors, corner, ring, lit)`, then `lit`): `PillButton` and `DetailStyler.glassToggle`, a title's round toggles that turn white while on; one GlassPage.follow per view |
| A rounded or round drawable from palette colours | `ThemeGradientDrawable.rounded` / `.oval`: inside `ThemeGradientDrawable().apply {}` a bare `colors` is GradientDrawable's own array |
| Fading the page colour into artwork | `ui/ScrimDrawable` |
| A vertical list of places with the blob (Settings' sections) | `ui/SideNavView` (each place has its icon, `Item.icon`, and the chosen one is lit white) |
| A settings panel, an on/off row, colour swatches | `ui/SettingsCard`, `SwitchRowView`, `SwatchRowView`. The card is the prototype's (`.scard2`, `.dcard`): page glass with 15dp corners, and `attention(true)` edges it in amber; Activity, Server monitor and Settings take them from here |
| How subtitles look | `SubtitleSettings` (style, size, lift) applied by `playback/SubtitleLooks` in the player and the Settings preview |
| When the up-next card shows, what gets a Skip button | `playback/UpNext` (`cardAt`, `skipLabel`); the card is `UpNextCardView` |
| A service's name on screen and its place in a list | `model/ServiceNames.display` / `rank` (`rank(id, books = true)`: the reading services first) |
| What the Activity dashboard shows: what needs attention, the short agenda, service lines, low disk space, an address the Pocket can open | `screens/downloads/ActivityDashboard` (`headline`: Glass's line under "Activity") |
| The calendar: weeks and their labels, releases grouped per title and day, whether one has arrived (Soon, Aired, Missing, In library) | `screens/discover/UpcomingPresentation` (Upcoming and the Activity dashboard) |
| Hub: intro/credits segments | Jellyfin's own, else `segmentsOrChapters` from whole chapter names ("OP", "Ending", "Credits") |
| Paging with L2/R2 | `HubActivity.page` (moves focus with the scroll) |
| Up from the top of a screen that walks its own columns | `ScreenHost.focusTabs()`: the tabs, as Up from the top of any page reaches them (Activity's left column reached "See all" in the next) |
| Where Back, a tab or Down from the tabs returns focus on a page | `ui/FocusPlace` (#23). The host changes pages only through `show` (the page left is closed to focus while its focus is cleared, and what had focus is marked as its place, Android's default focus) and `settle` (focus back on the place, else the page's start); a step on the page spends it, and a place that leaves the window is let go. A page that draws itself again carries the place with `across` (its focusables tagged, as a book page's actions are) or marks the new view with `mark`, and its `requestInitialFocus` asks `FocusPlace.focus` first |
| Switching between Media and Books | `nav/SidePages` (#18): each content tab keeps a stack per side. Choosing a side takes the other side's pages off every tab, kept alive as they were (`ScreenStack.park` / `restore`), and puts back the ones this side left, so switching back returns each tab to where it was. A page's side is its `contentDomain`, else the side it was opened on (`HubActivity.openedOn`); a page that follows the side itself (a root, the transfers) stays on both. A profile change still recreates everything |
| Books Discover's rows | `screens/discover/ReadingDiscoverRows.shown`: an empty row is left out, and under All a row whose name does not say what it holds says it ("Trending now · Manga"), since BookKeeprr names each kind's rows alike |
| Loading the next page of a list or row | `state/PagedLoadState`; one per row via `state/RowPaging` |
| A scrolling container | `ui/FocusScrollView` / `FocusHorizontalScrollView` (never a focus stop; `revealAbove` keeps a heading over the focused row visible; `revealWhole` brings a part into view whole while focus is in it, when it fits: a book or downloaded series page's header, so the title stays over the overview and Play). A scrolled page's words fade out at its top edge under the bar (`ui/glass/TopFade`) |
| A list of rows whose focused row rests at the top, heading and all (Home, Discover) | `ui/PinnedRows` (`RecyclerView.pinFocusedRows`; leaves room under the last row; put the list in a clipping frame; a row with two parts implements `PinnedRowsLayoutManager.Anchor`) |
| Up/Down through a scrolling page of rows (past an empty row, to rows scrolled away) | `ui/RowStep.move(rows, focused, up)` (Books home) |
| A comic or manga among books: the pill on its cover and its line | `ReadingBookFacts.kindTag` / `comicLine`; `PosterCardView.bindReadingWork(showKind = true)` on Books home only |
| A comic run's issues on its page; their names and counts | `screens/library/IssueStrip` (a lazy strip per volume, opening at the issue you are on); `ReadingBookFacts.issueTitle` / `issueLine`, "162 issues" in `length`, "On issue 51 · 1% read" in `progress`; the hub's `/v1/img/reading/kavita-chapter/{id}` cover per issue |
| Controls drawn over a picture (the player, the comic reader) | `ui/OverlayButtons` (`round`, `pill`, `dressDisc`, `dressPill`, `ringed`: a disc or pill of dark glass, the ring standing outside it; `light` for a pill that is on). No white discs: dark glass tinted by what is open, nearly solid on the video palette's dark (`GlassButtonBackground.overPicture`, `GlassColors.overPicture`), so it reads over the brightest frame; Play stays the white disc (`playFace`, its symbol `PLAY_INK`), and "−10" / "+10" are `jump` discs (the player's and the read-along dock's). `panel(view, corner)` for a panel of the same glass (the timeline's bar, the seek preview, the up-next card, subtitle timing, a reader's bars) |
| The comic reader's heading | `ReaderTitleFormatter.heading` / `issue` / `subtitle` ("Fantastic Four" over "Issue 51 · Page 2 of 24"; Kavita's "Chapter 51" is an issue for a comic); `part` while a page is read in steps ("Part 2 of 3") |
| What a key does in a reader (a comic, a book, an audiobook) | `reader/ReaderPadMap`, pure: `command(state, action)` turns every pad action into a `ReaderCommand`, so none falls through to the tabs; `hints` is the key row inside the controls and `sheet` the Controls sheet, both drawn by `reader/ReaderKeys` (`row`, `show`; `ROW_DP`, the row's height, is the hint bar's) with `SidePanelView.keys` (a key's caps in a column of their own, then what it does). The owner's keymap (#16): a comic's Ⓐ forward and Ⓑ back a page, Select leaves; a book's Ⓑ opens the menu and Ⓑ again leaves; an audiobook's Ⓑ leaves; read along with the menu closed, L1 and R1 step a sentence and L3 takes the page back to the voice ; an audiobook that has chapters says so (`ReaderPadState.chapters`: L1 and R1 are Previous chapter and Next chapter, else part) |
| The right stick and the stick clicks | `input/AnalogPan` (while the right stick is held, one `PadAction.Pan(dx, dy)` a frame: its travel past the 0.12 dead zone, curved, times the frame's time, which a reader scales into screens) and `PadAction.Click(stick, down)`, L3 and R3 pressed and let go, from `PadEventRouter`. The right stick is `AXIS_Z` / `AXIS_RZ` on this handheld |
| Scrolling a book with the D-pad and the right stick (#18, E1) | `reader/BookScroll`: whole pixels for the web view, the part of a pixel a gentle push asks for carried to the next frame (it rounded to nothing); at the end of a part of the book, on into the next (the D-pad at once, the stick after pushing on half a screen). The D-pad's third eases over `STEP_MS` |
| How long is left in a book (#18, E3) | `reader/ReadingPace` (pure: a pace in positions and minutes, learnt from plausible reading only, leaning on a prior until there is enough, the most recent `WINDOW_POSITIONS`; `Tracker` gives the stretch read since the last place) kept by `settings/ReadingPaceSettings` (per edition and over every book); `TimeLeft` (`ofPositions`, `ofNarration` while the page follows the voice, `label`: "12 min left in chapter · 4h 10m in book", in `Fmt.runtime` as the audiobook's line) under the book's title |
| A footnote and a link in a book (#18, E5) | Readium's `shouldFollowInternalLink`: a note reference opens `reader/FootnoteCard` (the note's words from `FootnoteText.plain`, in the glass of the reader's sheets, Go to the note and Close) and the page stays; another link is followed and `onJumpToLocator` sets "Return to previous place" |
| How a comic is read, kept per series | `reader/ComicView`: `ComicFit` (Whole page, Fit page width, Read in thirds), the direction and `trim` (Trim margins, on unless the series turns it off in Display), per series in `DomainPreferences.comicView`, with `comicDefaultFit` for a series never set (Thirds); `ComicPlace`, the page and the step you were on (`comicPlace`); `ComicZoom`, the zoom and its horizontal anchor, which the next page and the next issue (a reading list's too) open at, at their top |
| A comic page's paper border (#18, C5) | `reader/PageBounds.detect` (pure, on the hub's 96-pixel thumbnail of the page, never the scan: each side's outermost line one colour, then inward while 96% of a line stays within 24 luma of it; one pixel back; at most 12% of an axis) gives a `PageContent`, which the comic reader treats as the page for the fit, the steps (`ViewportStepPlanner` on the content's size), the map (`onPage`) and `ComicPanPolicy`'s range. Measured on generated 1000 x 1540 pages with a 5% border: the page reads 9.1% larger at fit width (11.1% ideal; the pixel held back costs the rest); a full-bleed page, or a flat colour to its edge, is left whole |
| A finger on a comic's page (#18, C7) | `reader/ComicTouch` (pure): a tap in the outer third reads on or back (sides swapped right to left), in the middle it shows the controls, a swipe across turns the page; not while zoomed, nor in a pinch. The page view keeps its own drag, pinch and double tap |
| A page read in steps (Thirds) | `ViewportStepPlanner.count` / `fitWidth`: how many steps from the page's shape and the screen's, 12% overlap (three for a comic page on this screen, two for a spread), per page through `PagedImageState(stepsFor)`; the minimap is `reader/PageMapView` |
| A reader's bars (comics and books) | `reader/ReaderBars` (#16, X7): a bar of the cover's near-solid glass along the top and the foot, each floating 8dp in with 16dp corners (`OverlayButtons.panel`), the keys' row across the very foot. `ReaderPagePreviewController(makesRoom)` is the owner's choice: a comic's page keeps its size under the bars (`makesRoom = false`), a book's shrinks with the menu round it. Read along is the book with a player (#21): `useAsLowerBar(dock)` makes the narration's dock the menu's lower bar, above the keys, so it shows and hides with the bars and the page makes room for it as for a book's row. A comic reports its issue's cover (`IssueCover.path`) as the page's artwork, so the glass takes its colours |
| A comic's page turn without the blank | `reader/PageSurface` (#16, C3): the page on one of three tiled views, the others holding the pages either side decoded and placed, drawn but unseen (alpha 0, since SubsamplingScaleImageView decodes only what it draws); `PageSlots` (pure) says which pages and keeps what is decoded. A turn swaps at once; a jump keeps the page you were on until the new one is ready. Measured on the emulator: about 35 MB for three 1988 x 3056 pages |
| Comfort in a long session: how bright and warm a reader is drawn, a book's black page, the screen kept on while narrating | `ui/ScreenComfort` (pure; `dimAlpha` is also the player's brightness drag), kept for every reader by `settings/ComfortSettings`, drawn by `ui/ComfortLayerView` over the whole reader (the warmth multiplied in, so black stays black, then the dim) and changed in `reader/ComfortSheet`, the same glass sheet in every reader (#16, X3). Software only: the backlights are never touched |
| The read-along dock and the sentence it reads | `reader/ReadAlongDock` (the prototype's glass dock and read along's lower bar: a line, "Read along · Following" over `ReadAlongDockText.time`, the jumps, the white Play, the speed, back to the sentence) and `reader/ReadAlongGlow` (the sentence as Readium's highlight template: a wash of the Books accent with a glow) |
| Read along: whether the page follows the voice, a sentence at a time (#16, A5) | `ReadAlongFollow.label` ("Following", "Reading" once you turn the page while it reads, "Alignment unavailable"), on the dock and on the pill the page shows while it narrates with the menu closed ("▶ Following · 1.1×"); `ReadAlongTimeline.step` (back from more than `RESTART_MS` into a sentence is its own start) through `ReadAlongPlayback.stepSentence`; `ReadAlongPlayback.isOn`, not `isPlaying`, for anything a seek's moment of buffering must not flip |
| Read along's narration, streamed (#19, A3) | `reader/ReadAlongStream`: `plan` (the slim edition, `file?format=readaloud&audio=omit`, with the narration from the audiobook's tracks when the hub mapped the edition's audio onto them; the whole edition, its audio taken out here, when it cannot: a 409, a missing route, an edition it could not map; what this device has when the hub cannot be asked) and `sources` (each stretch's track and where its file begins, as a `NarrationSource`; a file the hub did not map is no narration, never another file's). The slim edition is kept in `reading-epub/<scope>/aligned-slim`; `ReadAlongPlayback` plays through `AudioStreams`, so the audiobook and read along share one cache. The place is the sentence, a text locator any reader understands (`ReadAlongLocation`; the private `pocketdsAudio` offset is gone) |
| A book kept on the device, and whether it is still the hub's (#41) | The hub serves each ebook as a reading copy with a strong ETag of its own bytes (font sizes that follow the text size, two columns on narrow screens), and a copy downloaded earlier has the old sizes in it, so `EpubPackageCache.isComplete` reusing it for ever left the text size and "Two pages" doing nothing on A Game of Thrones. `reader/EpubEdition.open` is how an edition is opened (`EpubReaderScreen.editionFile`): `EpubPackageCache.copyState` is `Missing`, `Unrecorded` (no tag kept: downloaded before this), `Unverifiable` (the hub sent none) or `Tagged`; a kept copy is asked about with `If-None-Match` and `EpubFreshness.decide` (pure) says what the answer means: a 304, or a body carrying the tag already kept (a hub that ignores the condition), opens the copy untouched; another tag, or the first tag a copy has had, replaces it ("Updating book…"); no network, a timeout, any error status or a held token opens the copy as before. The tag is `<name>.etag` beside `<name>.epub` (`promote(…, etag)` writes it after the move, `remove` and Remove offline copy take it with the copy). The question is `ResumableEpubTransfer`'s own first request (`EpubRevalidation`, through `HubApi.downloadReadingEpub(check =)`): the headers get `ANSWER_MS` (5 s), an unanswered question is not retried, and a newer edition is streamed to the `.part` by that same response, so ranges and resume work as for any download. Asked about: the ebook and the slim read-along edition. Not asked about: the whole read-along edition (the hub passes it through from Storyteller, which ignores `If-None-Match`, so a question costs a stream of hundreds of megabytes and a change as much again), and an edition opened because the hub could not be reached a moment ago |
| Kindle's corners over a book's page: the time, where you are, how far (#42) | `reader/PageInfo` (pure: `next` is the cycle, Page in book, Page in chapter, Time left in chapter, Time left in book, None; `place`, `bottomLeft`, `bottomRight`, `clock`, `ink`, `sideInsetDp`) with `PageInfoChoice` / `PageInfoCorner`, kept by `settings/PageInfoSettings`. `reader/PageInfoView` draws them (clock top right, the choice bottom left, `Fmt.readingPercentLabel` bottom right, 11sp in the page's text colour at 60%, level with the text's gutter) over the page inside `navigatorContainer`, so they move and shrink with it; `ReaderPagePreviewController(corners =)` hides them while the bars are up and keeps them while a sheet is open, so the "Page info" tab of `EpubAppearancePanel` (Clock, the bottom-left choices, Percentage) shows its change live. A comic has none. The page keeps a 26dp strip clear for each edge that shows a corner (`pageHost`'s margins, `PageInfoChoice.topStrip` / `bottomStrip`; `navigatorContainer` takes the page's colour behind them). Words: "Page 213 of 765" is the book's own page count from the hub (`EpubReaderScreen(bookPages =)`, from `ReadingBookFacts.pages(work)`: the longest text edition) and `ReadingBookFacts.page(how far through, pages)`, the very formula and rounding of "49% · page 363 of 735" on the book's page and Resume, so every place says the same page; "Page 3 of 18 in chapter" is the chapter's share of those pages (`PageInfo.sectionSpan`: its start and the next one's, in `totalProgression`, the same span the percentage is measured from), counted from the page it starts on. Where the hub has no page count both are Readium's positions (`TimeLeft.position`, the same the time left is measured in); where it has one but how far through is not known they are blank. The times `TimeLeft.chapterLabel` / `bookLabel` from `EpubReaderScreen.currentTimeLeft()`, the one pace (the menu's line is built from the same call, and reading along it is the narration's). A tap on the bottom left, or L3 (`ReaderCommand.NextPageInfo`; "Page info" in the hint row and the Controls sheet), moves to the next choice and keeps it; reading along L3 stays the voice's ("Back to the narration"), so there the corner is a tap away. The clock follows `DateFormat.is24HourFormat` and is redrawn on the minute (`PageInfoView.start` / `stop` from `onShow` / `onHide`). `moveControlFocus` blocks `pageHost`'s descendants while it searches: with a strip above the text the web view's top lay below the top bar's buttons and the search found it before the position row |
| The reader's own look, by default: justified, hyphenated, 1.5 line spacing (#42, Part 3) | `EpubReaderPreferences()` is the one place for what a device with nothing stored gets: Publisher styling off (Readium's advanced mode), `textAlignment` "justify", `lineHeight` 1.5, `hyphenation` on (Readium's `hyphens`; a "Hyphenation" row in the Layout tab). `EpubAppearanceStore.decode` falls back to those key by key and keeps what is stored, so a device whose look was already changed does not move; a guard rule fails a hand-written fallback. "Publisher styling" brings the book's own look back; picking the Publisher typeface no longer turns it on (a typeface of the reader's own still turns it off). A device that changed its look once stored every key and so never sees the new default: the Layout tab's "Reset text style" row (`EpubLayoutPolicy.resetTextStyle`, its subtitle `textStyleSummary()`, both read from `EpubReaderPreferences()`) sets Publisher styling, alignment, hyphenation and line spacing to the defaults in one press and leaves the size, typeface, theme, margins, columns and the page info as they are; it is an ordinary row, so the D-pad reaches it and A presses it |
| The seek step of every transport: the player's ±, the audiobook's, the read-along dock's | `PlaybackSettings.seekSeconds` (5, 10, 15 or 30; Settings › Playback) |
| How fast a book is heard, kept per book | `settings/ListeningSettings` (an audiobook and its read-along edition share it); the speeds `Listening.SPEEDS` (0.75× to 3×, `nextSpeed` for the dock's pill), their words `PlayerLabels.rate` ("1×", "1.25×") |
| One sound at a time: video, a book's narration, an audiobook | `reader/AudioArbiter` (pure: a start returns what must pause) through `reader/AudioHandoff`: each player reports `started` and `stopped`, the reading players register how they pause, and the video service's only part is reporting from its `onIsPlayingChanged`; it is paused through its own `PlaybackService.pause` |
| An audiobook that plays on without its screen (#16, A1) | `reader/ReadingAudio` (open, play, seek within and across parts, the parts, speed, sleep; `state` for its screen and the mini player, with `problem` when a stream stops; the place every 15 seconds and on every pause: a streamed book's through the reading outbox, sent to the hub no faster than every 15 seconds (`SyncThrottle`) and written finished at the end, a book out of its ZIP in this device's `audiobook_positions`; a 412 reads the manifest again and plays on at the same place) on `reader/ReadingAudioService` (its own ExoPlayer and media session, id `reading-audio`, apart from the video service; foreground while it plays, a wake lock with the screen off). Its screen is `AudiobookScreen`, which only shows and steers it; Stop takes it off, its place kept. The screen (#11) is the book page and the read-along dock together: the square cover large beside `ReadingBookFacts.listeningEyebrow` ("Audiobook · Book 2 · Mistborn"), the title and `listeningLine` ("Brandon Sanderson · read by Michael Kramer"), on its own ambient page of the cover (a full-screen screen hides the app's); the dock's glass player under them and the tools as glass pills. A part's name drops its file's extension (`AudiobookArchive.partLabel`). Where the book has chapters (#31) the line under the title, its two times, the timeline (`ReadingAudio.seekInSpan`), Previous and Next (`part`), the time left and the sleep timer's end (`setSleep`) are the chapter's, which can run on across tracks, and the tool, the steps and the key hints say "chapter" |
| An audiobook streamed from the hub (#19, A3) | `reader/AudiobookStream` (the manifest as parts: each track's URL under the revision, through `HubApi.readingAudioTrackUrl`; `cacheKey` by track id and ETag, so a new revision keeps what is cached; `downloadsWhole`: a 409 `audio_not_streamable` or a 404 means the whole ZIP, as before) and `reader/AudioStreams` (the one `SimpleCache`, 512 MB least recently played first out; `mediaSources`, both reading players' sources: the cache over `HubClient.audioHttp`, so the bearer and the gate apply, a 409 or 412 handed over at once rather than retried; `prefetch`, the next track's first 2 MB; `cachedBytes` and `remove` for Remove offline copy). `AudiobookScreen.load` reads the manifest first and opens at the place the outbox gives (`chooseReadingResume`, asked when the hub only worked it out from a reader's page) |
| An audiobook's contents sheet, its steps and what its line measures (#19, #31) | `reader/AudiobookContents`: the book's own chapters (`source` "book", the read-along edition's table of contents), which can start in one track and run on into the next: the voice before the first belongs to it, so the first entry starts with the book, and each lasts to the next one's start across the tracks, the last to the book's end; else the marks inside the files (an older hub's chapters say no source: an entry never leaves its file, a track's opening before its first mark kept); else the parts. `step`: next is the next entry, back from three seconds in is the entry's own start, those seconds counted across the tracks (`partsMs`; a length not known counts as well in). `span` is what the line under the title, its two times, the timeline and the time left measure: the chapter playing across tracks, else the part by the player's own length (a chapter whose length is not known yet is still named, and the part is measured); `place` is a moment in it as a track and an offset; `endsWithPart` says whether a track's end is a chapter's (a chapter that runs on through it is not); `noun` is "chapter" or "part", which every label says |
| A listening place (#19, A4) | `reader/AudioPlace`: the `audio` checkpoint's location (a track by its id and a moment), kept as the hub reads it back (`canonical`: a track's end past its length, the book's last two seconds as finished), so the outbox's compare never takes the hub's own reading for another device; its write names the base as `expected` (`body`), and nothing reads the hub's timestamp. `ReadingProgress` fetches and sends it like a book's page; `legacyTrack` finds an old device place's part by the ZIP's sizes and `ReadingCheckpointStore.seed` moves it over once. On this device the location also keeps how far through the whole book it is (`progress`, 0 to 1; #30): `kept` writes it with the place (the player's save and the seed), `progressOf` reads it (1 once finished; null for a place an older build kept), and Books Home and a book's page show it for a place still waiting to be sent (`ReadingProgressPresentation.project`: a place with none leaves the hub's progress as it is, never 0%). The hub never reads it back and `body` never sends it, so the outbox compares an `audio` place by track, moment and finish alone (`samePlace`, through `ReadingCheckpointStore`): compared whole, the hub's own reading of the place just sent would look like another device moving the book |
| The audiobook playing, while you browse | `nav/MiniPlayerView` in the top bar after the tabs (Ⓐ or a tap opens its screen, Ⓧ or its symbol plays and pauses; hidden on that screen and when nothing plays), fed by `HubActivity.showListening`. A book with chapters has its title on one line and the chapter playing, then the book's time left, under it (`ListeningState.chapter`, `PlayerLabels.leftLine`); a book of parts is one line, as it was. The media notification and the lock screen name the chapter too, as the item's artist, replaced as the chapter changes inside a track (`ReadingAudio.nameChapter`: the player takes it without loading the track again) |
| Listening: the time left, the sleep timer | `reader/Listening` (`heard`, `bookLeft`: as heard at the speed playing; `bookProgress`: how far through the book, of the recording, so a speed does not move it; `distance` and `jump`: the recording between two places and a jump from one, counted across the parts, which a chapter that spans tracks needs; going back stops at the start of a part whose length is not known rather than at the book's start); `ListeningState.span` / `spanLeftMs` / `noun` (the chapter's time left, as heard, beside the book's: "12 min left in chapter · 4h 10m in book"); `SleepTimer` (minutes or the end of the part, which is the end of the chapter where the book has chapters and says so; counting only while it plays, fading over its last `FADE_MS`; a button while it fades carries on to the next end). A chapter ends nowhere the player reports, so the timer remembers which entry it counts to (`entry`): in a later one when it was within a tick of the end, it has played on past it, and any other move is followed; a track's end ends it only where `AudiobookContents.endsWithPart` says the chapter ends there. `SmartRewind.afterSleep(part, position, partsMs)`: back over what faded, into the track before when the chapter ended just past a track's start; words from `PlayerLabels.timeLeft` / `sleep` / `sleepChoice` (each takes the noun) |
| A comic's pages as pictures: the scrubber's preview, the Pages grid (#16, C4) | The hub's thumbnail, never the scan: `HubApi.readingPublicationThumbUrl` (`HubEndpoints.readingPublicationThumb`, `/pages/{n}/thumb?w=`, 64 to 512) at `PageGrid.THUMB_WIDTH` through `Artwork.bind`, so the preview's thumbnail is the grid's; `reader/PageGrid` (pure: `columns`, `move`, `page`, its own key row `HINTS`) and `reader/PageGridView` (seven across, the cursor drawn on the cell, the page you are on labelled; Ⓐ opens, Ⓑ back to the controls) |
| The end of an issue | `EndOfIssue.heading` / `next` ("End of Fantastic Four #51", "Next: #52", "That was the last issue") on `reader/EndOfIssueCard`: Ⓐ continues, Ⓑ stays on the last page, Select leaves |
| Reacting to focus while keeping the ring | `FocusDecorator.listen(view, ringVisible) { view, focused -> … }` |
| Player and playback-option wording | `playback/PlayerLabels` (Locale.US decimals; `playMethod`: "Direct play", "Direct stream", "Converting") |
| A change to what plays: an audio or subtitle track, the quality, a version, a conversion | `PlaybackRules.selection(plan, …)`, the hub's select body naming the version playing: Jellyfin applies a stream index only with its media source (#24) |
| Hub: a reading-lab fixture (generated test files; removed from the real reading servers on 2026-10-03, the guard stays in case they are generated again) | `isReadingFixture` (Storyteller) / `readingdomain.IsFixtureSeries` (Kavita "Lab Comics", "Lab Manga"); kept off shelves and off library cards, whose cover prefers the series read most recently |
| "1 transfer needs attention" | `ActivityDashboard.needAttention` |
| Subtitle decoding | `TolerantSubtitleDecoderFactory` in the text renderer (a broken ASS line is skipped, not fatal) |
| Hub: permission check | `requireScope(w, r, scope, action)` (403 `forbidden_scope`) |
| Hub: a screen built from several cached reads | `cacheSummary` (keeps stale/degraded) |
| Hub: a Jellyfin image path | `jellyfinImage` / `posterImage` / `backdropImage` |
| Hub: a library tile's picture, fan and count | `libraryViewArtwork` (the folder's own art wins) and `libraryDailyPicks` + `fanFrom` (places spread through the library from `fanIndices`, the day's pick first), behind `GET /v1/library`'s `image`, `fan` and `total` |
| Hub: the order libraries are listed in | `arrangeLibraries` (saved first, then A to Z) with `libraryOrderStore` (`library-order.json`, per Jellyfin profile and side), written by `PUT /v1/library/order`; `GET /v1/library` and `/v1/reading/libraries` come back in it with `order` |
| Hub: a service's own error sentence | `upstreamText` / `upstreamMessage` / `serviceOf` |
| Hub: a title's request state changed | `invalidateTitle` (search, Discover, detail) |
| An artwork's Glass colours on the Pocket | `ui/glass/ArtworkColors.shared(context, api)`: `prefetch` what a screen shows, `request` what is in focus, `peek`; when to ask again is `ArtworkColorBook` |
| A Glass panel, bar and page | `ui/glass/GlassColors` (`panel`, `bar`, `sheet` for a side sheet or dialog over a screen's own words, `over`, `contrast`; the words on the page by loudness: `EYEBROW`, `FACTS`, `QUIET`, and `TRACK` under a bar), `GlassPanelDrawable` (`attach(view, radius)`: a view's background that follows the page; `retint`; `edge(color)` for an edge that says something, such as amber), `AmbientLayerView` (`show(path, palette)`) |
| A Glass search field | `ui/glass/GlassSearchField.style`: a pill of the page's glass inside a taller target, ringed on focus (Discover, the Library) |
| A library on the Glass Library root | `screens/library/LibraryTileView` (the picture, soft and dimmed, the hub's `fan` of posters, a glass label); where the posters sit, what the label and the root's line say: `LibraryTiles` (`slots`, `fan`, `summary`, `kindLabel`). The root is `LibraryRootView`, on both sides (Movies and TV adds search and Favourites); a tile pushes `LibraryFolderScreen` or the Books library's page |
| The order libraries are shown in, and changing it (#15) | The hub's, never sorted here. `screens/library/LibraryOrder` (`move`, `step`, `slotAt`, `inOrder` for a list kept beside the tiles, such as the capsule on a library's page), `LibraryArrangeSession` (what is lifted, what the hub has), `LibraryOrderQueue` (one save out, the newest waiting) and `LibraryOrderChanges` (a screen that drew an older order reads it again); `LibraryOrderEditor` shows a move at once, saves it with `PUT /v1/library/order`, puts it back and says why when a save fails, and for A to Z sends `[]` and reads the hub's order again |
| Libraries that can be put in order: tiles or rows, and the grip that says so | `screens/library/LibraryArrangeGrid` (`Style.TILES` three across, `Style.ROWS` one under another; a new order slides each to its place; the grip, two columns of dots, always at a row's end and in a tile's corner while arranging, lit on the lifted one; tiles wiggle while arranging and a fixed one such as reading lists stays still without a grip; a pointer drags a row by its grip, a tile after a hold or at once while arranging) in `LibraryRootView` (Arrange beside the heading, Done while arranging; Ⓐ picks up and drops, the D-pad moves, Ⓑ puts a lifted tile back and otherwise finishes, Ⓨ A to Z) and Settings › Libraries (`LibraryOrderSection`: Ⓐ picks up and drops, the D-pad moves, Ⓑ puts back). No arrow buttons |
| The Glass page: which artwork it shows | A screen's `pageArtwork` (the focused card, a title's backdrop, the cover in focus, what is playing), re-read on focus changes, `showCurrent` and `ScreenHost.pageArtworkChanged`; `nav/PageArtwork` (`next`: none keeps the last, `title`; `backdrop(itemId, seriesId)` by id alone, for what is playing and a downloaded title); `ScreenHost.prefetchArtwork` for the cards a row binds. `HubActivity.showArtwork` feeds `AmbientLayerView`, the bars' `setPalette` and `GlassPage` |
| The Glass page's colours, for what is drawn over it | `ui/glass/GlassPage`: `palette(context)` for a panel opening now; `follow(view) { palette -> … }` for glass that stays on screen (the bars, a Details pill, a play disc, a day chip), held only while the view is attached. The player's controls take `Theme.onVideo`, which stays solid |
| Artwork fading into the Glass page | `ui/glass/FadedImageView`: `stops` (opacity down its height) and a `shade` for words over it, both inside its own layer, so neither ends in a line; `HERO` / `HERO_SHADE` for Home's hero, `TITLE` / `TITLE_SHADE` for a title page's backdrop (`DetailHeaderView`) |
| A Glass tile or poster | `LandscapeCardView` and `EpisodeCardView` (11dp corners, 3dp ring and `ui/glass/GlassStillMarks`: the white progress bar inside the still, the accent tick, the glass play disc on focus; an episode's UP NEXT at its top left) and `PosterCardView` (a white count pill, an accent tick, where a title stands as a glass chip at the top left, `setDayChip`, and with captions the title on one bold line over the year; a book's cover keeps its words on the page, an audiobook's is square, a comic's kind sits on a dark pill); Discover's featured card is `DiscoverFeatureCardView` |
| How far in, drawn inside a picture (a still, a poster, a cover, a book also being read) | `ui/glass/GlassProgressBar`: a 4dp white bar on a faint track with round ends, hidden at nothing; the accent where a book is being read. `GlassStillMarks` and `PosterCardView` carry it |
| A row's heading on Glass, with a quiet count after it ("Also reading 2", "Volume 1961 147 issues") | `ui/glass/GlassHeading` (`create`, `text`); the caller sets the padding that lines it up |
| A title's pipeline as Glass chips | `screens/discover/PipelineChip` (`Tone.of(state)`: done in the accent, the stage under way amber with its dot pulsing, failed or stuck red, the rest waiting) |
| The request form | `ui/FormOverlay` over `state/FormModel`, drawn into the shared side sheet: a heading where each part begins (`FormRow.section`), "‹ value ›" on a row that steps with left and right, Request white at the foot. `centred = true` draws a short question as a small centred card (`FormRow.Action(quiet = true, icon = false)` for a Cancel beside the main button; two buttons side by side are walked with left and right) |
| A Jellyfin profile's tile | `screens/home/ProfileAvatar` (`colors`: the profiles in alphabetical order take the palette, so four profiles get four colours; `initial`); Glass's "Who is watching?" is `ProfilePickerView`, a centred `SidePanelView` (`centredWidthDp`, `wrapsHeight`, `centreHeading`) |
| Words and icons on a white pill (the selected tab, the status pill, a play mark) | `PocketColors.inverseText`, never `background`: Glass's page colour paints nothing |
| Hub: an artwork's Glass colours | `internal/artcolor` (`Analyze` → `Palette`) behind `GET /v1/img/colors`; `artworkColorStore` keys a picture without its width and keeps `artwork-colors.json` |
| Hub: the artwork routes | `imageRoutes`, registered on the API and on `artworkMux`, which the hub reads its own artwork through |
| Hub: a Storyteller path read from this PC (an audiobook's files) | `readingdomain.ResolveMediaFile`, the read-only twin of `ResolveRemovalFile`: one `walkMediaPath` for both (a clean absolute path under `media_removal_roots`, no link or junction, no device name, alias or trailing dot, the real place still inside the root), a regular file with an audio extension, opened once and compared with what was checked. `ListMediaFolder` for the one question of which file is a lone M4B; `AudioKindOf` is the one list of what is served, with its MIME and ffprobe container. The error says `MediaUnmapped`, `MediaMissing` or `MediaRefused` and names no path |
| Hub: an audiobook's tracks, order, chapters and revision | `buildAudioPlan` (`reading_audio.go`) behind `GET …/publications/{id}/audio`: files in their own tag order when the tags are exactly 1..N, else Storyteller's manifest order; lengths from the manifest; chapters only from files with two or more marks, or a lone M4B's manifest (`source: "marks"`), `startMs` from the start of that track; an aligned book's come from its edition's contents instead (#31, below). Held 60 s (`cache.ReadingAudio`), cleared by `invalidateStorytellerWork` and every reading-cache sweep. `409 audio_not_streamable` carries `Error.Reason`: `unmapped_root`, `missing_file`, `unsupported_layout`. `resolveStorytellerBook` finds the book and leaves the edition to the route. `manifestEntry` is Storyteller's manifest as it was read (a file, or a chapter of a lone M4B), what places are written against |
| Hub: an audio track's bytes | `handleReadingAudioTrack`, `GET …/audio/tracks/{n}?rev=` (rev required): `http.ServeContent` on the handle `openMedia` gave, so 200, 206, 304, 416, `If-Range`, HEAD, a strong ETag and no file name; the file is checked against the held list, and `412 audio_changed` drops it. Only `/audio/tracks/` is transport in `limiterFor`; the manifest and the place are screen-sized |
| Hub: ffprobe on an audio file | the `Server.probeAudio` seam through `probeFile`: cached by path, size and modified time, four at once, five seconds each, a failure is no information and is not remembered. `ffprobeArgs` forces the container from the extension and has no `-nostdin` (ffprobe rejects it; the process gets the null device as stdin). Found by `findFFprobe` beside `findFFmpeg` |
| Hub: one Range check for a file route | `requireSingleByteRange` (one valid range, an `If-Range` of sane length; else 400): the EPUB file route and the audio track |
| Hub: a transfer that must not outlive its client | `streamUntilStalled` with `stallPolicy` (a five-minute window re-armed every 128 KB written): audio tracks. `prepareLongStream`, no deadline at all, is for a transfer that holds nothing open |
| Hub: a read-along edition's narration, and which track each of its audio files is | `readingdomain.ReadAlignment` reads only the zip directory, `container.xml`, the package, the SMIL and the contents (4 MB each, no DOCTYPE, never the audio) and answers `Sentence` (the one being spoken at a time) and `Find` (where a span is spoken). The overlays are listed in the order of the text and the narration need not follow it (Mistborn's lists a two-sentence chapter among its front matter and speaks it mid-way through the second part), so each audio file's sentences are put in the order they are spoken. `Sources` and `MatchSources` pair the narrated files with the book's by length (250 ms and 15 ms a chunk), completely and one to one, since a wrong guess puts every sentence in another file; files whose lengths cannot tell them apart (Mistborn's two parts are 44413.407 and 44413.403 s) are paired in the order they are played, the order the narration read them in, only where each fits its place by length (`Pairing.ByOrder`, logged), else refused as `lengths_ambiguous`. `alignPlan` / `mapNarration` (`reading_audio_alignment.go`) keep it per path, size and time (`cache.ReadingAlignment`, a key outside `reading:`) and put `aligned`, `alignment.audio` [{href, track, startMs}] (the edition's own paths) or `alignmentReason` (`unmapped_root`, `missing_file`, `unreadable`, `no_narration`, `files_do_not_match`, `lengths_do_not_match`, `lengths_ambiguous`) in the manifest, and the edition into its `revision`. `openReadaloudEdition` is the one way to the edition's file |
| Hub: a listening place, read and written | `audioPlan.placeFor` and `locatorFor` (`reading_audio_position.go`) behind `GET` and `POST …/audio/position`. Written as Storyteller's audio apps write it, in the manifest's order (a lone M4B as its virtual chapter file); finishing is the end of the last track the hub plays with a total of 1. Read from an audio locator (exact), from a text locator on an aligned book through the SMIL (exact, with the `sentence`), else as a proportion of the whole (`exact:false`). `completed` of an exact audio place is the end of the last track played, never a total taken in the manifest's order. `expected` (absent skips, null means nothing is saved, or a place) is compared in whole milliseconds with the place read from the current record; the stamp is `nextPositionStamp`: `max(now, stored+1)` after the check, from `Server.now`, and the app's `timestamp` is ignored (on the EPUB route too, where the field is still accepted for older apps) |
| Hub: a refused write of a reading position, and what a write drops | `writePositionChanged` (409 `reading_position_conflict`: the person settles it) and `writeStorytellerNewerPosition` (Storyteller's own refusal): one text for the EPUB route and the audio one, which take the same `lockReadingCheckpoint("storyteller", id)`. `currentStorytellerPosition` is nil for a book never opened. `nextPositionStamp` is the one stamp for both: a clock that runs behind the PC must not read as another device moving the book, and what protects a save that arrives late is the base (`checkBase` / `expected`), not the clock. `invalidateStorytellerPosition` drops the record and Storyteller's list and leaves the track list; `invalidateStorytellerWork` drops that too |
| Hub: an audio place shown to a reader of the text | `textPlaceOfAudio` (`reading_epub.go`): on a book whose edition is mapped, `GET …/position` answers a listener's place as the sentence being spoken (a locator with the zip path and the id of the span) and adds `audio`; anything inexact is the stored locator, and no audio file is looked at to say so. A write's `expectedLocator` may be that sentence or the stored locator. The reader keeps writing its text locator here; the hub stamps it |
| Hub: the read-along edition without its audio | The reading copy (next row) with `CopyOptions{OmitAudio: true}`: every entry but the audio, which is never read, behind `GET …/file?format=readaloud&audio=omit` (`serveSlimReadaloud`, `reading_audio_slim.go`). Planned once per path, size and time (`cache.ReadingEPUBCopy`, at most `maxEPUBCopyBytes` held) and served by `serveEPUBCopy` (`http.ServeContent`: Range, HEAD, a strong ETag and `X-Reading-Content-Hash` of the bytes sent). `409 audio_not_streamable` with `unmapped_root`, `missing_file`, `unreadable` or `unsupported_layout`; the full file (`format=readaloud` alone) is untouched |
| Hub: the reading copy of an ebook (#41) | Both readers hand the text size and "Two pages" to Readium CSS, which scales the *root* font size and applies two explicit columns only from `min-width: 60em`. A book whose stylesheet says `font-size: medium`/`small`/`12px` (A Game of Thrones: `p.* { font-size: medium }` 42 times, `body { font-size: small }`; 11 of this library's 60 or so titles: the ASOIAF books, Hyperion and Endymion, Iron Gold, Project Hail Mary) never follows the root, and the Pocket (853dp), every iPhone and an iPad mini upright are narrower than 60em. `readingdomain.WriteReadingEPUB` / `PlanReadingEPUB` (`epub_copy.go`) therefore serve each Storyteller EPUB as the same book with its absolute `font-size` and `line-height` as rem (`epub_css.go`, `rewriteSizes`: a tokenising pass, so comments, strings, `url()`, `font:` and every relative unit, bare number or `normal` stay; keywords at 16px, `Npx`/16, `Npt`/12) in stylesheets, `<style>` blocks and `style=""` attributes. The line heights matter because a size that scales inside a line that does not is clipped: the book's drop cap, `span.dropcaps { font-size: 80px; line-height: 70px }`, is 120px on a 70px line at a text size of 150% (`CopyReport.LineHeights`; 18 in the 122 files, in 6 titles, GoT's two drop caps among them; everywhere else a line height is `em` or a bare number). One `<style>` before `</head>` that asks for two columns from `30em` (`epub_html.go`; scroll mode is left to Readium's own `!important`, which wins at equal specificity). The package's first `dc:language` goes on each content document's root `<html>` as `lang` and `xml:lang` when it has neither (`validLanguage`: a plausible BCP 47 tag, not `und`/`zxx`/`mul`; `CopyReport.Languages`), because WebKit and Chrome hyphenate only text whose language they know and documents like A Game of Thrones' name none; a root that already says one, even `lang=""`, is the publisher's word, and 4408 documents of the 122 files were given one, 4077 had their own. The scan splices bytes into the original, so a well-formed document stays well-formed (swept over all 122 EPUBs: 8626 documents), hrefs, ids and the SMIL are untouched, and a document in another encoding, a fixed-layout package or a document over 32 MB is copied as it was (`CopyReport.Left`). Entries are copied raw (`mimetype` first and stored); a *plan* keeps only what it wrote and the small entries in memory and reads the rest from the file as it is sent, so Rhythm of War (112 MB) holds 1.4 MB. `GET …/file` (no format, or `ebook`) is `serveReadingCopy` (`reading_epub_copy.go`), found through `openStorytellerEPUB` and `media_removal_roots` like the read-along edition; the stall deadline, not the server's two minutes, bounds the transfer. If the file cannot be mapped, opened or rewritten the response is Storyteller's own pass-through, with a warning that names the reason (`no_path`, `unmapped_root`, `missing_file`, `unreadable`, `not_an_epub`, `too_large`, `too_slow`) and never the path. Folder entries that carry a two-byte empty deflate stream (most of this library's books) are written as empty stored folders, since a raw copy of them is refused by `archive/zip` |
| Hub: one audiobook to a work | `oneAudiobookPerWork` (`reading_audio_work.go`) in `handleReadingWork`: of copies of one recording (the same narrators, lengths within 2%) it offers the book that has the read-along edition, else the ebook; another narrator or an abridgement stays. The left-out book's own routes are untouched |
| Hub: a test that needs a read-along edition, a place or the clock | `newAlignedTrackedEnv` (`alignedOptions`), `withPositions` (Storyteller's real position table `storytellerPositions` and a `Server.now` the test sets), `newPairEnv` (one book held twice), `newEbookEnv` (`ebookOptions`: the ebook is an EPUB on disk that Storyteller names by path, with keyword sizes in it); `readingdomain.GenerateAlignedEPUB` (every clock form, a chunked file, `FixtureNarration`). A test that waits on real time asserts an outcome and an order, never a speed: `patient`, `eventually`, `within` |
| Hub: which version Jellyfin is asked for when a track changes (#24) | `negotiatePlayback` (`playback.go`): a PlaybackInfo that carries an audio or subtitle index and names no source names the session's current one (`session.Source.ID`), since Jellyfin applies an index only to a named source. So a select that names a track and no `mediaSourceId` (Apple's, an older app's) changes the track, and a change of quality made after a language was chosen keeps it. A prepare is not changed: it has no version playing yet, so a client that names a track at prepare names the version too. A select that names a version is the client's choice, as before |
| Hub: what an Apple download's MP4 holds (#5) | `repackage.PlanApple` (`internal/repackage/plan.go`), a pure function of the source's streams: H.264 (8-bit 4:2:0) copied as `avc1` and HEVC (8 or 10-bit 4:2:0) as `hvc1`, anything else (Xvid, VC-1, MPEG-2, AV1, 10-bit H.264) converted to 8-bit H.264 (`DetectEncoder`: NVENC when a tiny encode on it works, else libx264 veryfast); every audio track kept, AAC, AC-3, E-AC-3 and MP3 copied and the rest AAC with 5.1 kept; text subtitles as mov_text with a language (`NormalizeLanguage`: the three-letter T code), picture and unknown ones left out with a reason; one default audio track. The manifest's `apple` block (`appleManifestOf`) and the ffmpeg command (`BuildArgs`: one `-map` per kept stream, every input `-protocol_whitelist file`) are both made from that one plan, and its `Signature` is kept in the grant, so a file built for another plan is never served (the build compares it against the plan it finds; renew compares what the plan decides about the file, leaving the sidecars out: `appleSameMedia`, #45). `PatchTracks` states the track headers afterwards: ffmpeg groups audio and subtitle tracks but always enables the first subtitle track, which a player then shows. No chapters are written (ffmpeg points a chapter reference on every track at a track that does not exist), and converted audio uses the AAC `fast` coder (3 times quicker, measured). `TestARealFile` repackages a film named in the environment (`REPACKAGE_FILE`, `REPACKAGE_OUT`) and reports what came out: run it on a film that surprises |
| Hub: an Apple download's file, its queue and cache (#5) | `repackage.Manager` (`manager.go`), configured by `server.offline_cache`, `offline_cache_max_bytes` (20GB) and `offline_cache_max_age` (48h): one repackage at a time in the order asked (`prepare` queues the batch, and asking about a grant queues it if nothing is there, so a restart or a release needs no special case); a finished file is kept until it is released (`DELETE …/media`), has gone unfetched for the age, or is spent for room once it has been fetched in full (`spanSet` counts the bytes sent) and is idle: a file nobody has fetched is never thrown away for a newer one, the queue waits; a restart keeps finished files, discards half-written ones and touches only files named for a grant id. A failure is sticky and named (`JobError`: `source_missing`, `source_changed`, `no_ffmpeg`, `disk_full`, `ffmpeg_failed`, `subtitle_unavailable`) until `POST …/retry`. The routes are in `offline_apple.go`: `status`, `media` (409 `offline_preparing` or `offline_failed` until ready, then `http.ServeContent` with a strong ETag), `retry`, and `?format=apple` on the selection |
| Hub: where a download's file comes from (#5) | Never stored: not in a response, the grant registry, the cache's records or the log. `appleBuild` asks Jellyfin for the item when it builds (`withSourcePaths` asks as the server itself, `Client.ItemAsServer`, when a profile is not shown paths), checks the size and the plan are the ones prepared, fetches sidecar text subtitles from Jellyfin as UTF-8 text (`fetchAppleSubtitle`; a Hebrew SRT is often Windows-1255), and blanks the source and cache paths from ffmpeg's own words before they are logged. The source is only ever read; the cache folder must be outside every library |
| Hub: a subtitle file as WebVTT (#45) | `webvtt.Convert` (`internal/webvtt`), a pure function of the text with no clock, file or network: the format is read from the text (`WEBVTT` first, an `[Events]` section is ASS/SSA, anything else is SRT), never from the codec. SRT: dots in the timings, cues sorted by start, a cue with no words or no length dropped, only `<i>`, `<b>` and `<u>` kept (every other tag and any `{\an8}` block gone, `<br>` a line break), entities decoded and `&`, `<`, `>` re-escaped. ASS/SSA: the `Dialogue:` lines of `[Events]` by its `Format:` columns as plain text (overrides and `\p` drawings gone, `\N` a line break, `\h` a no-break space, comments dropped). WebVTT: passed through with only its line endings and last newline made regular. Bytes that are not UTF-8 become U+FFFD and control characters go; right-to-left text keeps its logical order and every mark (nothing added, removed or reversed, no cue setting), and `IsRTL(language)` says how to lay it out. A non-blank file with no cue is `ErrNoCues`, a blank one a track with none. `Version` is bumped when the same input would turn into other text, since it is part of an embedded track's signature |
| Hub: an Apple download's kept subtitles (#45) | `handleOfflineSubtitleTracks` / `handleOfflineSubtitleTrack` (`offline_apple_subtitles.go`): `GET /v1/offline/grants/{id}/subtitle-tracks` and `…/{key}`, `download` scope and the grant's own checks (`offlineGrantForRequest`: this token, this Jellyfin user, not expired, an `apple` grant), working after the MP4 is released since nothing touches the hub's file. `appleSubtitleSet` reads the source as Jellyfin has it now and answers 409 `source_changed` (`source_missing` / `source_differs`: another size) when it is not the downloaded video; the plan (`repackage.PlanApple`) decides what is text, the rest is `omitted` with its reason (`picture_subtitle`, `unsupported_format`, `unreadable`, `too_many` past 24 sidecars). `appleSubtitleKeys` names a track by what it is (`emb-<index inside the file>`, `ext-<language>[-forced][-sdh][-n]`; never Jellyfin's number, which is not stable: Jellyfin 10.11 numbers a file's sidecar subtitles first, so adding or removing one moves the index of every stream of the file, the video and audio included, and `repackage.FileIndexes` gives the index inside the file that does not move), so a replaced file keeps its key and a new language leaves the others alone; the same function over the manifest gives `mp4Index`. The signature of a sidecar is a hash of its WebVTT (`signatureOf`), read from Jellyfin for four tracks at a time; an embedded track's is a hash of what identifies it (its place inside the file, never Jellyfin's index) and `webvtt.Version` (`embeddedSubtitleSignature`), so a list never makes Jellyfin extract a 39-track film. The ETag of a track is its signature. A track that cannot be had (404, 4xx, over 8 MB, no cues) is `omitted` with its key and `unreadable`, so the app keeps its file; a Jellyfin that cannot be asked (5xx, 401, 429, down) fails the whole list, so no good file is deleted for a bad moment. `readSubtitleText` is the one place that asks Jellyfin for a subtitle's UTF-8 text (also behind `fetchAppleSubtitle`). Nothing is kept between two lists. Renew follows the same idea (`appleSameMedia`, `keepPlannedMedia` in `offline_apple.go`): `POST …/renew` of an Apple grant compares the file's size and what the plan decides about the MP4 (container, video, every audio track, the subtitles inside the file, each by its place inside the file as that grant's own manifest says: `sourceFileIndexes`/`inFile` over the manifest's source description, since a sidecar added in front moves Jellyfin's number for every stream) and leaves the external sidecars out, since Bazarr adds, replaces and removes them for as long as a download is kept; so a grant older than 30 days renews after a sidecar changed and its subtitles can be refreshed. Such a renewal keeps the stored plan signature, manifest and source description as they were (the manifest's track indexes still name the MP4 the app has, and the build and the cache still check a file against the plan it was promised) and only takes the new expiry and the item as it is; any other difference (another size, container, video, audio track, default audio or embedded subtitle) is still 409 `source_changed` |
| Hub: a Library person's TMDB id (#27) | `addPersonTMDBIDs` (`library_people.go`), called by `GET /v1/library/items/{id}` and by the answer to a watched or favourite toggle (the app draws the page again from that answer): one `GET /Items?ids=…&fields=ProviderIds` per title for its first 100 distinct people, which puts `tmdbId` on each `LibraryPerson` Jellyfin holds a positive TMDB id for (a Person item's own; absent otherwise, for the others and for a lookup that failed or took over 4 s, which is not remembered). Kept a day under `cache.Metadata`, keyed by the profile and the set of people (`library:people:…`, so a cast that gains a person is a new question, a library scan clears it and a watched toggle does not). Other routes that map an item with `libraryItemFrom` (offline, play targets, lists) do not carry it |
| Hub: an aligned audiobook's chapters (#31) | `GET …/audio`'s `chapters` of a book whose edition is `aligned` are the edition's table of contents, each `{title, track, startMs, source: "book"}`; the chapters of its files (ffprobe marks, a lone M4B's manifest) are `source: "marks"`, and a book has one source only. `ReadAlignment` reads the contents (`readingdomain/contents.go`: the EPUB 3 `nav` whose `epub:type` holds `toc`, else the NCX; nested entries flattened parent first; no DOCTYPE, 5,000 entries, titles cut at 200 runes; a contents that cannot be read is no contents, never a refused edition) and `Alignment.Chapters` places each entry on the narration. The first entry into a document begins at that document's first narrated sentence, wherever its anchor is: what is spoken ahead of the heading it points at (Mistborn's epigraphs) is its chapter's. Each later entry into the same document begins at the first sentence at or after the id it names, since what lies between two anchors belongs to the earlier: the sentence it names, else the first at or after its anchor (`scanIDs` looks through that one text document for where its ids stand, 64 MB of text in all, never acting on a DOCTYPE; an anchor it cannot find is the top of the document). When nothing in an entry's own document is narrated it begins at the first sentence of the documents after it, up to the next that an entry points at (Dark Matter's chapters ten and eleven are headed by a document of one picture, with the words in the next). `audioAlignment.bookChapters` and `inListeningOrder` (`reading_audio_chapters.go`) put each on its track through the narration map, drop one that begins outside its track, and keep the longest run that is later and later in the audio in the order the contents list them: an entry spoken out of place (Mistborn's front matter) is the one that goes, and of entries at one moment the first stays; an empty title is "Chapter N". `dressChapterTitle` writes the book's titles the same for both apps: a bare number is "Chapter N" ("1" is "Chapter 1"), a title wholly in capitals is in title case (a capital to every word, a Roman numeral kept as one: "PART II" is "Part II") and anything else is as written; a file mark's name is never dressed. They stand for the book's chapters when there are at least two (`minBookChapters`); otherwise, for an unaligned book and for a refused mapping (`files_do_not_match`…), the files' own stay exactly as before. `revision` already holds the edition. Generator: `AlignedEPUBOptions.Contents`, `ContentsIn`, `Documents`, `Anchors` |
| Hub: a profile's "you" for a book, and the app writing it (#39) | `readingYouStore` (`reading_you_store.go`): `reading-you.json` beside `offline_registry`, mode 0600, written whole through a temporary file, per Jellyfin profile (`readingProfile` is `libraryOrderProfile`: `X-Jellyfin-User`, else the default). It holds a profile's last Goodreads import (the fields of the rows that matched, never a review or note) and, apart from it, the app's edits of a work: a rating, a month finished and a read count, each a value or `Cleared`. A file it cannot read is kept as it is and every write is refused (500), since it holds what cannot be fetched again. `mergeYou` (`reading_you.go`) is the only place the two come together: an edit, set or cleared, wins over the import and goes on winning when the import is replaced; the shelves are the import's only; a finished month makes the status `read` unless the import says `currently-reading`, and clearing it drops an imported `read`. `handleReadingYou` is `PATCH …/works/{id}/you` (a key present sets, `null` clears, absent is left; rating 1 to 5, finished `YYYY-MM` from 1900-01 to this month by `Server.now`, readCount 1 to 99, all JSON numbers and never strings; a finished month with no count anywhere is count 1; unknown keys refused whole). `addPersonalFields` (`reading_community.go`) puts `you` and `community` on a work's own detail only, with `Vary: X-Jellyfin-User` |
| Hub: the Goodreads import and how a row finds its work (#39) | `readingdomain.ParseGoodreads` (`reading/goodreads.go`): columns by header name (a byte-order mark and any order are fine), `="…"` unwrapped, an ISBN only with a good check digit (`ISBN13`, `ISBN10`, `ISBNForms`; the ISBN-10 check letter is a lower-case `x` in the hub and a capital to Hardcover), `read`/`to-read`/`currently-reading` are statuses and never shelves, 20,000 rows. `CombineGoodreads` makes one record of several rows of a work. `POST /v1/reading/import/goodreads` takes the file as the raw body (8 MiB; a form is 415) and `?dryRun=true` matches and reports and keeps nothing. `goodreadsMatcher.match`: ISBN in either spelling through `CatalogStore.FindIdentity`, else the title past its series note with one of the work's authors (`authorKey`: words sorted, a parenthesis dropped), else the main title before a subtitle with an author; two works is `ambiguous` and never a guess, and a title with no author match is never a match. Candidates are the Storyteller books bound to works (`goodreadsCandidates`); a library that cannot be read is a retryable 503 and nothing is kept, since every row would be missing. The report's lists are capped at 200 (`truncated`), its counts are whole, and **only counts are logged**: no row, title, author or error text from the file reaches the log or a response. `GET` is the last import's report, `DELETE` forgets it and keeps the app's edits |
| Hub: a book's community rating and genres, from Hardcover (#39) | `internal/adapters/hardcover`: `New` refuses without a key (`ErrNoKey`) so no client and no request exist; `ByISBN` (13 and 10 forms) and `ByTitle` (GraphQL at `/v1/graphql`, `Bearer` token, a `ratings_count`-style schema error comes back as the service's own words, never the key). Its match is exact and `_ilike` is refused with a 403, so `ByTitle` asks `title: {_in: TitleSpellings}` (as written, small words in lower case, a leading "The" taken away or added, a curly apostrophe straight) ordered by `ratings_count` desc: "Dark Matter" has over twenty books and unordered the twenty returned left out Crouch's. Checked live on 2026-10-08, all 60 Storyteller works found. `genresOf` leaves out tags that are not genres (`notGenres`: Audiobook, General). `communityFor` (`reading_community.go`): ISBN, then the title with one of the work's authors, a book written as asked (capitals aside) before another spelling; kept `cache.ReadingCommunity` 72 hours, found or not, 14 days to serve when it fails; a first view waits `communityBudget` (2 s) and the lookup goes on behind it (`context.WithoutCancel`) to be there next time; a failure or a limit pauses Hardcover for 5 minutes (`pauseHardcover`, its free key is 60 requests a minute); never a page error. `communityOf`: Hardcover's rating, else the export's Average Rating as `source: goodreads`. `mergeGenres`: the work's own, then Hardcover's most-tagged Genre tags, none twice, up to 6 (the work's own are never cut). Hardcover is in `config.ExternalServices`: accepted without a `base_url`, not probed by `/v1/health`. `storytellerISBNs` and the Kavita `isbn` identifier give a work its ISBNs |

Cards measure at their natural height (`EpisodeCardView`, `DetailArtworkCardView`); give a row
`WRAP` height rather than leftover space.

`ConsolidationGuardTest` (part of `dev.sh test`) fails when a removed copy comes back -- a new
`HubClient(context)`, a hand-built image loader or image request, a hand-written episode code or "Specials", a
`"Selected"` detail line, an untinted progress bar, a bare `ScrollView(`, a `delay(POLL…)` loop, words drawn in the page colour, a list of libraries sorted on the device, a reading percentage worked out by hand, a white Play disc of its own, a multiply over a page, a reader pausing the video itself, an audiobook route built by hand, a page keeping its own default focus (#23), or a playback selection built by hand (#24) -- and names the owner
to use instead. Extend its rules when you consolidate something new. It also fails on a top-level class or
object that nothing in the app names, only its tests (#22): delete it with its tests, or list it in `keptDormant`
with the plan that needs it (`SpreadPlanner`, `EpubPackageCachePolicy`).

---

## Conventions (inherited from `../Ayaneo PocketDS Keyboard+Mouse`)

That sibling project targets the same device. Read it before inventing anything — several
device quirks are already documented there in comments, and several files are lifted verbatim.

**Tests first, and logic stays pure.** Navigation, input routing, retry and cache policy, and
load-state transitions live in plain Kotlin classes that take **primitives, never Android
types**. Views translate framework events into small data classes and hand them over. This is
why `PadNames` redeclares the `KeyEvent`/`MotionEvent` constants rather than importing them:
those classes are stubs in a JVM unit test and throw on access. No Robolectric.

**Plain Android Views. No Compose, no Fragments, no Navigation component, no ViewModel, no
LiveData, no DI.** Coroutines *are* adopted, but only the runtime — because cancellation is
the core problem (backing out of a screen must kill its in-flight requests) and Coil pulls
them in transitively anyway.

**One Activity.** The gamepad pipeline is per-window state that must exist exactly once.

**Comments explain *why*, usually citing observed device behaviour.** Commit subjects are
lowercase prose, no conventional-commits prefixes.

**Settings** are one `SharedPreferences` file (`pocketds_hub_settings`, via `settings/Prefs`)
with a small `object` per concern.

**This repo is LF-only** (`.gitattributes`). The sibling is CRLF; keeping them apart avoids a
whole-file diff.

---

## Commands

Everything goes through `scripts/dev.sh` (Git Bash; it shells out to `gradlew.bat`).

```
scripts/dev.sh              build, install, relaunch, print state
scripts/dev.sh test         JVM unit tests -- runs ~50x per feature
scripts/dev.sh pad          what the gamepad reports: sources, axes, dead zones
scripts/dev.sh display      screen size + density; every dp number depends on it
scripts/dev.sh keys off|on  remove/restore the vendor key-filtering a11y service
scripts/dev.sh seed         push hub URL + token from scripts/dev.env (gitignored)
scripts/dev.sh trace        last 40 of our own log lines
scripts/dev.sh cache clear  drop HTTP/image caches, keeping the token
scripts/dev.sh state        one-shot summary
```

Device connection is **wireless ADB over mDNS** — never hardcode the port, it is reassigned
every time the service restarts. Pairing is permanent; `resolve_device()` finds it.

---

## Device facts (measured on the hardware, 2026-09-07)

From `scripts/dev.sh display` and `scripts/dev.sh pad` on the real unit. Several of these
correct assumptions made before the device was available — trust this section over the plan.

**Android 13.** Device `AYANEO_PocketDS`, model `Pocket_DS`.

**Top display** (`port=131`, displayId 0): physical 1080x1920, presented landscape as
**1920x1080**, app area **1920x1026** after the nav bar. **`densityDpi` 360, so density scale
is 2.25 — not the 2.0 that was assumed.** That makes the usable area **853 x 456 dp**, not
960x540. Every grid-column and cell-size calculation follows from this. Refresh modes
60/90/120/144/165; default mode is 165 but it was observed running at **120**.

**Bottom display** (`port=132`): 768x1024 physical, `densityDpi` **227** (scale ~1.42), 60Hz
only. Roughly 722 x 541 dp in landscape.

**Controller** — framework `dev 55 "AYANEO Controller"`, sources `0x01000511` =
keyboard + gamepad + **joystick**:

| | |
|---|---|
| Sticks | `ABS_X/Y` (left), `ABS_Z/RZ` (right). Range ±32768, **fuzz 255, flat 4095** |
| Triggers | `ABS_GAS` / `ABS_BRAKE` — **not** `LTRIGGER`/`RTRIGGER`. Range 0..255, flat 15 |
| D-pad | `ABS_HAT0X` / `ABS_HAT0Y` only. **No `BTN_DPAD_*` exists in hardware** |
| Buttons | `BTN_GAMEPAD`(south) `BTN_EAST` `BTN_NORTH` `BTN_WEST` `BTN_TL` `BTN_TR` **`BTN_TL2` `BTN_TR2`** `BTN_SELECT` `BTN_START` `BTN_MODE` `BTN_THUMBL` `BTN_THUMBR` |

Consequences that change the design:

- **The sticks ARE in joystick mode.** The stick-to-mouse-mode risk does not apply. Stick
  navigation is viable.
- **`flat` is 0.125, not 0.** 4095/32768. The "Hall sticks declare no dead zone" worry does
  **not** hold on this unit — the driver declares a 12.5% one. `AxisStats.FLOOR` of 0.12 is
  about right; the plan's proposed 0.28 floor would be far too aggressive. Resting drift still
  needs measuring before this is final.
- **Triggers are dual-reported**: analog via `ABS_GAS`/`ABS_BRAKE` *and* digital via
  `BTN_TL2`/`BTN_TR2`. So they need the same source de-duplication as the D-pad, or every
  trigger pull fires twice.
- **The D-pad is hat-only in hardware**, so any `KEYCODE_DPAD_*` we receive is Android
  synthesising it from the hat. Expect both streams; `DpadSourceLatch` is required.

Two further input devices exist: `"AYANEO DEVICE"` (`BTN_MISC`, `BTN_1`..`BTN_7` — the extra
hardware buttons, almost certainly what the vendor accessibility service is for) and
`"AYANEO Controller aya_haptic"` (`FF_RUMBLE`, `FF_PERIODIC`, …), which confirms haptics are
routed to the rumble motors.

### Confirmed by pressing every button, A/B tested with the vendor service on and off

The whole button set was pressed twice: once with `WindowKeyEventService` enabled, once
with it removed via `dev.sh keys off`. **The two runs were identical.**

- **Neither accessibility service touches the gamepad.** Every button arrived in both runs:
  `A` 96/scan304, `B` 97/305, `X` 99/307, `Y` 100/308, `L1` 102/310, `R1` 103/311,
  `L2` 104/312, `R2` 105/313, `SELECT` 109/314, `START` 108/315, `MODE` 110/316,
  `THUMBL` 106/317, `THUMBR` 107/318 — all `dev=55`, source `keyboard+gamepad`. So L1/R1
  section switching is safe and no workaround is needed.
  Note there are **two** services enabled, not one: the vendor's, and
  `com.pocketds.kbm/...CursorAccessibilityService` from the sibling keyboard project.
- **The D-pad produces NO key events at all — hat axes only.** Not "both", as feared, and
  **not** a case of keycodes being intercepted: with the vendor service disabled the result
  was unchanged, while `HAT_X`/`HAT_Y` both reached ±1.0 proving all four directions were
  pressed. So directional input must be read from `ABS_HAT0X/Y` in
  `dispatchGenericMotionEvent`, **not** from `dispatchKeyEvent`.
  Happy side effect: the D-pad is therefore *also* out of any accessibility service's reach,
  exactly like the sticks — **every navigation input in the app is**. `DpadSourceLatch`
  drops from required to a cheap guard against firmware changes.
- **Triggers are dual-reported**, seen in one run: analog `ABS_BRAKE` reached 1.000 and
  `ABS_GAS` 0.855, *and* `BUTTON_L2`/`BUTTON_R2` key events fired for the same pulls.
  De-duplicate, or every pull fires twice. (Conventionally `BRAKE`=L2, `GAS`=R2; confirm
  once if the distinction ever matters.)
- **Face button layout is Xbox-standard and matches the keycodes exactly**: physically
  top=`Y`, bottom=`A`, left=`X`, right=`B`. No remap needed;
  `PadSettings.swapAcceptCancel` stays a safety net rather than a default.
- **`BACK` arrives as `dev=-1`, source `0x00000000`** (the virtual device), not from the
  controller — so it is distinguishable from a real button.
- Stick travel reaches ±1.0. `RX`/`RY` appear in the framework's axis list but this
  hardware never reports them, so they are filtered out per-device.
- **No meaningful drift.** Untouched, the sticks produced *zero* motion events in 34s; over
  166 resting samples the worst reading was **0.004**, giving a suggested dead zone of
  **0.12**. These are clean Hall sticks. The 0.28 floor the plan proposed would throw away
  a quarter of the usable travel for no reason.

  Caveat on the probe: a *deliberate* nudge under the tracker's 0.5 "that was on purpose"
  threshold still counts as drift. A stick knocked to 0.199 while being clicked in produced
  a bogus 0.30 suggestion. Cross-check the per-axis rows — real drift moves every axis a
  little, a nudge moves exactly one a lot.

### Probe bugs found and fixed during this run

- "stick drift at rest" was computed from session-wide min/max, so one deliberate full
  push made it report 1.000 and suggest a 0.50 dead zone. Replaced with `RestDriftTracker`,
  which ignores deflection past 0.5 and waits out a settle window after release.
- Every watched axis was recorded on every event regardless of whether the device has it,
  so absent axes showed as rows of perfect zeros with thousands of samples. Now only the
  axes a device declares are recorded, cached per `deviceId`.

## Device facts (from spec / the sibling project)

- Main screen **1920x1080, 7", 165Hz**. Secondary **1024x768, 5"**. v1 is single-screen.
- Secondary display's `displayId` is **unstable** (seen going 2 → 4). A `Presentation` bound to
  a stale `Display` becomes a zombie that reports itself visible while showing nothing.
- `displayMetrics` under-reports height on the bottom screen; use `display.getRealMetrics()`.
- Vendor accessibility service `WindowKeyEventService` owns hardware keys. It sees `KeyEvent`s
  **before the foreground app** and can consume them; a normal app cannot outrank it.
  **But it cannot intercept `MotionEvent`s** — analog stick axes are structurally out of its
  reach. This is why the sticks must be a *sufficient* input path, never a convenience.
- A vendor `SECONDARY_HOME` launcher (`com.ayaneo.gamewindow`) always occupies the bottom
  screen — treat it as "empty", not "an app is here".
- System edge gestures own the outermost ~14dp of the bottom screen.
- Haptics are forwarded to the rumble motors: `CLOCK_TICK` = 10 ms pulse, `KEYBOARD_TAP` =
  50 ms at 86 %. So "light" is the clock tick, contrary to the constant names.

## Feature notes and future work

### Library, as built

The Library (Movies and TV, #11) opens on the prototype's root: a glass search field and
Favourites, "Your libraries" over how many titles they hold, and a 16:10 tile per library, three
across. Each tile is the library's picture, blurred and dimmed, with up to three of the hub's `fan`
posters leaning across its top right (the day's pick at the back on the right) and a glass label
naming it; Marvel TV, a library of one title, is the back poster alone. A tile, or Favourites, pushes
the library's page: a capsule of every library and Favourites that switches in place, a round
search, Sort and its direction, and the seven-column grid of glass posters with their counts or
ticks.

`GET /v1/library` supplies the server folders in Jellyfin's order.
An explicit collection image wins; otherwise each tile uses a title
poster selected deterministically from that folder for the media PC's current local day. On this Jellyfin
10.11.8 install, generated view collages live under `metadata/library`, while the explicit Marvel
images are `folder.jpg` / `folder.webp` under the configured library root; `/Items/{id}/Images`
provides that distinction. For the tiles each view also carries `fan` and `total` (#13). `fan`
holds up to three posters from places spread through the library (the day's pick first, the others
kept apart so one franchise cannot fill a fan), skipping any without a poster. `total` counts the library's films and
series. A banner library fans its contents too. Both apps read these rather than choosing their own.
Libraries are listed A to Z by default, on both sides. A person can arrange them, and the order is kept
on the hub per Jellyfin profile so every device shows it (#15). The apps show the hub's order and
send a new one with `PUT /v1/library/order`; they never sort libraries themselves.

On the Pocket both roots arrange in place. Arrange beside "Your libraries" (or "Your reading
libraries") starts it, and so does holding a tile; the tiles wiggle, each shows the grip in its top
corner, and Arrange reads Done. Ⓐ picks up the tile in focus, which stands still and grows with its
grip lit, the D-pad moves it a place or a row with the others sliding out of its way, Ⓐ puts it down
and Ⓑ puts it back where it was; a pointer drags it. Each drop saves at once; a save that fails slides
the tiles back and says "The new order could not be saved · …". Ⓨ goes back to A to Z, and Ⓑ (with
nothing lifted), Back, Done or leaving the page finishes, a tile still lifted put down where it is.
Reading lists stay last and still, since they are not a library. Settings › Libraries lists both sides
in the same order, each row with the same grip at its end: a pointer drags a row by it, and on the pad
Ⓐ picks the row up, the D-pad moves it, Ⓐ drops it and Ⓑ puts it back (the user asked for the grip on
2026-10-04, in place of up and down arrows). Back to A–Z sits under a side that has its own order. A
root that drew an older order reads the hub's again when it comes back (`LibraryOrderChanges`), and the
capsule on a library's page follows the root. uiautomator cannot dump the screen while the tiles
wiggle (it waits for the animations to settle), so a scripted check reads a screenshot there.

Opening a folder uses `GET /v1/library/{viewId}/items?page=&sort=&order=` in 60-item pages. The
poster grid derives its span count from the measured content width: seven columns on the Pocket,
fewer only on a smaller window. Y opens a parameter menu (name, release date, date added,
year, parental rating, community/critic rating, runtime, last played) followed by ascending/descending.
Sorting happens in Jellyfin so every page shares the same order. Library cards suppress the redundant
Partial/In library badges while retaining watch progress and showing watched, favourite and unwatched-count
badges. The Library root also exposes Jellyfin search and a paged Favourites destination. The screen
retains loaded pages and focused position on its section
stack; requests stop while hidden and an interrupted page becomes retryable when the screen
returns.

Details use Jellyfin item IDs directly, so an absent TMDB provider ID cannot block browsing:

- `GET /v1/library/items/{itemId}` returns a movie, series, season, or episode with artwork,
  overview, original title, runtime/ratings, certification, studios, people, normalized media
  versions/tracks, parent IDs, and configured-user watch/favourite state.
- `GET /v1/library/series/{seriesId}/seasons` returns Jellyfin's seasons, including Specials when
  present.
- `GET /v1/library/series/{seriesId}/episodes?seasonId=…&page=…` returns available episodes in
  60-item pages with episode numbering, thumbnails, runtime, and progress.
- `GET /v1/library/search?q=…&page=…` and `GET /v1/library/favorites?page=…` provide the
  selected user's paged collection views.
- `POST /v1/library/items/{itemId}/state` changes exactly one watched or favourite value,
  then returns the freshly read Jellyfin item; the app updates optimistically and rolls back on failure.

Series details also render the selected user's Resume/Next/Start episode as a landscape progress
card. Seasons are a horizontal poster strip using Jellyfin's season artwork, and a selected season
opens a horizontal episode strip with 16:9 stills and watched/resume progress. Focusable Library
cards activate on the first pointer tap as well as with A; pointer swipes still scroll either strip.
Y on a focused season, or from inside its episode strip, opens the manual release-target screen when
the series has a TMDB provider id. The screen keeps the season poster visible and offers the whole
season plus each episode that has aired; choosing an episode performs a Sonarr `episodeId` search.

The adapter shapes were checked against Jellyfin 10.11.8 on this machine. In particular,
`/Shows/{id}/Episodes` accepts the season ID and needs `isMissing=false` so an API-key caller does
not receive virtual missing episodes.

### Books (#11)

Books Home is the prototype's: the book being read at full cover size with the words beside its foot
(`ContinueReadingView`), Resume reading gold and Details glass; the other books being read under it
as glass rows two to a line, with their formats as glass chips; Your series as fans (`CoverFanView`);
then rows of glass covers with their captions, comics with their kind pill. The page takes the colours
of the cover in focus. A book's page is `DetailHeaderView` with `book` set: no backdrop,
the cover at the left (square for an audiobook, a series' fan in its place), the eyebrow
(`ReadingBookFacts.eyebrow`) over the title, the formats as glass chips (missing ones dimmed), the bar
in the accent, the main action gold and the toggles glass. A book of its own has the owner's layout "1"
since #39 (its row under Shared building blocks): formats you can open, Resume with the chapter, stars,
the finish date and shelves under the cover. A series' page adds its continue card; a
comic run of several volumes shows one volume's issues at a time, picked with glass chips. An author's
page is a round portrait in a white ring over the series as glass pills. The Books Library page is the
library's name, Series | Authors | Books as a glass capsule with Sort, and seven columns of covers
(64dp portraits for Authors). Books Discover is the filters as a glass capsule beside the search over
rows of captioned covers; its request page is a book page with "EBOOK · NOT IN YOUR LIBRARY" and the
form as the glass side sheet. The Books Activity tab's transfers are glass rows with the state as a
chip (`ReadingTransferSummary.chipLabel`).

### Discover, as built

Four rows from Jellyseerr's own discover feeds, all in **one** hub request — the hub fans out
concurrently, measured at 99ms for all four. Trending now / Popular films / Popular series /
Coming soon. Each row pages when focus gets within 6 cards of its end, so a row is effectively
endless (upstream reports 58,798 pages of popular films).

Card geometry is measured, not guessed: the usable area is 853x456dp, and after the tab bar,
hint bar, search field and status line there are ~340dp left. A row therefore has to fit inside
~170dp for two to be visible at once, which is the Findroid look. A row's posters are
`ROW_CARD_DP = 82` wide (82 x 123dp); search fills seven columns since it has no row labels. A
poster's width decides its height, at 2:3.

Discover (#11) is a glass capsule (Discover | Upcoming) beside a glass search pill, the
featured card (picture and "FEATURED · NOT IN YOUR LIBRARY"), then rows of caption-less 82 x 123dp
posters with where each stands as a glass chip (In library, Partial, On the way, Requested, in the
badge colours above); search is the dense grid with captions. The page takes the colours of the
card in focus (`pageArtwork`). Upcoming is the week as a glass capsule, the days with each release
on a glass row and its state as a chip, the one in the preview lit in the accent, and the preview
a glass card that tints the page.

**Load on "do I have data", never on "have I tried".** A once-only flag left Discover stuck on
"Loading…" forever: this device pauses and resumes the activity once during startup, `onHide`
cancels the in-flight request, and the flag then blocked the retry. Nothing had failed, so
there was no error to show either. `ReleasesScreen` had the same shape and the same fix.

**A paging guard needs the requested page, not just an in-flight job.** A cached page comes
back in 7ms, so the job is already finished when the next scroll event arrives while the row
object the strip holds has not been re-bound yet — page 2 was fetched twice, 48ms apart.
`requestedPages` tracks the high-water mark instead.

**Manual series searches have two distinct scopes.** Sonarr's `seriesId + seasonNumber` release
query is a broad season search and can return both packs and individual episodes in one flat list.
For an episode, first read `/api/v3/episode?seriesId=&seasonNumber=` and then search
`/api/v3/release?episodeId=` with Sonarr's canonical episode id. On this installed Sonarr, Last Seen
season 1 returned six episode records on 2026-09-09, two of which had aired; episode id 9998 returned
ten S01E01 releases. The Hub sends only season/episode numbers to the app, resolves the id again for
search/grab, and scope-blocks a full-season or multi-episode result when one episode was selected.
Artwork comes from Jellyseerr's `/api/v1/tv/{tmdbId}/season/{season}` response so season posters and
episode `stillPath` values can use the existing authenticated TMDB image proxy.

### Focus, and why the framework cannot be trusted with it

Three separate escapes had to be closed before a row of posters behaved. All three looked like
"the D-pad is dead", and none of them were.

- **`LinearLayoutManager.onFocusSearchFailed` scrolls while hunting for a candidate.** That
  scroll detaches the card holding focus, and Android then hands focus to the first focusable
  view in the window. Running along the trending row silently dumped the selection on the
  *Discover chrome* — no move was ever accepted, the selection simply fell sideways out of the row.
  `ui/StripNav` therefore moves left/right **by adapter position**, never by focus search: the
  next card either exists and takes focus, or it does not and nothing happens. That "nothing
  happens" is the behaviour you want while the next page loads.
- **`FocusFinder` is a global search.** With nothing to the right it happily returns the
  leftmost card, so the selection teleported back to the start of the row. `input/FocusGuard`
  rejects a candidate that is not genuinely in the pressed direction. `HorizontalMode.CONFINED`
  (the default) keeps left/right inside the band; `GRID` lets the search results wrap to the
  next line, which is what a grid should do.
- **A declined directional key falls through to the framework.** The source latch drops
  duplicates on purpose, and `super.dispatchKeyEvent` then performed its own unguarded search.
  `HubActivity` now consumes DPAD keys itself — except in a text field, where left and right
  move the caret.

**The top bar is reached only with Up, and left only with Down or B.** It replaced the side rail
(2026-10 redesign: the rail cost a poster column on every page). `HubActivity.moveFocus` notices a
focus search that would land in `nav/TopBarView` and hands over to `TopBarView.focusFirst` (the
current tab); inside the bar left and right walk tabs, Media/Books and the icons in order. The bar
is added to the window after the content, so a cleared focus lands in the content first.

**`hints()` reads the focused item, so it has to be recomputed after focus lands** — in
`moveFocus`, in `showCurrent`'s post, and in each adapter's own focus settling. And that
settling must run even when nothing was focused before, which is the first-load case.

**Row labels: top padding, not a scroll override.** RecyclerView scrolls the focused *card*
into view, and a card sits below its row title, so "Trending now" vanished the moment focus
returned to the first row. Two overrides were tried and both were worse:
`requestChildRectangleOnScreen` is **never consulted on the focus path**, so expanding its
rectangle did nothing at all; correcting the position in `requestChildFocus` did work, but only
*after* RecyclerView had already scrolled — two scrolls per press, the second an instant jump,
which read as a stutter.

The answer is `paddingTop = 28dp` with `clipToPadding = false`. RecyclerView brings a focused
card inside the **padded** bounds, so the label directly above it lands in the padding band,
which is still drawn. One animated scroll, label always visible, no override.

### Home: a hero over rows

Since the 2026-10 redesign, Home (media) is a hero for the focused card over rows of cards. The
hero (`screens/home/HomeHeroView`, content from the pure `HomeHero`) changes the moment focus
moves, from what the card already carries, and fills in the certification and runtime from
`/v1/library/items/{id}` once focus rests for 220ms (cached per item). It has no overview, and
every line keeps its place: the 120dp progress line is held open (empty) for an unstarted title
and the buttons have a fixed position, so nothing moves as focus runs along a row. A two-line
overview plus a progress line used to push Play under the first row. The focused row always
rests at the top of the rows (`pinFocusedRows`), so the hero is one size on every row. An episode's hero
uses its series' backdrop (`/v1/img/jf/{seriesId}/Backdrop`, untagged) at `w=1280`. Play and
Details sit in it: Up from the first row lands on Play, Down returns to the card you came from,
and a series' Play resolves its resume/next/first episode through `seriesPlayTarget`. Cards carry
no focus fill: a white ring round the artwork and a small lift, as everywhere.

The rows, in the default order (`HomeRows.DEFAULT_ORDER`), are ordered by how soon you'd act:

1. **Continue watching** — part-watched items. `/Items?filters=IsResumable`, carrying
   `positionSeconds` / `runtimeSeconds` so the card can draw a progress bar. It keeps the
   first, most recent unfinished episode per series.
2. **Next up** — you finished an episode and the next one exists. Jellyfin has a purpose-built
   endpoint for exactly this, `/Shows/NextUp?userId=`; do **not** try to derive it from watch
   state, it handles specials, gaps and season boundaries. A series is removed from this row
   while it has an item in Continue watching; finishing that episode makes it eligible again.
3. **Recently added** — `/Items/Latest?userId=&includeItemTypes=Movie,Episode&groupItems=true`.
   Episodes are promoted to their parent series and duplicate series are collapsed while keeping
   Jellyfin's recency order. Asking this endpoint for `Movie,Series` dropped episode-heavy results;
   the live Dgdan row contained one card before the fix and 19 after it.
4. **Favourites** — Jellyfin `/Items?filters=IsFavorite&recursive=true`.
5. **Coming up** — built on the handheld from `/v1/calendar` for the next 14 days: each monitored
   series or film once, at its next release not yet on disk, with a day tag ("Tomorrow", "Fri").
   Not in the library, so A opens its request-side page and the hero offers Details only.

The first four come from one `GET /v1/home` request. Settings › Home (`HomeRowSettings`) reorders
and hides rows and can add "From <library>", the newest 20 of one library. Empty rows are
hidden; a row the hub could not refresh keeps its place. The screen keeps its loaded rows and
focused item across detail navigation and section switches.

Continue Watching and Next Up use 16:9 tiles (186dp) with the series title and
`SxxEyy · episode title` under them; the other rows are caption-less 82 x 123dp posters,
since the hero names the focused one. A opens details and X plays or resumes, on any row.

Home is the prototype's (#11): a 204dp hero whose art runs under the bar and fades into
the page through `FadedImageView` (solid to 40%, gone by 97%, the word shade inside the same
layer), the eyebrow's code in the accent (`HeroContent.eyebrowMark`), Play as a white pill that
names Next up's episode (`playAction`: "Play S2E1") beside a glass Details; rows from 204dp with
Figtree bold titles, 186dp tiles and 82 x 123dp posters, Coming up's day on a glass chip, and no
fades at the rows' edges.

**Y / Profiles** opens the profile picker; the selection persists on the handheld and is sent as
`X-Jellyfin-User`. The hub varies and keys Home and Library caches by that id. Changing profile
recreates the section stacks so rows, detail progress and loaded Library pages cannot retain the
previous user's state. With no device choice, the configured server default remains the
fallback (Dgdan here).

### A richer request flow — built

Requesting now opens an in-layout form: quality profile, root folder, and for a series either
"all seasons" or a per-season tick list. Interactive search is Ⓨ on the detail screen. What
follows is the original note, kept for the measurements:

**1. A request dialog** — quality profile, root folder, and (for series) which seasons.
Everything needed is already there and verified on this install:

`GET /api/v1/service/radarr` lists the servers; `GET /api/v1/service/radarr/{id}` returns
`profiles`, `rootFolders` and `tags`. Measured here: **7 quality profiles** (Any, SD, HD-720p,
HD-1080p (Normal), Ultra-HD, HD-720p/1080p, 1080p High Quality) and **2 root folders** —
`E:\Videos\Daniel\Movies` and `E:\Videos\Daniel\Marvel\Movies`. A separate Marvel folder
means "where does this go?" is a genuine question here, not a hypothetical.

`POST /api/v1/request` already accepts `profileId`, `rootFolder`, `serverId`, `is4k`,
`seasons`, `languageProfileId` and `tags` — the hub just does not send them yet. Only one
Radarr server is configured and it is not 4K, so the multi-instance hazard does not apply on
this stack.

Needs a modal that is gamepad-drivable. Per the A1 decision that must be an **in-layout
overlay, not an AlertDialog** — a dialog opens a second window with its own focus rules and
leaves the hint bar stale behind it.

**2. Interactive search** — the manual release picker from Sonarr/Radarr: see every release
with size, seeders, quality, indexer and rejection reasons, and grab a specific one.
`GET /api/v3/release?movieId=` answers 200 on this Radarr. Two things to design around: it
queries every indexer so it takes **tens of seconds**, and grabbing is
`POST /api/v3/release` with the chosen release's `guid`. Needs the Radarr/Sonarr adapters.

A season-specific search can include a rejected multi-season pack. Sonarr explicitly reported
`Multi-season releases are not supported` for The Mentalist result that downloaded 149 files
across Seasons 1–7 from a Season 1 search. Such a result is now `scopeBlocked`: the app explains
the mismatch and the hub refuses the grab even from an older app. Ordinary manual overrides,
such as a quality-profile rejection, remain available.

**3. A download manager** — pause, resume, delete, remove-and-blocklist, re-search. This is
H3, and the same adapters unlock interactive search.

### Native playback

The original Findroid handoff is impossible because its player Activity is not exported. The
replacement is implemented as a full-screen Media3 player whose stream and Jellyfin session
are prepared through the hub, keeping the Jellyfin API key off the handheld. `HubActivity`
stays the single UI Activity; `PlaybackService` owns ExoPlayer and a MediaSession while
`PlayerScreen` supplies the controller-first overlay.

Movies, episodes and series open their Jellyfin detail page from Home, Library and season rows.
Their compact action row is icon-only, apart from the time displayed beside a resumable Play
symbol; accessibility labels and the hint bar retain the action names. Series details resolve
the selected user's resumable, next, or first episode. Playback options expose
media version, audio, subtitles and Original/40/20/10/5/2 Mbps quality. The hub owns byte-range
streaming, HLS manifest rewriting, external subtitle extraction, ordered Jellyfin events,
session ownership and 30-minute abandoned-session cleanup.

The player (#11, GLASS_PLAN.md › Player) reports what is playing as the page's artwork (an
episode its series' backdrop), so its glass takes that title's colours; over video the page keeps the
colours it has until the new ones arrive. Every control but Play is that dark glass, Audio & subtitles and
Chapters carry their icons, the timeline and its times sit in a frosted bar 14dp in from the edges
(a 6dp white line on a faint track, the buffered part lighter, a white thumb that rings with focus),
and the panels are the glass side sheet. What the controls do and where focus goes are unchanged.

The player overlay (2026-10 redesign, `playback/PlayerChrome`) has a round Back at the top left, the
title with the episode under it ("S1E1 · Somewhere Not Here", `PlayerLabels.title`/`subtitle`), pills
for Audio & subtitles, Chapters and This video, and round Cast, Lock and PiP buttons. Their panels
are the shared side panel: Audio & subtitles lists the audio tracks, Off and the subtitle tracks, then
subtitle timing and look, in one list (the cursor starts on the kind changed last); This video has
Quality ("Original · 1080p"), Version (several sources only), Speed, Aspect, Subtitle timing and
Stream rows, each opening its own list with Back returning there; Chapters shows a frame from a
quarter of the way into each chapter (the hub's extracted preview, `chapterFrameMillis`), its start,
length and Intro/Credits when a skip segment starts with it. Previous, −10,
a white Play disc, +10 and Next sit in the middle of the picture (a missing neighbour keeps its slot,
so Play stays centred), using a configurable 5/10/15/30-second seek step (10 seconds by default).
The timeline runs the full width with "5:34 · Part A" and "−22:53" under it
(`PlayerLabels.positionLine`/`remainingLine`; generic "Chapter 2" names are left out). Focus runs
top bar, middle row, timeline; A clicks any focused overlay control and plays/pauses on the timeline. A tap outside a control
toggles the overlay. Android system Back, including the Pocket DS edge gesture, exits playback
immediately and restores the originating Library/Home focus; physical B retains the layered
sheet/hide/exit behavior. Native Android PiP keeps the same MediaSession playback running;
dismissing the PiP window stops playback, closes the hub session and removes the task.
Double-tapping either video side applies the configured short seek. A horizontal drag scrubs
with destination time, delta and a small preview anchored over the matching timeline point;
dragging the timeline shows the destination time and frame without a delta. The timeline thumb
moves with either gesture. D-pad/stick left and right traverse the focused controls and only seek
when the timeline itself has focus. Vertical drags change brightness on the left half and media
volume on the right, with compact percentage bars. The CC sheet opens a live timing bar from 60
seconds earlier through 60 seconds later. SRT/WebVTT is fetched and parsed once into a local cue
timeline, so 0.1-second slider/D-pad changes redraw immediately without rebuilding the video or
subtitle source; L2/R2 changes by one second. The compact panel exposes controller-focusable Reset
and Done actions. It stores the correction on the device per Jellyfin user and series, or per movie,
so another profile is unaffected. Session URLs remain private to the app.

Hardware validation on Android 13 exercised H.264 HLS playback, E-AC-3/AC-3 to AAC conversion,
external SRT delivery, pause/resume, a 10-second jump, background pause/return, clean exit and
origin focus restoration. Jellyfin 10.11.8 returns hyphenated UUIDs inside transcode URLs even
when item APIs use compact IDs; HLS validation canonicalizes both forms. It also puts `ApiKey`
in those URLs, so credentials are stripped case-insensitively before any opaque URL reaches the
app and upstream requests use the adapter's private header.

Playback exit also refreshes Home, item details, series play targets and the selected loaded
episode without discarding their focus or paging state. A two-minute checkpoint stored per
Jellyfin user covers the moment between Back and the hub's refreshed read; it is used only for
valid Resume requests and never for Start over.

**Jellyfin keeps a watch position only through the user-data endpoint.** `/Sessions/Playing*`
reports are attributed to the signed-in user, and the hub signs in with an API key, which has no
user: Jellyfin accepts them and saves nothing. Until 2026-09-30 that was the only path, and the
checkpoint above hid it for two minutes before the old position came back (the earlier "S2E8 moved
to Resume · 2:16" check was the checkpoint, not the server). Measured on 10.11.8: resume at 17:12,
watch 40 seconds, stop, and the server still said 17:12. `hub/internal/api/watchstate.go`
`recordWatchPosition` is now the only writer: `POST /UserItems/{id}/UserData?userId=` with
`PlaybackPositionTicks` and `LastPlayedDate` (a partial update that leaves favourite and play count
alone), judged by the server's own `MinResumePct`/`MaxResumePct`/`MinResumeDurationSeconds` from
`/System/Configuration`. Live progress, stops, abandoned sessions and offline sync all go through
it; the session reports remain only for Jellyfin's dashboard. Verified against the live server
through the deployed hub: a stop at 18:20 was kept as 18:20.

The delivered first release and later player milestones are recorded in `PLAYER_PLAN.md`.
The trickplay gateway and preview UI are delivered. The live server returned no generated
trickplay data across 15 sampled Home items, so the session-bound preview endpoint now extracts
five-second-bucket JPEG frames with the Jellyfin server's ffmpeg without exposing media paths.
Chapters, media-segment skipping, subtitle appearance, speed/aspect modes and PiP remote
actions/automatic entry remain later work. Offline playback and its queue/catalog/storage/sync
contract are delivered and recorded in `OFFLINE_DOWNLOAD_PLAN.md`. mpv/libass remains optional if hardware testing
finds a format Media3 plus Jellyfin transcoding cannot handle well.

### Later custom theme packs

The app has one dark look since #20, with no light theme to follow, and the service logos are
one set, drawn for the dark page.
The later work here is user-defined palette packs:

Settings picks a **pack of two or three seed colours** rather than a fixed light/dark pair:
accent, background, and an optional contrast colour for when white is wrong on that
background. Everything else in `PocketColors` derives from those.

This fits the project's conventions almost too neatly: `ThemePack.derive(accent, background,
contrast?) -> PocketColors` is a pure function of three ints, so the derivation — surface
tints, muted text, the focus fill, badge colours, and a *contrast check* that text stays
readable on every generated surface — is unit-tested on the JVM with no device. `Theme` then
just picks the stored pack instead of the fixed dark palette.

`KeyPressTint.pressed()` and `isDarkSurface()` already do the "move a fraction of the
remaining distance toward white or black" trick that surface derivation needs, so the maths is
half written.

## Input requirements

**Every action must be reachable three ways.** The bottom screen runs the sibling keyboard
project as a trackpad + keyboard, so pointer and text input are first-class, not fallbacks.

| Intent | Gamepad | Pointer (trackpad or direct touch) |
|---|---|---|
| Move focus | left stick, D-pad (hat) | n/a — the cursor *is* the selection |
| Activate | `BUTTON_A` | tap / Left Click |
| Back | `BUTTON_B`, `KEYCODE_BACK` | tap the Back chip in the hint bar |
| Primary action | `BUTTON_X` | tap its hint-bar chip |
| Secondary action | `BUTTON_Y` | long-press the card, or Right Click |
| Switch section | `L1` / `R1` | tap a tab |
| Page | `L2` / `R2` | two-finger scroll / fling |
| Text entry | focus a field, press `A` | tap the field |

Design consequences:

- **The hint bar doubles as a touch action bar.** Its `Ⓐ Ⓑ Ⓧ Ⓨ` chips are real tappable
  buttons, so pointer users get every contextual action through the same mechanism that
  labels them for gamepad users. One widget, one source of truth, no parallel UI.
- **`focusableInTouchMode` is mandatory, not defensive.** The trackpad drives a cursor via
  `dispatchGesture`, which puts the window into touch mode; plain `focusable` views then
  refuse focus and the gamepad appears dead. Always go through `Styler.makeFocusable()`.
- **The focus ring is shown only in directional mode.** A ring following the gamepad while
  someone is using a cursor is noise. `InputModeTracker` (pure) flips on the last input
  seen — pointer events hide it, stick/D-pad/button events bring it back.
- **The D-pad needs our own repeat.** Hat axes deliver one event on press and one on
  release, with no `repeatCount` and no OS auto-repeat. The same `AnalogRepeater` +
  `Choreographer` ticker that repeats the stick drives the hat at full magnitude.
- **Tap targets stay finger-sized** even though a cursor is precise, because direct touch on
  the top screen is also supported.

## Traps already paid for

- **ScrollView and HorizontalScrollView are focusable by default.** They become invisible
  focus stops: a directional press lands on the scroller, draws no ring, and reads as the pad
  being dead. Set `isFocusable = false` on every scroll container so focus passes through to
  its contents. This cost an hour and looked like three different bugs.
- **A GONE view keeps window focus.** After switching screens, the outgoing screen's focused
  view is still what `currentFocus` returns, so the next directional press searches outward
  from something invisible. `showCurrent` clears it, and `moveFocus` also ignores a `from`
  that is not `isShown`.
- **`hints()` reads the focused item, so it must be recomputed after focus lands.** Since
  2026-10-01 a global focus listener in `HubActivity` does this on every focus change. Contextual
  chips are derived from the selection, and every path that moves focus asynchronously needs to
  refresh them afterwards: `moveFocus`, `showCurrent`'s post, and the adapter's own focus
  settling. Downloads showed a blank Ⓧ on a transfer that could plainly be stopped, because
  every refresh ran a frame before focus existed.
- **Focus settling must run even when nothing was focused before.** Restoring focus only when
  there was a previous id meant that on first load — an empty list — no row was ever focused by
  us and the hint bar was never recomputed.
- **Clearing focus hands it to the first focusable in the window.** The top bar is added after
  the content for exactly this reason, so a clear cannot strand the selection in the tabs.
  Expect focus to be *somewhere* after a clear, not nowhere.
- **Focus passes through a page while it is pushed away and while it comes back** (#22, #23).
  Leaving, the host cleared focus while the page was still on screen, so the view nearest the scroll
  position took it for a moment; coming back, the page's first layout restores the window's default
  focus before the page's own `requestInitialFocus` runs, and took that same nearest view. A page
  whose focus listeners remember "where you were" recorded those: Back landed on a continue card,
  the tabs, or Settings' list of sections rather than its switch. `FocusPlace.show` now closes the
  page to focus while the focus is cleared and marks what had focus as its default focus, so
  Android's own restore lands there. A page that draws itself again on coming back loses that view,
  so it carries the place to the new one (`across`, `mark`): before #23 a comic run's page landed on
  its Want to Read toggle. A page that reads itself again a moment after it is back must not draw
  what is unchanged: both title pages drew their cast again (the Library's 450 ms after Back,
  Discover's every four seconds while a download runs) and dropped the card in focus; `CastRowView`
  and `FactsGridView` now leave the same people and facts as they are.
- **Neither form of `requestFocus` gives traversal order inside a ScrollView.** It overrides
  `onRequestFocusInDescendants` to prefer whatever is nearest the current scroll position. A
  screen that cares must say where focus starts: `Screen.requestInitialFocus()`.
- **Not every screen should take focus when it appears.** A grid should; a detail page should
  not, or it scrolls its own header out of view before the title has been read.
  `Screen.focusOnShow` controls it.

- **Touch mode.** `focusable="true"` views refuse focus once the screen has been touched; only
  `focusableInTouchMode="true"` take it. D-pad `KeyEvent`s leave touch mode automatically —
  **analog-stick `MotionEvent`s do not**. Always set both, via `Styler.makeFocusable()`.
- **A held stick stops producing events.** Repeat must be pumped from a `Choreographer` frame
  callback holding the last known axis values, not driven by event arrival.
- **Hall sticks report `MotionRange.flat == 0`** and drift anyway. Never trust it; floor it.
- **`<queries>` is mandatory** on targetSdk 34, or `getLaunchIntentForPackage` returns null for
  an app that is plainly installed.
- **`IntArray` is not `Iterable`** — it has `map` but not `mapNotNull`. Use `.asIterable()`.
- **Readium reports a drag `End` with every tap**, with no `Start` before it (measured 2026-10-05).
  A listener that takes the end of a drag for a page moved by hand must check that a drag began.
  Read along took every tap for one: the tap that closed the menu also paused the narration (#21).
- **Findroid cannot be deep-linked.** Verified against its manifest: `PlayerActivity` is not
  exported and `MainActivity` declares only `MAIN`/`LAUNCHER`. The implemented replacement is
  Media3 in this app, with playback prepared and proxied by the hub.

---

## Hub notes (for when it starts)

- **Jellyfin has no provider-id query.** Only `hasTmdbId` etc. as *booleans*;
  `AnyProviderIdEquals` is Emby-only. The hub maintains an in-memory ProviderIds index by
  sweeping `/Items?fields=ProviderIds`; the measured 250-item sweep is too small to justify
  SQLite.
- **Bazarr's API is `/api`**, not `/api/v2`. Auth header is `X-API-KEY` (uppercase KEY).
  `/api/system/ping` is unauthenticated. Its `base_url` subfolder setting prefixes everything,
  so probe the path at startup.
- **Sonarr sets `DownloadId = torrent.Hash.ToUpper()`** — the *arr↔qBittorrent join is exact,
  case-insensitive. The only verified-exact join in the system.
- **qBittorrent**: newer builds accept `Authorization: Bearer`; older need the cookie dance,
  and the cookie name is not stable. Set both `Referer` and `Origin` or CSRF rejects you.
  Repeated failed logins get your IP banned by qBittorrent itself.
- **Do not codegen from Jellyfin's published OpenAPI spec** — it reports 12.0.0, where
  `/Users/{userId}/Items` and the HLS paths no longer exist. Use the server's own
  `/api-docs/openapi.json`, and prefer `/Items?userId=`.
- **Docker Desktop on Windows does not start without a logged-in user.** The hub runs as the
  Automatic (Delayed Start) FireDaemon service `AyaneoHub` under LocalSystem. Its deployed
  binary is `C:\Program Files\AyaneoHub\hub.exe`; config and logs stay under
  `C:\ProgramData\AyaneoHub`. The checked-in definition and idempotent administrator installer
  are `hub/deploy/windows/AyaneoHub.firedaemon.xml` and `install-firedaemon.ps1`.
- **Manage dashboard links come from `services.<name>.web_url`.** The deployed values use the
  `ayaneo-media-pc.tail737e96.ts.net` Tailscale Serve hostname and standard service ports. When
  `web_url` is empty, the Hub falls back to a credential-free copy of `base_url`, which is usually
  loopback and therefore wrong on the handheld. Keep Funnel disabled; these dashboards are for
  authenticated tailnet devices only.
- **The Activity tab opens each service's `web_url` in the default browser.** A loopback address
  would look for the service on the Pocket, so `ActivityDashboard.reachableFromPocket` refuses it and
  names the setting instead. Every service, the reading ones included, is served by Tailscale on its
  own port (`https://ayaneo-media-pc.tail737e96.ts.net:<port>`) and its `web_url` holds that address.
  Sonarr, Radarr and Readarr reach Prowlarr and qBittorrent through `localhost`, so serving those
  ports over HTTPS on the tailnet does not affect them.
- **Jellyfin library refresh is a narrow Hub action.** `POST /v1/manage/jellyfin/scan` requires the
  existing `control` scope and invokes Jellyfin's asynchronous `/Library/Refresh`. The app exposes
  it as a visible action on the Jellyfin Manage row and as X. The Hub clears affected response
  caches immediately and sweeps its provider-id index again 10, 30 and 60 seconds after the scan
  starts, so newly imported episodes can correct Discover availability without giving the APK an
  administrator API key.
- **Search and Discover enrich cached Jellyseerr results at render time.** A provider-index match
  replaces delayed `processing` with `available` for a movie or `partially_available` for a series,
  attaches the Jellyfin item id, and recomputes scoped actions. A live download remains visible as
  `downloading`. This was verified against Last Seen after its first episode was imported.

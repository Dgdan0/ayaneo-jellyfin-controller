# Ayaneo Jellyfin Controller

A single control seat for a self-hosted media stack — Jellyfin, Jellyseerr, Radarr, Sonarr,
Bazarr, qBittorrent — driven from an AYANEO Pocket DS handheld with the gamepad.

Two components, built in parallel:

- **`app/`** — `com.pocketds.hub`, Kotlin Android. Talks **only** to the hub, over HTTPS with
  a bearer token. Holds no service API keys.
- **`hub/`** — a Go service on the Windows media PC. Holds every API key, fans out to all six
  services, and does the cross-service join.

The full plan lives at `~/.claude/plans/hey-claude-i-know-shiny-boole.md`.

---

## Status

| Phase | State |
|---|---|
| **A0** app skeleton + input probe | **Done**, verified on the hardware |
| **A1** input + navigation | **Done.** Collapsible left rail plus L1/R1, compact controller hints and reliable focus; shell drivable on the device |
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
| **A13** settings + themes | **Done.** Follow-system/light/dark appearance, theme-specific service assets, notification history limits, playback seek distance and controller test |
| **H8 / A14** offline downloads | **Done.** Durable scoped Hub grants, range-resumable original files, series/season picker, persistent foreground queue, grouped manager, local-first playback and per-user deferred progress sync; live device path verified |

The 2026-09-08 Findroid 1.1.0 hardware audit, screenshot index, side-by-side feature matrix,
and recommended next milestones are in `FINDROID_COMPARISON.md`.

Offline opens on the durable downloaded catalog, grouped as one alphabetized poster per movie or
series across all libraries. A local series screen derives its season and episode rows only from
completed files. The manager is a secondary tab, and repository broadcasts replaced its former
750 ms polling rebuild. Offline Settings enumerates app-private roots returned by
`getExternalFilesDirs`: the chosen internal/SD location is used only for new jobs and stored absolute
paths keep earlier files valid. The Pocket DS has no platform AC-3/E-AC-3 decoder, so Original MKVs
with AC-3 audio use a minimal arm64 build of the official Media3 1.4.1 FFmpeg extension. Keep its
exact-source and LGPL material in `app/third_party/media3-ffmpeg` and `assets/licenses` with the AAR.

## Reaching the hub from the handheld

The preferred address is now **`https://ayaneo-media-pc.tail737e96.ts.net`**. Pocket DS and the
media PC are members of the same tailnet, and Tailscale Serve proxies this private HTTPS origin to
the loopback-only Hub on `127.0.0.1:8791`. Dashboard listeners on ports 8920, 5055, 7878, 8989,
6767, and 8080 are also tailnet-only; `services.<name>.web_url` contains those HTTPS addresses.
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
A2 swaps `PlaceholderScreen`'s adapter for real data behind `HubApi`/`FakeHubApi`.

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

`GET /v1/library` supplies the server folders in Jellyfin's order. The selector renders three
large bounded square cards across, which remain inside the content area even with the rail expanded.
An explicit collection image wins; otherwise each card uses a title
poster selected deterministically from that folder for the media PC's current local day. On this Jellyfin
10.11.8 install, generated view collages live under `metadata/library`, while the explicit Marvel
images are `folder.jpg` / `folder.webp` under the configured library root; `/Items/{id}/Images`
provides that distinction.

Opening a folder uses `GET /v1/library/{viewId}/items?page=&sort=&order=` in 60-item pages. The
poster grid derives its span count from the measured content width: seven columns with the compact
rail, six with the expanded rail, and fewer only on a smaller window. Y opens a parameter menu (name, release date, date added,
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

### Discover, as built

Four rows from Jellyseerr's own discover feeds, all in **one** hub request — the hub fans out
concurrently, measured at 99ms for all four. Trending now / Popular films / Popular series /
Coming soon. Each row pages when focus gets within 6 cards of its end, so a row is effectively
endless (upstream reports 58,798 pages of popular films).

Card geometry is measured, not guessed: the usable area is 853x456dp, and after the navigation rail,
hint bar, search field and status line there are ~340dp left. A row therefore has to fit inside
~170dp for two to be visible at once, which is the Findroid look. `ROW_POSTER_DP = 118`,
`ROW_CARD_DP = 82`; the search grid gets `GRID_POSTER_DP = 138` across 8 columns since it has no
row labels. `PosterCardView` takes the poster height as a parameter and drops to smaller type
and tighter padding below 170dp.

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

**The side rail is deliberately not focusable**, like the hint-bar chips: sections switch with
L1/R1 or a tap, and Start/app-mark tap toggles its 68dp/190dp collapsed/expanded width. Keeping it
out of focus search closes the whole "orphaned focus lands in navigation" class of bug.

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

### Home rows, in this order

The Infuse/Jellyfin shape. Deliberately ordered by how soon you'd act on the row:

1. **Favourites** — Jellyfin `/Items?filters=IsFavorite&recursive=true`. Things you keep
   coming back to belong above things the server guessed at.
2. **Continue watching** — part-watched items. `/Items?filters=IsResumable`, carrying
   `positionSeconds` / `runtimeSeconds` so the card can draw a progress bar. It keeps the
   first, most recent unfinished episode per series.
3. **Next up** — you finished an episode and the next one exists. Jellyfin has a purpose-built
   endpoint for exactly this, `/Shows/NextUp?userId=`; do **not** try to derive it from watch
   state, it handles specials, gaps and season boundaries. A series is removed from this row
   while it has an item in Continue watching; finishing that episode makes it eligible again.
4. **Recently added** — `/Items/Latest?userId=&includeItemTypes=Movie,Episode&groupItems=true`.
   Episodes are promoted to their parent series and duplicate series are collapsed while keeping
   Jellyfin's recency order. Asking this endpoint for `Movie,Series` dropped episode-heavy results;
   the live Dgdan row contained one card before the fix and 19 after it.

All four are rendered by the first app section from one `GET /v1/home` request, so the
handheld makes one round trip per screen rather than four. Empty rows are hidden. The screen
keeps its loaded rows and focused Jellyfin item across detail navigation and section switches,
and retains successful old rows when a refresh is partial. Verified on the Pocket DS with
three live rows and per-item watch progress.

Continue Watching and Next Up use 16:9 cards for the concrete playable movie or episode. Episodes
carry their own still, series title, and `SxxEyy · episode title`; completed items have no progress
line. Recently Added represents new movies and the parent series of new episodes, and A opens their
detail screen. A on Continue/Next plays or resumes the concrete item and X opens its details.

Home shows the active Jellyfin profile in its heading. **Y / Users** opens the profile picker;
the selection persists on the handheld and is sent as `X-Jellyfin-User`. The hub varies and
keys Home and Library caches by that id. Changing profile recreates the section stacks so rows,
detail progress and loaded Library pages cannot retain the previous user's state. With no
device choice, the configured server default remains the fallback (Dgdan here).

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

The player overlay has a compact title/action strip with box-free, dark-outlined Audio, CC,
Quality, PiP and Close symbols. Its bottom timeline uses Play/Pause, a configurable 5/10/15/30-second seek step
(10 seconds by default), and available Previous/Next episode actions. A tap outside a control
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
Jellyfin user bridges the short interval before Jellyfin publishes the final stopped position;
it is used only for valid Resume requests and never for Start over. A live Pocket DS check moved
Drake & Josh S2E8 from Play to `Resume · 2:16` immediately after Back, then reopened beyond that
position instead of using the earlier server value.

The delivered first release and later player milestones are recorded in `PLAYER_PLAN.md`.
The trickplay gateway and preview UI are delivered. The live server returned no generated
trickplay data across 15 sampled Home items, so the session-bound preview endpoint now extracts
five-second-bucket JPEG frames with the Jellyfin server's ffmpeg without exposing media paths.
Chapters, media-segment skipping, subtitle appearance, speed/aspect modes and PiP remote
actions/automatic entry remain later work. Offline playback and its queue/catalog/storage/sync
contract are delivered and recorded in `OFFLINE_DOWNLOAD_PLAN.md`. mpv/libass remains optional if hardware testing
finds a format Media3 plus Jellyfin transcoding cannot handle well.

### Later custom theme packs

Follow-system, Light and Dark modes are implemented, including Android night
resource variants for every service logo used by Manage and Notifications.
The later work here is user-defined palette packs:

Settings picks a **pack of two or three seed colours** rather than a fixed light/dark pair:
accent, background, and an optional contrast colour for when white is wrong on that
background. Everything else in `PocketColors` derives from those.

This fits the project's conventions almost too neatly: `ThemePack.derive(accent, background,
contrast?) -> PocketColors` is a pure function of three ints, so the derivation — surface
tints, muted text, the focus fill, badge colours, and a *contrast check* that text stays
readable on every generated surface — is unit-tested on the JVM with no device. `Theme` then
just picks the stored pack instead of a hardcoded LIGHT/DARK.

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
| Switch section | `L1` / `R1` | tap a rail item |
| Expand/collapse navigation | `Start` | tap the app mark |
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
- **`hints()` reads the focused item, so it must be recomputed after focus lands.** Contextual
  chips are derived from the selection, and every path that moves focus asynchronously needs to
  refresh them afterwards: `moveFocus`, `showCurrent`'s post, and the adapter's own focus
  settling. Downloads showed a blank Ⓧ on a transfer that could plainly be stopped, because
  every refresh ran a frame before focus existed.
- **Focus settling must run even when nothing was focused before.** Restoring focus only when
  there was a previous id meant that on first load — an empty list — no row was ever focused by
  us and the hint bar was never recomputed.
- **Clearing focus hands it to the first focusable in the window.** The rail is deliberately
  nonfocusable so this cannot strand the selection in top-level navigation.
  Expect focus to be *somewhere* after a clear, not nowhere.
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

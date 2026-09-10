# Ayaneo Jellyfin Controller — handoff

Everything a new contributor (human or agent) needs to pick this up. Read this
first, then `CLAUDE.md`, which holds the accumulated device- and
upstream-specific findings and is the file to keep updating.

---

## 1. What this is

One control seat for a self-hosted media stack — Jellyfin, Jellyseerr, Radarr,
Sonarr, Bazarr, qBittorrent — driven from an **AYANEO Pocket DS** handheld with
the gamepad.

The problem it solves: answering *"where is the film I asked for three days
ago?"* currently means opening four web UIs in a browser, on a handheld, none of
which are usable with a D-pad.

Two components:

| | |
|---|---|
| **`app/`** | `com.pocketds.hub`, Kotlin Android. Talks **only** to the hub, over HTTP(S) with a bearer token. Holds no service API keys. |
| **`hub/`** | A Go service on the Windows media PC. Holds every API key, fans out to all six services, does the cross-service join. |

The split exists so the handheld never carries credentials and makes **one**
round trip per screen instead of orchestrating five over a slow link.

---

## 2. Current state

### Done and verified against the real stack

| Phase | What |
|---|---|
| **A0–A3** | App skeleton, gamepad input, navigation, networking spine, search |
| **H0** | Hub skeleton, config validation, bearer auth, rate limiting, `/v1/health` |
| **H1** | Jellyseerr adapter — search, detail + pipeline, requests, image proxy |
| **H3 / A5** | qBittorrent + Radarr + Sonarr adapters, `/v1/activity`, one row per client transfer with episode-pack grouping, stop/start/delete/blocklist |
| **H4a / A6a** | Request options + interactive search; visual season/episode target picker, exact Sonarr episode searches, request dialog, and release picker in the app |
| **H5 / A7** | `/v1/discover` (four rows, one call, 99 ms); Discover rebuilt as Findroid-style poster rows |
| **H2 / A4** | Jellyfin adapter + provider-id index; Library browsing, daily folder artwork, sorting, search/Favourites, watched/favourite actions, count badges, cast/crew, media streams, seasons, and episodes |
| **A8** | Home is the first app section: selectable Jellyfin profile; landscape episode/movie cards for Continue Watching and Next Up with season/episode context; title-level Recently Added; per-series row exclusivity and focus restoration |
| **H6 / A9** | Native Media3 playback: Jellyfin prepare/range/HLS/subtitle/session gateway, movies and episodes, series play targets, resume/start-over, source/audio/subtitle/quality selection, progress reporting, adjacent episodes and native PiP |
| **A10** | Manage health dashboard: Ayaneo Hub plus all configured services with official project logos, state, version, latency, uptime, explicit vertical focus, A/tap dashboard launching, and a Jellyfin library-scan action |
| **A11** | Collapsible left navigation rail: compact icons by default, labels on Start or app-mark tap, persistent preference, touch section selection, and unchanged L1/R1 switching |
| **H7 / A12** | In-app notification center: authenticated `/v1/notifications` joins recent Sonarr/Radarr history, Bazarr subtitle history, and current health warnings into three service columns; stable IDs drive persistent unread state, focus marks entries seen, and badges show unread totals |
| **A13** | Settings: follow-system/light/dark appearance, theme-matched service logos, configurable per-service notification history limits, playback seek distance, and controller-test access |
| **H8 / A14** | Offline downloads: durable user-bound Hub grants, range resume and renewal, series/season/episode selection, persistent foreground queue, grouped manager, local-first playback, external subtitle/artwork storage, deletion, and deferred per-user progress sync |

**Kotlin and Go test suites are green.** Android debug build, `gofmt` and `go vet` are clean.

**Offline catalog/storage/codec polish (2026-09-10):** Offline now opens on an alphabetized poster
catalog with one item per movie or series. Series drill-down is fully local and contains only seasons
and episodes present on the device; the transfer manager is a secondary tab. The page listens to
repository change broadcasts instead of rebuilding every 750 ms. Offline Settings lets new jobs use
internal app-private storage or a mounted SD card, while existing downloads keep their paths. The
side rail uses 40 dp entries so all eight icons fit above the hint bar. A live file probe found why
Lanterns S01E04 was silent: its only audio stream is AC-3 5.1 and Pocket DS publishes no AC-3 decoder.
The APK now bundles the official Media3 1.4.1 FFmpeg extension as a minimal arm64 AC-3/E-AC-3 build;
source revisions, LGPL notice and rebuild steps are checked in under `app/third_party/media3-ffmpeg`.

**Offline refresh polish (v0.1.3):** byte and speed changes now update only their existing progress
labels, bars and batch summaries; the queue is rebuilt only when its structure changes. Transfer
updates are coalesced for 180 ms and preserve the selected row or scroll position. Repeated checks
that find an unchanged waiting/paused state no longer write SQLite or broadcast a false change.

**Connection recovery (v0.1.1):** A fresh install can enter both the Hub HTTPS address and bearer
token under Manage > Ayaneo Hub. Android app data is the only place these values are stored, so an
uninstall clears both. The screen points the owner to the gitignored `scripts/dev.env` file when
using Jump Desktop. The client refuses empty or incomplete credentials locally and stops issuing
requests after a token receives 401/403, preventing refreshes from tripping the Hub's 15-minute
authentication ban. Changing the token allows a new connection test.

**Deployment note (2026-09-08):** the latest working tree and APK include unified Y search,
controller focus traversal, timeline-anchored seek previews, gesture percentage bars, bare top
controls, and stop-on-PiP-dismiss. A localhost live probe extracted the expected frame from The
Mentalist. The matching hub binary is installed under FireDaemon and its bearer token was rotated.
The latest APK is installed on the Pocket DS. A private Tailscale network now joins Pocket DS and
`ayaneo-media-pc`; Tailscale Serve exposes the Hub and service dashboards through tailnet-only HTTPS.
The private Hub live endpoint returns `200 ok`, all dashboard routes answer, Hub browser URLs use the
tailnet hostname, and Bazarr form authentication is enabled. The public Caddy route on port `55886`
is retained as a fallback. ADB changed the installed app to the private Hub while preserving its
token; users, Home, Library, activity, health, artwork, and the Jellyfin Manage link pass on-device.
The final phone-hotspot check also passes, confirming the private Hub works away from the home Wi-Fi.

**Release targeting (2026-09-09):** Discover now shows visual season cards before manual
search. A chosen season opens a portrait **Entire season** target followed by horizontal 16:9 cards
for episodes whose Sonarr `airDateUtc` has passed. The episode card uses TMDB artwork through the
existing image proxy and shows air date, runtime, monitored state, and whether a file already exists.
Library series and season screens expose the same flow on Y using Jellyfin's season artwork as the
fallback. `GET /v1/media/{key}/release-targets?season=N` joins Sonarr's episode records to
Jellyseerr/TMDB artwork; `GET .../releases?season=N&episode=M` and the matching grab body resolve the
current Sonarr episode id inside the Hub. A per-episode search blocks season packs and multi-episode
releases from being grabbed accidentally.

**Notifications and appearance (2026-09-09):** the side rail bell opens three landscape columns
for Sonarr, Radarr and Bazarr. `GET /v1/notifications` accepts independent history limits (defaults:
Sonarr 60, Radarr 20, Bazarr 40), adds active service health warnings, and returns useful partial
data if one upstream is unavailable. Stable event IDs let the app keep seen state locally per Hub.
Unseen cards use a quiet red inner tint; focusing or tapping one marks it seen and immediately
updates the service and rail badges. X marks all visible entries seen. The screen refreshes every
30 seconds and the rail every minute. Settings replaces the top-level Pad entry and contains
appearance, notification limits, playback seek distance, and the controller test. Appearance can
follow Android or force light/dark mode; service images switch through Android night resources.
Transient status strips use the app's teal family in both themes. Focused cards retain the accent
ring and scale, while unfocused cards remain fully opaque so light mode does not wash white into
episode artwork.

**Home and Library refresh (2026-09-09):** Continue Watching and Next Up now render concrete
movies/episodes as landscape cards. Episode cards use the episode still and show the series plus
`SxxEyy · episode title`; Recently Added requests playable movies/episodes, promotes episodes to
their parent series, removes duplicate series, and opens title details with A. This avoids
Jellyfin dropping episode-heavy latest media when asked for Series objects; the live row increased
from one title to 19 after deployment.
Manage exposes Jellyfin's asynchronous library scan on the Jellyfin row and with X, then clears
stale Home/Library/detail/search caches and refreshes the provider-id index while the scan imports.
Discover card and detail availability are corrected from that live Jellyfin index. Library poster columns
now follow actual content width, keeping seven with the compact rail and dropping to six with the
expanded rail. Manage rows and Library detail actions own their directional focus movement.

**Player and item-action polish (2026-09-09):** external SRT/WebVTT is fetched once, parsed with
Media3 and drawn from a local cue timeline. The CC timing control is a live on-video bar spanning
±60 seconds with 0.1-second D-pad/touch steps, one-second L2/R2 steps and reset; changes do not reload
video or subtitle data. Its smaller three-row panel gives Reset and Done controller focus. Timing is
stored locally per Jellyfin user and series (or per movie). Hardware verification on Drake & Josh
restored +0.1 seconds after reopening playback without a new media/subtitle request; the test value
was reset afterward. Item details now pair labels with play, restart, sliders,
eye/eye-off and filled/outlined-star symbols.

**Offline downloads (2026-09-10):** movie and episode actions queue one item directly; series and
season actions open a poster/still picker with All, Unwatched, Next, Next X and manual choices.
The Offline rail section has Download manager and Downloaded tabs, serial season/episode ordering,
aggregate/item progress, live speed and ETA, pause/resume/retry/cancel/remove, Wi-Fi/charging/free-space
constraints and reboot-safe SQLite state. Original files keep embedded tracks; external subtitles and
artwork are stored beside private app media. Complete files win before network prepare, adjacent local
episodes remain local, and watch progress is compacted per Jellyfin user for later conflict-aware sync.
The deployed Hub adds a `download` scope, durable grants, Range media, subtitle, renewal and progress
endpoints. A live Attack on Titan test transferred 545 MB at about 20 MB/s, played with the Offline
indicator without a playback request, restored focus on Back and removed the file cleanly. The final
foreground-service stale-command crash found during that run was fixed and reverified past Android's
five-second service deadline. `OFFLINE_DOWNLOAD_PLAN.md` records the contract and delivered details.

### Not done

- **Manage controls** — health, dashboard launching and Jellyfin library scanning are delivered;
  service configuration, stuck-item repair and Bazarr maintenance remain later work.
- **Findroid comparison** — the on-device screenshots, behavior matrix, and prioritized follow-up work are in `FINDROID_COMPARISON.md`.
- **H4 proper** — the broader `correlate` engine; the read-only Bazarr history/health adapter now exists for notifications.
- **Additional theme customization** — light/dark/system is delivered; custom palettes remain later work.
- **Offline format hardening** — the core path is delivered; broaden device checks across HEVC/HDR,
  ASS/SSA, PGS and DVB subtitle combinations before claiming every rare embedded track is renderable.

---

## 3. Running it

### The hub

Go 1.27. Only external module: `gopkg.in/yaml.v3`.

```
cd hub
go test ./...
go build -o hub.exe ./cmd/hub
go build -o hubctl.exe ./cmd/hubctl

./hub.exe --check --config C:/ProgramData/AyaneoHub/hub.yaml   # validate, don't start
./hub.exe --config C:/ProgramData/AyaneoHub/hub.yaml           # run
./hubctl.exe doctor --config C:/ProgramData/AyaneoHub/hub.yaml # probe every service
./hubctl.exe probe jellyfin /Users --config ...                # raw upstream, no key in your shell
./hubctl.exe token new --label pocketds                        # issue a token, printed once
```

**The hub listens on 8791**, not 8787 — Readarr owns 8787 on this machine and
Prowlarr owns 9696.

`hubctl probe` is the tool to reach for when an upstream does something
surprising. It reads the key from `hub.secrets.yaml`, uses it, and never prints
it. `--query`, `--keys`, `--limit` and `--timeout` keep the output readable.

> **Git Bash gotcha:** `hubctl probe jellyfin /Users` has its `/Users` argument
> rewritten to `C:/Program Files/Git/Users` by MSYS path conversion. Prefix
> every such command with `MSYS_NO_PATHCONV=1`. This cost an hour once.

### The app

```
scripts/dev.sh              build, install, relaunch, print state
scripts/dev.sh test         JVM unit tests — run these constantly
scripts/dev.sh pad          what the gamepad actually reports
scripts/dev.sh display      screen size + density
scripts/dev.sh seed         push hub URL + token from scripts/dev.env (gitignored)
scripts/dev.sh trace        last 40 of our own log lines
scripts/dev.sh cache clear  drop HTTP/image caches, keeping the token
```

Or directly:

```
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Connecting to the handheld

**Wireless ADB over mDNS.** The port is reassigned on every restart — never
hardcode it. `adb mdns services` finds it, sometimes only on the second or third
try:

```
adb mdns services | grep adb        # -> 10.100.102.45:PORT
adb connect 10.100.102.45:PORT
adb reverse tcp:8791 tcp:8791       # optional localhost development tunnel
```

**Prefer the private Tailscale HTTPS route for the installed app.** With Tailscale connected on
Pocket DS, set the Ayaneo Hub row in Manage to
`https://ayaneo-media-pc.tail737e96.ts.net`. Tailscale Serve proxies it to the loopback Hub on
`127.0.0.1:8791`; its service dashboard routes use the same hostname with their standard ports.
All listeners report `tailnet only`, so no router or Windows firewall rule is needed. The previous
`https://myjellydan.duckdns.org:55886` Caddy route remains available as a tested fallback until the
phone-hotspot verification passes. Selecting the Ayaneo Hub row edits and tests the address without
ADB while retaining the existing bearer token. An `adb reverse`
tunnel is only a development fallback for an app deliberately seeded with
`http://127.0.0.1:8791`; reverse mappings are ephemeral and were the reason FireDaemon
could look healthy while the handheld reported the hub offline.

**Two connection traps already hit:**

- **A VPN can silently break it.** The university VPN pushed `10.100.102.0/25`
  and `10.100.102.128/25`, which together cover the whole home LAN and are
  *more specific* than the Wi-Fi's `/24` — longest-prefix match wins over
  metric, so every packet to the handheld went into the tunnel. Fix: a `/32`
  host route (`route add 10.100.102.45 mask 255.255.255.255 10.100.102.1 metric
  1 if 8`), or disconnect the VPN.
- **USB did not work on this unit.** The handheld enumerates as
  `VID_18D1&PID_2D01` bound to the *"Android Accessory Interface"* driver —
  Android Open Accessory mode, which exposes no ADB interface. Enabling USB
  debugging does not change it. Wireless is the working path.

---

## 4. Conventions — please keep these

Inherited from the sibling project `../Ayaneo PocketDS Keyboard+Mouse`, which
targets the same hardware. Read it before inventing anything; several files are
lifted verbatim and several device quirks are documented there.

**Tests first, and logic stays pure.** Navigation, input routing, retry and
cache policy, focus rules, form state and window geometry live in plain Kotlin
classes taking **primitives, never Android types**. Views translate framework
events into small data classes and hand them over. This is why `PadNames`
redeclares the `KeyEvent`/`MotionEvent` constants rather than importing them:
those classes are stubs in a JVM unit test and throw on access. **No
Robolectric.**

**Plain Android Views. No Compose, no Fragments, no Navigation component, no
ViewModel, no LiveData, no DI.** Coroutines *are* used, but only the runtime —
cancellation is the core problem (backing out of a screen must kill its
in-flight requests) and Coil pulls them in transitively anyway.

**One Activity.** The gamepad pipeline is per-window state that must exist
exactly once.

**Comments explain *why*, and cite the observed behaviour.** Most of the value in
this codebase is in the comments recording what a service or the device actually
did. A comment that restates the code is noise; one that says "measured: the
reply came in 11ms and the state had not moved 24ms later" is the reason the
code looks the way it does.

**Commit subjects are lowercase prose.** No conventional-commits prefixes.

**This repo is LF-only** (`.gitattributes`). The sibling is CRLF; keeping them
apart avoids a whole-file diff.

**Sentence case in the UI** — *Show all*, *Hide done*, *Find release*. Title Case
on one chip makes it the odd one out.

---

## 5. Architecture notes that will save you time

### The app

```
Screen → HubApi (one suspend fun/endpoint) → HubEndpoints (PURE) → HubClient → OkHttp
```

`HubActivity` is the only Activity. It owns the gamepad router, the collapsible section rail, the
hint bar, the status strip, and the floating trailer window. Screens are plain
objects implementing `nav.Screen` with a per-section back stack
(`nav.SectionStacks`).

**Focus is the hard part of this app.** Three separate escapes had to be closed
before a row of posters behaved, and all three looked like "the D-pad is dead":

- `LinearLayoutManager.onFocusSearchFailed` **scrolls** while hunting for a
  candidate; that scroll detaches the focused card and Android hands focus to
  the first focusable view in the window. So horizontal movement inside a row is
  done **by adapter position** (`ui/StripNav`), never by focus search.
- `FocusFinder` is a **global** search — with nothing to the right it returns the
  leftmost card. `input/FocusGuard` rejects candidates that are not genuinely in
  the pressed direction. `CONFINED` for rows, `GRID` for the search grid.
- A *declined* directional key fell through to `super.dispatchKeyEvent`, which
  did its own unguarded search. `HubActivity` now consumes DPAD keys itself —
  except in a text field, where left/right move the caret.

The section rail is deliberately **not focusable**: sections switch with L1/R1 or a
tap, Start expands/collapses its labels, and keeping it out of focus search closes the
whole "orphaned focus lands in app chrome" class of bug. Its width is 68dp collapsed
and 190dp expanded, with the preference persisted in `HubSettings`.

**`hints()` reads the focused item, so it must be recomputed after focus lands** —
in `moveFocus`, in `showCurrent`'s post, and in each adapter's own focus
settling. And that settling must run even when nothing was focused before, which
is the first-load case.

**Load on "do I have data", never on "have I tried".** A once-only flag left
Discover stuck on "Loading…" forever: the device pauses and resumes the activity
once during startup, `onHide` cancels the in-flight request, and the flag then
blocked the retry.

### The hub

Router is stdlib `http.ServeMux` — since Go 1.22 its patterns carry the method,
which is all this API needs.

- `internal/config` — fail-fast validation, exit 78. `Secret` renders as
  `[redacted]` through every formatting verb; `.Reveal()` is the only way out.
- `internal/httpx` — shared client machinery: per-service timeout as a hard cap,
  error classification, `WithTimeout` for calls that are slow by nature.
- `internal/cache` — TTL store with singleflight, stale-while-revalidate and
  stale-if-error. `policy.go` is the one place TTLs live.
- `internal/index` — the Jellyfin provider-id index. **A map, not SQLite** — see
  below.
- `internal/adapters/*` — one package per service.
- `internal/api` — handlers, envelope, middleware.

**The partial-failure envelope is the contract the app relies on:** a screen
endpoint returns 2xx if it can render *anything*, with a `partial[]` array
naming what is missing. "Bazarr is down but the screen still renders" is a
normal response, not error handling.

**The raw upstream response is what gets cached, not the rendered one** —
`actions` depend on the caller's scopes, so a read-only token must never be
served a response computed for one that can request things.

---

## 6. Decisions already made — please don't silently reverse them

**No SQLite for the Jellyfin index.** The plan called for one because Jellyfin
has no provider-id query (only `hasTmdbId` as a *boolean*). The index is
necessary; the database is not. Measured: **250 items, 436 KB, 362 ms for a full
sweep, 99% TMDB coverage.** SQLite existed to avoid re-sweeping a big library and
to survive restarts; neither concern survives those numbers. It also keeps the
hub's only external module `gopkg.in/yaml.v3`. If a library grows to where the
sweep costs 30s, the answer is paging plus persistence and `internal/index` is
the seam.

**Native playback is implemented.** Findroid cannot be
deep-linked (verified against its manifest: `PlayerActivity` is not exported),
so the app uses Media3 behind the hub rather than an external-app handoff. The
hub prepares and proxies Jellyfin playback so the APK never receives the
Jellyfin API key; `HubActivity` remains the one Activity and a
`MediaSessionService` owns the player. The compact overlay exposes box-free Audio, CC,
Quality, PiP and Close symbols above the video, plus configurable short seeks and
Previous/Next below it. Android system Back exits directly; physical B still
dismisses a sheet or controls before exiting. `PLAYER_PLAN.md` separates the
delivered release from later chapters, skip-segment and offline work. Touch adds
double-tap short seeks, horizontal scrub previews, left-side brightness, right-side
volume, vertical percentage bars, and timeline-anchored thumbnail previews. D-pad/stick
left and right traverse controls; seeking moves the timeline thumb when the timeline has
focus. Generated Jellyfin trickplay is preferred, with an authenticated hub/ffmpeg frame
fallback for libraries that have no generated tiles. Closing Android PiP stops playback
and its Jellyfin session. External text subtitles can be shifted
earlier or later from the CC menu.

All Home, Library and season-row movie/episode activations now open the Jellyfin item details
first. The action row uses symbols for Play, Start over, playback options, watched and favourite;
only a valid resume timestamp remains as visible text beside Play. Action names remain in content
descriptions and controller hints.

Playback session traffic uses a separate authenticated 3600 rpm / 240 burst limiter. The normal
90 rpm / 30 burst budget remains on interactive APIs, and the IP ban for failed credentials is
unchanged. This split is required because Media3 range, HLS, subtitle, preview and event requests
previously consumed the screen budget and caused 429 responses after several quick player exits.
A deployed six-cycle Pocket DS test produced 85 requests, six successful session deletes and no
429 responses.

Returning from playback refreshes Home, details, series play targets and the selected loaded
episode while retaining focus and paging. Because Jellyfin can expose the previous resume point
briefly after the final stop event, the app keeps a two-minute checkpoint per Jellyfin user and
uses it for an immediate Resume of the same item. Start over bypasses it. Hardware verification
showed the Drake & Josh S2E8 detail change to `Resume · 2:16` immediately on Back and a reopen
continue beyond that point.

Two live Jellyfin 10.11.8 details are contract requirements. Transcode URLs use
hyphenated UUIDs while item responses use compact IDs, so the HLS allowlist must
canonicalize both without weakening item ownership. Those URLs also contain an
`ApiKey` query field; strip API-key and token spellings case-insensitively before
encoding any client-facing resource and authenticate upstream with
`X-Emby-Token`. POST PlaybackInfo can clear external-subtitle delivery flags, so
text codecs fall back to Jellyfin's session-bound subtitle extraction endpoint.

**Trailers use YouTube's embed, with a fallback.** Three approaches were tried;
two are dead ends and the errors are not self-explanatory:

| Approach | Result |
|---|---|
| `loadUrl` on `/embed/KEY` | **Error 153** — no page origin |
| `<iframe src=…>` in `loadDataWithBaseURL` | **Error 152** for every video |
| The IFrame Player API | **Still 152** on this device |

So the player reports the refusal over a JS bridge and falls back to
`m.youtube.com/watch?v=KEY`, which is an ordinary website visit and plays fine.
The embed attempt is kept because it is the nicer player where it works.
The toolbar's **Open in YouTube** action can use YouTube's own PiP when the user
leaves YouTube with playback running. This app cannot force or resize another
app's PiP window; Android and YouTube own that window and its eligibility.

**Confirmations are in-layout overlays, never `AlertDialog`.** A dialog opens a
second window with its own focus rules and leaves the hint bar stale behind it.

**Destructive actions take a second press**, with Cancel under the cursor. Not
because the hub requires it, but because a thumbstick flings the selection
around and "delete the files" must never be one press away.

**The hub never forwards a release's `guid` or `downloadUrl`.** Measured: 40 of
40 Radarr releases embedded the Prowlarr API key in clear text. The app gets
`sha256(indexerId + guid)[:8]` and grabs by that — which also stops a token
making the hub fetch an arbitrary URL.

---

## 7. Suggested next steps, in order

1. **Player milestone 2.** Add chapters, intro/recap/credits skipping, subtitle appearance,
   speed, screen lock, independent skip intervals and aspect modes.
2. **Manage controls** — maintenance actions, stuck items and Bazarr subtitle operations.
3. **Broaden playback and offline format coverage.** Exercise HEVC/HDR, unsupported video,
   image/ASS subtitles and a movie across direct play and full transcode, then
   add PiP remote actions, notification/headset controls and optional background audio.
4. **Detail screen additions.** `watchProviders` is already in the Jellyseerr
   payload the hub fetches — "where to watch" answers *do I even need to
   download this?* before requesting. Then certification/studio, then a
   similar-titles row.
5. **H4 — the `correlate` engine and Bazarr.**
6. **Custom theme packs.**

### Two things that are the owner's to do, not code

- **Ports 8989 (Sonarr) and 8080 (qBittorrent) are still forwarded** on the
  router. Neither is open — both reject unauthenticated API calls — but a login
  page on the public internet is brute-forceable, and qBittorrent's WebUI can set
  a save path and run a program on torrent completion, which makes any future CVE
  in it code execution on the machine holding every API key. Un-forward both and
  reach them through the hub.
- **`hub.secrets.yaml` and `scripts/dev.env` are gitignored and must stay that
  way.** `deploy/windows/collect-secrets.ps1` regenerates the former from each
  service's own config.

---

## 8. Where the state lives on this machine

| Path | What |
|---|---|
| `C:\ProgramData\AyaneoHub\hub.yaml` | Config, no secrets, safe to read |
| `C:\ProgramData\AyaneoHub\hub.secrets.yaml` | Five API keys, ACL'd to SYSTEM / Administrators / owner |
| `scripts/dev.env` | `HUB_URL` + `HUB_TOKEN` for `dev.sh seed`. **Gitignored** |
| `CLAUDE.md` | The accumulated findings. Keep it updated — it is the reason this project has not repeated its mistakes |

---

## 9. The single most useful habit

**Verify against the real stack rather than recalling.** Nearly every
hard-to-find bug in this project came from an upstream behaving differently to
how it is documented or remembered:

- Jellyseerr rejects `+` as a space and answers a bare 400 — **every multi-word
  search silently failed** while single-word ones worked.
- Sonarr sends `indexerFlags` as an integer bitfield where Radarr sends an array
  of strings, and the decode failure surfaced as *"sonarr is not responding"*.
- `/Items/Latest` answers a bare array where every other Jellyfin paged query
  answers an object.
- qBittorrent does not flip a torrent's state synchronously with the reply —
  measured 11 ms to answer, still the old state 24 ms later.

`hubctl probe` exists for exactly this. Use it before writing the adapter, not
after the bug.

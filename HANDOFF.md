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
| **H3 / A5** | qBittorrent + Radarr + Sonarr adapters, `/v1/activity`, downloads screen with stop/start/delete/blocklist |
| **H4a / A6a** | Request options + interactive search; request dialog and release picker in the app |
| **H5 / A7** | `/v1/discover` (four rows, one call, 99 ms); Discover rebuilt as Findroid-style poster rows |
| **H2** | Jellyfin adapter + provider-id index; `/v1/home`, `/v1/library`, `/v1/library/{id}/items`, `/v1/img/jf/*` |

**Tests: 239 Kotlin, 149 Go.** All green. `gofmt` and `go vet` clean.

### Not done

- **A4 — the Library screen.** The hub side is finished and verified; this is
  pure app work and is the obvious next task. See §7.
- **Home rows in the app.** `/v1/home` returns Continue watching / Next up /
  Recently added; nothing renders them yet.
- **Manage tab** — still a `PlaceholderScreen`.
- **H4 proper** — the `correlate` engine and the Bazarr adapter.
- **Playback** — deliberately deferred, see §6.
- **Theme packs** — planned, not started.

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
adb reverse tcp:8791 tcp:8791       # required, see below
```

**`adb reverse` is mandatory.** The hub binds loopback only, so the handheld
cannot see it over the LAN. The reverse tunnel makes the hub appear on the
device's own `127.0.0.1`, so traffic never leaves the handheld and nothing is
exposed. `network_security_config.xml` permits cleartext for `127.0.0.1` and
`localhost` only.

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

`HubActivity` is the only Activity. It owns the gamepad router, the tab bar, the
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

The tab bar is deliberately **not focusable**: sections switch with L1/R1 or a
tap, and keeping it out of focus search closes the whole "orphaned focus lands
on the first tab" class of bug.

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

**The app does not play video, and that is deliberate.** Findroid cannot be
deep-linked (verified against its manifest: `PlayerActivity` is not exported).
The decision: build a player only if it can be genuinely good, which means
researching Infuse / mpv / VLC / Findroid / Jellyfin Media Player / Kodi first.
`PlaybackLauncher` is already an interface and `PlayableRef` already carries
`streamUrl` and `startPositionMs`, so whatever gets built plugs in.

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

1. **A4 — the Library screen.** All hub endpoints exist and are verified. Add a
   `LibraryScreen` with the five views, then a paged poster grid reusing
   `PosterCardView` and the paging pattern already in `DiscoverScreen`. Replace
   the `PlaceholderScreen` in section 1 of `HubActivity`.
2. **Home rows in the app.** `/v1/home` already returns Continue watching, Next
   up and Recently added. Render them above the Discover rows, or as a new first
   section. Cards need a progress bar for part-watched items —
   `SearchHit.progress` already carries it.
3. **The Manage tab** — service health, stuck items, Bazarr subtitles.
4. **Detail screen additions.** `watchProviders` is already in the Jellyseerr
   payload the hub fetches — "where to watch" answers *do I even need to
   download this?* before requesting. Then certification/studio, then a
   similar-titles row.
5. **H4 — the `correlate` engine and Bazarr.**
6. **Playback**, after the research phase.

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

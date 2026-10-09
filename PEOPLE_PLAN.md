# People plan (#65)

Friends and guests on JellyHub, like profiles on a streaming service: invites, "Who's watching?"
with Jellyfin pictures, guests who save nothing, and owner controls. The owner approved the flows
in the prototype linked from #65 (`people/people-prototype.html` in the PC session's scratchpad).
This file is the design for all five phases. Status lives only in #65.

**The build is on hold** until the rest of the work in flight is finished (owner's decision,
2026-10-09). Nothing here is implemented yet.

This repository is public. Nothing in this plan depends on being secret: the limits, the formats
and the file layout can all be read by an attacker and still hold. No real token, code, password,
key or e-mail address belongs in this file or in any commit.

Contents:

1. [Principles](#1-principles)
2. [Roles](#2-roles)
3. [Threat model](#3-threat-model)
4. [What a member or guest can reach](#4-what-a-member-or-guest-can-reach)
5. [Limits and lockouts](#5-limits-and-lockouts)
6. [Telling the public link from Tailscale](#6-telling-the-public-link-from-tailscale)
7. [Storing passwords, PINs, codes and sessions](#7-storing-passwords-pins-codes-and-sessions)
8. [Logging and audit](#8-logging-and-audit)
9. [Data model](#9-data-model)
10. [Routes](#10-routes)
11. [Configuration](#11-configuration)
12. [Existing hub.yaml tokens](#12-existing-hubyaml-tokens)
13. [The phases](#13-the-phases)
14. [Caddy and the router: steps for the owner](#14-caddy-and-the-router-steps-for-the-owner)
15. [The Tailscale path](#15-the-tailscale-path)
16. [Pre-launch checklist and test plan](#16-pre-launch-checklist-and-test-plan)
17. [Recovery](#17-recovery)
18. [Decisions for the owner](#18-decisions-for-the-owner)
19. [Found while planning: today's hub](#19-found-while-planning-todays-hub)

---

## 1. Principles

- **Nothing changes until it is switched on.** Every part sits behind `people.enabled`
  (default `false`). With it off, no new route exists, so a request for one is answered exactly
  as any unknown `/v1/` path is today; tokens are checked exactly as today; and the tests prove
  both.
- **Fail closed.** A route nobody has classified is owner-only. A config the hub cannot fully
  check stops it from starting (exit 78, as H0 does today). A store file it cannot read is never
  written over.
- **Private by default.** The owner and family use Tailscale. The public link is for casual friends
  and guests with an invite, and it can be shut in one step.
- **No secret at rest in plain text, ever.** Passwords, PINs, codes and session tokens are stored
  only as salted slow hashes or keyed hashes. Nothing secret is logged, returned twice or put in a
  URL.
- **Everything is revocable one device at a time, and at once.** Locking or removing someone
  stops every request of theirs on the next byte, including a film already streaming.
- **The owner never sees a password.** Not in the app, the logs or the files.
- **One behaviour, one implementation.** The existing ban list, rate limiter, `requireScope` and
  atomic file writing are reused, not copied.

## 2. Roles

| | Owner (Dgdan) | Member | Guest |
|---|---|---|---|
| Watch and read online | Yes | Yes | Yes |
| Their own place and history | Yes | Yes (their own Jellyfin user) | Not saved (phase 4) |
| Offline downloads | Yes | If the invite or the owner allows it | No |
| Requests (Jellyseerr, books) | Yes | If the invite or the owner allows it | No |
| Interactive release search and grabbing | Yes | No (see [decision 5](#18-decisions-for-the-owner)) | No |
| Manage, Activity, notifications, server monitor | Yes | No | No |
| Delete media, stop/start/delete transfers, scans | Yes | No | No |
| People, invites, resets, locks | Yes | Only their own devices, PIN and password | No |

There is exactly one owner. The owner cannot be locked, demoted or removed through the API.

## 3. Threat model

Who might attack, from where, and what they want. Every defence named here is specified in the
sections that follow, and every row has a test in [section 16](#16-pre-launch-checklist-and-test-plan).

### 3.1 A stranger who finds the address

- **From:** the internet, through the public link (`myjellydan.duckdns.org:55886`). Addresses are
  found by scanners within hours of a port opening, and this one is already in a public repository.
- **Wants:** anything: the library, the service keys, a foothold on the PC.
- **Defences:**
  - Caddy answers only `/v1/*`; everything else is a flat 404.
  - Every `/v1/` route needs a bearer token, except five "door" routes: hello, invite check, join,
    sign-in and reset. Those are capped per address and globally ([section 5](#5-limits-and-lockouts)).
  - Hello says only the server's display name and whether joining is open. No version, no names.
  - The router forwards one port only, to Caddy. No dashboard, Jellyfin or qBittorrent port is
    reachable from the internet ([section 14](#14-caddy-and-the-router-steps-for-the-owner)).
  - There are no cookies, so there is nothing for cross-site request forgery to ride on, and the
    hub sends no CORS permission for any people route.
- **Left over:** they learn that a JellyHub server exists and what it is called.

### 3.2 A botnet guessing codes or passwords

- **From:** thousands of internet addresses at once.
- **Wants:** a working invite, guest or reset code, or someone's password.
- **Defences:**
  - Codes are 12 characters from 32 (60 bits, about 10^18 combinations), expire in 7 days
    (reset codes in 24 hours) and are single use (guest codes: 5 devices).
  - Per address: 5 wrong answers in 15 minutes lock that address out of the door for 15 minutes,
    and every try during the lock restarts the 15 minutes. IPv6 is counted per /64, since one home
    or one attacker holds a whole /64.
  - Globally: more than 30 door attempts in a minute, from everywhere together, pause joining and
    signing in for everyone for 5 minutes. Signed-in devices carry on.
  - Per name: 10 wrong passwords for one name in an hour pause password sign-in for that name for
    an hour. Unknown names are counted and timed exactly like known ones.
  - Passwords must be 8 or more characters and are refused if they are among the 10,000 most common,
    or contain the person's or server's name.
  - With [decision 1](#18-decisions-for-the-owner), the owner's password is not accepted over the
    public link at all.
- **Arithmetic:** the global cap bounds the whole internet to 43,200 door attempts a day. Against
  five live codes over a code's 7-day life that is a chance of about 1 in 760 billion. Against one
  member's password, the per-name pause bounds it to 240 guesses a day on each path: the 10,000
  most common passwords are refused outright, and a dictionary of 14 million would take 160 years.
- **Left over:** a botnet can keep joining paused for everyone (a nuisance, not a breach), and a
  password that is a real word plus a number is still weaker than a random one.

### 3.3 A flood

- **From:** anywhere: too many requests, slow connections, huge bodies.
- **Wants:** to take the server down, or starve playback of CPU.
- **Defences:**
  - Caddy: read-header and read-body timeouts and an idle timeout, so a slow connection is closed;
    16 KB request bodies on the door, 10 MB elsewhere; optional per-address request limits at the
    edge.
  - The hub: 4 KB body caps on every people route; the door's own per-address and global caps,
    counted before any password is hashed; at most 2 password hashes at once, so a flood of
    sign-ins costs at most a small part of one CPU core and never the transcoder.
  - Memory: the ban and limiter tables are pruned every 10 minutes and capped at 100,000 addresses.
    Beyond that, the global pause takes over.
- **Left over:** a flood big enough to fill the home internet connection cannot be stopped at
  home. If that ever happens, close the public link ([section 17](#17-recovery)); a free Cloudflare
  tunnel in front can absorb it later. Tailscale users are unaffected by a flood on the public link
  only if the home connection itself still has room.

### 3.4 A friend who shares their login or code

- **From:** a real member or guest, handing it on.
- **Wants:** free access for someone the owner did not invite.
- **Defences:**
  - A member invite works once. A guest code works on 5 devices and ends 7 days after it was made.
  - A person can have at most 10 devices, and the owner sees every one: its name, platform,
    Tailscale or public link, when it signed in and when it was last seen.
  - Each new device is an audit event and a notification for the owner.
  - At most 2 streams at once per member and 1 per guest device
    ([decision 6](#18-decisions-for-the-owner)).
  - The owner can sign out one device, all of a person's devices, or lock the person.
- **Left over:** a shared password works until the owner notices. Nothing technical can stop two
  people agreeing to share; visibility and revocation are the answer.

### 3.5 A malicious or compromised member device

- **From:** a member's real session, signed in properly.
- **Wants:** the owner's routes, someone else's history, the service keys, the PC.
- **Defences:**
  - Every route checks a scope. Members never get `control`, `manage`, `grab` or `people`.
  - The hub overwrites `X-Jellyfin-User` with the member's own Jellyfin user before any handler
    reads it, so a member cannot read or write another profile's history.
  - Until phase 3, reading places are refused for members and guests rather than written into the
    owner's Storyteller account ([section 13](#13-the-phases)).
  - A test enumerates every registered route and fails if one has no classification, so a new
    route added later cannot be reachable by members by accident.
  - No response ever contains a service key, a service URL with credentials or a filesystem path
    (an H0 rule that stays).
  - Requests can be attributed to a Jellyseerr user without auto-approve
    ([decision 4](#18-decisions-for-the-owner)) and are capped at 20 a day.
  - Interactive search and grabbing a specific release stay owner-only, since grabbing puts an
    arbitrary torrent on the owner's PC.
- **Left over:** a member with downloads keeps whatever files they downloaded; locking them does
  not reach into their phone. Downloads are a statement of trust.

### 3.6 A guest trying to reach owner routes

- **From:** a guest session.
- **Defences:** everything in 3.5, plus guests never get `download` or `request`. In phase 4 their
  writes (watch position, played, favourite, reading place, rating) are dropped. The route matrix
  test runs once for a member and once for a guest.

### 3.7 Leaked invite or reset codes

- **From:** a code forwarded, screenshotted or seen over a shoulder.
- **Wants:** to join as a member, or take over a member's account.
- **Defences:**
  - Codes are shown once, when made, and stored only as a keyed hash.
  - The owner can cancel an invite at any time, and the code stops at once.
  - Issuing a new reset code cancels the previous one, and issuing any reset code stops the old
    password at once.
  - With [decision 2](#18-decisions-for-the-owner), a member who joins over the public link waits
    for the owner's approval before they can see anything. That turns a leaked member code into a
    request the owner can refuse.
  - The person page shows who used an invite, from which device and over which path.
- **Left over:** a guest code leaked during its 7 days lets up to 5 strangers watch until the owner
  cancels it.

### 3.8 Session theft

- **From:** a lost or stolen phone, a copied app data folder, a token pasted somewhere.
- **Wants:** to be that person.
- **Defences:**
  - Tokens travel only over HTTPS (Caddy or Tailscale) and only in the `Authorization` header,
    never in a URL.
  - They are never logged. The hub stores only their SHA-256.
  - On the device they live in app-private storage and are excluded from backups (Keychain
    "this device only" on Apple, `allowBackup=false` on Android).
  - A PIN-locked session cannot be used without the PIN, and 5 wrong PINs require the password.
  - Sessions unused for 90 days end by themselves, and the owner or the person can end any session
    from another device.
- **Left over:** a token stolen from an unlocked session works until it is signed out. That is
  true of every app that stays signed in.

### 3.9 The media PC itself

- **From:** malware on the PC, a careless backup, another Windows account on the PC.
- **Wants:** the stored hashes, the service keys.
- **Defences:**
  - The people store lives in its own folder whose permissions allow only SYSTEM and
    Administrators. Files inherit that, so the store needs no per-file permission handling.
  - Password hashes are keyed with a pepper kept in `hub.secrets.yaml` (already locked down), so a
    copied store alone cannot be cracked offline.
  - Session tokens are 256-bit random values, so their hashes cannot be reversed at all.
  - Never back the people folder up to a cloud drive.
- **Left over:** anything running as Administrator or SYSTEM on the PC can read everything,
  including every service key. That was true before this feature and is out of its reach;
  [section 17](#17-recovery) says how to recover.

### 3.10 Tailscale sharees and family on the tailnet

Not an attacker, but a trap. Tailscale Serve proxies each tailnet port to the service on
`127.0.0.1`, so **every service behind it sees every tailnet visitor as localhost**. Any "no
password for local addresses" setting (qBittorrent's localhost bypass, an *arr's "Disabled for
local addresses", a service with authentication off) is therefore "no password for anyone on the
tailnet". By default, a person you share the machine with can reach every port on it. Before
anyone but the owner's own devices joins the tailnet or receives a share:

- the Tailscale access policy limits shared users to port 443, which is the hub
  ([section 15](#15-the-tailscale-path));
- every dashboard requires its own login with no local bypass;
- qBittorrent requires a password on localhost too, and the hub is given qBittorrent's username
  and password (it already supports them).

## 4. What a member or guest can reach

### 4.1 Scopes

Today's scopes stay. Three new ones split what "every token is trusted" used to cover:

| Scope | Covers | Owner session | Member | Guest |
|---|---|---|---|---|
| `read` | browse, search, detail, Home, Library, Discover, calendar, artwork | yes | yes | yes |
| `play` | playback sessions, watched/favourite state | yes | yes | yes (writes dropped in phase 4) |
| `reading` | books, comics, audiobooks | yes | yes, no place before phase 3 | yes, no place |
| `download` | offline downloads and progress sync | yes | if allowed | no |
| `request` | Jellyseerr and reading requests | yes | if allowed | no |
| `control` | stop/start/delete transfers, scans, deletion, subtitle downloads | yes | no | no |
| **`grab`** (new) | interactive release search and grabbing a specific release | yes | no | no |
| **`manage`** (new) | `GET /v1/health`, `/v1/users`, `/v1/activity`, `/v1/notifications`, `/v1/manage/monitor`, `GET /v1/downloads/bandwidth`, `GET /v1/reading/downloads` | yes | no | no |
| **`people`** (new) | People, invites, resets, locks, approvals, audit, the public-link switch | yes | no | no |

For hub.yaml tokens the new scopes are derived, so nothing an existing device does changes
([section 12](#12-existing-hubyaml-tokens)). `/v1/health/live` stays unauthenticated.

### 4.2 Routes that gain a check

These routes have no scope check today, which was fine while every token was the owner's:

| Route | New check |
|---|---|
| `GET /v1/health` | `manage` |
| `GET /v1/users` | `manage` (members get their own profile from `/v1/people/me`) |
| `GET /v1/activity` | `manage` |
| `GET /v1/notifications` | `manage` |
| `GET /v1/manage/monitor` | `manage` |
| `GET /v1/downloads/bandwidth` | `manage` |
| `GET /v1/reading/downloads` | `manage` (it lists every transfer, not the caller's) |
| `GET /v1/requests/options` | `request` |
| `GET /v1/media/{key}/releases`, `GET /v1/media/{key}/release-targets` | `grab` (each search queries every indexer for up to 110 s) |
| `POST /v1/media/{key}/grab` | `grab` (was `request`) |
| `GET /v1/reading/requests/{seriesId}/releases`, `POST …/search`, `POST …/grab` | `grab` (was `request`) |
| `POST /v1/reading/downloads/{id}/retry`, `DELETE /v1/reading/downloads/{id}` | `control` (they act on any transfer) |

The full table of every route and its classification lives beside the router as a test fixture,
and `TestEveryRouteIsClassified` fails when a registered pattern is missing from it.

### 4.3 What must never be reachable from the public side

From the internet, only Caddy on the one forwarded port, and through it only `/v1/*` on the hub's
public listener. Never:

- any dashboard: Jellyseerr, Radarr, Sonarr, Bazarr, Prowlarr, Readarr, Cleanuparr, qBittorrent,
  Kavita web, Storyteller web, BookKeeprr;
- Jellyfin's own web or API (8096, 8920);
- the hub's private listener;
- the router's administration page;
- any service key, Jellyfin API key, Prowlarr download URL or filesystem path, in any response
  (H0 already holds this; the route matrix test also checks member and guest responses for them).

Members and guests on the public link reach only the hub routes their scopes allow. Tailscale
sharees reach only port 443, which is the hub ([section 15](#15-the-tailscale-path)).

## 5. Limits and lockouts

### 5.1 The numbers

| What | Limit | When over it |
|---|---|---|
| Wrong codes (invite, guest, reset), passwords and PINs from one address | 5 within 15 minutes | That address is locked out of the door for 15 minutes. Every try during the lock restarts the 15 minutes, and a lock never lasts more than 24 hours. `429 door_locked` with `Retry-After`. Devices already signed in at that address carry on. |
| Door requests from one address, whatever the outcome | 10 per 15 minutes (hello: 60) | `429 slow_down`, `Retry-After` until a slot frees |
| Door attempts from everywhere together (check, join, sign-in, reset) | 30 per minute | Joining and signing in pause for everyone for 5 minutes: `429 joining_paused`. Every signed-in device carries on. |
| Wrong passwords for one name | 10 per hour, counted separately for the public link and Tailscale | Password sign-in for that name pauses for an hour on that path: `429 name_paused`. Signed-in devices carry on; a reset code still works. Unknown names are counted the same way. |
| Password hashes being worked out at once | 2 | Others wait up to 2 s, then `429 slow_down` |
| Wrong PINs on one device | 5 in a row | That device needs the person's password: state `password_required` |
| Devices per person | 10 | `409 too_many_devices` until one is signed out |
| Devices per guest code | 5 | The code stops working |
| Requests per member | 20 a day | `429 request_quota` |
| Streams at once | 2 per member, 1 per guest device | `409 too_many_streams` |
| Request bodies | 4 KB on every people route; 2 MB for a picture (phase 2) | `413` |
| Bearer guesses (today's rule) | 5 wrong tokens in 1 minute | 15-minute ban on everything from that address; phase 1 makes tries during the ban restart it, as CLAUDE.md already says it does |
| Each device session's request budget (today's numbers) | 90 rpm / 30 burst for screens, 1800 / 150 for artwork, 3600 / 240 for transport | `429 rate_limited`, `Retry-After: 2` |
| Sessions unused | 90 days | The session ends |
| A PIN session left idle | 12 hours | It locks and needs the PIN again |

All numbers live under `people.limits` with these defaults
([section 11](#11-configuration)). Validation refuses a value that weakens a limit past a floor
(for example more than 10 wrong answers before a lock, or a lock shorter than 5 minutes).

### 5.2 How the existing machinery is reused

- **Ban list.** The door uses a second `auth.BanList` (5 attempts, 15-minute window, 15-minute
  lock) beside today's bearer ban list. It stays separate on purpose: a family member's mistyped
  password should lock the door, not stop the films already playing at that address. The door
  also refuses any address the bearer ban list has banned. One method is added,
  `BanList.Retry(key, now)`, which restarts a running lock and is capped at 24 hours. It is used
  by the door and by `withAuth`, which today returns 429 during a ban without extending it
  ([section 19](#19-found-while-planning-todays-hub)).
- **A success does not wipe the door's count.** Otherwise a member could interleave their own
  successful sign-in between guesses at someone else's password. Failures simply age out.
- **Rate limiter.** `auth.Limiter` keys each authenticated budget by token label. A people session's
  label is its session id (`s_…`), so every device has its own budget, its own playback sessions
  and its own offline grants, exactly as each hub.yaml token does today. The door's per-address
  cap is an `auth.Limiter` keyed by address; the global cap is one `auth.Bucket`.
- **Trusted proxies.** `X-Forwarded-For` is still honoured only from `server.trust_proxy_cidrs`,
  which must be `127.0.0.1/32` and `::1/128` (Caddy and Tailscale Serve both run on the PC). Two
  changes, both small edits in `middleware.go`:
  - take the right-most address that is not a trusted proxy, rather than the left-most, so a
    forwarded chain can never put a client-chosen value first;
  - key IPv6 addresses by their /64.

  Both proxies already set the header themselves rather than passing a client's value on: Caddy
  ignores incoming `X-Forwarded-*` from untrusted clients by default, and Tailscale Serve sets
  `X-Forwarded-For` to the tailnet source address. The checklist proves it with a forged header.

### 5.3 What a locked-out person sees

The prototype's wording, which the hub's messages match:

- a wrong code: "That code doesn't work. 4 tries left, then this address waits 15 minutes."
- locked: "Too many wrong codes from this address. Try again in 15 minutes. Trying meanwhile makes
  the wait longer."
- wrong PIN: "Wrong PIN. After 5 wrong PINs the password is needed."

A wrong, used, cancelled or expired code all answer the same way, so a guesser cannot tell a
real-but-expired code from a made-up one.

## 6. Telling the public link from Tailscale

The hub must know which way a request came, for the owner's rules, the audit and the device list.
Headers can be forged, and a forgery is exactly what to design against. So the edge is decided by
**which socket accepted the connection**:

| Listener | Who connects | Edge |
|---|---|---|
| `server.listen`, `127.0.0.1:8791` (today's) | Tailscale Serve, and tools on the PC itself | `tailscale` when `X-Forwarded-For` is a tailnet address (`100.64.0.0/10`, `fd7a:115c:a1e0::/48`), `local` when there is no forwarded address |
| `server.public_listen`, `127.0.0.1:8792` (new) | Caddy only | `public` |

- Caddy's `reverse_proxy` moves from 8791 to 8792 ([section 14](#14-caddy-and-the-router-steps-for-the-owner)).
- `people.enabled: true` without `public_listen` refuses to start (exit 78), naming the setting.
- A request on the private listener whose forwarded address is neither a tailnet address nor
  missing came through something other than Tailscale Serve, most likely Caddy still pointed at
  8791. It is refused with `421 wrong_listener` and logged loudly, so that mistake fails closed.
- On the private listener the hub also reads `Tailscale-User-Login`, which Tailscale Serve sets for
  tailnet users and shared users and strips when a client forges it. The owner's own Tailscale
  logins are listed in `people.owner_tailscale_logins`. On the public listener every `Tailscale-*`
  header is ignored, and Caddy strips them as well.
- `GET /v1/people/hello` reports the edge it saw (`"via": "public"`), which is how the owner checks
  the wiring in one browser visit.

## 7. Storing passwords, PINs, codes and sessions

### 7.1 The choice: stdlib PBKDF2

Passwords are hashed with **PBKDF2-HMAC-SHA256** from the standard library (`crypto/pbkdf2`, Go
1.24 and later; the hub builds with 1.27):

- 600,000 iterations, OWASP's current figure for PBKDF2-HMAC-SHA256;
- a 16-byte random salt per password, from `crypto/rand`;
- a 32-byte output;
- the input is first keyed with the pepper: `HMAC-SHA256(pepper, password)`;
- compared with `crypto/subtle.ConstantTimeCompare`.

Phase 1 measures one hash on the media PC and records it here. The target is 100 to 400 ms. The
iteration count is stored with each hash, so it can be raised later; a password hashed at a lower
count or an older pepper is re-hashed on the next successful sign-in.

**Why not argon2id.** OWASP lists argon2id first, because its memory cost resists cracking on
GPUs. That advantage applies only to offline cracking of a stolen store. Here, a stolen store alone
is useless because of the pepper, and anyone who also has `hub.secrets.yaml` already holds every
service key on the machine. Online guessing is bounded by the limits in section 5, not by the hash.
Against that, argon2id needs `golang.org/x/crypto`, while the hub's only external module is
`gopkg.in/yaml.v3`, which is a property CLAUDE.md protects. PBKDF2 with a pepper is the right trade
here. If a future need adds `x/crypto` for another reason, the stored `alg` field lets argon2id
replace it password by password.

### 7.2 The pepper

- A 32-byte random value in `hub.secrets.yaml` under `people.peppers`, each with a short id. That
  file is already locked to SYSTEM, Administrators and the owner.
- It is made once by `hubctl people pepper`, which prints the YAML lines to paste, or by
  `collect-secrets.ps1`, which adds one if missing.
- **Rotation:** add a new pepper at the top of the list. New hashes use it, and each password moves
  to it at its next sign-in. A pepper can be deleted once `hubctl people doctor` reports that no
  hash uses it, and anyone still on it then needs a reset code.
- Without a pepper, `people.enabled: true` refuses to start.

### 7.3 Codes

- Invite, guest and reset codes are 12 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no 0,
  O, 1 or I).
- Each character takes 5 bits from `crypto/rand`. 32 is a power of two, so there is no modulo bias.
- They are shown as `XXXX-XXXX-XXXX`. Input is upper-cased and stripped of spaces and hyphens;
  anything else is `400 malformed_code`, which counts against the per-address request cap but not
  as a wrong answer.
- They are stored only as `HMAC-SHA256(pepper, purpose + ":" + code)`. The purpose (`invite` or
  `reset`) keeps a reset code from ever working as an invite. A fast keyed hash is right for a
  60-bit random value that expires within days.
- A presented code is checked against every live code with `ConstantTimeCompare`, without stopping
  at the first match, as `auth.Store.Verify` does for tokens.
- The owner's list shows a hint, the first four characters (`K7QM-····-····`), and nothing else.
  The full code is returned once, in the response that made it.

### 7.4 PINs

- Exactly 4 digits. The 20 most common PINs are refused ([decision 3](#18-decisions-for-the-owner)).
- Hashed with the same PBKDF2 function, salted per session, with the input
  `HMAC(pepper, "pin:" + sessionId + ":" + pin)`, so a PIN works only on the device session it was
  set on.
- A PIN hash is worthless without that session's token, and the token's hash cannot be reversed.
  So even 4 digits stay safe in a stolen store.

### 7.5 Session tokens

- `jhs1_` followed by 32 random bytes in unpadded base64url (48 characters in all).
- The prefix lets secret scanners recognise one, and lets `withAuth` look up the people store
  without first trying every hub.yaml digest.
- Stored only as SHA-256 hex, like hub.yaml tokens. A 256-bit random value needs no slow hash, and
  looking one up by its hash leaks nothing useful through timing.
- Ended sessions leave a tombstone (hash, reason, time) for 30 days. A device presenting one gets
  `401 session_ended` with the reason (`signed_out`, `locked`, `removed`, `expired`,
  `password_changed` or `guest_code_ended`), and that does not count as a guess toward the bearer
  ban, because only a real old token can match a tombstone.

### 7.6 Revocation

Checked on every request against in-memory state, never a cache:

| Event | Effect |
|---|---|
| Sign out one device (owner or person) | That session ends |
| Sign out all devices | Every session of the person ends; the password still works |
| Lock | Every session ends (`401 session_ended`, reason `locked`); sign-in is refused; history is kept |
| Unlock | Sign-in works again. Sessions ended by the lock stay ended |
| Reset code issued | The old password stops at once; sessions carry on |
| Password changed by the person | With `signOutOthers` (the app's default), every other session ends |
| Remove | Every session ends and the person, their invites and reset codes go; Jellyfin history stays in Jellyfin |
| Guest code cancelled or expired | Its guest sessions end |

"At once" includes work in flight. When a session ends, the hub:

- cancels the contexts of its open transport requests, so a film streaming as one long range
  request stops mid-file;
- closes its playback sessions;
- revokes its cast grants, which are capability URLs and would otherwise keep a TV playing;
- releases its offline grants.

### 7.7 Files

- **Folder.** `C:\ProgramData\AyaneoHub\people\` (`people.store`), made by a new administrator
  script, `deploy/windows/secure-people-folder.ps1`. It creates the folder, turns inheritance off
  and grants Full control to SYSTEM and Administrators only. Like `collect-secrets.ps1`, it leaves a
  folder that is already correct alone.
- **`people.json`.** People, sessions, invites, reset codes, tombstones and settings. It is written
  as `reading-resets.json` and the offline registry are: marshal, write `people.json.tmp`, rename
  over. The in-memory state rolls back if the write fails, and a file that cannot be read is never
  written over (`loadErr`).
- **Last-seen times.** Written at most once per 10 minutes per session, so streaming does not churn
  the disk.
- **`audit.jsonl`** and its rotations ([section 8](#8-logging-and-audit)).
- **Offline edits.** The `hubctl people …` commands that edit the store (`revoke-all`) refuse to
  run while the AyaneoHub service is running or anything answers on the hub's ports, so they can
  never race the hub's own writes.
- **Never** a plain password, PIN, code, token or the pepper. A test writes a store with known
  test secrets and greps the file for each.
- **Checking it.** `hubctl doctor` reports the folder's permissions by running `icacls` and fails
  if Users, Everyone or Authenticated Users appear.

## 8. Logging and audit

### 8.1 The hub's request log

Unchanged in shape. The `token` field carries the session label (`s_…`) for a people session, as
it carries the hub.yaml label today. Never logged:

- the `Authorization` header;
- request bodies of people routes;
- any password, PIN, code, token, hash, salt or pepper.

The redaction of `/v1/cast/` paths stays.

### 8.2 The audit log

`people/audit.jsonl`, one event per line, readable by the owner through `GET /v1/people/audit`.
Each event carries:

- `at`, `event`, `via` (`public`, `tailscale` or `local`) and `address` (the IPv4 address or IPv6
  /64);
- `personId`, `personName`, `sessionId` and `deviceName` when known;
- `inviteId` and its four-character hint;
- `outcome`, and `by` (who did it, for owner actions).

| Group | Events |
|---|---|
| Door | `invite_check_failed`, `joined`, `join_waiting_approval`, `signed_in`, `sign_in_failed` (person id only when the name exists, never the typed text), `reset_used`, `reset_failed`, `door_locked`, `joining_paused`, `name_paused`, `public_closed_refused` |
| Device | `pin_failed`, `pin_needs_password`, `unlocked`, `locked_device`, `signed_out`, `session_expired`, `password_changed`, `pin_set`, `pin_removed` |
| Owner | `invite_created`, `invite_cancelled`, `person_updated` (the fields and new values: permissions, Jellyfin user, name), `person_locked`, `person_unlocked`, `reset_issued`, `signed_out_all`, `person_removed`, `join_approved`, `device_added_by_owner`, `public_link_closed`, `public_link_opened` |

- **Kept** for 180 days, in files of at most 10 MB with three rotations.
- **Never** contains a secret, a typed name that matched nobody (it may be a password typed in the
  wrong field) or a request body.
- **Notifications.** New devices, approvals waiting, door locks and paused joining also appear in
  the owner's `/v1/notifications`, so the owner hears about them without opening the log.

## 9. Data model

`people.json`, version 1. Every value below is a made-up placeholder.

```json
{
  "version": 1,
  "settings": { "publicLink": "open" },
  "people": [
    {
      "id": "p_7k2m9q4w8x3n5r6t",
      "name": "Hadas",
      "role": "member",
      "jellyfinUserId": "0123456789abcdef0123456789abcdef",
      "permissions": { "downloads": true, "requests": true },
      "colour": "#E89BB5",
      "locked": false,
      "approval": "approved",
      "password": {
        "alg": "pbkdf2-sha256", "iter": 600000, "pepper": "p1",
        "salt": "<base64>", "hash": "<base64>", "setAt": "2026-11-02T18:04:11Z"
      },
      "createdAt": "2026-11-02T18:04:11Z",
      "lastSeenAt": "2026-11-09T21:40:00Z",
      "inviteId": "i_3v8b1c7d2e9f4g6h",
      "requestsToday": { "day": "2026-11-09", "count": 2 }
    }
  ],
  "sessions": [
    {
      "id": "s_q4w8x3n5r6t7k2m9",
      "personId": "p_7k2m9q4w8x3n5r6t",
      "tokenHash": "<sha256 hex>",
      "device": { "id": "<app install id>", "name": "Hadas's iPhone", "platform": "ios", "app": "JellyHub 1.6.0" },
      "via": "tailscale",
      "state": "active",
      "pin": { "alg": "pbkdf2-sha256", "iter": 600000, "pepper": "p1", "salt": "<base64>", "hash": "<base64>" },
      "pinFailures": 0,
      "createdAt": "2026-11-02T18:04:11Z",
      "lastSeenAt": "2026-11-09T21:40:00Z",
      "expiresAt": null
    }
  ],
  "invites": [
    {
      "id": "i_3v8b1c7d2e9f4g6h",
      "hint": "K7QM",
      "codeHash": "<hmac-sha256 hex>",
      "kind": "member",
      "label": "For Noa",
      "permissions": { "downloads": true, "requests": false },
      "jellyfinUserId": "fedcba9876543210fedcba9876543210",
      "maxDevices": 1,
      "uses": [],
      "createdBy": "p_owner…",
      "createdAt": "2026-11-02T10:00:00Z",
      "expiresAt": "2026-11-09T10:00:00Z",
      "cancelledAt": null
    }
  ],
  "resets": [
    {
      "id": "r_…", "personId": "p_…", "codeHash": "<hmac-sha256 hex>", "hint": "R2VN",
      "createdBy": "p_owner…", "createdAt": "…", "expiresAt": "…", "usedAt": null
    }
  ],
  "tombstones": [
    { "tokenHash": "<sha256 hex>", "reason": "signed_out", "at": "…" }
  ]
}
```

Rules the store enforces:

- **Ids.** `p_`, `s_`, `i_` and `r_` followed by 16 base32 characters (80 bits) from
  `crypto/rand`. Ids are not secrets; they appear in routes and logs.
- **Names.** 1 to 20 characters after trimming, unique without regard to case. Control characters
  and bidirectional override characters are refused, and so is a name equal to the owner's once
  case, spaces and punctuation are ignored.
- **The owner** is created from config when the store is first opened with people enabled. There
  is always exactly one.
- **Jellyfin users.** A member's or guest's `jellyfinUserId` must be set, must not be the owner's
  and must not be another person's, so each person's history is their own. Guests share
  `people.guest_jellyfin_user_id`, a Jellyfin user the owner makes for them. Without it, guest
  invites cannot be made (`409 no_guest_profile`).
- **A device** is a session from the hub's view. `device.id` is a random installation id the app
  makes once. It is not a secret: it only groups a shared device's sessions, so the owner can sign
  that device out for everyone. Claiming another device's id gains nothing, since the effect of a
  shared id is only that both are signed out together.
- **Session states:**

  | State | Meaning |
  |---|---|
  | `active` | Usable on every route the role allows |
  | `pin_locked` | Needs the PIN |
  | `password_required` | 5 wrong PINs; needs the password |
  | `awaiting_approval` | A public join waiting for the owner |

  In every state but `active`, only `GET /v1/people/me`, unlock and sign-out work. Everything else
  answers `401` with the state as its code, and that never counts toward a ban.
- **Guest sessions** end when their guest code expires, 7 days after it was made.

## 10. Routes

Every route below exists only with `people.enabled: true`. Bodies are JSON, at most 4 KB, one
object, unknown fields refused. Times are RFC 3339 UTC. Errors use the hub's existing envelope
(`{"error":{"code":…,"message":…,"retryable":…,"retryAfterSeconds":…}}`).

### 10.1 The door (no token)

Served on both listeners, under the door's caps (section 5).

**`GET /v1/people/hello`**: what a new device sees after typing the address.

```json
{ "server": "JellyHub", "people": true, "via": "public", "joining": "open" }
```

`joining` is `open`, `paused` (the global cap) or `closed`. `closed` means the owner closed the
public link: hello still answers on it, so the app can say so, and Tailscale still says `open`.

**`POST /v1/people/invites/check`**: tells the app which form to show. It does not use the code.

```json
{ "code": "K7QM-4XRT-9WDA" }
```

```json
{ "kind": "member", "from": "Dgdan", "expiresAt": "2026-11-09T10:00:00Z",
  "permissions": { "downloads": true, "requests": false } }
```

For a guest code: `{"kind": "guest", "from": "Dgdan", "devicesLeft": 4}`. A wrong, used,
cancelled or expired code:

```json
{ "error": { "code": "code_not_valid", "message": "That code doesn't work.", "triesLeft": 4 } }
```

with status 400. It is never 401, because the apps read 401 as "this token is wrong" and stop all
traffic.

**`POST /v1/people/join`**: a member invite makes the person and this device's session.

```json
{
  "code": "K7QM-4XRT-9WDA",
  "name": "Noa",
  "password": "<at least 8 characters>",
  "pin": "4821",
  "colour": "#B79CF0",
  "device": { "id": "<install id>", "name": "Noa's Pixel", "platform": "android", "app": "JellyHub 0.5.0" }
}
```

`201`:

```json
{
  "session": { "id": "s_…", "token": "jhs1_…", "state": "active" },
  "person": { "id": "p_…", "name": "Noa", "role": "member",
              "permissions": { "downloads": true, "requests": false } }
}
```

- Over the public link with approval on ([decision 2](#18-decisions-for-the-owner)), it answers
  `202` with `"state": "awaiting_approval"`.
- A guest code needs only `code` and `device`. Its first use makes the guest person, named from
  the invite's label or "Guest", and each further device adds a session until 5.
- Failures: `400 code_not_valid`, `400 weak_password` (`reason`: `too_short`, `too_common`,
  `contains_name`), `400 weak_pin`, `409 name_taken`, `429 door_locked`, `429 joining_paused`,
  `503 public_closed`.

**`POST /v1/people/sign-in`**

```json
{ "name": "Hadas", "password": "…", "device": { "id": "…", "name": "Hadas's iPad", "platform": "ipados", "app": "JellyHub 1.6.0" } }
```

- `201` answers as join does.
- A wrong password and an unknown name both answer `400 sign_in_failed` with `triesLeft`, after the
  same amount of work: an unknown name is checked against a dummy hash.
- A locked person answers `403 person_locked`, but only after a correct password, so a guesser
  cannot use it to learn who is locked.
- The owner over the public link answers `403 private_only`
  ([decision 1](#18-decisions-for-the-owner)), also only after a correct password.
- Also `429 name_paused`, `409 too_many_devices` and the door's 429s.

**`POST /v1/people/reset`**: redeem a reset code with a new password.

```json
{ "code": "R2VN-8HLC-3QPE", "password": "…", "device": { … } }
```

`200` with `person` (and `session` when `device` is given). Failures as invite codes.

### 10.2 Signed in (any people session; hub.yaml tokens act as the owner)

| Route | Body | Answer |
|---|---|---|
| `GET /v1/people/me` | | person, session, `scopes` (works in every session state) |
| `POST /v1/people/me/unlock` | `{"pin":"4821"}` or `{"password":"…"}` | `{"state":"active"}`; wrong: `400 pin_wrong` with `pinTriesLeft`, then state `password_required` |
| `POST /v1/people/me/lock` | | `{"state":"pin_locked"}`; `409 no_pin` |
| `PUT /v1/people/me/pin` | `{"pin":"4821","password":"…"}` (password only when replacing a PIN) | `204` |
| `DELETE /v1/people/me/pin` | `{"password":"…"}` | `204` |
| `POST /v1/people/me/password` | `{"current":"…","new":"…","signOutOthers":true}` | `204` |
| `GET /v1/people/me/devices` | | this person's sessions |
| `DELETE /v1/people/me/devices/{sessionId}` | | `204` |
| `DELETE /v1/people/me/session` | | `204`, signs this device out |
| `PATCH /v1/people/me` (phase 2) | `{"colour":"#7FB8E6"}` | person |
| `PUT /v1/people/me/picture` (phase 2) | `image/jpeg` or `image/png`, at most 2 MB | person; saved to Jellyfin |
| `GET /v1/img/person/{personId}` | | the Jellyfin profile picture, or 404 (the app then draws the colour and initial) |

`GET /v1/people/me`:

```json
{
  "person": { "id": "p_…", "name": "Hadas", "role": "member", "colour": "#E89BB5",
              "picture": "/v1/img/person/p_…?tag=3f9a…",
              "permissions": { "downloads": true, "requests": true } },
  "session": { "id": "s_…", "state": "active", "pinSet": true, "via": "tailscale",
               "device": { "name": "Hadas's iPhone", "platform": "ios" },
               "createdAt": "…" },
  "scopes": ["read", "play", "reading", "download", "request"]
}
```

**Pictures.** The hub reads the person's Jellyfin user (`PrimaryImageTag` on the user) and proxies
Jellyfin's user image (`GET /UserImage?userId=…&tag=…`; confirm the path against the server's own
`/api-docs/openapi.json`, as CLAUDE.md requires).

- It goes on the artwork budget, cached 30 days per tag.
- A person can fetch their own picture; the owner can fetch anyone's.
- Setting a picture (phase 2) uploads to Jellyfin's `POST /UserImage?userId=…` with the hub's
  admin key, so the picture shows in Jellyfin's own apps too.

### 10.3 Owner (scope `people`; with [decision 1](#18-decisions-for-the-owner), Tailscale or local only)

| Route | Body | Answer |
|---|---|---|
| `GET /v1/people` | | people with device counts and last seen, invites waiting, reset codes waiting, approvals waiting, `publicLink` |
| `GET /v1/people/{personId}` | | the person and every device |
| `PATCH /v1/people/{personId}` | `{"permissions":{"downloads":false},"jellyfinUserId":"…","name":"…"}` | person; takes effect on the next request of every device |
| `POST /v1/people/{personId}/lock` / `unlock` | | person |
| `POST /v1/people/{personId}/reset-code` | | `{"code":"R2VN-8HLC-3QPE","expiresAt":"…"}`, shown once |
| `POST /v1/people/{personId}/sign-out` | | `{"signedOut":2}` |
| `DELETE /v1/people/{personId}/sessions/{sessionId}` | | `204` |
| `POST /v1/people/{personId}/approve` | | person (approval waiting → active) |
| `DELETE /v1/people/{personId}` | `{"confirm":true}` | `204` |
| `POST /v1/people/{personId}/sessions` | `{"device":{…}}` | a session for that person on the calling device ("Add to this device", so the family can be on the Pocket DS without typing passwords there) |
| `DELETE /v1/people/devices/{deviceId}` | | signs out everyone on one device (a stolen shared tablet) |
| `POST /v1/people/invites` | `{"kind":"member","label":"For Noa","permissions":{"downloads":true,"requests":false},"jellyfinUserId":"…","days":7}` | `201 {"invite":{…},"code":"K7QM-4XRT-9WDA"}`, the code shown once |
| `DELETE /v1/people/invites/{inviteId}` | | `204`; a guest invite's sessions end too |
| `GET /v1/people/audit?limit=200&personId=` | | newest events first |
| `PUT /v1/people/public-link` | `{"open":false}` | `{"publicLink":"closed"}` |

- `days` may be 1 to 7.
- Owner actions on the owner themselves are limited to reset code, sign out and devices.
- **The public-link switch.** When closed, the public listener answers every request with
  `503 public_closed`, hub.yaml tokens included, except `/v1/health/live` and
  `/v1/people/hello` (which says `"joining": "closed"`). Tailscale is unaffected. It can be
  reopened only over Tailscale or locally.

## 11. Configuration

`hub.yaml`:

```yaml
server:
  listen: "127.0.0.1:8791"          # Tailscale Serve and local tools
  public_listen: "127.0.0.1:8792"   # Caddy only; required when people.enabled
  trust_proxy_cidrs: ["127.0.0.1/32", "::1/128"]

people:
  enabled: false                    # nothing below does anything until true
  server_name: "JellyHub"           # shown to anyone who has the address
  store: "people"                   # folder beside hub.yaml, locked by secure-people-folder.ps1
  owner:
    name: "Dgdan"
    jellyfin_user_id: ""            # defaults to services.jellyfin.user_id
  owner_tailscale_logins: []        # the owner's own Tailscale login(s)
  owner_private_only: true          # decision 1
  approve_public_joins: true        # decision 2
  refuse_common_pins: true          # decision 3
  guest_jellyfin_user_id: ""        # a Jellyfin user made for guests
  member_jellyseerr_user_id: 0      # decision 4; 0 keeps today's act_as user
  invite_days: 7
  guest_devices: 5
  reset_hours: 24
  session_idle_days: 90
  pin_relock_after: 12h
  limits:                           # the defaults from section 5
    door_failures: 5
    door_window: 15m
    door_lock: 15m
    door_lock_max: 24h
    door_requests_per_address: 10
    door_requests_window: 15m
    door_global_per_minute: 30
    door_pause: 5m
    name_failures: 10
    name_window: 1h
    hash_concurrency: 2
    pin_failures: 5
    devices_per_person: 10
    member_requests_per_day: 20
    member_streams: 2
    guest_streams: 1
```

`hub.secrets.yaml`:

```yaml
people:
  peppers:
    - id: "p1"
      value: "<from hubctl people pepper>"
```

With `enabled: true`, validation refuses to start (exit 78), naming the setting, when:

- `public_listen` is missing, not loopback, or the same as `listen`;
- `trust_proxy_cidrs` does not cover loopback;
- there is no pepper, or one is shorter than 32 bytes or below 3.5 bits per character;
- Jellyfin is not configured, or the owner has no Jellyfin user;
- the store folder does not exist (run the script);
- a limit is weakened past its floor;
- `owner_private_only` is on with no `owner_tailscale_logins`. That is allowed, but the hub warns
  that the owner's people sessions then work only from the PC itself; hub.yaml tokens are
  unaffected.

## 12. Existing hub.yaml tokens

They keep working exactly as today, on both listeners, with people on or off.

- With people on, a hub.yaml token acts as the owner person: `GET /v1/people/me` answers the
  owner, and `X-Jellyfin-User` is honoured as today, so the owner can still pick any profile.
- Their scopes are their configured scopes plus derived ones, so no existing route answers
  differently:
  - `manage`, always, since every token can reach those routes today;
  - `grab`, when the token has `request`;
  - `people`, when the token has `control`.
- They have no PIN. On a shared device the owner should sign in as a person with a PIN rather than
  use a hub.yaml token.
- People routes are new, so applying `owner_private_only` to hub.yaml tokens on those routes breaks
  nothing. With [decision 1](#18-decisions-for-the-owner), the Pocket DS's token can manage people
  over Tailscale; over the public fallback it can do everything it does today, but not manage
  people.
- Revoking one is still removing its line from `hub.yaml` and restarting the service.

**What an older app sees:** with people off, nothing changes. With people on, an older app holds a
hub.yaml token and still sees nothing new. The only new 403s are on member and guest sessions,
which only a phase 2 app can hold.

## 13. The phases

Each phase ships on its own and changes nothing until enabled.

### Phase 1: hub accounts

Everything in sections 4 to 12:

- the people store and its folder script;
- PBKDF2 with the pepper;
- invites, guest codes, sessions, PINs, reset codes, lock, sign-out and remove, approval;
- the door's caps;
- the two listeners;
- the new scopes and the route checks in 4.2;
- the `X-Jellyfin-User` overwrite;
- the audit log, the public-link switch and person pictures;
- `hubctl people pepper`, `people doctor`, `people revoke-all` (hub stopped) and `doctor`'s
  folder check;
- the list of the 10,000 most common passwords (SecLists, MIT licence), embedded with
  `go:embed` and recorded in `THIRD_PARTY_SOFTWARE.md`.

Two rules hold until phase 3 and 4 replace them:

- Reading places (Storyteller and Kavita positions, EPUB and audiobook places, start-over) are
  refused for members and guests: reads answer "no place", writes answer `403 place_not_saved`.
  Today every place is the owner's Storyteller account, and a member's reading would move the
  owner's bookmark.
- Ratings, finished months and Goodreads imports (`readingYou`) are already per Jellyfin profile,
  so they are per person once each person has their own Jellyfin user.

Built in new files where possible (`people_*.go` in `internal/api`, a new `internal/people`
package for the store and hashing), with small additive edits to `router.go`, `middleware.go`,
`config.go`, `validate.go` and `auth.go`, so the merges with the reading work in flight stay
clean.

**Tests first**, `go vet ./... && go test ./...` green. They cover:

- flag off: every new route 404s, hub.yaml tokens verify exactly as today, and the response for
  every existing route is byte-identical for a hub.yaml token;
- constant-time compares, and an unknown name timed like a wrong password;
- every lock and cap in section 5, with an injected clock, including the restart during a lock,
  the 24-hour ceiling, the IPv6 /64 key and the separate public and Tailscale name counts;
- expiry, single use, 5 guest devices, cancellation, a new reset code cancelling the old, and the
  old password stopping at issue;
- a locked or removed person's open stream, playback session and cast grant ending at once;
- the route matrix for owner, member and guest, `TestEveryRouteIsClassified`, and a member's
  `X-Jellyfin-User` being ignored;
- the edge: the right listener, a wrong forwarded address answering 421, forged `Tailscale-*`
  headers ignored on the public listener, right-most `X-Forwarded-For`;
- no secret in any log line, audit line, response or store file, by grepping each for known test
  values;
- store durability: an atomic write, a rollback on a failed write, an unreadable file never written
  over.

### Phase 2: the apps, Pocket then Apple

- **First run.** The app holds two addresses: the Tailscale one and the public one. It tries
  Tailscale first with a 3-second timeout and falls back to public. The connect screen shows
  "Private connection" or "Public link" as in the prototype. Then: I have an invite code / Sign in
  with my name and password / I have a guest code.
- **Join.** Name, colour or photo, password (8 or more), optional 4-digit PIN, then Join. Then
  "Waiting for Dgdan to let you in" when approval applies.
- **Tokens.** Stored per person per device. Apple: Keychain, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.
  Android: today's `HubSettings` reasoning holds for the owner's own handheld, but the APK will run
  on friends' phones. Recommended: a session token wrapped with an Android Keystore AES-GCM key, and
  if the keystore ever fails, sign in again (the password or PIN is now the recovery path the old
  reasoning lacked). `allowBackup` stays false.
- **"Who's watching?"** lists the people signed in on this device (the sessions the app holds),
  plus Guest when a guest session exists, plus "Add someone". It never lists everyone on the
  server: picking a member without a PIN would then need no credential at all. A PIN pad appears
  for a `pin_locked` session. The app locks a PIN session when switching away and after it has been
  in the background for a while; the hub's 12-hour relock is the safety net.
- **The owner's People page.**
  - Invite someone: member or guest, downloads and requests toggles, Jellyfin user, "Works for
    7 days", Make the code, then the share sheet with the address and the code.
  - Invites waiting with Cancel, and approvals waiting.
  - A person page with permissions, devices, Reset password, Sign out all devices, Lock or Unlock,
    and Remove from the server.
  - Add to this device, Public link Open or Closed, and the audit.
- **Errors.** The apps learn the new codes:
  - door calls go around `CredentialGate`, which keys on a token;
  - `pin_locked`, `password_required`, `awaiting_approval` and `session_ended` (with its reason)
    are 401s that open the right screen rather than "token rejected";
  - `person_locked` from sign-in says the owner has locked this person;
  - `door_locked`, `joining_paused` and `name_paused` show a countdown from `Retry-After`;
  - `private_only` says "Sign in over Tailscale".
- **Hiding.** The app hides what the session's `scopes` do not include: Manage, Activity,
  requests, downloads and the profile picker.

### Phase 3: per-person reading places

The hub keeps each person's places itself: EPUB locations, audiobook positions, Kavita pages and
start-over stamps, keyed by person id. The owner stays on Storyteller, as today. The #60, #62 and
#63 data move from per-profile (Jellyfin user id) to per-person: the owner's records go to the
owner person, and every other record goes to the person mapped to that Jellyfin user. Phase 1's
refusal of member places is then lifted.

### Phase 4: guests

- The hub drops guest writes: watch positions, played, favourites, reading places, ratings. It
  answers `204` with `"saved": false`, so the app can say "Nothing is saved".
- Guests play as the Jellyfin "Guest" user, whose library access the owner sets in Jellyfin.
- One stream per guest device.

### Phase 5: going public

1. Caddy hardening ([section 14](#14-caddy-and-the-router-steps-for-the-owner)), with the edge rate
   limit if the plugin build is used.
2. The Tailscale access policy and the sharing guide ([section 15](#15-the-tailscale-path)).
3. The whole checklist in [section 16](#16-pre-launch-checklist-and-test-plan) passes.
4. Distribution: a TestFlight public link (Apple's beta review), and APK sharing for friends.
5. Revisit whether hub.yaml tokens should keep `control` over the public link (a per-token
   `private_only`).

## 14. Caddy and the router: steps for the owner

Every command runs in **PowerShell as Administrator** on the media PC (Start, type PowerShell,
right-click, Run as administrator). Each step says how to check it worked and how to undo it.

### 14.1 Find Caddy and back it up

```powershell
Get-CimInstance Win32_Service |
  Where-Object { $_.Name -match 'caddy' -or $_.PathName -match 'caddy' } |
  Select-Object Name, State, PathName
```

Note the service name and the folder in `PathName`. The Caddyfile is normally in the same folder;
below, that folder is `C:\Caddy`, so use your own.

```powershell
Copy-Item C:\Caddy\Caddyfile "C:\Caddy\Caddyfile.before-people-$(Get-Date -Format yyyyMMdd-HHmmss)"
```

- **Check:** `Get-ChildItem C:\Caddy\Caddyfile*` lists the copy.
- **Undo, at any later step:** copy the backup back over `Caddyfile`, then reload (14.4).

### 14.2 Optional: a Caddy build with rate limiting

The edge rate limit is an extra layer; the hub's own limits hold without it.

1. On caddyserver.com/download, choose Windows amd64 and tick `dns.providers.duckdns` (the module
   in use now). Then tick `http.handlers.rate_limit` if it is listed.
2. Download it, stop Caddy (`Stop-Service <name>`), rename the old `caddy.exe` to
   `caddy.exe.old`, put the new one in its place, and start Caddy (`Start-Service <name>`).

- **Check:** `C:\Caddy\caddy.exe list-modules | Select-String "duckdns|rate_limit"` shows both
  lines.
- **If `rate_limit` is not offered:** skip it and leave the `rate_limit` block out in 14.3.

### 14.3 Edit the Caddyfile

**At the very top**, add a global block, or merge it into one that is already there:

```caddyfile
{
	servers {
		timeouts {
			read_header 10s
			read_body 60s
			idle 2m
		}
		max_header_size 16KB
	}
}
```

There is deliberately no `write` timeout: films stream as long responses, and a write timeout
would cut them.

**In the `myjellydan.duckdns.org` site block**, keep `tls`, `encode`, `header` and the
`@notapi` 404 as they are. Add the body limits and the optional rate limit, and change the
`reverse_proxy` block to this:

```caddyfile
	@door path /v1/people/hello /v1/people/invites/check /v1/people/join /v1/people/sign-in /v1/people/reset
	request_body @door {
		max_size 16KB
	}
	request_body {
		max_size 10MB
	}

	# Only with the build from 14.2. Leave this block out otherwise.
	rate_limit {
		zone people_door {
			match {
				path /v1/people/hello /v1/people/invites/check /v1/people/join /v1/people/sign-in /v1/people/reset
			}
			key {remote_host}
			ipv6_prefix 64
			events 20
			window 1m
		}
	}

	reverse_proxy 127.0.0.1:8792 {
		header_up X-Real-IP {remote_host}
		header_up -Tailscale-*
		flush_interval -1
	}
```

- The port is **8792**, the hub's public listener. Change it only once the hub with people is
  deployed: an older hub does not listen there.
- `header_up -Tailscale-*` keeps an internet client from sending Tailscale identity headers.
- The 10 MB limit leaves room for the Goodreads import (8 MB).

### 14.4 Validate and reload

```powershell
C:\Caddy\caddy.exe validate --config C:\Caddy\Caddyfile --adapter caddyfile
C:\Caddy\caddy.exe reload --config C:\Caddy\Caddyfile --adapter caddyfile
```

- **Check:** the first prints `Valid configuration`; the second prints nothing.
- **If reload says it cannot reach the admin endpoint:** run `Restart-Service <name>` instead.

### 14.5 Check from outside

On a phone with **Wi-Fi off and Tailscale off** (mobile data only):

- `https://myjellydan.duckdns.org:55886/v1/people/hello` shows `"via":"public"`.
- `https://myjellydan.duckdns.org:55886/` shows an empty page (Caddy's 404), not anything of the
  hub's or Jellyfin's.

### 14.6 The router (Sagemcom)

1. **Find it.** In PowerShell, `ipconfig`. The "Default Gateway" line is the router's address.
   Open it in a browser and sign in.
2. **Port forwards.** Find "Port forwarding" (it may be under NAT, Firewall or Access control).
   There must be **exactly one** rule: external TCP **55886** to **10.100.102.8**, port **443**.
   Delete every other forward. The 2026-09-07 audit found Sonarr (8989) and qBittorrent (8080)
   forwarded; make sure they are gone.
3. **UPnP.** Turn off automatic port mapping for web pages. In qBittorrent, Options › Web UI:
   untick "Use UPnP / NAT-PMP to forward the port from my router". Turning UPnP off on the router
   entirely is stronger, but it can slow torrents unless the torrent port gets its own manual
   forward.
4. **The router itself.** Remote management from the internet off; its admin password not the
   one printed on the sticker.
5. **DHCP.** Keep the DHCP reservation of 10.100.102.8 for the PC (CLAUDE.md).

**Check from outside**, from a laptop on the phone's hotspot (not home Wi-Fi):

```powershell
55886, 80, 443, 3000, 5000, 5055, 6767, 7878, 8001, 8080, 8096, 8787, 8920, 8989, 9696 |
  ForEach-Object {
    [pscustomobject]@{ Port = $_; Open = (Test-NetConnection myjellydan.duckdns.org -Port $_ -WarningAction SilentlyContinue).TcpTestSucceeded }
  }
```

Only 55886 may say `True`. If there is no laptop, open `http://myjellydan.duckdns.org:<port>` on
the phone for 8080, 8989, 8096 and 5055; each must fail to load.

### 14.7 Visitors on home Wi-Fi

The dashboards also listen on the home network. A guest on the home Wi-Fi is therefore inside that
fence. Give visitors the router's guest Wi-Fi, which is kept apart from the home network.

- **Check:** a phone on the guest Wi-Fi cannot open `http://10.100.102.8:8080`.

## 15. The Tailscale path

### 15.1 Why it is the safer default

Over Tailscale, nobody on the internet can see the door at all:

- there is nothing to scan, guess or flood;
- the connection is encrypted end to end;
- each person's access can be revoked in Tailscale as well as in JellyHub, which makes two locks.

The owner and family use it. Trusted friends get **machine sharing**: only the media PC, nothing
else on the owner's network. Casual friends and guests use the public link with an invite.

### 15.2 Limit what shared people can reach (do this first)

A person you share a machine with can reach **every port on it** by default (Tailscale's
documentation), and every port behind Tailscale Serve sees them as localhost (section 3.10). So
before the first share:

1. Open the Tailscale admin console, **Access controls**.
2. In the policy, find the `grants` (or older `acls`) section and replace the allow-everything rule
   with these two. Keep every other section as it is.

```json
"grants": [
  // Your own devices: everything, as today.
  { "src": ["autogroup:member"], "dst": ["*"], "ip": ["*"] },
  // Anyone the media PC is shared with: JellyHub only.
  { "src": ["autogroup:shared"], "dst": ["*"], "ip": ["443"] }
]
```

If the policy uses the older `acls` form, the same two rules are:

```json
"acls": [
  { "action": "accept", "src": ["autogroup:member"], "dst": ["*:*"] },
  { "action": "accept", "src": ["autogroup:shared"], "dst": ["*:443"] }
]
```

3. Save.

- **Check:** the editor saves without an error. After the first share (15.4), add a test to the
  policy with the friend's Tailscale login, which Tailscale then checks on every save. Use the
  media PC's Tailscale address from the Machines page (`100.x.y.z`):

```json
"tests": [
  { "src": "<friend's tailscale login>", "accept": ["100.x.y.z:443"],
    "deny": ["100.x.y.z:8080", "100.x.y.z:8989", "100.x.y.z:8920"] }
]
```

  If Tailscale will not accept a test for a user from another tailnet, check by hand from their
  phone instead (15.4).

**Family.** Rather than adding family members to the owner's own Tailscale account, which gives
them `autogroup:member` and every port, share the machine with each of them as with a friend.
Everyone who is not the owner then gets the same single port.

### 15.3 Dashboards behind Tailscale

The rule from section 3.10, as steps:

- **qBittorrent.** Options › Web UI: untick "Bypass authentication for clients on localhost" and
  set a strong password. Then give the hub that username and password (`services.qbittorrent.username`
  and `password` in `hub.secrets.yaml`) and check that Activity still loads.
- **Radarr, Sonarr, Prowlarr and Readarr.** Settings › General › Security: Authentication "Forms",
  with "Authentication Required" set to **Enabled**, not "Disabled for Local Addresses".
- **Bazarr.** Settings › General › Security: authentication on.
- **Jellyseerr, Kavita, Storyteller, BookKeeprr, Cleanuparr and Jellyfin:** each has its own login,
  and none may allow anonymous access.
- **Check:** on the owner's phone over Tailscale, each dashboard address in Manage asks for a
  password before showing anything.

### 15.4 Share the media PC with someone

1. Admin console, **Machines**. Find **ayaneo-media-pc**, open its menu (the three dots) and
   choose **Share**.
2. Either enter their e-mail and choose **Share**, or open **Copy invite link** and copy a link
   with **Reusable link** left off.
3. They install Tailscale on their phone (a free personal account) and open the link.
4. In JellyHub they enter `https://ayaneo-media-pc.tail737e96.ts.net` and their invite code.

- **Check, on their phone:**
  - JellyHub shows "Private connection";
  - `https://ayaneo-media-pc.tail737e96.ts.net:8080` does not load;
  - `https://ayaneo-media-pc.tail737e96.ts.net:8989` does not load.
- **To stop sharing:** Machines › ayaneo-media-pc › Share, then the menu beside that person, then
  **Revoke invite**. Also sign them out in People.

A shared machine is "quarantined": it answers the people it is shared with but cannot start
connections to their devices.

### 15.5 Keep Funnel off

On the PC:

```powershell
tailscale funnel status
tailscale serve status
```

- `funnel status` must not show any Funnel.
- `serve status` must say "(tailnet only)" for every listener.

## 16. Pre-launch checklist and test plan

Nobody but the owner uses the public link until every line here passes. Record the date and the
result of each in #65.

### 16.1 Configuration

1. The hub with people enabled is deployed from `claude/consolidation`, and
   `hub.exe --check --config C:\ProgramData\AyaneoHub\hub.yaml` passes.
2. `public_listen` is set, and Caddy points at it (14.3). Hello over the public link says
   `public`; over Tailscale it says `tailscale`.
3. `trust_proxy_cidrs` covers `127.0.0.1/32` and `::1/128`.
4. `icacls C:\ProgramData\AyaneoHub\people` lists only `NT AUTHORITY\SYSTEM` and
   `BUILTIN\Administrators`, and `hubctl doctor` agrees.
5. A pepper is in `hub.secrets.yaml`, and that file's permissions are unchanged.
6. The owner has a long password, and `owner_tailscale_logins` holds the owner's login.
7. Funnel is off and Serve is tailnet only (15.5).
8. The router forwards only 55886, web-page UPnP is off, and remote management is off (14.6).
9. Every dashboard asks for a password over Tailscale, and qBittorrent has no localhost bypass
   (15.3).
10. The Tailscale policy limits shared users to 443, with a test for each sharee (15.2).
11. Caddy validates, with timeouts, body limits, the header strip and port 8792 (14.3).
12. Windows Update, Caddy, Tailscale and Jellyfin are current.
13. The test people, codes and passwords used in 16.2 are made up for the test, and removed
    afterwards.

### 16.2 A test for each threat

Run from a phone on mobile data with Tailscale off unless a line says otherwise. The hub's own
tests cover each rule exhaustively. These prove the deployed wiring.

| Threat | Do this | Expect |
|---|---|---|
| Stranger | Open `/`, `/v1/health` and `/v1/people` on the public address | A 404 page, then 401, then 401 |
| Stranger | The port check in 14.6 | Only 55886 open |
| Guessing codes | On the join screen, enter 6 made-up codes | Tries left count down from 4; the 5th locks the address for 15 minutes; the 6th, even a real code, is refused and restarts the wait (the countdown goes back up) |
| Forged address | From a laptop on the hotspot, the first command below | Still locked, or counted against the laptop's own address; the audit shows the real address, never 203.0.113.9 |
| Guessing passwords | Sign in as a test member with 10 wrong passwords, waiting out each address lock or using a second network | The 11th says sign-in for that name is paused; the test member's signed-in device still plays |
| Unknown names | Sign in as a name that does not exist | The same message and about the same time as a wrong password |
| Flood | From the laptop, the second command below | `413` from Caddy |
| Global pause | Hub test only (it needs many addresses) | `joining_paused` for everyone; signed-in devices unaffected |
| Shared login | Sign a test member in on more than 10 devices (hub test) | `too_many_devices` |
| Shared login | The owner looks at the test member's page | Every device listed, with public or Tailscale |
| Malicious member | Hub route-matrix test; then, by hand with a member session, `/v1/activity`, `/v1/health`, `/v1/media/<key>/releases` | 403 each |
| Malicious member | Send `X-Jellyfin-User` set to the owner's id | The member's own Continue watching, not the owner's |
| Malicious member | A member session on a Jellyfin user without access to one library: prepare playback of an item in it | Refused (Jellyfin's per-user access holds through the hub) |
| Guest | A guest session: download, request and Manage | 403 each |
| Leaked invite | Cancel an invite, then use it | "That code doesn't work" |
| Leaked invite | Use a member code twice | The second fails |
| Leaked invite | A public join with approval on | The new member waits; nothing loads until the owner approves |
| Leaked reset code | Use a reset code twice; use one older than 24 hours; issue two and use the first | All three fail |
| Leaked reset code | Try the old password after issuing a code | It fails |
| Session theft | Sign a device out from People while it plays | Playback stops within seconds; the device shows it was signed out |
| Session theft | A PIN-locked session: 5 wrong PINs | The password is required |
| Lock | Lock a test member while they stream to a TV with Cast | The phone and the TV stop; sign-in is refused |
| The PC | Search the people folder, the logs and the audit for the test password, PIN, codes and a `jhs1_` token: `Select-String -Path C:\ProgramData\AyaneoHub\people\*, C:\ProgramData\AyaneoHub\logs\* -Pattern "<test value>"` | No match |
| Sharee | A sharee's phone: hub, 8080 and 8989 | Hub works; the other two do not load |

The two laptop commands (PowerShell, not administrator). Both are expected to fail with an error;
the error text is the answer to read:

```powershell
# A forged address: the hub must ignore it.
Invoke-RestMethod -Method Post -Uri https://myjellydan.duckdns.org:55886/v1/people/invites/check `
  -Headers @{ 'X-Forwarded-For' = '203.0.113.9' } -ContentType application/json `
  -Body '{"code":"AAAA-AAAA-AAAA"}'

# A 1 MB body to the door: Caddy must refuse it (413).
Invoke-WebRequest -Method Post -Uri https://myjellydan.duckdns.org:55886/v1/people/join `
  -ContentType application/json -Body ('{"name":"' + ('a' * 1MB) + '"}')
```

### 16.3 Rehearse recovery once

1. Close the public link from People. Over the public link, hello then says
   `"joining": "closed"` and every other route answers `503`; Tailscale still works.
2. Reopen it.
3. Stop and start Caddy (17.6). Note how long the public link was down.

## 17. Recovery

### 17.1 The owner locked out

- **"This address waits 15 minutes"**: wait 15 minutes without trying. Bans live in memory, so
  restarting the hub also clears them: `Restart-Service AyaneoHub` (administrator).
- **Forgot the owner password**: on the Pocket DS, which holds a hub.yaml token, go to People ›
  Dgdan › Reset password, then use the code on the sign-in screen.
- **Every owner device lost**: on the PC, as administrator:
  1. Run `hubctl token new --label rescue`.
  2. Put the printed sha256 into `hub.yaml` under `auth.tokens`.
  3. Run `hub.exe --check` and `Restart-Service AyaneoHub`.
  4. Use that token to issue the owner a reset code.

### 17.2 A leaked invite code

- **Not yet used:** People › Invites waiting › Cancel.
- **Already used by someone unexpected:** remove that person, or refuse their approval.
- **A guest code:** cancelling it also signs out its guests.

### 17.3 A leaked reset code

Issue a new reset code (it cancels the old one), or lock the person until it is sorted out.

### 17.4 A member abusing it

1. People › the person › **Lock**. They are out everywhere at once and cannot sign in.
2. Read their audit.
3. If they had a Tailscale share, revoke it (15.4).
4. Then **Remove from the server**, or Reset password and Unlock if it was a misunderstanding.

Files they downloaded stay on their device.

### 17.5 A stolen device

- People › the person › the device › **Sign out**. For a shared tablet, sign the device out for
  everyone.
- If it held an owner session, also reset the owner's password.
- If it held a hub.yaml token (the Pocket DS), delete that token's line in `hub.yaml` and restart
  the hub.

### 17.6 Shut public access off in one step

From gentlest to strongest:

1. **In the app:** People › Public link › **Close**. Everything through Caddy is refused; Tailscale
   is unaffected.
2. **On the PC (administrator):** `Stop-Service <caddy service name>`. Nothing answers on the
   public address. To keep it off across restarts, also run
   `Set-Service <name> -StartupType Disabled`. Undo with `Set-Service <name> -StartupType Automatic`
   and `Start-Service <name>`.
3. **At the router:** disable the 55886 rule.

### 17.7 Suspected compromise of the PC

1. Close the public link at the router (17.6, step 3).
2. Rotate every service key: regenerate each in its own service, then run `collect-secrets.ps1`.
3. Make new hub.yaml tokens and delete the old ones.
4. Add a new pepper and remove the old one, which means every member needs a reset code.
5. With the hub stopped, run `hubctl people revoke-all`, which ends every session.
6. Change the Windows administrator password.
7. Only then reopen.

## 18. Decisions for the owner

Each has a recommendation; the build follows it unless the owner says otherwise in #65.

1. **The owner signs in and manages people only over Tailscale or at the PC.** Recommended: yes.
   An internet attacker then gains nothing even from the owner's correct password. Watching still
   works anywhere.
2. **A member who joins over the public link waits for the owner's approval.** Recommended: yes.
   It turns a leaked member code into a request the owner can refuse. Joins over Tailscale (shared
   friends) skip it.
3. **Refuse the 20 most common PINs**, which include 1234, the prototype's example. Recommended:
   yes. In published studies of chosen 4-digit PINs, those 20 cover about a quarter, and the top
   five alone about a fifth. With them refused, a thief's 5 tries succeed about 1% of the time
   instead of about 20%.
4. **Member requests wait for approval in Jellyseerr.** Recommended: yes, by attributing them to a
   Jellyseerr user without auto-approve (`member_jellyseerr_user_id`). Today every request is made
   as the owner's Jellyseerr user and approves itself.
5. **Interactive search and grabbing a specific release stay owner-only** (the new `grab` scope).
   Recommended: yes. A grab puts an arbitrary torrent on the owner's PC.
6. **Streams at once**: 2 per member and 1 per guest device. Recommended: yes, to keep the home
   upload from being filled by one person. A bitrate cap for the public link could be added later
   if needed.
7. **Members' books before phase 3**: read online without a saved place (recommended), or no books
   for members until phase 3.
8. **A new member's Jellyfin user**: phase 1 asks the owner to choose an existing Jellyfin user in
   the invite. Phase 2 could let the hub create one named after the person (it has the admin key).
9. **Guest codes end, with their guests, 7 days after they were made.** Recommended: yes, so the
   owner has one date to remember.
10. **One-time administrator steps** the build depends on:
    - running `secure-people-folder.ps1`;
    - adding the pepper;
    - moving Caddy to port 8792;
    - the router clean-up;
    - the Tailscale policy;
    - qBittorrent's password, and the hub's copy of it.
11. **The Caddy build with rate limiting** (14.2): optional, recommended if the download page
    offers it.

## 19. Found while planning: today's hub

Things in the current hub that matter before anyone else is let in, independent of this feature:

1. **A ban does not lengthen when someone keeps trying.** CLAUDE.md says hammering through a ban
   extends it. In fact `withAuth` returns 429 for a banned source before it ever calls `Fail`, so
   `Fail`'s extend branch is never reached from a request. Phase 1 adds `BanList.Retry` and uses it
   in both places.
2. **IPv6 addresses are banned one by one.** A single home or attacker has a /64. Ban by /64.
3. **The left-most `X-Forwarded-For` entry is used.** That is safe today only because both proxies
   replace the header rather than appending to it. Use the right-most untrusted entry.
4. **Several routes have no scope check** (the list in 4.2). Interactive search
   (`/v1/media/{key}/releases`) in particular can be started by any token and keeps every indexer
   busy for up to 110 seconds. That is fine while every token is the owner's, and must change
   before it is not.
5. **Tailscale Serve makes tailnet visitors localhost** (3.10). CLAUDE.md's own trap note about
   qBittorrent's localhost bypass behind a reverse proxy now applies, since port 8080 is served
   through Tailscale Serve. Check whether qBittorrent's Web UI opens without a password over
   Tailscale, and fix it before the tailnet holds anyone but the owner.
6. **If `trust_proxy_cidrs` is not set on the live hub,** every request through Caddy and
   Tailscale looks like `127.0.0.1`. Five wrong tokens from anyone would then ban everyone. The
   example config sets it; the live one should be checked (checklist line 3).

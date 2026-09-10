# Remote access plan

## Goal

Keep media playback convenient away from home while preventing the management
applications from being exposed directly to the public internet.

## Target layout

### Private Tailscale access

`ayaneo-media-pc.tail737e96.ts.net` is reachable only by devices signed into
the owner's Tailscale network.

| Private URL | Local service | Purpose |
|---|---|---|
| `https://ayaneo-media-pc.tail737e96.ts.net` | `127.0.0.1:8791` | Ayaneo Hub API |
| `https://ayaneo-media-pc.tail737e96.ts.net:8920` | `https://127.0.0.1:8920` | Jellyfin |
| `https://ayaneo-media-pc.tail737e96.ts.net:5055` | `127.0.0.1:5055` | Jellyseerr |
| `https://ayaneo-media-pc.tail737e96.ts.net:7878` | `127.0.0.1:7878` | Radarr |
| `https://ayaneo-media-pc.tail737e96.ts.net:8989` | `127.0.0.1:8989` | Sonarr |
| `https://ayaneo-media-pc.tail737e96.ts.net:6767` | `127.0.0.1:6767` | Bazarr |
| `https://ayaneo-media-pc.tail737e96.ts.net:8080` | `127.0.0.1:8080` | qBittorrent |

Tailscale Serve terminates HTTPS and forwards each address to its loopback-only
upstream. Tailscale Funnel stays disabled. Application login remains enabled as
a second layer for every management service.

### Existing public fallback

Keep `https://myjellydan.duckdns.org:55886` and its router rule unchanged until
the private route passes a phone-hotspot test. This prevents the migration from
breaking the already working Hub connection.

After private access is verified, investigate standard public port 443. If the
router and ISP permit it, Caddy can use the existing hostname as follows:

| Public path | Local service |
|---|---|
| `/v1/*` | Ayaneo Hub on `127.0.0.1:8791` |
| `/komga/*` | Komga on `127.0.0.1:25600` |
| `/marvel/*` | Marvel service on `127.0.0.1:4001` |
| all remaining paths | Jellyfin on `127.0.0.1:8096` |

Only Jellyfin and the authenticated Hub API belong on the public listener.
Jellyseerr, Radarr, Sonarr, Bazarr, and qBittorrent remain private.

## Implementation sequence

1. Install Tailscale on the media PC and join it as `ayaneo-media-pc`.
2. Confirm Pocket DS is online in the same tailnet.
3. Enable Tailscale HTTPS certificates without enabling Funnel.
4. Configure persistent Tailscale Serve routes for the Hub and dashboards.
5. Update the installed Hub `public_url` and service `web_url` values to the
   private HTTPS addresses, validate the YAML, and restart AyaneoHub.
6. Point the Pocket DS app at the private Hub URL while preserving its bearer
   token.
7. Verify health, Home, artwork, Library playback preparation, and every Manage
   link on the home Wi-Fi.
8. Repeat the checks with Pocket DS connected to a phone hotspot.
9. Only after both tests pass, remove unnecessary public dashboard forwards and
   diagnose whether external TCP 443 can replace the temporary 55886 route.

## Deployment status — 2026-09-09

- Steps 1–5 are complete. The PC is `ayaneo-media-pc`, Pocket DS is online in
  the same tailnet, Funnel is not used, all seven Serve listeners report
  `tailnet only`, the Hub configuration validates, and AyaneoHub and Bazarr are
  running after the update.
- The private Hub live endpoint returns `200 ok`. Jellyfin, Jellyseerr, Radarr,
  Sonarr, Bazarr, and qBittorrent all answer through their private HTTPS URLs.
  Jellyfin uses a Tailscale listener on port 8920 with an insecure local
  upstream because Jellyfin's own certificate is self-signed; clients still
  receive Tailscale's trusted certificate.
- Hub configuration backup:
  `C:\ProgramData\AyaneoHub\hub.yaml.before-tailscale-20260909-165939`.
- Bazarr configuration backup:
  `C:\ProgramData\Bazarr\config\config.yaml.before-tailscale-20260909-165939`.
- Step 6 and the local part of step 7 are complete through ADB. The app uses
  `https://ayaneo-media-pc.tail737e96.ts.net`; users, Home, Library, activity,
  health, and artwork return 200. The Jellyfin Manage card opens the trusted
  private 8920 URL and reaches its sign-in page.
- Step 8 still requires repeating the checks on a phone hotspot.
- Step 9 remains deferred until that remote device test passes.

## Failure and rollback

- If Tailscale is offline, restore the app Hub address to
  `https://myjellydan.duckdns.org:55886`.
- `tailscale serve reset` removes private proxy routes without changing any
  application data.
- Restore the timestamped `hub.yaml` backup and restart AyaneoHub if a service
  link is wrong.
- Do not delete the working router rule until remote verification is complete.

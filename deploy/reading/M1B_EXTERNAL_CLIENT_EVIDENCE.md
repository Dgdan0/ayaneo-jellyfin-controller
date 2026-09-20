# M1B external-client qualification evidence

Date opened: 2026-09-20
Branch: `feature/reading-library`
Status: In progress

## Safety boundary

The isolated M1 lab remains the only mounted catalog. Komga and production
reading media have not been changed. HTTPS routes are visible only to devices
authenticated to the owner's Tailscale tailnet; Tailscale Funnel is not
enabled.

The pre-existing Hub route remains unchanged:

- `https://<ayaneo-tailnet-host>/` -> `127.0.0.1:8791`

The M1B routes use separate ports:

- Kavita: `https://<ayaneo-tailnet-host>:5000/` ->
  `127.0.0.1:5000`
- Storyteller: `https://<ayaneo-tailnet-host>:8001/` ->
  `127.0.0.1:8001`

On the Ayaneo Media PC, Kavita returned HTTP 200 through its Tailscale HTTPS
route. Storyteller returned HTTP 307 to its expected login route. `tailscale
serve status` showed both routes alongside the unchanged Hub and existing
service routes.

## Acceptance matrix

Record the client version, test account, content fixture, result, and useful
evidence for every row. Never record a password or token here.

| Client and behavior | Local HTTPS | Tailscale HTTPS | Result/evidence |
| --- | --- | --- | --- |
| Panels browses the Kavita catalog | Pending | Pending | |
| Panels streams comic/manga pages | Pending | Pending | |
| Panels downloads and reopens offline | Pending | Pending | |
| Panels writes page progress back | Pending | Pending | |
| Android visual reader browses the Kavita catalog | Pending | Pending | |
| Android visual reader streams comic/manga pages | Pending | Pending | |
| Android visual reader downloads and reopens offline | Pending | Pending | |
| Android visual reader writes page progress back | Pending | Pending | |
| Storyteller iOS browses ebook/audiobook/readaloud | Pending | Pending | |
| Storyteller iOS downloads and returns offline | Pending | Pending | |
| Storyteller iOS saves reading and audio position | Pending | Pending | |
| Storyteller iOS readaloud highlights and advances | Pending | Pending | |
| Storyteller Android browses ebook/audiobook/readaloud | Pending | Pending | |
| Storyteller Android downloads and returns offline | Pending | Pending | |
| Storyteller Android saves reading and audio position | Pending | Pending | |
| Storyteller Android readaloud highlights and advances | Pending | Pending | |
| A second user has isolated progress and downloads | Pending | Pending | |
| Backup/restore retains users, catalog, pairing, and progress | Pending | Pending | |

## Decision gate

Kavita remains a candidate until this matrix passes. Komga stays active and is
not removed during M1B. The final catalog decision must record client-specific
limitations and rollback steps rather than relying on API-only tests.

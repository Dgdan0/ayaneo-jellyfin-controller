# M1B external-client qualification evidence

Date opened: 2026-09-20
Branch: `feature/reading-library`
Status: In progress

## Safety boundary

The isolated M1 lab remains the only mounted catalog. Komga and production
reading media have not been changed. Tailscale Funnel is not enabled.

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

Kavita is also available through the existing Caddy and Cudy public entry
point. Cudy maps WAN TCP 55886 to the Ayaneo Media PC's HTTPS listener on TCP
443. Caddy exposes only Kavita's `/kavita/*` web path and root `/api/*` native
client path. Storyteller and bookkeeprr remain loopback/tailnet only. Direct
WAN TCP 443 was tested from the remote Pocket DS and was not reachable; it is
not part of the supported topology.

## Acceptance matrix

Record the client version, test account, content fixture, result, and useful
evidence for every row. Never record a password or token here.

| Client and behavior | Local HTTPS | Tailscale HTTPS | Result/evidence |
| --- | --- | --- | --- |
| Panels browses the Kavita catalog | Pending | Pending | |
| Panels streams comic/manga pages | Pending | Pending | |
| Panels downloads and reopens offline | Pending | Pending | |
| Panels writes page progress back | Pending | Pending | |
| Android visual reader browses the Kavita catalog | Pending | Pass | CDisplayEx 1.3.96 on Pocket DS authenticated through Tailscale and rendered Reading, Collections, Series, Want To Read, Lab Books, Lab Comics, and Lab Manga |
| Android visual reader streams comic/manga pages | Pending | Pass | CDisplayEx opened both pages of `Lab Comics #1`; cover and second-page fixtures rendered correctly in landscape |
| Android visual reader downloads and reopens offline | Pending | Pass | Downloaded `Lab Comics #1`, stopped the Kavita container, and reopened the first page successfully before restarting Kavita |
| Android visual reader writes page progress back | Pending | Pass | Opening the second page updated the primary user's Kavita progress to `PagesRead=2` at `2026-09-20 11:03:19Z` without changing the isolated secondary user |
| Storyteller iOS browses ebook/audiobook/readaloud | Pending | Pending | |
| Storyteller iOS downloads and returns offline | Pending | Pending | |
| Storyteller iOS saves reading and audio position | Pending | Pending | |
| Storyteller iOS readaloud highlights and advances | Pending | Pending | |
| Storyteller Android browses ebook/audiobook/readaloud | Pending | Pending | |
| Storyteller Android downloads and returns offline | Pending | Pending | |
| Storyteller Android saves reading and audio position | Pending | Pending | |
| Storyteller Android readaloud highlights and advances | Pending | Pending | |
| A second user has isolated server progress | Pass | N/A | Kavita retained primary page 1 while secondary saved page 2; Storyteller retained primary 50% while secondary saved 25% |
| A second user's client downloads remain isolated | Pending | Pending | Requires mobile-client storage checks |
| Backup/restore retains users, catalog, pairing, and progress | Pass | N/A | Stopped-state copy restored into a separate Compose project; all services healthy, authenticated API/OPDS passed, catalog counts matched, Kavita page 1 and Storyteller 50% progress matched |

## Public HTTPS acceptance

| Client and behavior | Result | Evidence |
| --- | --- | --- |
| Pocket DS reaches the Kavita API from a remote network | Pass | Device public IP differed from the Ayaneo WAN IP; `https://myjellydan.duckdns.org:55886/api/Settings/base-url` returned the expected authenticated-API challenge through Caddy |
| CDisplayEx authenticates through the public route | Pass | Existing `Kavita Lab` connection was changed to host `myjellydan.duckdns.org`, port 55886, SSL enabled; authorization succeeded without storing the password in 1Password |
| CDisplayEx browses and reads through the public route | Pass | The remote Pocket DS listed Lab Books, Lab Comics, and Lab Manga, opened `Lab Comics #1`, and rendered its first page |
| Public web path preserves the external port | Pass | `/kavita` returns HTTP 308 with relative `Location: /kavita/`; `/kavita/` returns HTTP 200 on public port 55886 |

## Multi-user isolation evidence

Generated secondary accounts were created in the isolated lab only. Their
credentials are stored under ignored `lab-secrets` files and no values were
written to this report.

For Kavita, both users addressed the same comic chapter. The primary account
remained at page 1 before and after the secondary account saved page 2. For
Storyteller, both users addressed the same logical book. The secondary account
initially received HTTP 404 for a position, saved 25% with HTTP 204, and read
25% back while the primary account remained at 50%.

This proves server-side progress ownership. Offline download separation remains
a client-side acceptance check because Kavita and Storyteller do not store the
mobile apps' downloaded files in the server account database.

## Pocket DS CDisplayEx evidence

CDisplayEx 1.3.96 (`versionCode=6296`) was already installed on the Pocket DS.
A separate `Kavita Lab` location was first qualified over the tailnet HTTPS
endpoint, then changed to the public Caddy endpoint on port 55886. The existing
Komga location and its 843-book catalog were left unchanged. Kavita's generated
lab password was not saved to the device's password manager.

The client authenticated against Kavita after its base URL was set to
`/kavita/`, browsed all three fixture libraries, streamed the two-page comic,
and wrote its second-page position back to the primary Kavita user. The comic
was then downloaded. With only the Kavita container stopped, CDisplayEx opened
the downloaded first page, proving that the client did not fetch it from the
server. Kavita was restarted immediately afterward and returned to its normal
loopback/Tailscale service. A final remote-network pass authenticated, browsed,
and rendered the comic through public Caddy without changing the content or
progress model.

## Backup and restore evidence

The three lab containers were stopped cleanly while the Hub, Jellyfin, Komga,
and production media remained online and untouched. The lab data, derived data,
and secrets were copied to an ignored backup directory and then copied again to
a separate restore root. All 44 files matched by relative path, size, and
SHA-256 before startup.

The restored project ran concurrently on alternate loopback ports. All three
containers became healthy. An authenticated Kavita library request succeeded,
Storyteller OPDS v1 returned HTTP 200 with catalog entries, and bookkeeprr's
health endpoint reported healthy. Database checks matched these semantic
records before the temporary restore containers were removed:

| Service | Restored records |
| --- | --- |
| Kavita | 1 user, 3 libraries, 6 series, 6 volumes, 6 chapters, 1 progress row at page 1 |
| Storyteller | 1 user, 2 logical books, 1 ebook, 1 audiobook, 1 readaloud, 1 position at 50% |
| bookkeeprr | Empty first-run catalog, matching the source lab |

The original lab was restarted immediately after the consistent copy and all
three original containers returned healthy. Backup data remains ignored under
`deploy/reading/lab-backups/`; it contains credentials and must not be
committed or shared.

## Decision gate

Kavita remains a candidate until this matrix passes. Komga stays active and is
not removed during M1B. The final catalog decision must record client-specific
limitations and rollback steps rather than relying on API-only tests.

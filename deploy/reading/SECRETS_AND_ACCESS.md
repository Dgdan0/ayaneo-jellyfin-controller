# Reading platform secrets and access

This document identifies secret locations without recording secret values. The
files below must stay outside Git, backups must be encrypted, and credentials
must never be embedded in the Android application.

## Lab credentials

The M1 lab stores bootstrap material under
`deploy/reading/lab-secrets/`. The whole directory is ignored by Git.

| File | Purpose | How to rotate it |
| --- | --- | --- |
| `admin-credentials.json` | Local record of the generated lab username, display name, email, and initial password used while provisioning Kavita and Storyteller. | Change the password in each service first. Update or remove this record only afterward. Editing this JSON does not change either running account. |
| `kavita-register-response.json` | Captured lab registration response. It can contain API, access, and refresh tokens. | Revoke or replace tokens in Kavita, then remove the captured response when qualification scripts no longer need it. |
| `storyteller_secret.txt` | Storyteller's server signing secret, mounted through a Compose secret. It is not the user's login password. | Keep it stable. Rotating it signs users out and invalidates existing sessions. Generate a new random value only during a planned credential rotation. |
| `storyteller-init-response.txt` | Captured response from initial Storyteller setup. Treat it as secret bootstrap evidence. | Remove it after the lab no longer needs bootstrap evidence. Account changes happen in Storyteller, not in this file. |
| `m1b-secondary-credentials.json` | Generated Kavita secondary account used only for progress-isolation checks. | Reset or delete it in Kavita after M1B. Updating this file alone does not change the account. |
| `m1b-storyteller-secondary-credentials.json` | Generated Storyteller secondary account used only for progress-isolation checks. | Reset or delete it in Storyteller after M1B. Updating this file alone does not change the account. |
| `bookkeeprr-admin-credentials.json` | Local record of the isolated BookKeeprr administrator created for M3 qualification. | Change the password in BookKeeprr first, then update or remove this record. |
| `bookkeeprr-qbittorrent.json` | Credentials for the dedicated lab qBittorrent Web UI. | Change the qBittorrent password and BookKeeprr connection setting together, then update or remove this record. |
| `bookkeeprr-api-key.json` | Revocable personal BookKeeprr API key used for qualification. | Revoke it under the BookKeeprr account settings, then remove the file. The pinned release cannot use this key for its admin download controls; see the M3 evidence. |
| `bookkeeprr-qualification-state.json` | Non-secret IDs and fixture hashes used to correlate the qualification run. It remains in the ignored secrets directory so one run cannot leak state into another. | Remove it when resetting the acquisition lab. |

The service databases are the authority after first-run setup:

- Kavita users and tokens live in
  `deploy/reading/lab-data/kavita/kavita.db`.
- Storyteller users and sessions live under
  `deploy/reading/lab-data/storyteller/storyteller.db`.
- bookkeeprr users and settings live in
  `deploy/reading/lab-data/bookkeeprr/bookkeeprr.db`.
- qBittorrent's Web UI authentication and client settings live under
  `deploy/reading/lab-data/qbittorrent/`.

Kavita, Storyteller, and bookkeeprr currently have isolated lab accounts. Use
their account/settings screens to change passwords. BookKeeprr first-run setup
was completed for M3; additional accounts and password resets are managed under
**Settings > Users**. Its qBittorrent and future indexer credentials are
separate integration secrets stored in the BookKeeprr database. The dedicated
qBittorrent backend is isolated from the production movie/TV download client.

Docker Desktop does not add an application username or password to these
services. Windows/Docker permissions control container administration, while
each web application controls its own users.

## Production Hub credentials

The running Windows service uses:

- `C:\ProgramData\AyaneoHub\hub.yaml` for shareable service configuration;
- `C:\ProgramData\AyaneoHub\hub.secrets.yaml` for API keys, qBittorrent
  credentials, and Hub token material;
- `C:\ProgramData\AyaneoHub\offline-grants.json` for durable offline download
  grants and watch-progress receipts. This is application state, not a password
  file.

`hub.secrets.yaml` is ignored by the repository. The Hub merges it over
`hub.yaml` at startup. After changing a service API key or password, update the
upstream service and `hub.secrets.yaml`, validate the merged configuration with
the Hub's check command, and restart the **Ayaneo Jellyfin Hub** FireDaemon
service. A Hub bearer token also has a digest in server configuration and a raw
copy stored by the Android app; rotating it requires updating both ends.

The Android application must receive only its Hub URL and scoped Hub bearer
token. Kavita, Storyteller, bookkeeprr, qBittorrent, indexer, and Jellyfin
administrator secrets stay on the Ayaneo Media PC.

The Hub's read-only BookKeeprr discovery adapter uses a revocable personal API
key from `hub.secrets.yaml` under `services.bookkeeprr.api_key`. The matching
service address and enable flag belong in `hub.yaml`; tokens that call the
reading endpoints need the `reading` scope. This key does not grant the Hub
BookKeeprr's administrator-only acquisition controls.

## Network boundary

The qualification containers bind only to loopback. Current Tailscale Serve
routes are tailnet-only. HTTPS port 443 is reserved for the Hub and proxies to
`127.0.0.1:8791`; adding reader-client endpoints must use separate tested Serve
ports or a deliberately designed reverse-proxy route without replacing the Hub
mapping.

Do not expose bookkeeprr directly to the public internet. Remote administration
and future Hub integration should use Tailscale, service authentication, and a
least-privilege Hub adapter.

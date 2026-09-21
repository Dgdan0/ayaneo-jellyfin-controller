# Reading platform lab

This Compose project is an isolated qualification environment for Kavita,
Storyteller, bookkeeprr, and a dedicated qBittorrent client. It must not point
at production media during qualification.

The separately tested production mount model is in
[`compose.production.yaml`](compose.production.yaml), with concrete Ayaneo
defaults in [`.env.production.example`](.env.production.example) and its
promotion gate in [`PRODUCTION_STORAGE.md`](PRODUCTION_STORAGE.md). It keeps
the existing Bookshelf read-only and gives BookKeeprr a new managed root.

See [SECRETS_AND_ACCESS.md](SECRETS_AND_ACCESS.md) before changing accounts,
tokens, or remote-access routes. Bootstrap files are not the authority for a
running service account.

Automated qualification is recorded in
[M1A_SERVICE_LAB_EVIDENCE.md](M1A_SERVICE_LAB_EVIDENCE.md). Real-client checks
are tracked in
[M1B_EXTERNAL_CLIENT_EVIDENCE.md](M1B_EXTERNAL_CLIENT_EVIDENCE.md). The M2
scanner implementation and its remaining production gate are recorded in
[M2_MIGRATION_EVIDENCE.md](M2_MIGRATION_EVIDENCE.md). The acquisition proof,
Hub request/status integration, and remaining production gate are recorded in
[M3_ACQUISITION_EVIDENCE.md](M3_ACQUISITION_EVIDENCE.md).

## Safety model

- Every published port binds to loopback.
- Kavita and Storyteller see fixture media read-only.
- bookkeeprr and its dedicated qBittorrent backend share only the writable
  `READING_ACQUISITION_ROOT`; neither can see the reader fixture media.
- The internal `qbittorrent` compatibility proxy rewrites BookKeeprr 1.1.1's
  qBittorrent 4 pause/resume route names to qBittorrent 5 stop/start. It has no
  published host port. The backend Web UI remains loopback-only.
- Secrets are file-backed and ignored by Git.
- Images are pinned to the immutable digests qualified by the M1A lab.
- All persistent data stays below this directory unless `.env` explicitly
  chooses another lab path.

## Prepare the lab

1. Copy `.env.example` to `.env`.
2. Review the pinned image digests. Change one only as part of a new service
   qualification run.
3. Create `lab-secrets/storyteller_secret.txt` with a cryptographically random
   value of at least 32 bytes.
4. Generate the synthetic fixture corpus from the repository root:

   ```powershell
   Set-Location hub
   go run ./cmd/reading-fixtures -out ../deploy/reading/lab-media
   ```
5. Acquisition qualification can generate a tracker-free, single-file torrent
   from any synthetic fixture:

   ```powershell
   Set-Location hub
   go run ./cmd/reading-acquisition-fixture `
     -payload ../deploy/reading/lab-acquisition-source/book.epub `
     -out ../deploy/reading/lab-acquisition-source/book.torrent `
     -url http://host.docker.internal:18081/book.epub
   ```

   The URL is an HTTP web seed. Use a local disposable server only; do not put
   credentials in it.
6. Validate the expanded Compose model before starting anything:

   ```powershell
   docker-compose --env-file .env config
   ```

7. Start the lab only after the configuration contains no production paths:

   ```powershell
   docker-compose --env-file .env up -d
   ```

BookKeeprr must target Docker host `qbittorrent` and port `18080`; that name is
the internal compatibility proxy. The qBittorrent Web UI remains available to
the host at `127.0.0.1:${QBITTORRENT_WEBUI_PORT}`. Credentials belong in each
service's ignored persistent configuration and never in Compose.

Kavita libraries must enable embedded metadata parsing while leaving external
metadata matching disabled. EPUB files are otherwise skipped because Kavita
uses that switch for local OPF/ComicInfo parsing too.

Storyteller 2.14.21 scans matched ebook and audiobook files as separate books.
After both are present, call its supported `/api/v2/books/merge` endpoint. The
two-path create endpoint is not used because this pinned release attempts to
insert the same UUID twice. The M1A evidence report records the observed error.

Stopping with `docker-compose down` removes containers and the isolated network,
but retains lab data. Deleting lab data is a separate, deliberate operation.

## M1 acceptance checklist

- All three health checks become healthy after reboot.
- No service is reachable through a non-loopback host port.
- Kavita cannot modify fixture media.
- Storyteller cannot modify source EPUB/audio and writes generated assets only
  to its data/derived volumes.
- bookkeeprr existing-library import is read-only and one fixture import lands
  in the expected content root.
- Panels can browse/stream/download from Kavita and writes progress back.
- The selected Android client passes the same test.
- Storyteller iOS and Android apps browse, download, resume, and play a supplied
  Media Overlay fixture.
- A matching EPUB/audio pair aligns; a mismatched pair fails clearly without
  corrupting either source.
- Container restart preserves users, metadata, and progress.
- Backup and restore reproduce the same counts and progress.

## Optional public Kavita route

Kavita can share the existing public HTTPS listener. On the current Cudy
configuration the externally reachable URL is
`https://myjellydan.duckdns.org:55886/kavita/`; Cudy forwards WAN TCP 55886 to
the Ayaneo Media PC's Caddy listener on TCP 443. Set Kavita's **Base URL** to
`/kavita/` first, then add the handlers from
`Caddyfile.public.example` inside the existing Caddy site block. The route
preserves the prefix deliberately; `handle_path` would strip it and break
generated links and OPDS URLs. Its relative `/kavita` redirect also preserves
the external port.

The fragment also routes root `/api/*` requests to Kavita. CDisplayEx 1.3.96
was observed using that API shape on the Pocket DS even after it learned the
`/kavita/` web base URL. Without this handler, the browser works while native
Kavita clients fail authentication or catalog loading.

Only Kavita is included in this public fragment. Storyteller and bookkeeprr
remain tailnet or loopback services until their authentication and client
flows are qualified. Public Kavita accounts must use unique passwords because
the login and authenticated API are reachable from the Internet.

## Read-only migration inventory

M2 begins with a report and has no apply mode. Copy
`migration.example.yml` to the ignored `migration.local.yml`, replace the four
example roots with the actual media roots, and set the environment variables
named by each `komga_exports` entry. Use one entry per Komga account whose
progress and read lists must be retained.

Prefer `api_key_env`: create a revocable key from the user's Komga **Account
Settings** page and expose it only to the migration command. The exporter sends
it as `X-API-Key`. `username_env` plus `password_env` remains supported as an
alternative; the two authentication modes cannot be combined in one entry.

From `hub/`, generate the ignored evidence bundle:

```powershell
go run ./cmd/reading-migrate `
  -config ../deploy/reading/migration.local.yml `
  -out ../deploy/reading/migration-reports/initial
```

The command reads and hashes every regular source file, validates supported
embedded and sidecar metadata, compares existing canonical targets, and writes
`config.json`, `inventory.json`, `plan.json`, `rollback.json`, `summary.txt`,
and optionally `komga-state.json`. It refuses source/destination overlap and
refuses to put its report inside any media root. `ready` remains a proposal;
the command contains no code that copies, moves, renames, or deletes media.

Do not share the report bundle without reviewing it. It intentionally contains
media paths, titles, account identifiers, progress, and read-list membership,
although it never contains the configured Komga password.

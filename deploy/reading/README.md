# Reading platform lab

This Compose project is an isolated qualification environment for Kavita,
Storyteller, and bookkeeprr. It must not point at production media during M0 or
M1.

## Safety model

- Every published port binds to loopback.
- Kavita and Storyteller see fixture media read-only.
- bookkeeprr is the only container with a writable fixture-media mount.
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
5. Validate the expanded Compose model before starting anything:

   ```powershell
   docker-compose --env-file .env config
   ```

6. Start the lab only after the configuration contains no production paths:

   ```powershell
   docker-compose --env-file .env up -d
   ```

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

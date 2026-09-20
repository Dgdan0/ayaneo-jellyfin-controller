# Reading platform lab

This Compose project is an isolated qualification environment for Kavita,
Storyteller, and bookkeeprr. It must not point at production media during M0 or
M1.

## Safety model

- Every published port binds to loopback.
- Kavita and Storyteller see fixture media read-only.
- bookkeeprr is the only container with a writable fixture-media mount.
- Secrets are file-backed and ignored by Git.
- Image placeholders force an explicit tested version or digest.
- All persistent data stays below this directory unless `.env` explicitly
  chooses another lab path.

## Prepare the lab

1. Copy `.env.example` to `.env`.
2. Replace the Kavita and Storyteller image placeholders with exact versions or
   immutable digests selected during M1.
3. Create `lab-secrets/storyteller_secret.txt` with a cryptographically random
   value of at least 32 bytes.
4. Generate or copy only public-domain/personally-created fixtures into
   `lab-media/Books`, `lab-media/Audiobooks`, `lab-media/Comics`, and
   `lab-media/Manga`.
5. Validate the expanded Compose model before starting anything:

   ```powershell
   docker compose --env-file .env config
   ```

6. Start the lab only after the configuration contains no production paths:

   ```powershell
   docker compose --env-file .env up -d
   ```

Stopping with `docker compose down` removes containers and the isolated network,
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

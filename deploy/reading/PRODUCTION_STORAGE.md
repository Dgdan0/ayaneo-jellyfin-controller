# Production reading storage

The Ayaneo Media PC keeps existing reading media and new managed acquisitions
separate. This avoids copying roughly 215 GB of existing Bookshelf data and
prevents BookKeeprr or qBittorrent from seeing it.

## Host roots

| Purpose | Host path | Container access |
| --- | --- | --- |
| Existing Komga-era catalog | `D:\Bookshelf` | Kavita and Storyteller read-only at `/legacy`; invisible to BookKeeprr and qBittorrent |
| New managed library | `D:\Media\Reading` | BookKeeprr read/write at `/media`; Kavita read-only at `/reading`; Storyteller read-only at `/library`; invisible to qBittorrent |
| Acquisition workspace | `E:\Downloads\Reading` | BookKeeprr and qBittorrent read/write at `/downloads`; invisible to readers |
| Reader generated data | `D:\Media\ReadingDerived\Storyteller` | Storyteller read/write at `/derived` |
| Container state | `D:\Apps\PocketDS\Reading` | One private configuration directory per service |
| File-backed secrets | `C:\ProgramData\AyaneoHub\reading-secrets` | Only the named secret is mounted into Storyteller |

`compose.production.yaml` enforces those boundaries. Published administration
ports stay on `127.0.0.1`; remote clients continue through the separately
qualified Caddy or Tailscale routes.

## Managed layout

BookKeeprr owns only these folders below `/media`:

```text
books/
audiobooks/
comics/
manga/
light-novels/
```

qBittorrent uses `/downloads/incomplete` while receiving data and
`/downloads/complete` after completion. BookKeeprr validates and imports from
that workspace into `/media`; qBittorrent never receives a mount for either
library root.

## Promotion sequence

1. Copy `.env.production.example` to the ignored `.env.production` and inspect
   every root.
2. Create the empty managed, download, derived, state, and secret directories.
   Do not copy or move anything from `D:\Bookshelf`.
3. Back up all four lab service-state directories and the Storyteller secret.
4. Stop the lab Compose project so SQLite databases and qBittorrent state are
   consistent, copy the qualified service state into `READING_STATE_ROOT`, and
   immediately restart the lab if promotion is not completed.
5. Validate the expanded production model:

   ```powershell
   docker compose --env-file .env.production -f compose.production.yaml config --quiet
   ```

6. Start production and confirm every container is healthy. The lab and
   production projects use the same loopback ports and therefore cannot run at
   the same time.
7. In Kavita, retain `/reading/*` for new managed libraries and add the
   applicable `/legacy/Comics`, `/legacy/Manga`, and `/legacy/Ebooks` folders.
8. In Storyteller, retain `/library/books` and `/library/audiobooks`, then add
   `/legacy/Ebooks` and `/legacy/Audiobooks`. Storyteller is not used for comic
   and manga rendering.
9. Confirm qBittorrent's default path is `/downloads/`, its incomplete path is
   `/downloads/incomplete/`, and BookKeeprr connects to Docker host
   `qbittorrent:18080`.
10. Confirm BookKeeprr roots remain under `/media`, run a disposable generated
    EPUB acquisition, and verify the source is removed from neither legacy nor
    managed media unexpectedly.
11. Only after that disposable gate passes, run the deferred Red Rising
    end-to-end acceptance test.

The existing Bookshelf remains Komga's authority during the parallel run.
Removing Komga or migrating legacy files is a separate milestone with its own
hash and rollback evidence.

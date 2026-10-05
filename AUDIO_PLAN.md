# Audio plan: tracks by Range, and a place that follows you

Spike for rows A3/H3 and A4/H4 of `READER_IMPROVEMENTS.md` (§4.3, §4.5), 2026-10-05, branch `claude/consolidation`.
Read-only: I read code, listed media folders, read file headers with Jellyfin's ffprobe, read Storyteller's public
source and made GETs on the live hub. No book opened, no secret read, no code changed. **Measured** = from disk or
the hub log. **By code** = read in source, not run. Another session is editing `app/`, so Android line numbers may drift.

## 1. Summary

- Storyteller 2.14.21 keeps an audiobook as a folder. For a multi-file folder its `/files` route rebuilds a ZIP on every request;
  the hub forwards it and the app downloads and extracts it before the first sound (§2).
- The hub can serve the files itself: all four Storyteller roots are already mapped in `hub.yaml`, a read-only twin of
  `ResolveRemovalFile` is enough (§5), and Jellyfin's `ffprobe.exe` is on the PC (§7).
- Storyteller holds one position per account and book (a Readium locator and a client timestamp); its apps convert between audio and
  sentence only by proportion. The SMIL timeline converts exactly, and here the aligned audio matches the audiobook to the
  millisecond (§3, §6). Three data problems on this library need an owner decision whatever we build (§2, §11).

## 2. Current state

- **Hub.** `handleReadingEpubFile` (`reading_epub.go:29-97`, routed at `router.go:294`) takes `format=audiobook` (`:34`), calls Storyteller
  `/api/v2/books/{id}/files` (`adapters/storyteller/client.go:276-298`), forwards `Range`/`If-Range` and forces `application/zip`
  (`:82-85`). The adapter decodes only `audiobook.manifest.readingOrder[].href` (`client.go:84-95`). Kavita has no audio.
- **Storyteller** (`files/route.ts`, tag `web-v2.14.21`): with more than one audio file it writes a stored ZIP of all of them to
  `tmpdir()` on every request, then SHA-256s the whole file (`assets/fs.ts:502`) before the first byte; a resume repeats both.
  One audio file is sent bare; the hub relabels it ZIP (`reading_epub.go:82-85`) and the app opens it as one
  (`AudiobookArchive.kt:20-30`), so a one-file audiobook cannot play (by code).
- **App.** `AudiobookScreen.load()` (`AudiobookScreen.kt:404-434`) downloads `book.zip` into `cacheDir/reading-audio/` (`:408-419`),
  extracts every part beside it (`AudiobookArchive.kt:24-64`) and plays `Uri.fromFile` parts (`ReadingAudio.kt:219-224`). Caps: 4 GB
  a part, 12 GB a book (`AudiobookArchive.kt:11-12`); no cache budget. The place is SharedPreferences `audiobook_positions`, saved
  every 10 s and zeroed on finish (`ReadingAudio.kt:279,329-343`).
- **Read-along.** `EpubReaderScreen.openBook` (`:513-530`) downloads the aligned EPUB with its audio; `prepareNarration` (`:840-847`) parses
  SMIL and copies each audio entry out (`ReadAlongPackage.kt:134-166`), so audio is stored twice. The place is a text locator with a
  private `locations.pocketdsAudio` (`ReadAlongLocation.kt:27-37`).

| Measured | Size |
|---|---|
| Dark Matter audiobook: 8 MP3, 10 h 9 min, CBR 64 kbps | 292,767,888 B; the ZIP is about the same; ZIP plus parts on the device about 2x |
| Dark Matter aligned EPUB (read-along) | 293,309,553 B, of which 292,332,336 B is audio; the rest is 0.96 MB compressed |
| "Well of Ascension" audiobook: 7 M4B, 91.8 h; largest file 419,286,150 B (14 h 28 min) | 2,660,058,388 B (2.48 GiB); about 5.3 GB on the device |

Hub log (query strings are not logged): `/publications/3204198752768630/file` took 24,709 ms on 2026-09-23; that book has only an
audiobook edition, so it was the ZIP. Requests for `3726292328809367` took 8,547, 33,753 and 771,627 ms, and one ended at exactly
120,001 ms, the server `WriteTimeout` (`cmd/hub/main.go:76`). The Mistborn audiobook was never requested.

Findings that change the design:
- **Dark Matter's files sort wrong.** The unsuffixed file is track 01 of 08 by ID3; `(1)` to `(7)` are 02 to 08. Storyteller's manifest
  sorts by `localeCompare` (`utils/audioManifest.ts`) and the app by `numericSortKey` (`AudiobookArchive.kt:28,71`). I ran both on
  these names: both put the unsuffixed file last. Storyteller's alignment numbers files in `readdir` order (`processAudiobook.ts`)
  and put it first. One book, three orders.
- **The Mistborn "book" is four books** in 7 files (Final Empire, Well of Ascension, Hero of Ages, Alloy of Law, two parts each),
  named `Mistborn (1..6).m4b` and `Mistborn.m4b` in no story order. Its `durationMs` matches their sum.
- **Dark Matter is two Storyteller books** with the same audio (`3726292328809367` with ebook, audiobook and readaloud;
  `3204198752768630` audiobook only), merged into one work by `sameStorytellerEditionWork` (`reading_catalog.go:861-891`, by code).
  Each book has its own position slot.

## 3. Storyteller 2.14.21

Source `gitlab.com/storyteller-platform/storyteller`, tag `web-v2.14.21` (GitHub mirrors it); installed version from
`deploy/reading/M1A_SERVICE_LAB_EVIDENCE.md:17`. 2.14.23 is out and a 3.0 beta is tagged: re-check on upgrade. Paths are under
`applications/web/src`. One book (numeric id, the hub's `sourceItemId`, `reading_catalog.go:1183-1188`) holds up to three editions.
- **Audiobook = directory + manifest snapshot** from `createAudiobookManifest` (`utils/audioManifest.ts`). One `.m4b`: one link per
  chapter with virtual names like `00000-00001.mp3`. Otherwise one link per top-level file: `href` = name, `title` "Track <tag>",
  `duration`, `size`. `GET /api/v2/books/{id}` returns it (`database/books.ts:538-542,697`). `GET /books/{id}/listen/{path}` streams
  files with one Range; the hub need not use it.
- **Position** (`.../positions/route.ts`, `database/positions.ts`): one row per (user, book), `{locator, timestamp}`. POST 204; 409 if the
  stored timestamp is newer, or equal with another locator; GET 404 if empty. `totalProgression` under 0.98 moves "To read" to
  "Reading"; 0.98 or more sets "Read".
- **Audio locator** (`mobile/modules/readium/audiobook.ts:49`, web `BookService.ts:900`): `{href:<track href>, type:"audio/mpeg",
  locations:{fragments:["t=123.4"], progression, totalProgression}}`, `totalProgression` = (earlier tracks + t) / total in manifest
  order. **Read-along locator** (`mobile/.../BookService.kt:111,173`): `{href:"text/part0005.html",
  locations:{fragments:["id34-s12"], progression, totalProgression}}`. Storyteller's web reader converts between them by
  `totalProgression` and calls that "likely not very accurate".
- **Aligned EPUB:** one SMIL per chapter, one `<par>` per sentence: `<text src="../text/part0005.html#id34-s0"/><audio
  src="../Audio/00001-00001.mp3" clipBegin="0.000s" clipEnd="16.000s"/>`. Audio is stored; files are `<source file>-<chunk>.<ext>`, and a
  source over `maxTrackLength` (2 h default) is cut into chunks. **Measured** on Dark Matter: 23 SMIL files (1.73 MB), 9,322 pars, 8 audio
  files all `-00001`; pars gapless, never overlapping; each file's last `clipEnd` is 12 ms short of the original track, as Storyteller's own
  durations are. So sentence to (track, offset) is exact given a checked map from aligned files to tracks (§6); the reverse is many-to-one.

## 4. H3: serve the tracks

Scope `reading`, `GET` (HEAD works). The path differs from the draft `.../audio/{sourceItemId}`: this one sits beside `/file` and
`/position` and already matches `limiterFor` (`middleware.go:249-258`).

1. `GET /v1/reading/works/{workId}/publications/{sourceItemId}/audio`: the manifest.
2. `GET .../audio/tracks/{n}?rev=`: bytes, with Range.
3. `GET .../file?format=readaloud&audio=omit`: the aligned EPUB without audio (0.96 MB, not 293 MB), so read-along opens text first.

```json
{ "workId": "rw_1560...", "sourceItemId": "3726292328809367", "revision": "9f2c1a7e4b10",
  "narrator": "Jon Lindstrom", "totalMs": 36538680, "aligned": true,
  "tracks": [ { "index": 0, "id": "t_5d41402abc4b", "title": "Track 01", "durationMs": 4610652,
                "bytes": 36942522, "mime": "audio/mpeg", "etag": "\"2a9f1c\"" } ],
  "chapters": [ { "title": "Part One", "startMs": 0, "track": 0 } ],
  "alignment": { "audio": [ { "href": "Audio/00001-00001.mp3", "track": 0, "startMs": 0 } ] } }
```

Rules the hub decides:
- The edition is passed in, not read from the URL: `resolveStorytellerEbook` treats any `.../position` as "ebook or readaloud"
  (`reading_epub.go:264`), which would 404 every audiobook-only book.
- Tracks follow Storyteller's manifest. Each `href` joins `audiobook.filepath` as `storytellerRemovalPaths` does
  (`media_removal.go:354-363`), resolves through §5 and must be a regular audio file. `id` = `t_` + 12 hex of sha256(href); hrefs and
  paths never leave the hub. A lone M4B is one track with the manifest's chapters. Exception to the order: if the ffprobe `track`
  tags are exactly 1..N, order by tag (fixes Dark Matter); what is written to Storyteller still uses manifest order.
- `durationMs` is the manifest value, so `totalProgression` matches Storyteller's apps. `revision` hashes hrefs, sizes, mtimes and
  durations; a stale `rev` gets `412 audio_changed`, so a rescan cannot play another file under an old index.
- Bytes: `http.ServeContent` on the opened file (200/206/304/416, `If-Range`), the existing single-range rule (`reading_epub.go:19,38-42`),
  `Content-Type` by extension (`audio/mp4` for m4a/m4b), strong `ETag`, `Accept-Ranges`, `private, max-age=3600`, `nosniff`; no path or
  file name in any header or error. Replace the blanket deadline removal (`stream_deadline.go:11-12`) with a 5-minute deadline re-armed
  every 128 KB, so a stalled phone cannot hold a handle.
- Budget: tracks use the transport budget (3600 rpm, burst 240); manifest and position stay on the screen budget, as progress does
  (`middleware_test.go:61-89`). Narrow `middleware.go:257` from `Contains(path, "/audio")` to `/audio/tracks/`.
- Errors: 404 no audiobook; `409 audio_not_streamable` with `reason` (`unmapped_root`, `missing_file`, `unsupported_layout`); 412; 416; 503
  Storyteller down. Older apps: nothing changes, `/file?format=audiobook` stays (additive); a new app uses the ZIP only after a 409.

## 5. The file mapping

`ResolveRemovalFile` (`reading/removal_files.go:20-80`) maps a Storyteller path to a host file through `server.media_removal_roots`
(`config.go:47-49,70-74`). The live `hub.yaml` has every root Storyteller uses: `/library` to `D:/Media/Reading`, `/legacy` to `D:/Bookshelf`,
`/derived` to `D:/Media/ReadingDerived/Storyteller`, `/data/assets` to `D:/Apps/PocketDS/Reading/storyteller/assets`. Audiobooks are
imported by `reference`, so they stay under `/library/audiobooks` and `/legacy/Audiobooks` (`deploy/reading/PRODUCTION_STORAGE.md:58-66`).
Today that means `D:\Media\Reading\audiobooks\` (the two audiobooks above; `D:\Bookshelf\Audiobooks` is empty) and the aligned EPUB under
`D:\Apps\PocketDS\Reading\storyteller\assets\Dark Matter\aligned\`.
- **Twin:** `ResolveMediaFile(roots, service, remote) (MediaFile, error)` in `internal/reading/media_files.go`, sharing one unexported
  path walk with the removal function. It returns a file opened `O_RDONLY` with size and mtime, accepts regular audio files only
  (extension allow-list) and also refuses Windows reparse points (`ModeIrregular`) and device names (`CON`, `NUL`).
- **Kept from the removal rules:** absolute `path.Clean` path with no `..`, `.`, empty, `:` or NUL parts; longest root prefix for the
  service; root exists and is not a drive root; every component `Lstat`ed, symlinks refused; `EvalSymlinks` plus `filepath.Rel` containment.
- **Read-only by construction:** the client never names a path (index, manifest href, join). The file is opened once and served from that
  handle. No write flag, rename or remove. ffprobe gets an argument vector with `-nostdin`, never a shell (`playback_transport.go:238-246`).
- **Config:** v1 reuses `media_removal_roots`: no config change, and its entries only name folders (a delete still needs `control` and a
  ticket). A later `media_read_roots` can fall back to it. `hubctl doctor` should list audiobook folders that resolve under no root.

## 6. H4: one place for listening and read-along

Scope `reading`: `GET` and `POST .../audio/position`. Optional H4b: the EPUB `GET .../position` gains an `audio` hint and turns an
audio-form locator into a sentence on aligned books. Older apps never call the new route; with H4b an older text reader gets a usable
sentence locator where it would have got an audio one.

```json
GET -> { "workId": "rw_1560...", "sourceItemId": "3726292328809367", "position": {
  "trackId": "t_5d41402abc4b", "track": 0, "offsetMs": 1234567, "globalMs": 1234567, "completed": false,
  "exact": true, "form": "audio", "timestamp": 1764000000000,
  "sentence": { "href": "text/part0005.html", "fragment": "id34-s12" } } }      // position is null when none
POST { "trackId": "t_5d41402abc4b", "offsetMs": 1234567, "completed": false, "timestamp": 1764000123456,
       "expected": { "trackId": "t_5d41402abc4b", "offsetMs": 1200000 } }      // expected is optional
```

Rules the hub decides:
- **Write form.** Always Storyteller's audio form: track `href`, `t=<seconds, 3 decimals>`, `progression`, `totalProgression` in manifest
  order. `completed:true` writes the end of the last track with `totalProgression: 1`. A lone M4B converts its offset to Storyteller's
  virtual chapter file and `t` using the chapter starts. Bad input (unknown field or `trackId`, offset past the track's end) is a 400;
  success answers `{ok, action:"save_audio_position"}` like the EPUB route.
- **Read.** Audio form: direct, matching `href` after decoding `%XX` and dropping a leading `/`. Text form on an aligned book: SMIL
  lookup, exact. Another locator with `totalProgression`: a proportion of `totalMs`, `exact:false`. Otherwise `null`, as for a
  Storyteller 404 (`reading_epub.go:111-117`). `completed` = the flag, the last 2 s of the last track, or `totalProgression >= 0.999`.
- **Timestamp.** After the compare-and-set passes, write `max(now, stored+1)`, so a slow phone clock cannot lose to Storyteller's
  "older write" 409.

**Conversion, aligned books.**
1. Once per aligned EPUB (`book.Readaloud.Filepath`, `media_removal.go:344-349`, through §5), cached by (path, size, mtime): read the zip
   directory, `container.xml`, the OPF and each spine item's `media-overlay` SMIL (4 MB cap per XML, no DOCTYPE); never the audio.
2. Per `<par>`: resolve `text src` and `audio src` against the SMIL path (decode, no `..`, no scheme), parse the clock (`0.000s`,
   `h:m:s`, `ms`, `min`, `h`, as `ReadAlongPackage.clock`, `:168-183`), drop zero-length pars (`:113`), keep order.
3. Aligned files are `NNNNN-CCCCC.ext`; a chunk starts after the earlier chunks of its source file. A chunk's length is its last `clipEnd`,
   which falls about 12 ms short here, so ten chunks drift under 0.15 s.
4. Map each source file to one manifest track by duration: the sum of its chunks' lengths against the manifest `duration`, within 250 ms
   plus 15 ms per chunk (Dark Matter: 12 ms against ffprobe). Demand a complete one-to-one match, else `aligned:false` with a reason.
   Duration, not index: the orders disagree (§2).
5. Sentence to audio: the par for (`href`, `fragment`), then track and `offsetMs = chunkStart + clipBegin`. Audio to sentence: binary
   search for the last `clipBegin <= t` in that file's pars. First strip a leading `/` and decode `%20` in locator hrefs; Storyteller
   stores both spellings (`database/positions.ts`, `fromLegacyHref`).

**Without alignment:** `aligned:false`, no `sentence`. Audio clients stay exact. A text-form position is a proportional guess marked
`exact:false`; the app should ask before jumping. A text reader can still meet an audio-form locator (Storyteller's audiobook apps
already write them into this slot); H4b fixes that for aligned books only.

**Conflicts, matched to the EPUB route.** That route (`reading_epub.go:128-169`) takes `{locator, timestamp, checkBase, expectedLocator}`,
locks per edition (`reading_checkpoint.go:12-20`), compares Storyteller's position deep-equal with `expectedLocator` (`:157`) and answers
409 `reading_position_conflict`; Storyteller's own 409 becomes the same code (`:164-166`); the app shows its choose-which sheet. Audio
does the same: same code and text, same lock key (`lockReadingCheckpoint("storyteller", id)`) so audio and text writers of one book queue,
and `expected` is compared in whole milliseconds against the position derived from Storyteller's current record, since its form may
differ. No `expected` skips the check. GET returns exactly what the last POST sent, so the app's local-first compare keeps working.

## 7. Chapters

- M4B chapter marks exist in the format, but here each M4B has one mark spanning the whole file (measured, all 7) and the 8 MP3s have
  none. The hub lists chapters only for a file with two or more marks (ffprobe `-show_chapters`) or a lone-M4B manifest that has them;
  never from file names. Today that is `chapters: []`.
- ffmpeg and ffprobe exist: `C:\Program Files\Jellyfin\Server\ffmpeg.exe` and `ffprobe.exe` (ffprobe also in `C:\ProgramData\Radarr\bin`
  and `Sonarr\bin`); neither is on PATH. ffprobe took 84 ms on a 419 MB M4B and 22 ms on an MP3.
- The hub finds ffmpeg in `findFFmpeg` (`playback_transport.go:257-274`): `LookPath`, then `%ProgramFiles%\Jellyfin\Server\`. Add
  `findFFprobe` beside it and a `probeAudio` field on `Server` like `previewFrame` (`router.go:83,125`), cached by (path, size, mtime), 5 s
  timeout, 4 at once. Without it: no chapters, no tag order, the rest works.

## 8. Risks

| Risk | Mitigation |
|---|---|
| Three track orders for one book (§2) | Order by complete ID3 tags else manifest; map aligned files by duration; write fractions in manifest order |
| Mapping missing or stale (relocated files, new mount) | `409 audio_not_streamable` falls back to the ZIP; `hubctl doctor`; manifest cached 60 s, cleared with `reading:storyteller:work:<id>` |
| Traversal, links, Windows names | The twin plus extension allow-list, reparse and device-name refusal; table tests |
| Stalled or huge Range streams; clock skew between devices | Per-chunk deadline, transport budget, handle closed on cancel; hub-assigned timestamp after the compare-and-set |
| Two Storyteller books, two slots (Dark Matter) | Owner merges or deletes the duplicate, or the hub mirrors writes (§11) |
| Wrong sentence from a wrong map | Verify by duration, refuse on mismatch, `exact` flag, chunk fixtures |
| One Storyteller account for all profiles; Storyteller changes shape (2.14.23, 3.0) | H7 per-profile accounts; generated manifest and position fixtures |

## 9. Test plan

Fixtures are generated: extend `internal/reading/fixtures.go` (`GenerateFixtureSet`, `retagFixtureMP3`, `deterministicZip`) with a
multi-track folder (the tricky names, ID3 `track` tags, a stored-bytes M4B) and an aligned EPUB (gapless SMIL, stored audio, one chunked
file, mixed clock forms). Extend `newEpubUpstream` (`reading_epub_test.go:19-85`) with manifest fields and a `/positions` that applies
the rules of `database/positions.ts`; its `/files` fails the test if streaming calls it.
- Mapping: the twin of `removal_files_test.go:10-41` plus `..`, `C:\`, `a.mp3:stream`, `CON`, trailing dot, `%2e%2e`, non-audio, directory,
  link (skip without privilege), file swapped after resolve.
- Manifest and bytes: order by manifest and by tags (stub prober), lone M4B, `revision`, no host path anywhere, 403, 409 reasons, Range
  table (`0-0`, `0-`, `-1`, past end, multi-range, stale `If-Range`, HEAD), 412, cancel closes the handle, slow reader, budgets
  (`middleware_test.go:61-89`).
- Alignment: every sentence start round-trips; tail and zero-length pars; unknown fragment; equal lengths and mismatch give
  `aligned:false`; chunked files; hostile XML. Position: audio-form write, text-form read, estimate, null, `expected` mismatch and
  Storyteller 409 to 409, future timestamp bumped, audio and EPUB POSTs race under `-race`, completed, lone-M4B chapter maths.
- Android JVM: manifest to parts, place to `ReadingLocation`, outbox conflict on the `ReadingCheckpointStore` tests, a
  `ConsolidationGuardTest` rule against hand-built audio URLs. After deploy: GET the two manifests (8 and 7 tracks).

## 10. What the apps must change

**Android A3 (stream).**
- `HubEndpoints`/`HubApi`/`HubClient` (through `HubClient.shared`): manifest, track URL with `rev`, slim EPUB URL. `AudiobookPart(title, file)`
  (`AudiobookArchive.kt:6`) becomes title, `uri` (hub or local), `durationMs`, `bytes`; `ReadingAudio.load` builds items from `uri`
  (`:219-224`) and drops `length()` (`:240-245`). `AudiobookScreen.load()` (`:404-434`) fetches the manifest and opens; on 409 it falls
  back to the ZIP path. The parts sheet uses `chapters`.
- `ReadingAudioService` (`:27-35`): ExoPlayer over `CacheDataSource` and `OkHttpDataSource` with the bearer, as `PlaybackService.kt:123`
  does (already a dependency). Add a `SimpleCache` LRU (budget is X5's call), prefetch the next track's head, and
  `setWakeMode(C.WAKE_MODE_NETWORK)`: `:33-34` assumed local files.
- Read-along opens `audio=omit`: `ReadAlongPackage.read` stops requiring audio entries (`:107`); `ReadAlongPlayback` takes URIs from
  `alignment` and clips at `startMs + clipBegin` (`ReadAlongPlayback.kt:64-70`); `prepareNarration` stops extracting
  (`EpubReaderScreen.kt:846-847`). `ReaderOfflineFiles.files` (`:31-34`) also lists the stream cache.

**Android A4 (place).**
- Checkpoint kind `audio` in `ReadingProgress.fetch` and `flush` (`ReadingProgress.kt:63-100`). The location is `{trackId, offsetMs,
  completed}` in `ReadingLocation.locator`, so the XOR rule (`ReadingCheckpointStore.kt:28-29`) holds; `expected` is `base`.
- `ReadingAudio.save()` (`:329-338`) writes through `ReadingProgress.save` (local first, durable outbox), syncing no faster than every
  15 s; finishing writes `completed:true` instead of zeroing (`:335-337`). Seed once from `audiobook_positions`, then delete it. Open with
  `progress.resume` and the conflict sheet, as `EpubReaderScreen.kt:545`. Read-along keeps writing its text locator through the EPUB
  route and stops relying on `pocketdsAudio`.

**Apple, later** (`apple/` holds no code yet; Books is item 7 of `APPLE_PLAN.md`): the same routes. AVPlayer needs `Content-Length`, Range
and a real `Content-Type`, and the bearer through `AVURLAssetHTTPHeaderFieldsKey` (confirm on a device; the hub takes no token in URLs).
Same position contract, no private extension; read-along highlighting needs its own SMIL parser. Ticket per `CLAUDE.md`: one issue,
labels `hub`, `android`, `apple`, `needs-deploy`.

## 11. Open questions for the owner

1. **Route shape.** I moved the draft `.../audio/{sourceItemId}` to `.../publications/{sourceItemId}/audio` to match `limiterFor` and the
   existing routes. Agreed?
2. **A plain audiobook's place.** Draft H4 says non-aligned books are "stored per profile". I recommend Storyteller's slot (shared by all
   profiles, visible to its apps), since the goal is agreement with them.
3. **Dark Matter's duplicate book.** Merge or delete `3204198752768630` in Storyteller, or should the hub mirror writes?
4. **Order and the Mistborn folder.** Let the hub order by complete ID3 tags (my rule), or rename the Dark Matter file and follow Storyteller?
   And do you want the Mistborn folder split into one folder per book, files in story order, or one 91.8-hour "book"?
5. **Read access.** Reuse `media_removal_roots` for reading in v1, or add `media_read_roots` now?
6. **Slim read-along EPUB** (`audio=omit`) in this milestone or after streaming? **Timestamps:** hub-assigned write timestamps differ from
   the EPUB route, which passes the client's. Acceptable?

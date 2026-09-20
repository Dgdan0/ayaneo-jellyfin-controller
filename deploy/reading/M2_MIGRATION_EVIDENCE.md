# M2 canonical storage and migration tooling evidence

Date: 2026-09-20
Branch: `feature/reading-library`
Status: Migration tooling and production review bundle complete

## Safety boundary

M2 currently has no apply mode. The command opens source and existing
destination files read-only, then writes reports to a separate output directory.
It contains no media copy, move, rename, or delete operation. It rejects:

- source roots, destination roots, or source/destination roots that overlap;
- a report directory inside a source or destination root;
- a report generated from a plan whose configuration or content digest no
  longer matches;
- missing credential environment variables before it creates a report.

Komga and the production reading folders were not changed during this work.

## Implemented tooling

`hub/cmd/reading-migrate` now produces a review bundle from a strict YAML
configuration. The scanner:

- inventories and SHA-256 hashes every regular source file;
- classifies media, covers, metadata sidecars, and unsupported files;
- validates the canonical path for books, audiobooks, comics, and manga;
- reads EPUB package metadata and CBZ `ComicInfo.xml` without extracting either
  archive;
- reads sibling OPF and `ComicInfo.xml` sidecars, propagates their metadata to
  media in the same directory, and flags disagreements for manual review;
- identifies orphan/invalid sidecars, invalid archives, duplicate input bytes,
  destination collisions, files already present, and destination hash
  conflicts;
- exports the authenticated Komga user's progress and ordered read lists, one
  configured account at a time, through either the Komga 1.23 paged read-list
  response or the earlier array response, using Basic authentication or a
  revocable `X-API-Key`;
- writes `config.json`, `inventory.json`, `plan.json`, `rollback.json`, and
  `summary.txt`, plus `komga-state.json` when account exports are configured.

The rollback manifest records only proposed `ready` assets. Each entry may be
removed in a later apply milestone only when its created file still has the
recorded hash. M2 does not execute those rollback actions.

## Automated evidence

The implementation was written from failing tests first. Coverage includes:

- deterministic plans, complete bundles, and cancellation;
- source/destination/report overlap rejection;
- invalid layout, unsupported files, duplicate hashes, existing targets,
  collisions, and conflicting targets;
- EPUB, OPF, CBZ, and ComicInfo parsing, including ISBN normalization;
- orphan, invalid, propagated, and conflicting sidecar metadata;
- corrupt ZIP/CBZ data versus recoverable RAR/CBZ extension mismatches;
- configuration-to-plan and plan-content digest binding;
- Komga pagination, current-user progress, unread omission, read-list order,
  authentication, URL validation, and secret/error-body redaction;
- CLI configuration validation and refusal to create output when credentials
  are missing.

The following checks passed from `hub/`:

```text
go test ./internal/reading ./internal/adapters/komga ./cmd/reading-migrate
go test ./...
go vet ./...
```

## Repeatability fixture

The generated M1 fixture corpus was scanned twice into two separate ignored
report directories. Both runs returned:

```text
8 files, 8 ready, 0 conflicts, 0 manual review
```

Both plans had digest
`c75041c091c7d5e472278d922b6ed2eb56edd352111cc0224cfe090f1ea58787`.
The two `plan.json` files also had the same SHA-256,
`2BD098FA937C79E8279125739B93500E6A3CC340826D9BB81EFD76C63BB8CEF6`.
The configuration digest was
`60c948e73432072954713d01bbbd2718c01ee09761d0e0d090d5c2ce669549e2`.
Each rollback bundle contained eight matching entries, and the proposed
canonical roots still contained zero files after both scans.

## Production inventory

Komga 1.23.1 was confirmed healthy at its configured `/komga` context path on
TCP 25600. The FireDaemon wrapper and Java child were both running; the earlier
root-path HTTP 404 was the expected result of probing outside that context, not
a missing listener.

The single production Komga library was scanned repeatedly from ignored local
configuration. After adding explicit RAR-signature classification, two final
runs returned:

```text
450 files, 444 ready, 0 conflicts, 3 manual review, 3 unsupported
```

Both final plans had digest
`0c057debbc3de62f9db30d753d46438466fd7db2b7cb2dedd541ce8ed2124975`.
The two `plan.json` files had the same SHA-256,
`753BD5DF2E7CC02EC38BCB904649A28050BF76EB515BC3623CD321996A556662`.
The source still contained 450 files totaling 7,002,341,595 bytes, both
rollback manifests contained 444 entries, and the proposed destination still
contained zero files.

The production review bundle was then generated with a temporary, revocable
API key for the sole Komga user. It contains one user-scoped export with four
reading-progress records and one ordered read list containing 447 book IDs.
The API key existed only in the migration process environment, was not written
to local configuration, and a scan of every generated report confirmed that
the credential was not serialized. After export, only the newly created key
labelled `pocket test` was revoked: deletion returned HTTP 204, the supplied
credential then returned HTTP 401, and Komga health remained HTTP 200. The
exported `komga-state.json` hash remained unchanged while the plan was refreshed
with the final archive classification. The destination still contained zero
files.

The scanner now distinguishes a RAR-signature extension mismatch from a corrupt
ZIP. The two Defenders files have valid RAR signatures and each lists 24 image
entries without extraction, so the migration decision is to preserve their
bytes and use a `.cbr` destination name. Fantastic Four Annual #2 has a
3,306,596-byte zeroed prefix, seven local ZIP headers, and no central directory;
it is excluded from any apply operation until a valid replacement is acquired.
The original remains untouched. The Markdown, CBL, and CSV manifests remain
source-only reference files; their bytes and hashes remain in the inventory.

## Remaining gate

The M2 read-only tooling and production review gate are complete. No apply
operation has been implemented or authorized. M1B remains open for Panels and
Storyteller iOS checks plus client-side download isolation. Komga remains
active, and M3 acquisition integration does not start until that client gate is
resolved.

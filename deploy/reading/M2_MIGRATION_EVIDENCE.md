# M2 canonical storage and migration tooling evidence

Date: 2026-09-20
Branch: `feature/reading-library`
Status: Production inventory complete; per-user state export pending

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
  configured account at a time, through the current paginated API using Basic
  authentication or a revocable `X-API-Key`;
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

The single production Komga library was scanned twice from the ignored local
configuration. Both runs returned:

```text
450 files, 444 ready, 0 conflicts, 3 manual review, 3 unsupported
```

Both plans had digest
`89bca2ed0a9e1953fc3097bbde012c8c51353a082a75328ddd7e5786fdb39cd5`.
The two `plan.json` files had the same SHA-256,
`EC4A5E0ADB062DDE053C2A1BA9FEFE52408D33D1183BB51C0D9DA4909659445D`.
The source still contained 450 files totaling 7,002,341,595 bytes, both
rollback manifests contained 444 entries, and the proposed destination still
contained zero files.

The three manual-review files are not valid ZIP/CBZ archives. One begins with
zero bytes and appears corrupt. Two have valid RAR signatures but a `.cbz`
extension, so they need an explicit rename to `.cbr` or conversion rather than
an automatic migration. The three unsupported files are the existing Markdown,
CBL, and CSV manifests; their bytes and hashes remain in the inventory.

## Open gate

The remaining M2 work is:

1. create one revocable API key from each Komga user's Account Settings page,
   then run the exporter for every account whose progress or read lists must be
   retained;
2. decide whether to rename/convert the two RAR files and replace or exclude the
   corrupt archive;
3. review the generated ignored report before any later apply implementation.

M1B also remains open for Panels and Storyteller iOS checks plus client-side
download isolation. Komga remains active, and M3 acquisition integration does
not start until these gates are resolved.

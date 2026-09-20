package reading

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

type InventoryManifest struct {
	SchemaVersion int               `json:"schemaVersion"`
	ConfigDigest  string            `json:"configDigest"`
	PlanDigest    string            `json:"planDigest"`
	Sources       []MigrationSource `json:"sources"`
	Assets        []InventoryAsset  `json:"assets"`
}

type InventoryAsset struct {
	ID                 string    `json:"id"`
	SourceID           string    `json:"sourceId"`
	Kind               MediaKind `json:"kind"`
	Role               AssetRole `json:"role"`
	SourceRelativePath string    `json:"sourceRelativePath"`
	SizeBytes          int64     `json:"sizeBytes"`
	SHA256             string    `json:"sha256"`
}

type RollbackAction string

const RollbackRemoveCreatedIfHashMatches RollbackAction = "remove_created_if_hash_matches"

type RollbackManifest struct {
	SchemaVersion int             `json:"schemaVersion"`
	ConfigDigest  string          `json:"configDigest"`
	PlanDigest    string          `json:"planDigest"`
	Entries       []RollbackEntry `json:"entries"`
}

type RollbackEntry struct {
	Action                  RollbackAction `json:"action"`
	SourceID                string         `json:"sourceId"`
	SourceRelativePath      string         `json:"sourceRelativePath"`
	DestinationKind         MediaKind      `json:"destinationKind"`
	DestinationRelativePath string         `json:"destinationRelativePath"`
	SHA256                  string         `json:"sha256"`
}

// WriteMigrationBundle writes review artifacts only. It refuses to place the
// bundle inside any source or destination root so report generation cannot be
// mistaken for a media mutation.
func WriteMigrationBundle(output string, config MigrationConfig, plan MigrationPlan) error {
	normalized, err := validateMigrationConfig(config)
	if err != nil {
		return err
	}
	if plan.SchemaVersion != 1 || plan.Digest == "" {
		return fmt.Errorf("migration plan is incomplete")
	}
	calculated, err := migrationPlanDigest(plan)
	if err != nil {
		return err
	}
	if calculated != plan.Digest {
		return fmt.Errorf("migration plan digest does not match its contents")
	}
	configDigest, err := migrationConfigDigest(normalized)
	if err != nil {
		return err
	}
	if configDigest != plan.ConfigDigest {
		return fmt.Errorf("migration plan was built for a different configuration")
	}
	absoluteOutput, err := filepath.Abs(strings.TrimSpace(output))
	if err != nil || strings.TrimSpace(output) == "" {
		return fmt.Errorf("invalid migration bundle output")
	}
	absoluteOutput = filepath.Clean(absoluteOutput)
	for _, source := range normalized.Sources {
		if pathsOverlap(absoluteOutput, source.Root) {
			return fmt.Errorf("migration bundle output overlaps source %q", source.ID)
		}
	}
	for kind, destination := range normalized.Destinations {
		if pathsOverlap(absoluteOutput, destination) {
			return fmt.Errorf("migration bundle output overlaps %s destination", kind)
		}
	}
	if err := os.MkdirAll(absoluteOutput, 0o750); err != nil {
		return fmt.Errorf("create migration bundle directory: %w", err)
	}

	inventory := InventoryManifest{SchemaVersion: 1, ConfigDigest: plan.ConfigDigest, PlanDigest: plan.Digest, Sources: normalized.Sources}
	rollback := RollbackManifest{SchemaVersion: 1, ConfigDigest: plan.ConfigDigest, PlanDigest: plan.Digest}
	for _, asset := range plan.Assets {
		inventory.Assets = append(inventory.Assets, InventoryAsset{
			ID: asset.ID, SourceID: asset.SourceID, Kind: asset.Kind, Role: asset.Role,
			SourceRelativePath: asset.SourceRelativePath, SizeBytes: asset.SizeBytes, SHA256: asset.SHA256,
		})
		if asset.Disposition == MigrationReady {
			rollback.Entries = append(rollback.Entries, RollbackEntry{
				Action: RollbackRemoveCreatedIfHashMatches, SourceID: asset.SourceID,
				SourceRelativePath: asset.SourceRelativePath, DestinationKind: asset.Kind,
				DestinationRelativePath: asset.DestinationRelativePath, SHA256: asset.SHA256,
			})
		}
	}
	sort.Slice(rollback.Entries, func(i, j int) bool {
		if rollback.Entries[i].DestinationKind != rollback.Entries[j].DestinationKind {
			return rollback.Entries[i].DestinationKind < rollback.Entries[j].DestinationKind
		}
		return rollback.Entries[i].DestinationRelativePath < rollback.Entries[j].DestinationRelativePath
	})

	if err := writeJSONReport(filepath.Join(absoluteOutput, "inventory.json"), inventory); err != nil {
		return err
	}
	if err := writeJSONReport(filepath.Join(absoluteOutput, "config.json"), normalized); err != nil {
		return err
	}
	if err := writeJSONReport(filepath.Join(absoluteOutput, "plan.json"), plan); err != nil {
		return err
	}
	if err := writeJSONReport(filepath.Join(absoluteOutput, "rollback.json"), rollback); err != nil {
		return err
	}
	summary := fmt.Sprintf(
		"Reading migration dry run\nPlan digest: %s\nFiles: %d\nReady: %d\nAlready present: %d\nDuplicates: %d\nConflicts: %d\nManual review: %d\nUnsupported: %d\n",
		plan.Digest, plan.Counts.Files, plan.Counts.Ready, plan.Counts.AlreadyPresent,
		plan.Counts.Duplicates, plan.Counts.Conflicts, plan.Counts.ManualReview, plan.Counts.Unsupported,
	)
	if err := writeReport(filepath.Join(absoluteOutput, "summary.txt"), []byte(summary)); err != nil {
		return err
	}
	return nil
}

func writeJSONReport(name string, value any) error {
	raw, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return fmt.Errorf("encode %s: %w", filepath.Base(name), err)
	}
	raw = append(raw, '\n')
	return writeReport(name, raw)
}

func writeReport(name string, data []byte) error {
	temporary, err := os.CreateTemp(filepath.Dir(name), ".reading-migration-*")
	if err != nil {
		return fmt.Errorf("create %s: %w", filepath.Base(name), err)
	}
	temporaryName := temporary.Name()
	cleanup := func() {
		_ = temporary.Close()
		_ = os.Remove(temporaryName)
	}
	if err := temporary.Chmod(0o600); err != nil {
		cleanup()
		return err
	}
	if _, err := temporary.Write(data); err != nil {
		cleanup()
		return err
	}
	if err := temporary.Sync(); err != nil {
		cleanup()
		return err
	}
	if err := temporary.Close(); err != nil {
		_ = os.Remove(temporaryName)
		return err
	}
	if err := os.Remove(name); err != nil && !os.IsNotExist(err) {
		_ = os.Remove(temporaryName)
		return err
	}
	if err := os.Rename(temporaryName, name); err != nil {
		_ = os.Remove(temporaryName)
		return fmt.Errorf("publish %s: %w", filepath.Base(name), err)
	}
	return nil
}

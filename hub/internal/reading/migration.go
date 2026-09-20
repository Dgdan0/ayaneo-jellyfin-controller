package reading

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// MigrationConfig describes a read-only inventory. Sources are existing media
// roots and Destinations are the proposed canonical roots. Building a plan
// never creates a destination or changes a source.
type MigrationConfig struct {
	SchemaVersion int                  `json:"schemaVersion" yaml:"schema_version"`
	Sources       []MigrationSource    `json:"sources" yaml:"sources"`
	Destinations  map[MediaKind]string `json:"destinations" yaml:"destinations"`
}

type MigrationSource struct {
	ID   string    `json:"id" yaml:"id"`
	Kind MediaKind `json:"kind" yaml:"kind"`
	Root string    `json:"root" yaml:"root"`
}

type AssetRole string

const (
	AssetMedia       AssetRole = "media"
	AssetSidecar     AssetRole = "sidecar"
	AssetCover       AssetRole = "cover"
	AssetUnsupported AssetRole = "unsupported"
)

type MigrationDisposition string

const (
	MigrationReady           MigrationDisposition = "ready"
	MigrationAlreadyPresent  MigrationDisposition = "already_present"
	MigrationDuplicateSource MigrationDisposition = "duplicate_source"
	MigrationConflict        MigrationDisposition = "conflict"
	MigrationManualReview    MigrationDisposition = "manual_review"
	MigrationUnsupported     MigrationDisposition = "unsupported"
)

type MigrationIssue struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type AssetMetadata struct {
	Title       string            `json:"title,omitempty"`
	Series      string            `json:"series,omitempty"`
	Number      string            `json:"number,omitempty"`
	Volume      string            `json:"volume,omitempty"`
	Year        int               `json:"year,omitempty"`
	Language    string            `json:"language,omitempty"`
	Authors     []string          `json:"authors,omitempty"`
	Identifiers map[string]string `json:"identifiers,omitempty"`
}

type PlannedAsset struct {
	ID                      string               `json:"id"`
	SourceID                string               `json:"sourceId"`
	Kind                    MediaKind            `json:"kind"`
	Role                    AssetRole            `json:"role"`
	SourceRelativePath      string               `json:"sourceRelativePath"`
	DestinationRelativePath string               `json:"destinationRelativePath,omitempty"`
	SizeBytes               int64                `json:"sizeBytes"`
	SHA256                  string               `json:"sha256"`
	Metadata                AssetMetadata        `json:"metadata,omitempty"`
	Disposition             MigrationDisposition `json:"disposition"`
	RelatedAssetID          string               `json:"relatedAssetId,omitempty"`
	Issues                  []MigrationIssue     `json:"issues,omitempty"`
}

type MigrationCounts struct {
	Files          int `json:"files"`
	Ready          int `json:"ready"`
	AlreadyPresent int `json:"alreadyPresent"`
	Duplicates     int `json:"duplicates"`
	Conflicts      int `json:"conflicts"`
	ManualReview   int `json:"manualReview"`
	Unsupported    int `json:"unsupported"`
}

type MigrationPlan struct {
	SchemaVersion int             `json:"schemaVersion"`
	ConfigDigest  string          `json:"configDigest"`
	Digest        string          `json:"digest"`
	Assets        []PlannedAsset  `json:"assets"`
	Counts        MigrationCounts `json:"counts"`
}

// BuildMigrationPlan inventories every regular source file, hashes it, and
// compares the proposed target with the canonical destination. It opens files
// read-only and performs no filesystem writes.
func BuildMigrationPlan(ctx context.Context, config MigrationConfig) (MigrationPlan, error) {
	normalized, err := validateMigrationConfig(config)
	if err != nil {
		return MigrationPlan{}, err
	}
	if err := ctx.Err(); err != nil {
		return MigrationPlan{}, fmt.Errorf("migration inventory canceled: %w", err)
	}

	assets := make([]PlannedAsset, 0)
	for _, source := range normalized.Sources {
		err := filepath.WalkDir(source.Root, func(name string, entry os.DirEntry, walkErr error) error {
			if walkErr != nil {
				return walkErr
			}
			if err := ctx.Err(); err != nil {
				return err
			}
			if entry.IsDir() {
				return nil
			}
			info, err := entry.Info()
			if err != nil {
				return err
			}
			relative, err := filepath.Rel(source.Root, name)
			if err != nil {
				return err
			}
			relative = filepath.ToSlash(relative)
			asset := PlannedAsset{
				ID:                 stableAssetID(source.ID, relative),
				SourceID:           source.ID,
				Kind:               source.Kind,
				SourceRelativePath: relative,
				SizeBytes:          info.Size(),
			}
			if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
				asset.Role = AssetUnsupported
				asset.Disposition = MigrationManualReview
				asset.Issues = append(asset.Issues, MigrationIssue{Code: "special_file", Message: "only regular files are inventoried for migration"})
				assets = append(assets, asset)
				return nil
			}

			asset.SHA256, err = hashFile(ctx, name)
			if err != nil {
				return fmt.Errorf("hash %s/%s: %w", source.ID, relative, err)
			}
			asset.Role = classifyAsset(source.Kind, filepath.Base(name))
			if asset.Role == AssetUnsupported {
				asset.Disposition = MigrationUnsupported
				asset.Issues = append(asset.Issues, MigrationIssue{Code: "unsupported_file", Message: "file type is not part of the canonical reading layout"})
				assets = append(assets, asset)
				return nil
			}

			normalizedPath, pathErr := NormalizeAssetPath(source.Kind, relative)
			if pathErr != nil {
				asset.Disposition = MigrationManualReview
				asset.Issues = append(asset.Issues, MigrationIssue{Code: "unsafe_layout", Message: pathErr.Error()})
			} else {
				asset.DestinationRelativePath = normalizedPath
				asset.Disposition = MigrationReady
			}
			metadata, metadataIssues := ExtractAssetMetadata(name, source.Kind)
			asset.Metadata, asset.Issues = mergeMetadataIssues(asset.Issues, metadata, metadataIssues)
			if asset.Role == AssetSidecar && hasIssue(asset.Issues, "invalid_sidecar") {
				asset.Disposition = MigrationManualReview
			}
			if asset.Role == AssetMedia && (hasIssue(asset.Issues, "invalid_archive") || hasIssue(asset.Issues, "invalid_epub")) {
				asset.Disposition = MigrationManualReview
			}
			assets = append(assets, asset)
			return nil
		})
		if err != nil {
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				return MigrationPlan{}, fmt.Errorf("migration inventory canceled: %w", err)
			}
			return MigrationPlan{}, fmt.Errorf("inventory source %q: %w", source.ID, err)
		}
	}

	sort.Slice(assets, func(i, j int) bool {
		if assets[i].SourceID != assets[j].SourceID {
			return assets[i].SourceID < assets[j].SourceID
		}
		return strings.ToLower(assets[i].SourceRelativePath) < strings.ToLower(assets[j].SourceRelativePath)
	})
	applySidecarMetadata(assets)
	markOrphanSidecars(assets)
	if err := compareDestinations(ctx, normalized, assets); err != nil {
		return MigrationPlan{}, err
	}
	markDestinationCollisions(assets)
	markSourceDuplicates(assets)

	configDigest, err := migrationConfigDigest(normalized)
	if err != nil {
		return MigrationPlan{}, err
	}
	plan := MigrationPlan{SchemaVersion: 1, ConfigDigest: configDigest, Assets: assets}
	plan.Counts = countMigrationAssets(assets)
	plan.Digest, err = migrationPlanDigest(plan)
	if err != nil {
		return MigrationPlan{}, err
	}
	return plan, nil
}

func applySidecarMetadata(assets []PlannedAsset) {
	sidecars := make(map[string][]int)
	for index, asset := range assets {
		if asset.Role != AssetSidecar || asset.Disposition == MigrationManualReview {
			continue
		}
		key := asset.SourceID + "\x00" + strings.ToLower(pathDir(asset.SourceRelativePath))
		sidecars[key] = append(sidecars[key], index)
	}
	for index := range assets {
		asset := &assets[index]
		if asset.Role != AssetMedia {
			continue
		}
		key := asset.SourceID + "\x00" + strings.ToLower(pathDir(asset.SourceRelativePath))
		for _, sidecarIndex := range sidecars[key] {
			merged, conflict := mergeAssetMetadata(asset.Metadata, assets[sidecarIndex].Metadata)
			asset.Metadata = merged
			if conflict {
				asset.Disposition = MigrationManualReview
				asset.Issues = append(asset.Issues, MigrationIssue{
					Code: "metadata_conflict", Message: "embedded metadata and a sibling sidecar disagree",
				})
			}
		}
	}
}

func mergeAssetMetadata(primary, additional AssetMetadata) (AssetMetadata, bool) {
	conflict := false
	mergeText := func(target *string, value string) {
		value = strings.TrimSpace(value)
		if value == "" {
			return
		}
		if *target == "" {
			*target = value
			return
		}
		if !strings.EqualFold(strings.TrimSpace(*target), value) {
			conflict = true
		}
	}
	mergeText(&primary.Title, additional.Title)
	mergeText(&primary.Series, additional.Series)
	mergeText(&primary.Number, additional.Number)
	mergeText(&primary.Volume, additional.Volume)
	mergeText(&primary.Language, additional.Language)
	if additional.Year != 0 {
		if primary.Year == 0 {
			primary.Year = additional.Year
		} else if primary.Year != additional.Year {
			conflict = true
		}
	}
	if len(primary.Authors) == 0 {
		primary.Authors = append([]string(nil), additional.Authors...)
	} else if len(additional.Authors) > 0 && !equalFoldedStrings(primary.Authors, additional.Authors) {
		conflict = true
	}
	if len(additional.Identifiers) > 0 && primary.Identifiers == nil {
		primary.Identifiers = make(map[string]string, len(additional.Identifiers))
	}
	for key, value := range additional.Identifiers {
		if existing := primary.Identifiers[key]; existing == "" {
			primary.Identifiers[key] = value
		} else if !strings.EqualFold(existing, value) {
			conflict = true
		}
	}
	return primary, conflict
}

func equalFoldedStrings(first, second []string) bool {
	if len(first) != len(second) {
		return false
	}
	for index := range first {
		if !strings.EqualFold(strings.TrimSpace(first[index]), strings.TrimSpace(second[index])) {
			return false
		}
	}
	return true
}

func validateMigrationConfig(config MigrationConfig) (MigrationConfig, error) {
	if config.SchemaVersion != 0 && config.SchemaVersion != 1 {
		return MigrationConfig{}, fmt.Errorf("unsupported migration config schema %d", config.SchemaVersion)
	}
	if len(config.Sources) == 0 {
		return MigrationConfig{}, fmt.Errorf("at least one migration source is required")
	}
	if len(config.Destinations) == 0 {
		return MigrationConfig{}, fmt.Errorf("at least one canonical destination is required")
	}
	normalized := config
	normalized.SchemaVersion = 1
	normalized.Sources = append([]MigrationSource(nil), config.Sources...)
	normalized.Destinations = make(map[MediaKind]string, len(config.Destinations))
	destinationKinds := make([]MediaKind, 0, len(config.Destinations))
	for kind, root := range config.Destinations {
		if CanonicalRoot(kind) == "" {
			return MigrationConfig{}, fmt.Errorf("unknown destination media kind %q", kind)
		}
		absolute, err := filepath.Abs(strings.TrimSpace(root))
		if err != nil || strings.TrimSpace(root) == "" {
			return MigrationConfig{}, fmt.Errorf("invalid %s destination", kind)
		}
		normalized.Destinations[kind] = filepath.Clean(absolute)
		destinationKinds = append(destinationKinds, kind)
	}
	sort.Slice(destinationKinds, func(i, j int) bool { return destinationKinds[i] < destinationKinds[j] })
	for i, firstKind := range destinationKinds {
		for _, secondKind := range destinationKinds[i+1:] {
			if pathsOverlap(normalized.Destinations[firstKind], normalized.Destinations[secondKind]) {
				return MigrationConfig{}, fmt.Errorf("%s and %s canonical destinations overlap", firstKind, secondKind)
			}
		}
	}
	seen := make(map[string]struct{}, len(config.Sources))
	for i := range normalized.Sources {
		source := &normalized.Sources[i]
		source.ID = strings.TrimSpace(source.ID)
		if source.ID == "" {
			return MigrationConfig{}, fmt.Errorf("migration source id is required")
		}
		key := strings.ToLower(source.ID)
		if _, ok := seen[key]; ok {
			return MigrationConfig{}, fmt.Errorf("duplicate migration source id %q", source.ID)
		}
		seen[key] = struct{}{}
		if CanonicalRoot(source.Kind) == "" {
			return MigrationConfig{}, fmt.Errorf("source %q has unknown media kind %q", source.ID, source.Kind)
		}
		if _, ok := normalized.Destinations[source.Kind]; !ok {
			return MigrationConfig{}, fmt.Errorf("source %q has no %s destination", source.ID, source.Kind)
		}
		absolute, err := filepath.Abs(strings.TrimSpace(source.Root))
		if err != nil || strings.TrimSpace(source.Root) == "" {
			return MigrationConfig{}, fmt.Errorf("source %q has an invalid root", source.ID)
		}
		source.Root = filepath.Clean(absolute)
		info, err := os.Stat(source.Root)
		if err != nil {
			return MigrationConfig{}, fmt.Errorf("source %q: %w", source.ID, err)
		}
		if !info.IsDir() {
			return MigrationConfig{}, fmt.Errorf("source %q is not a directory", source.ID)
		}
		for _, kind := range destinationKinds {
			if pathsOverlap(source.Root, normalized.Destinations[kind]) {
				return MigrationConfig{}, fmt.Errorf("source %q overlaps the %s canonical destination", source.ID, kind)
			}
		}
		for previous := 0; previous < i; previous++ {
			if pathsOverlap(source.Root, normalized.Sources[previous].Root) {
				return MigrationConfig{}, fmt.Errorf("migration sources %q and %q overlap", normalized.Sources[previous].ID, source.ID)
			}
		}
	}
	sort.Slice(normalized.Sources, func(i, j int) bool { return normalized.Sources[i].ID < normalized.Sources[j].ID })
	return normalized, nil
}

func classifyAsset(kind MediaKind, name string) AssetRole {
	lower := strings.ToLower(name)
	switch lower {
	case "comicinfo.xml", "metadata.opf", "content.opf":
		if allowedAssetName(kind, name) {
			return AssetSidecar
		}
	case "cover.jpg", "cover.jpeg", "cover.png", "cover.webp", "folder.jpg", "folder.png":
		if allowedAssetName(kind, name) {
			return AssetCover
		}
	default:
		if allowedAssetName(kind, name) {
			return AssetMedia
		}
	}
	return AssetUnsupported
}

func hashFile(ctx context.Context, name string) (string, error) {
	file, err := os.Open(name)
	if err != nil {
		return "", err
	}
	defer file.Close()
	hash := sha256.New()
	buffer := make([]byte, 256*1024)
	for {
		if err := ctx.Err(); err != nil {
			return "", err
		}
		read, readErr := file.Read(buffer)
		if read > 0 {
			if _, err := hash.Write(buffer[:read]); err != nil {
				return "", err
			}
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			return "", readErr
		}
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func stableAssetID(sourceID, relative string) string {
	sum := sha256.Sum256([]byte(strings.ToLower(sourceID) + "\x00" + strings.ToLower(filepath.ToSlash(relative))))
	return hex.EncodeToString(sum[:16])
}

func mergeMetadataIssues(existing []MigrationIssue, metadata AssetMetadata, issues []MigrationIssue) (AssetMetadata, []MigrationIssue) {
	return metadata, append(existing, issues...)
}

func hasIssue(issues []MigrationIssue, code string) bool {
	for _, issue := range issues {
		if issue.Code == code {
			return true
		}
	}
	return false
}

func markOrphanSidecars(assets []PlannedAsset) {
	mediaDirs := make(map[string]bool)
	for _, asset := range assets {
		if asset.Role == AssetMedia {
			mediaDirs[asset.SourceID+"\x00"+strings.ToLower(pathDir(asset.SourceRelativePath))] = true
		}
	}
	for i := range assets {
		asset := &assets[i]
		if asset.Role != AssetSidecar {
			continue
		}
		key := asset.SourceID + "\x00" + strings.ToLower(pathDir(asset.SourceRelativePath))
		if !mediaDirs[key] {
			asset.Disposition = MigrationManualReview
			asset.Issues = append(asset.Issues, MigrationIssue{Code: "orphan_sidecar", Message: "sidecar directory contains no supported media file"})
		}
	}
}

func pathDir(value string) string {
	value = filepath.ToSlash(value)
	if index := strings.LastIndex(value, "/"); index >= 0 {
		return value[:index]
	}
	return "."
}

func compareDestinations(ctx context.Context, config MigrationConfig, assets []PlannedAsset) error {
	for i := range assets {
		asset := &assets[i]
		if asset.Disposition != MigrationReady || asset.DestinationRelativePath == "" {
			continue
		}
		if err := ctx.Err(); err != nil {
			return fmt.Errorf("migration destination comparison canceled: %w", err)
		}
		root := config.Destinations[asset.Kind]
		target := filepath.Join(root, filepath.FromSlash(asset.DestinationRelativePath))
		info, err := os.Lstat(target)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return fmt.Errorf("inspect canonical destination for %s: %w", asset.ID, err)
		}
		if !info.Mode().IsRegular() {
			asset.Disposition = MigrationConflict
			asset.Issues = append(asset.Issues, MigrationIssue{Code: "destination_not_regular", Message: "canonical destination exists but is not a regular file"})
			continue
		}
		digest, err := hashFile(ctx, target)
		if err != nil {
			return fmt.Errorf("hash canonical destination for %s: %w", asset.ID, err)
		}
		if digest == asset.SHA256 {
			asset.Disposition = MigrationAlreadyPresent
		} else {
			asset.Disposition = MigrationConflict
			asset.Issues = append(asset.Issues, MigrationIssue{Code: "destination_hash_conflict", Message: "canonical destination contains different bytes"})
		}
	}
	return nil
}

func markDestinationCollisions(assets []PlannedAsset) {
	groups := make(map[string][]int)
	for index, asset := range assets {
		if asset.DestinationRelativePath == "" || asset.Disposition == MigrationUnsupported || asset.Disposition == MigrationManualReview {
			continue
		}
		key := string(asset.Kind) + "\x00" + strings.ToLower(asset.DestinationRelativePath)
		groups[key] = append(groups[key], index)
	}
	for _, indexes := range groups {
		if len(indexes) < 2 {
			continue
		}
		firstHash := assets[indexes[0]].SHA256
		same := true
		for _, index := range indexes[1:] {
			if assets[index].SHA256 != firstHash {
				same = false
				break
			}
		}
		if same {
			primary := indexes[0]
			for _, index := range indexes {
				if assets[index].Disposition == MigrationAlreadyPresent {
					primary = index
					break
				}
			}
			for _, index := range indexes {
				if index == primary {
					continue
				}
				assets[index].Disposition = MigrationDuplicateSource
				assets[index].RelatedAssetID = assets[primary].ID
				assets[index].Issues = append(assets[index].Issues, MigrationIssue{
					Code: "identical_destination", Message: "another source file maps identical bytes to this canonical path",
				})
			}
			continue
		}
		for _, index := range indexes {
			assets[index].Disposition = MigrationConflict
			assets[index].Issues = append(assets[index].Issues, MigrationIssue{Code: "planned_path_collision", Message: "multiple source files map to this canonical path with different hashes"})
		}
	}
}

func markSourceDuplicates(assets []PlannedAsset) {
	groups := make(map[string][]int)
	for index, asset := range assets {
		if asset.Role == AssetMedia && asset.SHA256 != "" && (asset.Disposition == MigrationReady || asset.Disposition == MigrationAlreadyPresent) {
			groups[asset.SHA256] = append(groups[asset.SHA256], index)
		}
	}
	for _, indexes := range groups {
		if len(indexes) < 2 {
			continue
		}
		primary := indexes[0]
		for _, index := range indexes {
			if assets[index].Disposition == MigrationAlreadyPresent {
				primary = index
				break
			}
		}
		for _, index := range indexes {
			if index == primary || assets[index].Disposition != MigrationReady {
				continue
			}
			assets[index].Disposition = MigrationDuplicateSource
			assets[index].RelatedAssetID = assets[primary].ID
			assets[index].Issues = append(assets[index].Issues, MigrationIssue{Code: "duplicate_hash", Message: "another source media file has identical bytes"})
		}
	}
}

func countMigrationAssets(assets []PlannedAsset) MigrationCounts {
	counts := MigrationCounts{Files: len(assets)}
	for _, asset := range assets {
		switch asset.Disposition {
		case MigrationReady:
			counts.Ready++
		case MigrationAlreadyPresent:
			counts.AlreadyPresent++
		case MigrationDuplicateSource:
			counts.Duplicates++
		case MigrationConflict:
			counts.Conflicts++
		case MigrationManualReview:
			counts.ManualReview++
		case MigrationUnsupported:
			counts.Unsupported++
		}
	}
	return counts
}

func migrationPlanDigest(plan MigrationPlan) (string, error) {
	plan.Digest = ""
	raw, err := json.Marshal(plan)
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(raw)
	return hex.EncodeToString(digest[:]), nil
}

func migrationConfigDigest(config MigrationConfig) (string, error) {
	raw, err := json.Marshal(config)
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(raw)
	return hex.EncodeToString(digest[:]), nil
}

func pathsOverlap(first, second string) bool {
	first = filepath.Clean(first)
	second = filepath.Clean(second)
	return pathContains(first, second) || pathContains(second, first)
}

func pathContains(root, candidate string) bool {
	relative, err := filepath.Rel(root, candidate)
	if err != nil {
		return false
	}
	return relative == "." || (relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator)))
}

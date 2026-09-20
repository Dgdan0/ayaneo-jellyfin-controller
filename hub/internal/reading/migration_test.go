package reading

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"
)

func TestBuildMigrationPlanIsReadOnlyAndDeterministic(t *testing.T) {
	source := t.TempDir()
	destination := t.TempDir()

	comicPath := filepath.Join(source, "Green Lantern (2023)", "Green Lantern 001.cbz")
	writeTestCBZ(t, comicPath, `<?xml version="1.0"?><ComicInfo><Title>Test of Will</Title><Series>Green Lantern</Series><Number>1</Number><Volume>1</Volume><Year>2023</Year><Writer>Jeremy Adams</Writer><LanguageISO>en</LanguageISO></ComicInfo>`)
	writeTestFile(t, filepath.Join(source, "Green Lantern (2023)", "cover.jpg"), []byte("cover"))
	writeTestFile(t, filepath.Join(source, "Green Lantern (2023)", "notes.txt"), []byte("operator notes"))

	before := snapshotTree(t, source)
	config := MigrationConfig{
		Sources:      []MigrationSource{{ID: "komga-comics", Kind: MediaComic, Root: source}},
		Destinations: map[MediaKind]string{MediaComic: destination},
	}

	first, err := BuildMigrationPlan(context.Background(), config)
	if err != nil {
		t.Fatal(err)
	}
	second, err := BuildMigrationPlan(context.Background(), config)
	if err != nil {
		t.Fatal(err)
	}

	if first.Digest == "" || first.Digest != second.Digest {
		t.Fatalf("plan digests = %q and %q", first.Digest, second.Digest)
	}
	if !reflect.DeepEqual(first.Assets, second.Assets) {
		t.Fatalf("repeat scan changed assets:\nfirst=%+v\nsecond=%+v", first.Assets, second.Assets)
	}
	if after := snapshotTree(t, source); !reflect.DeepEqual(before, after) {
		t.Fatalf("source tree changed:\nbefore=%+v\nafter=%+v", before, after)
	}

	comic := findPlannedAsset(t, first, "Green Lantern (2023)/Green Lantern 001.cbz")
	if comic.Role != AssetMedia || comic.Disposition != MigrationReady {
		t.Fatalf("comic plan = %+v", comic)
	}
	if comic.Metadata.Title != "Test of Will" || comic.Metadata.Series != "Green Lantern" || comic.Metadata.Number != "1" {
		t.Fatalf("comic metadata = %+v", comic.Metadata)
	}
	unsupported := findPlannedAsset(t, first, "Green Lantern (2023)/notes.txt")
	if unsupported.Disposition != MigrationUnsupported || unsupported.SHA256 == "" {
		t.Fatalf("unsupported file = %+v", unsupported)
	}
	if entries, err := os.ReadDir(destination); err != nil || len(entries) != 0 {
		t.Fatalf("dry run wrote destination entries: entries=%v err=%v", entries, err)
	}
}

func TestBuildMigrationPlanDetectsExistingDuplicatesAndConflicts(t *testing.T) {
	source := t.TempDir()
	destination := t.TempDir()
	readyPath := filepath.Join(source, "Series", "Ready.cbz")
	writeTestCBZ(t, readyPath, `<ComicInfo><Title>Ready</Title><Series>Series</Series></ComicInfo>`)
	readyBytes, err := os.ReadFile(readyPath)
	if err != nil {
		t.Fatal(err)
	}
	writeTestFile(t, filepath.Join(source, "Duplicate", "Copy.cbz"), readyBytes)
	presentPath := filepath.Join(source, "Series", "Present.cbz")
	writeTestCBZ(t, presentPath, `<ComicInfo><Title>Present</Title><Series>Series</Series></ComicInfo>`)
	presentBytes, err := os.ReadFile(presentPath)
	if err != nil {
		t.Fatal(err)
	}
	writeTestFile(t, filepath.Join(destination, "Series", "Present.cbz"), presentBytes)
	writeTestCBZ(t, filepath.Join(source, "Series", "Conflict.cbz"), `<ComicInfo><Title>Incoming</Title><Series>Series</Series></ComicInfo>`)
	writeTestCBZ(t, filepath.Join(destination, "Series", "Conflict.cbz"), `<ComicInfo><Title>Existing</Title><Series>Series</Series></ComicInfo>`)

	plan, err := BuildMigrationPlan(context.Background(), MigrationConfig{
		Sources:      []MigrationSource{{ID: "komga", Kind: MediaComic, Root: source}},
		Destinations: map[MediaKind]string{MediaComic: destination},
	})
	if err != nil {
		t.Fatal(err)
	}

	if got := findPlannedAsset(t, plan, "Series/Present.cbz").Disposition; got != MigrationAlreadyPresent {
		t.Fatalf("present disposition = %q", got)
	}
	if got := findPlannedAsset(t, plan, "Series/Conflict.cbz").Disposition; got != MigrationConflict {
		t.Fatalf("conflict disposition = %q", got)
	}
	ready := findPlannedAsset(t, plan, "Series/Ready.cbz")
	copy := findPlannedAsset(t, plan, "Duplicate/Copy.cbz")
	if !((ready.Disposition == MigrationReady && copy.Disposition == MigrationDuplicateSource && copy.RelatedAssetID == ready.ID) ||
		(copy.Disposition == MigrationReady && ready.Disposition == MigrationDuplicateSource && ready.RelatedAssetID == copy.ID)) {
		t.Fatalf("duplicate pair = ready:%+v copy:%+v", ready, copy)
	}
}

func TestBuildMigrationPlanDeduplicatesIdenticalDestinationPaths(t *testing.T) {
	firstSource := t.TempDir()
	secondSource := t.TempDir()
	destination := t.TempDir()
	output := t.TempDir()
	relativeMedia := filepath.Join("Series", "Issue.cbz")
	relativeSidecar := filepath.Join("Series", "ComicInfo.xml")
	comicInfo := []byte(`<ComicInfo><Title>Issue</Title><Series>Series</Series></ComicInfo>`)
	writeTestCBZ(t, filepath.Join(firstSource, relativeMedia), string(comicInfo))
	mediaBytes, err := os.ReadFile(filepath.Join(firstSource, relativeMedia))
	if err != nil {
		t.Fatal(err)
	}
	writeTestFile(t, filepath.Join(secondSource, relativeMedia), mediaBytes)
	writeTestFile(t, filepath.Join(firstSource, relativeSidecar), comicInfo)
	writeTestFile(t, filepath.Join(secondSource, relativeSidecar), comicInfo)

	config := MigrationConfig{
		Sources: []MigrationSource{
			{ID: "first", Kind: MediaComic, Root: firstSource},
			{ID: "second", Kind: MediaComic, Root: secondSource},
		},
		Destinations: map[MediaKind]string{MediaComic: destination},
	}
	plan, err := BuildMigrationPlan(context.Background(), config)
	if err != nil {
		t.Fatal(err)
	}
	for _, relative := range []string{"Series/Issue.cbz", "Series/ComicInfo.xml"} {
		first := findPlannedAssetForSource(t, plan, "first", relative)
		second := findPlannedAssetForSource(t, plan, "second", relative)
		if first.Disposition != MigrationReady || second.Disposition != MigrationDuplicateSource || second.RelatedAssetID != first.ID {
			t.Fatalf("identical destination %s = first:%+v second:%+v", relative, first, second)
		}
	}
	if err := WriteMigrationBundle(output, config, plan); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(filepath.Join(output, "rollback.json"))
	if err != nil {
		t.Fatal(err)
	}
	var rollback RollbackManifest
	if err := json.Unmarshal(raw, &rollback); err != nil {
		t.Fatal(err)
	}
	if len(rollback.Entries) != 2 {
		t.Fatalf("rollback contains duplicate destinations: %+v", rollback.Entries)
	}
}

func TestBuildMigrationPlanRejectsInvalidAndOrphanSidecars(t *testing.T) {
	source := t.TempDir()
	destination := t.TempDir()
	writeTestFile(t, filepath.Join(source, "Broken", "Issue.cbz"), []byte("not a zip"))
	writeTestFile(t, filepath.Join(source, "Broken", "ComicInfo.xml"), []byte("<ComicInfo><Title>unfinished"))
	writeTestFile(t, filepath.Join(source, "Orphan", "ComicInfo.xml"), []byte("<ComicInfo><Title>Orphan</Title></ComicInfo>"))
	writeTestFile(t, filepath.Join(source, "Root.cbz"), []byte("root file"))

	plan, err := BuildMigrationPlan(context.Background(), MigrationConfig{
		Sources:      []MigrationSource{{ID: "komga", Kind: MediaComic, Root: source}},
		Destinations: map[MediaKind]string{MediaComic: destination},
	})
	if err != nil {
		t.Fatal(err)
	}

	broken := findPlannedAsset(t, plan, "Broken/ComicInfo.xml")
	if broken.Disposition != MigrationManualReview || !containsIssue(broken.Issues, "invalid_sidecar") {
		t.Fatalf("broken sidecar = %+v", broken)
	}
	orphan := findPlannedAsset(t, plan, "Orphan/ComicInfo.xml")
	if orphan.Disposition != MigrationManualReview || !containsIssue(orphan.Issues, "orphan_sidecar") {
		t.Fatalf("orphan sidecar = %+v", orphan)
	}
	root := findPlannedAsset(t, plan, "Root.cbz")
	if root.Disposition != MigrationManualReview || !containsIssue(root.Issues, "unsafe_layout") {
		t.Fatalf("root media = %+v", root)
	}
}

func TestBuildMigrationPlanAppliesExternalSidecarMetadataToSiblingMedia(t *testing.T) {
	source := t.TempDir()
	destination := t.TempDir()
	writeTestFile(t, filepath.Join(source, "Pierce Brown", "Red Rising", "Red Rising.mp3"), []byte("audio"))
	writeTestFile(t, filepath.Join(source, "Pierce Brown", "Red Rising", "metadata.opf"), []byte(`<?xml version="1.0"?><package unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:isbn:978-1-56619-909-4</dc:identifier><dc:title>Red Rising</dc:title><dc:creator>Pierce Brown</dc:creator><dc:language>en</dc:language><meta property="belongs-to-collection">Red Rising Saga</meta><meta property="group-position">1</meta></metadata></package>`))

	plan, err := BuildMigrationPlan(context.Background(), MigrationConfig{
		Sources:      []MigrationSource{{ID: "audio", Kind: MediaAudiobook, Root: source}},
		Destinations: map[MediaKind]string{MediaAudiobook: destination},
	})
	if err != nil {
		t.Fatal(err)
	}
	media := findPlannedAsset(t, plan, "Pierce Brown/Red Rising/Red Rising.mp3")
	if media.Metadata.Title != "Red Rising" || media.Metadata.Series != "Red Rising Saga" || media.Metadata.Identifiers["isbn"] != "9781566199094" {
		t.Fatalf("sidecar metadata was not applied to media: %+v", media)
	}
}

func TestBuildMigrationPlanFlagsEmbeddedAndSidecarMetadataConflict(t *testing.T) {
	source := t.TempDir()
	destination := t.TempDir()
	writeTestCBZ(t, filepath.Join(source, "Series", "Issue.cbz"), `<ComicInfo><Title>Embedded title</Title><Series>Series</Series></ComicInfo>`)
	writeTestFile(t, filepath.Join(source, "Series", "ComicInfo.xml"), []byte(`<ComicInfo><Title>Different sidecar title</Title><Series>Series</Series></ComicInfo>`))

	plan, err := BuildMigrationPlan(context.Background(), MigrationConfig{
		Sources:      []MigrationSource{{ID: "comics", Kind: MediaComic, Root: source}},
		Destinations: map[MediaKind]string{MediaComic: destination},
	})
	if err != nil {
		t.Fatal(err)
	}
	media := findPlannedAsset(t, plan, "Series/Issue.cbz")
	if media.Disposition != MigrationManualReview || !containsIssue(media.Issues, "metadata_conflict") {
		t.Fatalf("metadata conflict was not held for review: %+v", media)
	}
}

func TestBuildMigrationPlanHonorsCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := BuildMigrationPlan(ctx, MigrationConfig{
		Sources:      []MigrationSource{{ID: "komga", Kind: MediaComic, Root: t.TempDir()}},
		Destinations: map[MediaKind]string{MediaComic: t.TempDir()},
	})
	if err == nil || !strings.Contains(err.Error(), "canceled") {
		t.Fatalf("canceled scan error = %v", err)
	}
}

func TestMigrationSafetyRejectsOverlappingRootsAndReportLocations(t *testing.T) {
	source := t.TempDir()
	overlappingDestination := filepath.Join(source, "Canonical")
	if err := os.MkdirAll(overlappingDestination, 0o755); err != nil {
		t.Fatal(err)
	}
	_, err := BuildMigrationPlan(context.Background(), MigrationConfig{
		Sources:      []MigrationSource{{ID: "books", Kind: MediaBook, Root: source}},
		Destinations: map[MediaKind]string{MediaBook: overlappingDestination},
	})
	if err == nil || !strings.Contains(err.Error(), "overlaps") {
		t.Fatalf("overlapping root error = %v", err)
	}

	safeDestination := t.TempDir()
	config := MigrationConfig{
		Sources:      []MigrationSource{{ID: "books", Kind: MediaBook, Root: source}},
		Destinations: map[MediaKind]string{MediaBook: safeDestination},
	}
	plan, err := BuildMigrationPlan(context.Background(), config)
	if err != nil {
		t.Fatal(err)
	}
	if err := WriteMigrationBundle(filepath.Join(source, "report"), config, plan); err == nil || !strings.Contains(err.Error(), "overlaps") {
		t.Fatalf("source report error = %v", err)
	}
	if _, err := os.Stat(filepath.Join(source, "report")); !os.IsNotExist(err) {
		t.Fatalf("unsafe report directory was created: %v", err)
	}
}

func TestMigrationSafetyRejectsCrossKindAndNestedDestinationRoots(t *testing.T) {
	t.Run("source overlaps another media kind destination", func(t *testing.T) {
		root := t.TempDir()
		comicSource := filepath.Join(root, "Comics")
		bookDestination := filepath.Join(comicSource, "CanonicalBooks")
		if err := os.MkdirAll(comicSource, 0o755); err != nil {
			t.Fatal(err)
		}
		_, err := BuildMigrationPlan(context.Background(), MigrationConfig{
			Sources: []MigrationSource{
				{ID: "comics", Kind: MediaComic, Root: comicSource},
				{ID: "books", Kind: MediaBook, Root: t.TempDir()},
			},
			Destinations: map[MediaKind]string{
				MediaComic: t.TempDir(),
				MediaBook:  bookDestination,
			},
		})
		if err == nil || !strings.Contains(err.Error(), "overlaps") {
			t.Fatalf("cross-kind overlap error = %v", err)
		}
	})

	t.Run("canonical destinations overlap", func(t *testing.T) {
		root := t.TempDir()
		_, err := BuildMigrationPlan(context.Background(), MigrationConfig{
			Sources: []MigrationSource{
				{ID: "comics", Kind: MediaComic, Root: t.TempDir()},
				{ID: "books", Kind: MediaBook, Root: t.TempDir()},
			},
			Destinations: map[MediaKind]string{
				MediaComic: root,
				MediaBook:  filepath.Join(root, "Books"),
			},
		})
		if err == nil || !strings.Contains(err.Error(), "overlap") {
			t.Fatalf("destination overlap error = %v", err)
		}
	})

	t.Run("source roots overlap", func(t *testing.T) {
		root := t.TempDir()
		nested := filepath.Join(root, "Nested")
		if err := os.MkdirAll(nested, 0o755); err != nil {
			t.Fatal(err)
		}
		_, err := BuildMigrationPlan(context.Background(), MigrationConfig{
			Sources: []MigrationSource{
				{ID: "comics", Kind: MediaComic, Root: root},
				{ID: "manga", Kind: MediaManga, Root: nested},
			},
			Destinations: map[MediaKind]string{
				MediaComic: t.TempDir(),
				MediaManga: t.TempDir(),
			},
		})
		if err == nil || !strings.Contains(err.Error(), "overlap") {
			t.Fatalf("source overlap error = %v", err)
		}
	})
}

func TestWriteMigrationBundleIsCompleteAndDoesNotContainCredentials(t *testing.T) {
	source := t.TempDir()
	destination := t.TempDir()
	output := t.TempDir()
	writeTestCBZ(t, filepath.Join(source, "Series", "Issue.cbz"), `<ComicInfo><Title>Issue</Title><Series>Series</Series></ComicInfo>`)
	config := MigrationConfig{
		Sources:      []MigrationSource{{ID: "komga", Kind: MediaComic, Root: source}},
		Destinations: map[MediaKind]string{MediaComic: destination},
	}
	plan, err := BuildMigrationPlan(context.Background(), config)
	if err != nil {
		t.Fatal(err)
	}
	before := snapshotTree(t, source)
	if err := WriteMigrationBundle(output, config, plan); err != nil {
		t.Fatal(err)
	}
	if after := snapshotTree(t, source); !reflect.DeepEqual(before, after) {
		t.Fatal("writing the report changed source media")
	}

	for _, name := range []string{"config.json", "inventory.json", "plan.json", "rollback.json", "summary.txt"} {
		if _, err := os.Stat(filepath.Join(output, name)); err != nil {
			t.Errorf("missing %s: %v", name, err)
		}
	}
	raw, err := os.ReadFile(filepath.Join(output, "rollback.json"))
	if err != nil {
		t.Fatal(err)
	}
	var rollback RollbackManifest
	if err := json.Unmarshal(raw, &rollback); err != nil {
		t.Fatal(err)
	}
	if rollback.SchemaVersion != 1 || rollback.PlanDigest != plan.Digest || len(rollback.Entries) != 1 {
		t.Fatalf("rollback = %+v", rollback)
	}
	entry := rollback.Entries[0]
	if entry.Action != RollbackRemoveCreatedIfHashMatches || entry.SHA256 == "" {
		t.Fatalf("rollback entry = %+v", entry)
	}
}

func TestWriteMigrationBundleRejectsPlanBuiltForDifferentRoots(t *testing.T) {
	source := t.TempDir()
	firstDestination := t.TempDir()
	secondDestination := t.TempDir()
	writeTestCBZ(t, filepath.Join(source, "Series", "Issue.cbz"), `<ComicInfo><Title>Issue</Title><Series>Series</Series></ComicInfo>`)
	firstConfig := MigrationConfig{
		Sources:      []MigrationSource{{ID: "komga", Kind: MediaComic, Root: source}},
		Destinations: map[MediaKind]string{MediaComic: firstDestination},
	}
	plan, err := BuildMigrationPlan(context.Background(), firstConfig)
	if err != nil {
		t.Fatal(err)
	}
	secondConfig := MigrationConfig{
		Sources:      []MigrationSource{{ID: "komga", Kind: MediaComic, Root: source}},
		Destinations: map[MediaKind]string{MediaComic: secondDestination},
	}
	err = WriteMigrationBundle(t.TempDir(), secondConfig, plan)
	if err == nil || !strings.Contains(err.Error(), "configuration") {
		t.Fatalf("mismatched configuration error = %v", err)
	}
}

func TestExtractEPUBMetadataUsesContainerAndStableIdentifiers(t *testing.T) {
	book := filepath.Join(t.TempDir(), "Author", "Series", "Book.epub")
	if err := os.MkdirAll(filepath.Dir(book), 0o755); err != nil {
		t.Fatal(err)
	}
	file, err := os.Create(book)
	if err != nil {
		t.Fatal(err)
	}
	zw := zip.NewWriter(file)
	writeZipEntry(t, zw, "META-INF/container.xml", `<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`)
	writeZipEntry(t, zw, "EPUB/package.opf", `<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:isbn:978-1-56619-909-4</dc:identifier><dc:title>Red Rising</dc:title><dc:creator>Pierce Brown</dc:creator><dc:language>en</dc:language><meta property="belongs-to-collection">Red Rising Saga</meta><meta property="group-position">1</meta></metadata></package>`)
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}

	metadata, issues := ExtractAssetMetadata(book, MediaBook)
	if len(issues) != 0 {
		t.Fatalf("metadata issues = %+v", issues)
	}
	if metadata.Title != "Red Rising" || metadata.Series != "Red Rising Saga" || metadata.Number != "1" || metadata.Language != "en" {
		t.Fatalf("metadata = %+v", metadata)
	}
	if metadata.Identifiers["isbn"] != "9781566199094" || !reflect.DeepEqual(metadata.Authors, []string{"Pierce Brown"}) {
		t.Fatalf("metadata identity = %+v", metadata)
	}
}

func findPlannedAsset(t *testing.T, plan MigrationPlan, relative string) PlannedAsset {
	t.Helper()
	for _, asset := range plan.Assets {
		if asset.SourceRelativePath == relative {
			return asset
		}
	}
	t.Fatalf("missing planned asset %q in %+v", relative, plan.Assets)
	return PlannedAsset{}
}

func findPlannedAssetForSource(t *testing.T, plan MigrationPlan, sourceID, relative string) PlannedAsset {
	t.Helper()
	for _, asset := range plan.Assets {
		if asset.SourceID == sourceID && asset.SourceRelativePath == relative {
			return asset
		}
	}
	t.Fatalf("asset %s/%q not found", sourceID, relative)
	return PlannedAsset{}
}

func containsIssue(issues []MigrationIssue, code string) bool {
	for _, issue := range issues {
		if issue.Code == code {
			return true
		}
	}
	return false
}

func writeTestFile(t *testing.T, name string, data []byte) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(name), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(name, data, 0o644); err != nil {
		t.Fatal(err)
	}
}

func writeTestCBZ(t *testing.T, name, comicInfo string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(name), 0o755); err != nil {
		t.Fatal(err)
	}
	file, err := os.Create(name)
	if err != nil {
		t.Fatal(err)
	}
	zw := zip.NewWriter(file)
	writeZipEntry(t, zw, "ComicInfo.xml", comicInfo)
	writeZipEntry(t, zw, "001.png", "page")
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
}

func writeZipEntry(t *testing.T, zw *zip.Writer, name, content string) {
	t.Helper()
	w, err := zw.Create(name)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := w.Write([]byte(content)); err != nil {
		t.Fatal(err)
	}
}

type treeSnapshotEntry struct {
	Path   string
	Size   int64
	Mode   os.FileMode
	SHA256 string
}

func snapshotTree(t *testing.T, root string) []treeSnapshotEntry {
	t.Helper()
	var entries []treeSnapshotEntry
	err := filepath.Walk(root, func(name string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}
		raw, err := os.ReadFile(name)
		if err != nil {
			return err
		}
		relative, err := filepath.Rel(root, name)
		if err != nil {
			return err
		}
		digest := sha256.Sum256(raw)
		entries = append(entries, treeSnapshotEntry{
			Path: filepath.ToSlash(relative), Size: info.Size(), Mode: info.Mode(), SHA256: hex.EncodeToString(digest[:]),
		})
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Path < entries[j].Path })
	return entries
}

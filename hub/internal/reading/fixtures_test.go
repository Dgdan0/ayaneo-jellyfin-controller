package reading

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestReadaloudFixtureProvidesObservableProgress(t *testing.T) {
	root := t.TempDir()
	manifest, err := GenerateFixtureSet(root)
	if err != nil {
		t.Fatal(err)
	}

	var fixturePath string
	for _, asset := range manifest.Assets {
		if asset.Name == "The Readaloud Journey" {
			fixturePath = filepath.Join(root, filepath.FromSlash(asset.Path))
			break
		}
	}
	if fixturePath == "" {
		t.Fatal("missing multi-segment readaloud fixture")
	}

	reader, err := zip.OpenReader(fixturePath)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()

	packageDoc := readFixtureZipEntry(t, reader.File, "EPUB/package.opf")
	if !strings.Contains(packageDoc, "0:00:06.000") {
		t.Fatal("readaloud package does not declare a six-second overlay")
	}
	if count := strings.Count(packageDoc, `<itemref idref="chapter`); count != 6 {
		t.Fatalf("readaloud package has %d spine sections, want 6", count)
	}
	for index := 1; index <= 6; index++ {
		chapterName := fmt.Sprintf("EPUB/chapter%d.xhtml", index)
		overlayName := fmt.Sprintf("EPUB/overlay%d.smil", index)
		chapter := readFixtureZipEntry(t, reader.File, chapterName)
		if !strings.Contains(chapter, fmt.Sprintf(`id="sentence%d"`, index)) {
			t.Fatalf("%s does not expose its timed text anchor", chapterName)
		}
		overlay := readFixtureZipEntry(t, reader.File, overlayName)
		if strings.Count(overlay, "<par>") != 1 || !strings.Contains(overlay, filepath.Base(chapterName)) {
			t.Fatalf("%s does not time exactly one matching spine section", overlayName)
		}
	}
}

func readFixtureZipEntry(t *testing.T, files []*zip.File, name string) string {
	t.Helper()
	for _, file := range files {
		if file.Name != name {
			continue
		}
		entry, err := file.Open()
		if err != nil {
			t.Fatal(err)
		}
		defer entry.Close()
		contents, err := io.ReadAll(entry)
		if err != nil {
			t.Fatal(err)
		}
		return string(contents)
	}
	t.Fatalf("fixture archive is missing %s", name)
	return ""
}

func TestGenerateFixtureSetProducesPortableCoverage(t *testing.T) {
	root := t.TempDir()
	manifest, err := GenerateFixtureSet(root)
	if err != nil {
		t.Fatal(err)
	}

	wantRoles := map[FixtureRole]bool{
		FixtureEbook:          false,
		FixtureReadaloud:      false,
		FixtureAudiobook:      false,
		FixtureComic:          false,
		FixtureManga:          false,
		FixturePDFText:        false,
		FixturePDFImage:       false,
		FixtureCorruptArchive: false,
	}
	for _, asset := range manifest.Assets {
		wantRoles[asset.Role] = true
		if strings.Contains(asset.Path, `\`) || filepath.IsAbs(asset.Path) {
			t.Errorf("fixture path is not portable: %q", asset.Path)
		}
		if asset.SHA256 == "" || len(asset.SHA256) != 64 {
			t.Errorf("fixture %q has invalid digest %q", asset.Path, asset.SHA256)
		}
		if _, err := os.Stat(filepath.Join(root, filepath.FromSlash(asset.Path))); err != nil {
			t.Errorf("fixture %q is missing: %v", asset.Path, err)
		}
	}
	for role, found := range wantRoles {
		if !found {
			t.Errorf("missing fixture role %q", role)
		}
	}

	raw, err := os.ReadFile(filepath.Join(root, "fixture-manifest.json"))
	if err != nil {
		t.Fatal(err)
	}
	var disk FixtureManifest
	if err := json.Unmarshal(raw, &disk); err != nil {
		t.Fatal(err)
	}
	if disk.SchemaVersion != 1 || len(disk.Assets) != len(manifest.Assets) {
		t.Fatalf("disk manifest = version %d with %d assets", disk.SchemaVersion, len(disk.Assets))
	}
}

func TestFixtureAudiobookMetadataCanPairWithEbook(t *testing.T) {
	audio, err := fixtureMP3()
	if err != nil {
		t.Fatal(err)
	}
	frames := fixtureID3TextFrames(t, audio)
	for id, want := range map[string]string{
		"TIT2": "The Clockwork Island",
		"TPE1": "Lab Author",
		"TALB": "The Clockwork Island",
	} {
		if frames[id] != want {
			t.Errorf("ID3 frame %s = %q, want %q", id, frames[id], want)
		}
	}
}

func fixtureID3TextFrames(t *testing.T, audio []byte) map[string]string {
	t.Helper()
	if len(audio) < 10 || !bytes.Equal(audio[:3], []byte("ID3")) {
		t.Fatal("fixture has no ID3 header")
	}
	frames := map[string]string{}
	end := 10 + decodeSynchsafe(audio[6:10])
	for offset := 10; offset+10 <= end; {
		id := string(audio[offset : offset+4])
		size := decodeSynchsafe(audio[offset+4 : offset+8])
		if size < 1 || offset+10+size > end {
			break
		}
		frames[id] = string(audio[offset+11 : offset+10+size])
		offset += 10 + size
	}
	return frames
}

func TestGeneratedEpubAndComicArchivesHaveRequiredMetadata(t *testing.T) {
	root := t.TempDir()
	manifest, err := GenerateFixtureSet(root)
	if err != nil {
		t.Fatal(err)
	}

	for _, asset := range manifest.Assets {
		if asset.Role != FixtureEbook && asset.Role != FixtureReadaloud && asset.Role != FixtureComic && asset.Role != FixtureManga {
			continue
		}
		reader, err := zip.OpenReader(filepath.Join(root, filepath.FromSlash(asset.Path)))
		if err != nil {
			t.Fatalf("open %s: %v", asset.Path, err)
		}
		names := map[string]bool{}
		for _, file := range reader.File {
			names[file.Name] = true
		}

		switch asset.Role {
		case FixtureEbook:
			assertEpubMimetypeEntry(t, asset.Path, reader.File)
			for _, name := range []string{"mimetype", "META-INF/container.xml", "EPUB/package.opf", "EPUB/nav.xhtml"} {
				if !names[name] {
					t.Errorf("%s missing %s", asset.Path, name)
				}
			}
		case FixtureReadaloud:
			assertEpubMimetypeEntry(t, asset.Path, reader.File)
			for _, name := range []string{"mimetype", "EPUB/package.opf", "EPUB/narration.mp3"} {
				if !names[name] {
					t.Errorf("%s missing %s", asset.Path, name)
				}
			}
			hasOverlay := false
			for name := range names {
				if strings.HasPrefix(name, "EPUB/overlay") && strings.HasSuffix(name, ".smil") {
					hasOverlay = true
					break
				}
			}
			if !hasOverlay {
				t.Errorf("%s missing a SMIL media overlay", asset.Path)
			}
		case FixtureComic, FixtureManga:
			if !names["ComicInfo.xml"] || !names["001.png"] || !names["002.png"] {
				t.Errorf("%s missing ComicInfo.xml or pages", asset.Path)
			}
		}
		reader.Close()
	}
}

func assertEpubMimetypeEntry(t *testing.T, path string, files []*zip.File) {
	t.Helper()
	if len(files) == 0 || files[0].Name != "mimetype" {
		t.Errorf("%s must store mimetype as the first ZIP entry", path)
		return
	}
	if files[0].Method != zip.Store {
		t.Errorf("%s mimetype must be uncompressed", path)
	}
	if len(files[0].Extra) != 0 {
		t.Errorf("%s mimetype must not contain ZIP extra fields", path)
	}
}

func TestGeneratedCanonicalAssetsPassLayoutValidation(t *testing.T) {
	root := t.TempDir()
	manifest, err := GenerateFixtureSet(root)
	if err != nil {
		t.Fatal(err)
	}
	for _, asset := range manifest.Assets {
		if asset.Role == FixtureCorruptArchive {
			continue
		}
		kind, err := ParseMediaKind(string(asset.Kind))
		if err != nil {
			t.Fatal(err)
		}
		relative := strings.TrimPrefix(asset.Path, CanonicalRoot(kind)+"/")
		if _, err := NormalizeAssetPath(kind, relative); err != nil {
			t.Errorf("fixture %q rejected by canonical validator: %v", asset.Path, err)
		}
	}
}

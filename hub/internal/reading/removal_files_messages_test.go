package reading

import (
	"ayaneohub/internal/config"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// The removal flow shows these sentences to the person confirming a deletion,
// so they are part of its behaviour. ResolveRemovalFile and ResolveMediaFile
// share one path walk; this pins what the removal side says about each way the
// walk can refuse, so sharing it cannot reword a confirmation screen.
func TestRemovalFileKeepsItsWordsForEachRefusal(t *testing.T) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "folder"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "book.cbz"), []byte("book"), 0o600); err != nil {
		t.Fatal(err)
	}
	driveRoot := filepath.VolumeName(root) + string(os.PathSeparator)
	mapped := []config.MediaRemovalRoot{{Service: "kavita", Remote: "/reading", Local: root}}

	for _, test := range []struct {
		name    string
		roots   []config.MediaRemovalRoot
		service string
		remote  string
		want    string
	}{
		{"not a clean path", mapped, "kavita", "/reading/../book.cbz", "invalid media path"},
		{"not absolute", mapped, "kavita", "reading/book.cbz", "invalid media path"},
		{"a part that cannot name a file", mapped, "kavita", "/reading/book.cbz:stream", "invalid media path"},
		{"no mapping", mapped, "kavita", "/other/book.cbz", "server file mapping is not configured for this library"},
		{"another service's mapping", mapped, "storyteller", "/reading/book.cbz", "server file mapping is not configured for this library"},
		{"a mapping that is not absolute", []config.MediaRemovalRoot{{Service: "kavita", Remote: "/reading", Local: "relative"}}, "kavita", "/reading/book.cbz", "invalid server file mapping"},
		{"a mapping that is a drive root", []config.MediaRemovalRoot{{Service: "kavita", Remote: "/reading", Local: driveRoot}}, "kavita", "/reading/book.cbz", "a drive root cannot be a deletion library"},
		{"a mapped folder that is gone", []config.MediaRemovalRoot{{Service: "kavita", Remote: "/reading", Local: filepath.Join(root, "gone")}}, "kavita", "/reading/book.cbz", "library folder is unavailable"},
		{"a file that is gone", mapped, "kavita", "/reading/missing.cbz", "a media file is unavailable"},
		{"a folder, not a file", mapped, "kavita", "/reading/folder", "only individual media files can be deleted"},
	} {
		t.Run(test.name, func(t *testing.T) {
			_, err := ResolveRemovalFile(test.roots, test.service, test.remote)
			if err == nil || err.Error() != test.want {
				t.Fatalf("error = %v, want %q", err, test.want)
			}
		})
	}

	link := filepath.Join(root, "link.cbz")
	if err := os.Symlink(filepath.Join(root, "book.cbz"), link); err != nil {
		t.Logf("no symlink privilege here, the link case is skipped: %v", err)
		return
	}
	_, err := ResolveRemovalFile(mapped, "kavita", "/reading/link.cbz")
	if err == nil || err.Error() != "linked media files cannot be deleted here" {
		t.Fatalf("link error = %v", err)
	}
}

// Size and modified time can be copied; the file's identity cannot. Sharing the
// walk pinned that identity when the file was checked (on Windows a FileInfo
// otherwise reads it from the path later, and always agrees with itself), so a
// replacement made between the preview and the confirmation is now noticed.
func TestRemovalFileNoticesAReplacementOfTheSameSizeAndTime(t *testing.T) {
	root := t.TempDir()
	file := filepath.Join(root, "book.cbz")
	if err := os.WriteFile(file, []byte("book"), 0o600); err != nil {
		t.Fatal(err)
	}
	roots := []config.MediaRemovalRoot{{Service: "kavita", Remote: "/reading", Local: root}}
	checked, err := ResolveRemovalFile(roots, "kavita", "/reading/book.cbz")
	if err != nil || !checked.Unchanged() {
		t.Fatalf("resolve = %v", err)
	}
	replacement := filepath.Join(root, "replacement.tmp")
	if err := os.WriteFile(replacement, []byte("BOOK"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(replacement, time.Time{}, time.Unix(0, checked.Modified)); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(replacement, file); err != nil {
		t.Fatal(err)
	}
	if checked.Unchanged() {
		t.Fatal("another file with the same size and time was taken for the one that was checked")
	}
}

// Go reports a junction as ModeIrregular, not ModeSymlink, so a walk that
// looked only for symlinks let a junction through to a later check. Deleting
// through an alias would remove another title's file; it is refused by name.
func TestRemovalFileRefusesAJunctionWithTheLinkWords(t *testing.T) {
	root := t.TempDir()
	writeMediaFiles(t, root, map[string]string{"Real/book.cbz": "book"})
	if !tryJunction(t, filepath.Join(root, "Real"), filepath.Join(root, "Alias")) {
		t.Skip("no junction here")
	}
	roots := []config.MediaRemovalRoot{{Service: "kavita", Remote: "/reading", Local: root}}
	_, err := ResolveRemovalFile(roots, "kavita", "/reading/Alias/book.cbz")
	if err == nil || err.Error() != "linked media files cannot be deleted here" {
		t.Fatalf("error = %v", err)
	}
}

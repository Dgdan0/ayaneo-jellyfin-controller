package reading

import (
	"ayaneohub/internal/config"
	"os"
	"path/filepath"
	"testing"
)

func TestRemovalFileStaysWithinMappedLibraryAndDetectsReplacement(t *testing.T) {
	root := t.TempDir()
	file := filepath.Join(root, "book.cbz")
	os.WriteFile(file, []byte("book"), 0600)
	roots := []config.MediaRemovalRoot{{Service: "kavita", Remote: "/reading", Local: root}}
	verified, err := ResolveRemovalFile(roots, "kavita", "/reading/book.cbz")
	if err != nil || !verified.Unchanged() {
		t.Fatalf("resolve %v", err)
	}
	for _, remote := range []string{"/reading", "/reading/../book.cbz", "/reading2/book.cbz", "/reading/book.cbz:stream", "/reading//book.cbz", "/reading/missing.cbz"} {
		if _, err := ResolveRemovalFile(roots, "kavita", remote); err == nil {
			t.Errorf("accepted %s", remote)
		}
	}
	if _, err := ResolveRemovalFile(roots, "storyteller", "/reading/book.cbz"); err == nil {
		t.Fatal("cross-service mapping accepted")
	}
	os.Mkdir(filepath.Join(root, "folder"), 0700)
	if _, err := ResolveRemovalFile(roots, "kavita", "/reading/folder"); err == nil {
		t.Fatal("accepted directory")
	}
	os.WriteFile(file, []byte("changed"), 0600)
	if verified.Unchanged() {
		t.Fatal("changed file accepted")
	}
	link := filepath.Join(root, "link.cbz")
	if err := os.Symlink(file, link); err == nil {
		if _, err := ResolveRemovalFile(roots, "kavita", "/reading/link.cbz"); err == nil {
			t.Fatal("symlink accepted")
		}
	}
}

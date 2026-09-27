package reading

import (
	"ayaneohub/internal/config"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"strings"
)

// RemovalFile is a single, verified regular file. Directories are never removed.
type RemovalFile struct {
	Path     string
	Size     int64
	Modified int64
	info     os.FileInfo
}

func ResolveRemovalFile(roots []config.MediaRemovalRoot, service, remote string) (RemovalFile, error) {
	remote = strings.ReplaceAll(remote, "\\", "/")
	if remote != path.Clean(remote) || !strings.HasPrefix(remote, "/") {
		return RemovalFile{}, fmt.Errorf("invalid media path")
	}
	var selected *config.MediaRemovalRoot
	for i := range roots {
		r := &roots[i]
		prefix := strings.TrimRight(r.Remote, "/")
		if r.Service == service && strings.HasPrefix(remote, prefix+"/") && (selected == nil || len(r.Remote) > len(selected.Remote)) {
			selected = r
		}
	}
	if selected == nil {
		return RemovalFile{}, fmt.Errorf("server file mapping is not configured for this library")
	}
	root, err := filepath.Abs(selected.Local)
	if err != nil || !filepath.IsAbs(selected.Local) {
		return RemovalFile{}, fmt.Errorf("invalid server file mapping")
	}
	if filepath.Dir(root) == root {
		return RemovalFile{}, fmt.Errorf("a drive root cannot be a deletion library")
	}
	resolvedRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		return RemovalFile{}, fmt.Errorf("library folder is unavailable")
	}
	relative := strings.TrimPrefix(remote, strings.TrimRight(selected.Remote, "/")+"/")
	for _, part := range strings.Split(relative, "/") {
		if part == "" || part == "." || part == ".." || strings.ContainsAny(part, ":\x00") {
			return RemovalFile{}, fmt.Errorf("invalid media path")
		}
	}
	candidate := filepath.Join(root, filepath.FromSlash(relative))
	// Reject symlinks/junctions anywhere below the configured root, including
	// links pointing back inside it: deleting an alias must not remove another title.
	current := root
	for _, part := range strings.Split(relative, "/") {
		current = filepath.Join(current, part)
		st, e := os.Lstat(current)
		if e != nil {
			return RemovalFile{}, fmt.Errorf("a media file is unavailable")
		}
		if st.Mode()&os.ModeSymlink != 0 {
			return RemovalFile{}, fmt.Errorf("linked media files cannot be deleted here")
		}
	}
	actual, err := filepath.EvalSymlinks(candidate)
	if err != nil {
		return RemovalFile{}, fmt.Errorf("a media file is unavailable")
	}
	rel, err := filepath.Rel(resolvedRoot, actual)
	if err != nil || rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || filepath.IsAbs(rel) {
		return RemovalFile{}, fmt.Errorf("media file is outside its library")
	}
	info, err := os.Stat(actual)
	if err != nil || !info.Mode().IsRegular() {
		return RemovalFile{}, fmt.Errorf("only individual media files can be deleted")
	}
	return RemovalFile{Path: actual, Size: info.Size(), Modified: info.ModTime().UnixNano(), info: info}, nil
}

func (f RemovalFile) Unchanged() bool {
	info, err := os.Lstat(f.Path)
	return err == nil && info.Mode().IsRegular() && info.Size() == f.Size && info.ModTime().UnixNano() == f.Modified && os.SameFile(f.info, info)
}

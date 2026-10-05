package reading

import (
	"ayaneohub/internal/config"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"
)

// walkFail says why a mapped path was not accepted. ResolveRemovalFile (a
// deletion screen's words) and ResolveMediaFile (a route's reason code) both ask
// the same walk, so they cannot drift apart on what a safe path is.
type walkFail int

const (
	walkOK         walkFail = iota
	walkBadPath             // not a clean absolute path, or a part that could climb, alias or reach a device
	walkUnmapped            // no mapping covers this service and path
	walkBadMapping          // the mapping's local folder is not an absolute path
	walkDriveRoot           // the mapping is a whole drive
	walkNoRoot              // the mapped folder is not there
	walkMissing             // a part of the path is not there
	walkLinked              // a part is a symlink, a junction or another reparse point
	walkOutside             // it resolves outside the mapped folder
	walkWrongKind           // not a regular file (or not a folder, when a folder was asked for)
)

// walkRules are the few ways the two callers differ.
type walkRules struct {
	// strictNames also refuses names Windows would alias or reinterpret. Asking
	// to delete "CON" finds nothing and does no harm; opening it, or
	// "a.mp3:stream", reaches something the library never held, so a reader
	// asks for this.
	strictNames bool
	// folder asks for a folder rather than a regular file.
	folder bool
}

// walked is a path the walk accepted, with the identity it had when it did.
type walked struct {
	path string
	info os.FileInfo
}

// walkMediaPath maps a Storyteller or Kavita path onto the media PC through the
// configured roots and checks every step. The client never names the result:
// the path comes from the hub's own index, a manifest or a join of the two.
//
// The checks, in order: a clean absolute path; the longest mapping for the
// service; a mapped folder that exists and is not a drive; parts that cannot
// climb or alias; no link anywhere below the mapped folder (an alias would let
// one book stand for another); the real location still inside the folder; and
// a regular file (or a folder).
func walkMediaPath(roots []config.MediaRemovalRoot, service, remote string, rules walkRules) (walked, walkFail) {
	remote = strings.ReplaceAll(remote, "\\", "/")
	if remote != path.Clean(remote) || !strings.HasPrefix(remote, "/") {
		return walked{}, walkBadPath
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
		return walked{}, walkUnmapped
	}
	root, err := filepath.Abs(selected.Local)
	if err != nil || !filepath.IsAbs(selected.Local) {
		return walked{}, walkBadMapping
	}
	if filepath.Dir(root) == root {
		return walked{}, walkDriveRoot
	}
	resolvedRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		return walked{}, walkNoRoot
	}
	relative := strings.TrimPrefix(remote, strings.TrimRight(selected.Remote, "/")+"/")
	parts := strings.Split(relative, "/")
	for _, part := range parts {
		if unsafePart(part, rules.strictNames) {
			return walked{}, walkBadPath
		}
	}
	candidate := filepath.Join(root, filepath.FromSlash(relative))
	// Reject links anywhere below the configured root, including ones pointing
	// back inside it. Go reports a symlink as ModeSymlink but a junction (and
	// every other Windows reparse point) as ModeIrregular, so both are refused.
	current := root
	for _, part := range parts {
		current = filepath.Join(current, part)
		st, err := os.Lstat(current)
		if err != nil {
			return walked{}, walkMissing
		}
		if st.Mode()&(os.ModeSymlink|os.ModeIrregular) != 0 {
			return walked{}, walkLinked
		}
	}
	actual, err := filepath.EvalSymlinks(candidate)
	if err != nil {
		return walked{}, walkMissing
	}
	rel, err := filepath.Rel(resolvedRoot, actual)
	if err != nil || rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || filepath.IsAbs(rel) {
		return walked{}, walkOutside
	}
	info, err := os.Stat(actual)
	if err != nil {
		return walked{}, walkWrongKind
	}
	if rules.folder && !info.IsDir() || !rules.folder && !info.Mode().IsRegular() {
		return walked{}, walkWrongKind
	}
	// On Windows a FileInfo from os.Stat does not hold the file's identity: it
	// reads it from the path the first time two infos are compared. Compared
	// later, it would read whatever is at the path by then and always agree with
	// itself. Comparing it with itself now pins the identity of the file that
	// was checked.
	_ = os.SameFile(info, info)
	return walked{path: actual, info: info}, walkOK
}

func unsafePart(part string, strict bool) bool {
	if part == "" || part == "." || part == ".." || strings.ContainsAny(part, ":\x00") {
		return true
	}
	return strict && (windowsUnsafeName(part) || decodesToUnsafe(part))
}

// windowsUnsafeName is a name Windows would not take at face value: a device
// ("NUL.mp3" is NUL), a name with a trailing dot or space (which Win32 drops,
// so "a.mp3." opens "a.mp3"), or characters it cannot hold.
func windowsUnsafeName(part string) bool {
	if strings.TrimRight(part, ". ") != part {
		return true
	}
	for _, r := range part {
		if r < 0x20 || strings.ContainsRune(`<>"|?*`, r) {
			return true
		}
	}
	stem := part
	if dot := strings.IndexByte(part, '.'); dot >= 0 {
		stem = part[:dot]
	}
	stem = strings.ToUpper(strings.TrimRight(stem, " "))
	switch stem {
	case "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$":
		return true
	}
	if runes := []rune(stem); len(runes) == 4 && (string(runes[:3]) == "COM" || string(runes[:3]) == "LPT") {
		switch runes[3] {
		case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '¹', '²', '³':
			return true
		}
	}
	return false
}

// decodesToUnsafe finds a part that only becomes "..", a drive or a separator
// once percent-decoded: a climb waiting for whoever decodes it next. Names that
// merely contain a "%" ("100% Pure.mp3") do not decode and are left alone.
func decodesToUnsafe(part string) bool {
	for round := 0; round < 3 && strings.Contains(part, "%"); round++ {
		decoded, err := url.PathUnescape(part)
		if err != nil || decoded == part {
			return false
		}
		part = decoded
		if part == "" || part == "." || part == ".." || strings.ContainsAny(part, ":\x00/\\") {
			return true
		}
	}
	return false
}

package reading

import (
	"ayaneohub/internal/config"
	"io"
	"os"
	"path"
	"sort"
	"strings"
	"time"
)

// AudioKind is one kind of audio file the hub will serve: the one list of them.
type AudioKind struct {
	Ext  string // lower case, with the dot
	MIME string
	// Demuxer is ffprobe's name for the container. A probe is told what to
	// expect rather than left to guess from the bytes, so a file named .mp3 that
	// is really a playlist (which a demuxer would follow to other files or
	// addresses) simply fails to probe.
	Demuxer string
}

// The extensions are Storyteller's own list of what it takes for audio, less
// the two it has no type for (.alac, .mogg). Anything else in a book's folder
// (a cover, a cue sheet, a playlist) is not served.
var audioKinds = map[string]AudioKind{
	".mp3":  {".mp3", "audio/mpeg", "mp3"},
	".m4a":  {".m4a", "audio/mp4", "mov"},
	".m4b":  {".m4b", "audio/mp4", "mov"},
	".mp4":  {".mp4", "audio/mp4", "mov"},
	".aac":  {".aac", "audio/aac", "aac"},
	".ogg":  {".ogg", "audio/ogg", "ogg"},
	".oga":  {".oga", "audio/ogg", "ogg"},
	".opus": {".opus", "audio/opus", "ogg"},
	".flac": {".flac", "audio/flac", "flac"},
	".wav":  {".wav", "audio/wav", "wav"},
	".aiff": {".aiff", "audio/aiff", "aiff"},
	".weba": {".weba", "audio/webm", "matroska,webm"},
}

// AudioKindOf names the kind of audio file a name is, by its extension.
func AudioKindOf(name string) (AudioKind, bool) {
	kind, ok := audioKinds[strings.ToLower(path.Ext(name))]
	return kind, ok
}

// MediaFailure is why a path could not be served, as a route needs to say it.
type MediaFailure int

const (
	// MediaUnmapped: no mapping covers the path, or the mapped folder is not
	// usable. The operator's configuration is the fix.
	MediaUnmapped MediaFailure = iota + 1
	// MediaMissing: the file or a folder on the way is not there.
	MediaMissing
	// MediaRefused: it is there but of a kind the hub will not serve: a link, a
	// device name, a file that is not audio, a folder, a file that changed.
	MediaRefused
)

func (f MediaFailure) String() string {
	switch f {
	case MediaUnmapped:
		return "unmapped"
	case MediaMissing:
		return "missing"
	case MediaRefused:
		return "refused"
	}
	return "unknown"
}

// MediaError says why, in words that name no path or file: a route may put
// them in a response or a log line.
type MediaError struct {
	Failure MediaFailure
	msg     string
}

func (e *MediaError) Error() string { return e.msg }

func mediaError(fail walkFail) *MediaError {
	switch fail {
	case walkUnmapped:
		return &MediaError{MediaUnmapped, "server file mapping is not configured for this library"}
	case walkBadMapping, walkDriveRoot, walkNoRoot:
		return &MediaError{MediaUnmapped, "the library's server folder is not usable"}
	case walkMissing:
		return &MediaError{MediaMissing, "a media file is unavailable"}
	case walkBadPath:
		return &MediaError{MediaRefused, "invalid media path"}
	case walkLinked:
		return &MediaError{MediaRefused, "linked media files are not served"}
	case walkOutside:
		return &MediaError{MediaRefused, "media file is outside its library"}
	default:
		return &MediaError{MediaRefused, "only individual audio files are served"}
	}
}

// ReadOnlyFile is what a verified media file can do: be read, sought, read at an
// offset (a zip keeps its directory at the end of its file) and closed. Nothing
// that changes it.
type ReadOnlyFile interface {
	io.ReadSeeker
	io.ReaderAt
	io.Closer
}

// MediaFile is a verified file opened for reading, and nothing more: no write,
// truncate or chmod is reachable from it, and the handle itself is O_RDONLY.
type MediaFile struct {
	ReadOnlyFile
	// Path is where the file is on the media PC, for the hub's own tools (the
	// ffprobe runner). It never goes into a response, a header or a log line.
	Path    string
	Size    int64
	ModTime time.Time
}

// ResolveMediaFile is the reading twin of ResolveRemovalFile: the same walk
// from a Storyteller path to a file on the media PC, with the extra refusals a
// reader needs (see walkRules), and the file opened once, here. The caller
// serves from that handle and never opens the path again.
//
// Only regular audio files are accepted: by extension, before the disk is
// touched, and as a regular file afterwards.
func ResolveMediaFile(roots []config.MediaRemovalRoot, service, remote string) (MediaFile, error) {
	return resolveFile(roots, service, remote, func(name string) bool {
		_, ok := AudioKindOf(name)
		return ok
	})
}

// ResolveEPUBFile is the same twin for a read-along edition, which is an EPUB:
// only the list of what may be opened differs. The hub reads its zip directory
// and its SMIL, and copies its non-audio entries, and never changes it.
func ResolveEPUBFile(roots []config.MediaRemovalRoot, service, remote string) (MediaFile, error) {
	return resolveFile(roots, service, remote, func(name string) bool {
		return strings.EqualFold(path.Ext(name), ".epub")
	})
}

func resolveFile(roots []config.MediaRemovalRoot, service, remote string, accepts func(string) bool) (MediaFile, error) {
	if !accepts(remote) {
		return MediaFile{}, mediaError(walkWrongKind)
	}
	file, fail := walkMediaPath(roots, service, remote, walkRules{strictNames: true})
	if fail != walkOK {
		return MediaFile{}, mediaError(fail)
	}
	return openWalked(file)
}

// openWalked opens what the walk checked, and proves that is what it opened.
// Between the walk and here someone who can write to the media folder could
// have swapped the file, or a folder above it, for a link to somewhere else;
// opening follows the link, so the handle is compared with what was checked.
func openWalked(checked walked) (MediaFile, error) {
	file, err := os.Open(checked.path)
	if err != nil {
		return MediaFile{}, &MediaError{MediaMissing, "a media file is unavailable"}
	}
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || !os.SameFile(info, checked.info) {
		_ = file.Close()
		return MediaFile{}, &MediaError{MediaRefused, "the file changed while it was being opened"}
	}
	return MediaFile{ReadOnlyFile: file, Path: checked.path, Size: info.Size(), ModTime: info.ModTime()}, nil
}

// ListMediaFolder names the audio files directly inside a mapped folder, in
// byte order. It exists for one question, which file stands for a book whose
// manifest names virtual files (a lone M4B), so names are all it gives back:
// each is then resolved with ResolveMediaFile like any other.
func ListMediaFolder(roots []config.MediaRemovalRoot, service, remote string) ([]string, error) {
	folder, fail := walkMediaPath(roots, service, remote, walkRules{strictNames: true, folder: true})
	if fail != walkOK {
		return nil, mediaError(fail)
	}
	entries, err := os.ReadDir(folder.path)
	if err != nil {
		return nil, &MediaError{MediaMissing, "a media folder is unavailable"}
	}
	names := []string{}
	for _, entry := range entries {
		name := entry.Name()
		if !entry.Type().IsRegular() || unsafePart(name, true) {
			continue
		}
		if _, ok := AudioKindOf(name); ok {
			names = append(names, name)
		}
	}
	sort.Strings(names)
	return names, nil
}

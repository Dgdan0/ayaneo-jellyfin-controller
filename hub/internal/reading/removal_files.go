package reading

import (
	"ayaneohub/internal/config"
	"errors"
	"os"
)

// RemovalFile is a single, verified regular file. Directories are never removed.
type RemovalFile struct {
	Path     string
	Size     int64
	Modified int64
	info     os.FileInfo
}

// ResolveRemovalFile is the deleting side of walkMediaPath; ResolveMediaFile is
// the reading side. Only the words differ, because these are shown to the
// person confirming a deletion.
func ResolveRemovalFile(roots []config.MediaRemovalRoot, service, remote string) (RemovalFile, error) {
	file, fail := walkMediaPath(roots, service, remote, walkRules{})
	if fail != walkOK {
		return RemovalFile{}, errors.New(removalWords(fail))
	}
	return RemovalFile{Path: file.path, Size: file.info.Size(), Modified: file.info.ModTime().UnixNano(), info: file.info}, nil
}

func removalWords(fail walkFail) string {
	switch fail {
	case walkBadPath:
		return "invalid media path"
	case walkUnmapped:
		return "server file mapping is not configured for this library"
	case walkBadMapping:
		return "invalid server file mapping"
	case walkDriveRoot:
		return "a drive root cannot be a deletion library"
	case walkNoRoot:
		return "library folder is unavailable"
	case walkMissing:
		return "a media file is unavailable"
	case walkLinked:
		return "linked media files cannot be deleted here"
	case walkOutside:
		return "media file is outside its library"
	default:
		return "only individual media files can be deleted"
	}
}

func (f RemovalFile) Unchanged() bool {
	info, err := os.Lstat(f.Path)
	return err == nil && info.Mode().IsRegular() && info.Size() == f.Size && info.ModTime().UnixNano() == f.Modified && os.SameFile(f.info, info)
}

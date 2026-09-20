package reading

import (
	"fmt"
	"net/url"
	"path"
	"regexp"
	"strings"
)

// MediaKind is the storage-level reading media classification. It stays
// independent of any upstream server's item types so a future migration does
// not alter canonical paths or identities.
type MediaKind string

const (
	MediaBook      MediaKind = "book"
	MediaAudiobook MediaKind = "audiobook"
	MediaComic     MediaKind = "comic"
	MediaManga     MediaKind = "manga"
)

var windowsDrivePath = regexp.MustCompile(`^[A-Za-z]:`)

func ParseMediaKind(value string) (MediaKind, error) {
	kind := MediaKind(strings.ToLower(strings.TrimSpace(value)))
	if CanonicalRoot(kind) == "" {
		return "", fmt.Errorf("unknown reading media kind %q", value)
	}
	return kind, nil
}

// CanonicalRoot returns the Jellyfin-friendly top-level directory name for a
// reading media kind. Manga remains separate operationally, but can be scanned
// by Jellyfin as another Books/Comics library.
func CanonicalRoot(kind MediaKind) string {
	switch kind {
	case MediaBook:
		return "Books"
	case MediaAudiobook:
		return "Audiobooks"
	case MediaComic:
		return "Comics"
	case MediaManga:
		return "Manga"
	default:
		return ""
	}
}

// NormalizeAssetPath validates a logical path relative to a canonical media
// root and returns its portable, slash-separated form. It intentionally does
// not join a host filesystem path; callers must select the root from MediaKind
// rather than accepting one from an API request.
func NormalizeAssetPath(kind MediaKind, value string) (string, error) {
	if CanonicalRoot(kind) == "" {
		return "", fmt.Errorf("unknown reading media kind %q", kind)
	}
	decoded, err := url.PathUnescape(strings.TrimSpace(value))
	if err != nil {
		return "", fmt.Errorf("invalid escaped asset path: %w", err)
	}
	if decoded == "" || strings.ContainsRune(decoded, '\x00') {
		return "", fmt.Errorf("asset path is empty or contains a null byte")
	}
	portable := strings.ReplaceAll(decoded, `\`, "/")
	if path.IsAbs(portable) || strings.HasPrefix(portable, "//") || windowsDrivePath.MatchString(portable) {
		return "", fmt.Errorf("asset path must be relative to %s", CanonicalRoot(kind))
	}

	parts := strings.Split(portable, "/")
	if len(parts) < 2 {
		return "", fmt.Errorf("asset must be inside its own title or series folder")
	}
	for _, part := range parts {
		if part == "" || part == "." || part == ".." {
			return "", fmt.Errorf("asset path contains an unsafe segment")
		}
	}
	cleaned := path.Clean(strings.Join(parts, "/"))
	if cleaned == "." || strings.HasPrefix(cleaned, "../") {
		return "", fmt.Errorf("asset path escapes the canonical root")
	}
	if !allowedAssetName(kind, path.Base(cleaned)) {
		return "", fmt.Errorf("%s is not an allowed %s asset", path.Base(cleaned), kind)
	}
	return cleaned, nil
}

func allowedAssetName(kind MediaKind, name string) bool {
	lower := strings.ToLower(name)
	if lower == "cover.jpg" || lower == "cover.jpeg" || lower == "cover.png" || lower == "cover.webp" ||
		lower == "folder.jpg" || lower == "folder.png" || lower == "metadata.opf" || lower == "content.opf" {
		return kind == MediaBook || kind == MediaAudiobook
	}
	if lower == "comicinfo.xml" {
		return kind == MediaComic || kind == MediaManga
	}
	ext := strings.ToLower(path.Ext(lower))
	switch kind {
	case MediaBook:
		return oneOf(ext, ".epub", ".pdf", ".azw", ".azw3", ".mobi")
	case MediaAudiobook:
		return oneOf(ext, ".m4b", ".m4a", ".mp3", ".mp4", ".aac", ".flac", ".ogg", ".opus")
	case MediaComic, MediaManga:
		return oneOf(ext, ".cbz", ".cbr", ".cb7", ".cbt", ".pdf", ".zip", ".rar", ".7z")
	default:
		return false
	}
}

func oneOf(value string, allowed ...string) bool {
	for _, candidate := range allowed {
		if value == candidate {
			return true
		}
	}
	return false
}

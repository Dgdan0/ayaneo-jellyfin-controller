package reading

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"

	"ayaneohub/internal/config"
)

// Our read-along (#66). Storyteller aligns per sentence and per chapter, which goes badly for a
// dramatized audiobook (6.65% of The Final Empire (Dramatized)'s sentences squeezed into a fraction
// of a second, a Prologue epigraph placed 47 minutes late) and writes no time per word. wordsync
// rebuilds the timing from Storyteller's own transcripts and writes it as a pack beside the library,
// one folder per Storyteller book uuid:
//
//	<uuid>/manifest.json   the edition it was built from (path, size, mtime), passes, retimed
//	<uuid>/sentence/<zip path of each SMIL>                 one <par> per spoken sentence
//	<uuid>/word/<zip path of each SMIL and each XHTML>      a <seq epub:textref="…#sid"> per sentence, a <par> per word
//
// A pack is an overlay and nothing more: its entries replace the edition's of the same name on the
// way out, everything else is copied as it was (the audio included), and Storyteller's files are
// only read. A pack that is missing, stale, failed, not re-timed, still being written or does not
// fit its edition is not used, and the hub serves exactly what it served before; deleting the folder
// restores that.

// The two sets a pack holds.
const (
	GranularitySentence = "sentence"
	GranularityWord     = "word"
)

// PackRemote is where the packs are, as Storyteller's container sees them: media_removal_roots maps
// it (with the rest of /derived) onto this PC.
const PackRemote = "/derived/readalong"

// Why a pack is not used, for the log. PackNone is the ordinary case, a book with no pack.
const (
	PackNone        = "none"
	PackUnreadable  = "unreadable"
	PackOtherBook   = "other_book"
	PackFailed      = "failed"
	PackNotRetimed  = "not_retimed"
	PackStale       = "stale"
	PackSettling    = "settling"
	PackUnexpected  = "unexpected_file"
	PackNoSet       = "no_set"
	PackUnmapped    = "unmapped_root"
	PackBadIdentity = "bad_uuid"
)

// What a pack may hold, and how recently it may have changed. Variables so a test can change them.
var (
	// maxWordPars is the narration a word overlay may hold: a word set is one <par> per spoken word,
	// 88k to 193k in the first three books, and a 50-hour book would pass the 200,000 that a sentence
	// set (and an edition of Storyteller's) is held to.
	maxWordPars = 1_000_000
	// packSettle: a pack with anything in it changed more recently than this is still being written
	// (wordsync writes in place, and validates after), so it is not used yet.
	packSettle = 10 * time.Minute
	// maxPackFiles is the most entries one set of a pack may replace.
	maxPackFiles = 20_000
	// maxPackManifest is the most a pack's manifest.json may be.
	maxPackManifest int64 = 64 << 10
)

// ErrOverlayMismatch is a pack that names a file its edition does not hold: built for another
// edition, or spelt otherwise. The edition is served as it is.
var ErrOverlayMismatch = errors.New("the read-along pack names a file the edition does not hold")

// PackManifest is a pack's manifest.json.
type PackManifest struct {
	Tool          string     `json:"tool"`
	Built         string     `json:"built"`
	UUID          string     `json:"uuid"`
	Title         string     `json:"title"`
	Source        PackSource `json:"source"`
	Granularities []string   `json:"granularities"`
	Retimed       bool       `json:"retimed"`
	Passes        bool       `json:"passes"`
}

// PackSource is the read-along edition a pack was built from, as wordsync saw it: its path in
// Storyteller's container, its size and its modification time in whole seconds.
type PackSource struct {
	Path  string `json:"path"`
	Size  int64  `json:"size"`
	Mtime int64  `json:"mtime"`
}

// Pack is a book's pack that holds for its edition as it is now: one Overlay per set it has.
type Pack struct {
	Manifest PackManifest
	// Skipped are the sets the manifest lists that cannot be used, by granularity, and why.
	Skipped  map[string]string
	overlays map[string]*Overlay
}

// Overlay returns the set of the granularity, or nil when the pack has none.
func (p *Pack) Overlay(granularity string) *Overlay {
	if p == nil {
		return nil
	}
	return p.overlays[granularity]
}

// Overlay is one set of a pack: the entries of the edition it replaces, by zip path.
type Overlay struct {
	Granularity string
	// Fingerprint names the pack and the set as they are on disk: its manifest and every file's
	// name, size and time. A copy, an alignment and a revision made with the overlay carry it, so a
	// pack built again is read again.
	Fingerprint string
	files       fs.FS
	names       map[string]string // zip path -> path inside files
	sizes       map[string]int64
	// newest is when the last of its files was written.
	newest time.Time
}

// NewOverlay is an overlay read from files, whose paths are the zip paths of the entries they
// replace (os.DirFS of a pack's set, or a test's fstest.MapFS). Only SMIL and text documents may
// be in it; anything else is a pack the hub does not understand.
func NewOverlay(granularity string, files fs.FS, salt string) (*Overlay, error) {
	overlay := &Overlay{Granularity: granularity, files: files, names: map[string]string{}, sizes: map[string]int64{}}
	digest := sha256.New()
	fmt.Fprintf(digest, "%s\x00%s\n", salt, granularity)
	err := fs.WalkDir(files, ".", func(name string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if name == "." {
			return nil
		}
		// A link or a device in a pack would let it stand for something it is not.
		if entry.Type()&(fs.ModeSymlink|fs.ModeIrregular|fs.ModeDevice|fs.ModeNamedPipe|fs.ModeSocket) != 0 {
			return fmt.Errorf("%w: %s is not a plain file", errPackUnexpected, name)
		}
		if entry.IsDir() {
			return nil
		}
		if !overlayKind(name) {
			return fmt.Errorf("%w: %s", errPackUnexpected, name)
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if info.Size() <= 0 || info.Size() > maxXMLBytes {
			return fmt.Errorf("%w: %s is %d bytes", errPackUnexpected, name, info.Size())
		}
		if len(overlay.names) >= maxPackFiles {
			return fmt.Errorf("%w: more than %d files", errPackUnexpected, maxPackFiles)
		}
		overlay.names[name] = name
		overlay.sizes[name] = info.Size()
		if info.ModTime().After(overlay.newest) {
			overlay.newest = info.ModTime()
		}
		fmt.Fprintf(digest, "%s\x00%d\x00%d\n", name, info.Size(), info.ModTime().UnixNano())
		return nil
	})
	if err != nil {
		return nil, err
	}
	if len(overlay.names) == 0 {
		return nil, fmt.Errorf("%w: the %s set is empty", errPackUnexpected, granularity)
	}
	overlay.Fingerprint = hex.EncodeToString(digest.Sum(nil))[:16]
	return overlay, nil
}

var errPackUnexpected = errors.New("the read-along pack holds something it should not")

// overlayKind: what a pack may replace, SMIL and text documents.
func overlayKind(name string) bool {
	switch strings.ToLower(path.Ext(name)) {
	case ".smil", ".xhtml", ".html", ".htm", ".xht":
		return true
	}
	return false
}

// Has says whether the overlay replaces the entry.
func (o *Overlay) Has(name string) bool {
	if o == nil {
		return false
	}
	_, ok := o.names[name]
	return ok
}

// Names are the entries it replaces, sorted.
func (o *Overlay) Names() []string {
	if o == nil {
		return nil
	}
	names := make([]string, 0, len(o.names))
	for name := range o.names {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

// size is what the overlay's entry holds, unpacked.
func (o *Overlay) size(name string) int64 { return o.sizes[name] }

// read is one entry of the overlay, within the cap any document of the narration is held to.
func (o *Overlay) read(name string) ([]byte, error) {
	inside, ok := o.names[name]
	if !ok {
		return nil, badf("a document the edition needs is not in it")
	}
	file, err := o.files.Open(inside)
	if err != nil {
		return nil, badf("a document of the read-along pack cannot be opened")
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, maxXMLBytes+1))
	if err != nil || int64(len(data)) > maxXMLBytes {
		return nil, badf("a document of the read-along pack cannot be read within its size")
	}
	return data, nil
}

// fits says whether every entry the overlay replaces is an entry of the archive: a pack built for
// another edition, or that spells a name otherwise, is not used at all, since a SMIL replaced
// without its text (or the other way round) names places that are not there.
func (o *Overlay) fits(has func(string) bool) error {
	for name := range o.names {
		if !has(name) {
			return ErrOverlayMismatch
		}
	}
	return nil
}

var packUUID = regexp.MustCompile(`^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$`)

// FindPack is the pack of the Storyteller book uuid for its read-along edition as it is now
// (edition, opened by the caller), or why there is none that holds: PackNone when the book has no
// pack, which is most books. A pack holds when its manifest says it passes and was re-timed, names
// this book, was built from this edition (the same size and modification time: a different one is
// Storyteller having aligned the book again), and nothing in it changed in the last packSettle.
// Its sets are listed here, and each must be SMIL and text documents only.
func FindPack(roots []config.MediaRemovalRoot, uuid string, edition MediaFile, now time.Time) (*Pack, string) {
	uuid = strings.TrimSpace(uuid)
	if !packUUID.MatchString(uuid) {
		return nil, PackBadIdentity
	}
	folder, fail := walkMediaPath(roots, "storyteller", PackRemote+"/"+uuid, walkRules{strictNames: true, folder: true})
	switch fail {
	case walkOK:
	case walkMissing, walkNoRoot:
		return nil, PackNone
	case walkUnmapped, walkBadMapping, walkDriveRoot:
		return nil, PackUnmapped
	default:
		return nil, PackUnreadable
	}
	return readPack(folder.path, uuid, edition, now)
}

// readPack is FindPack once the folder is found.
func readPack(dir, uuid string, edition MediaFile, now time.Time) (*Pack, string) {
	manifestPath := filepath.Join(dir, "manifest.json")
	info, err := os.Lstat(manifestPath)
	if err != nil {
		// A folder with no manifest is a pack being written for the first time.
		return nil, PackSettling
	}
	if !info.Mode().IsRegular() || info.Size() > maxPackManifest {
		return nil, PackUnreadable
	}
	data, err := os.ReadFile(manifestPath)
	if err != nil || int64(len(data)) > maxPackManifest {
		return nil, PackUnreadable
	}
	var manifest PackManifest
	if json.Unmarshal(data, &manifest) != nil {
		return nil, PackUnreadable
	}
	switch {
	case !strings.EqualFold(strings.TrimSpace(manifest.UUID), uuid):
		return nil, PackOtherBook
	case !manifest.Passes:
		return nil, PackFailed
	case !manifest.Retimed:
		return nil, PackNotRetimed
	case manifest.Source.Size != edition.Size || manifest.Source.Mtime != edition.ModTime.Unix():
		return nil, PackStale
	}
	newest := info.ModTime()
	pack := &Pack{Manifest: manifest, Skipped: map[string]string{}, overlays: map[string]*Overlay{}}
	salt := hex.EncodeToString(sha256Sum(data))
	// Each set holds or not on its own: a word set the hub cannot use (The Dark Forest's has overlays of 10 and
	// 11.6 MB, past the 4 MB any narration document may be) leaves the sentence set in use. Why one is left
	// out is in Skipped, for the log.
	failure := PackNoSet
	for _, granularity := range []string{GranularitySentence, GranularityWord} {
		if !listsGranularity(manifest, granularity) {
			continue
		}
		setDir := filepath.Join(dir, granularity)
		setInfo, err := os.Lstat(setDir)
		if err != nil || !setInfo.IsDir() {
			pack.Skipped[granularity] = PackNoSet
			continue
		}
		files := os.DirFS(setDir)
		overlay, err := NewOverlay(granularity, files, salt)
		if err != nil {
			failure = PackUnreadable
			if errors.Is(err, errPackUnexpected) {
				failure = PackUnexpected
			}
			pack.Skipped[granularity] = failure
			continue
		}
		if overlay.newest.After(newest) {
			newest = overlay.newest
		}
		pack.overlays[granularity] = overlay
	}
	if len(pack.overlays) == 0 {
		return nil, failure
	}
	if now.Sub(newest) < packSettle {
		return nil, PackSettling
	}
	return pack, ""
}

func listsGranularity(manifest PackManifest, granularity string) bool {
	for _, listed := range manifest.Granularities {
		if listed == granularity {
			return true
		}
	}
	return false
}

func sha256Sum(data []byte) []byte {
	sum := sha256.Sum256(data)
	return sum[:]
}

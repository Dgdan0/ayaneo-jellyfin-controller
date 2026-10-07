package reading

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	"image"
	"image/color"
	"image/png"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// FixtureAuthor and FixtureIdentifierPrefix mark the generated reading-lab
// books. They were real files on the reading servers for device tests (removed
// on 2026-10-03); if they are generated there again, the Hub keeps them out of
// library listings so they do not sit among real books.
const (
	FixtureAuthor           = "Lab Author"
	FixtureIdentifierPrefix = "urn:pocketds:fixture:"
)

// fixtureSeries are the series Kavita makes of the generated comic and manga
// archives ("Lab Comics (2026)", "Lab Manga"). Kavita's listings carry no
// writer to tell them apart, so they are known by name.
var fixtureSeries = []string{"lab comics", "lab manga"}

// IsFixtureSeries reports a Kavita series made from the reading-lab files.
func IsFixtureSeries(name string) bool {
	value := strings.ToLower(strings.TrimSpace(name))
	for _, fixture := range fixtureSeries {
		if value == fixture || strings.HasPrefix(value, fixture+" ") {
			return true
		}
	}
	return false
}

type FixtureRole string

const (
	FixtureEbook          FixtureRole = "ebook"
	FixtureReadaloud      FixtureRole = "readaloud"
	FixtureAudiobook      FixtureRole = "audiobook"
	FixtureComic          FixtureRole = "comic"
	FixtureManga          FixtureRole = "manga"
	FixturePDFText        FixtureRole = "pdf-text"
	FixturePDFImage       FixtureRole = "pdf-image"
	FixtureCorruptArchive FixtureRole = "corrupt-archive"
)

type FixtureAsset struct {
	Name   string      `json:"name"`
	Role   FixtureRole `json:"role"`
	Kind   MediaKind   `json:"kind"`
	Path   string      `json:"path"`
	SHA256 string      `json:"sha256"`
}

type FixtureManifest struct {
	SchemaVersion int            `json:"schemaVersion"`
	Assets        []FixtureAsset `json:"assets"`
}

type fixtureDefinition struct {
	name string
	role FixtureRole
	kind MediaKind
	path string
	data func() ([]byte, error)
}

// GenerateFixtureSet writes a small, deterministic, redistribution-safe media
// corpus. The content is synthetic: colored geometry, short original prose,
// and a generated one-second sine tone.
func GenerateFixtureSet(root string) (FixtureManifest, error) {
	definitions := []fixtureDefinition{
		{
			name: "The Clockwork Island ebook",
			role: FixtureEbook,
			kind: MediaBook,
			path: "Books/Lab Author/Lab Stories/01 - The Clockwork Island/The Clockwork Island.epub",
			data: func() ([]byte, error) {
				return buildEPUB(epubSpec{title: "The Clockwork Island", identifier: FixtureIdentifierPrefix + "clockwork-island", readaloud: false})
			},
		},
		{
			name: "The Readaloud Signal",
			role: FixtureReadaloud,
			kind: MediaBook,
			path: "Books/Lab Author/Lab Stories/02 - The Readaloud Signal/The Readaloud Signal.epub",
			data: func() ([]byte, error) {
				return buildEPUB(epubSpec{title: "The Readaloud Signal", identifier: FixtureIdentifierPrefix + "readaloud-signal", readaloud: true})
			},
		},
		{
			name: "The Readaloud Journey",
			role: FixtureReadaloud,
			kind: MediaBook,
			path: "Books/Lab Author/Lab Stories/05 - The Readaloud Journey/The Readaloud Journey.epub",
			data: func() ([]byte, error) {
				return buildEPUB(epubSpec{
					title: "The Readaloud Journey", identifier: FixtureIdentifierPrefix + "readaloud-journey",
					readaloud: true, timedSegments: 6,
				})
			},
		},
		{
			name: "The Clockwork Island audiobook",
			role: FixtureAudiobook,
			kind: MediaAudiobook,
			path: "Audiobooks/Lab Author/Lab Stories/01 - The Clockwork Island/The Clockwork Island.mp3",
			data: fixtureMP3,
		},
		{
			name: "Lab Comics issue 1",
			role: FixtureComic,
			kind: MediaComic,
			path: "Comics/Lab Comics (2026)/Lab Comics #001 (2026).cbz",
			data: func() ([]byte, error) { return buildCBZ(false) },
		},
		{
			name: "Lab Manga volume 1",
			role: FixtureManga,
			kind: MediaManga,
			path: "Manga/Lab Manga/Lab Manga - v01.cbz",
			data: func() ([]byte, error) { return buildCBZ(true) },
		},
		{
			name: "Searchable PDF",
			role: FixturePDFText,
			kind: MediaBook,
			path: "Books/Lab Author/Lab Stories/03 - The Searchable Map/The Searchable Map.pdf",
			data: func() ([]byte, error) { return buildPDF(true), nil },
		},
		{
			name: "Image-only PDF",
			role: FixturePDFImage,
			kind: MediaBook,
			path: "Books/Lab Author/Lab Stories/04 - The Picture Atlas/The Picture Atlas.pdf",
			data: func() ([]byte, error) { return buildPDF(false), nil },
		},
		{
			name: "Corrupt comic archive",
			role: FixtureCorruptArchive,
			kind: MediaComic,
			path: "Staging/Corrupt/corrupt.cbz",
			data: func() ([]byte, error) { return []byte("not a zip archive\n"), nil },
		},
	}

	manifest := FixtureManifest{SchemaVersion: 1}
	for _, definition := range definitions {
		data, err := definition.data()
		if err != nil {
			return FixtureManifest{}, fmt.Errorf("build %s: %w", definition.name, err)
		}
		fullPath := filepath.Join(root, filepath.FromSlash(definition.path))
		if err := os.MkdirAll(filepath.Dir(fullPath), 0o755); err != nil {
			return FixtureManifest{}, err
		}
		if err := os.WriteFile(fullPath, data, 0o644); err != nil {
			return FixtureManifest{}, err
		}
		digest := sha256.Sum256(data)
		manifest.Assets = append(manifest.Assets, FixtureAsset{
			Name: definition.name, Role: definition.role, Kind: definition.kind,
			Path: definition.path, SHA256: hex.EncodeToString(digest[:]),
		})
	}

	manifestBytes, err := json.MarshalIndent(manifest, "", "  ")
	if err != nil {
		return FixtureManifest{}, err
	}
	manifestBytes = append(manifestBytes, '\n')
	if err := os.WriteFile(filepath.Join(root, "fixture-manifest.json"), manifestBytes, 0o644); err != nil {
		return FixtureManifest{}, err
	}
	return manifest, nil
}

type epubSpec struct {
	title         string
	identifier    string
	readaloud     bool
	timedSegments int
}

func buildEPUB(spec epubSpec) ([]byte, error) {
	cover, err := fixturePNG(color.RGBA{R: 34, G: 111, B: 151, A: 255}, color.RGBA{R: 238, G: 196, B: 74, A: 255})
	if err != nil {
		return nil, err
	}
	manifest := `<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>` +
		`<item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>`
	var metadata strings.Builder
	var spine strings.Builder
	var navigation strings.Builder
	extra := map[string][]byte{}
	readaloudSentences := []string{
		"At dawn, the brass lighthouse answered the sea with one clear note.",
		"A copper bird lifted from the rail and followed the sound east.",
		"Below it, six quiet gears turned the sleeping island toward morning.",
		"Every window caught the light and passed it to the next house.",
		"The harbor bell replied, slower and deeper than the lighthouse.",
		"When the last echo faded, the clockwork island was awake.",
	}
	if !spec.readaloud {
		manifest += `<item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>`
		spine.WriteString(`<itemref idref="chapter"/>`)
		navigation.WriteString(`<li><a href="chapter.xhtml">Signal</a></li>`)
		extra["EPUB/chapter.xhtml"] = []byte(fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>%s</title></head><body><h1>%s</h1><p id="sentence1">%s</p></body></html>`, spec.title, spec.title, readaloudSentences[0]))
	} else {
		segments := spec.timedSegments
		if segments < 1 {
			segments = 1
		}
		fmt.Fprintf(&metadata, `<meta property="media:duration">0:00:%02d.000</meta>`, segments)
		for index := 0; index < segments; index++ {
			sentence := readaloudSentences[index%len(readaloudSentences)]
			chapterID := "chapter"
			chapterName := "chapter.xhtml"
			overlayID := "overlay"
			overlayName := "overlay.smil"
			if segments > 1 {
				chapterID = fmt.Sprintf("chapter%d", index+1)
				chapterName = fmt.Sprintf("chapter%d.xhtml", index+1)
				overlayID = fmt.Sprintf("overlay%d", index+1)
				overlayName = fmt.Sprintf("overlay%d.smil", index+1)
			}
			anchor := fmt.Sprintf("sentence%d", index+1)
			manifest += fmt.Sprintf(`<item id="%s" href="%s" media-type="application/xhtml+xml" media-overlay="%s"/><item id="%s" href="%s" media-type="application/smil+xml"/>`, chapterID, chapterName, overlayID, overlayID, overlayName)
			fmt.Fprintf(&metadata, `<meta property="media:duration" refines="#%s">0:00:01.000</meta>`, overlayID)
			fmt.Fprintf(&spine, `<itemref idref="%s"/>`, chapterID)
			fmt.Fprintf(&navigation, `<li><a href="%s">Step %d</a></li>`, chapterName, index+1)
			extra["EPUB/"+chapterName] = []byte(fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>%s — Step %d</title></head><body><h1>%s</h1><p id="%s">%s</p></body></html>`, spec.title, index+1, spec.title, anchor, sentence))
			extra["EPUB/"+overlayName] = []byte(fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?><smil xmlns="http://www.w3.org/ns/SMIL" version="3.0"><body><seq epub:textref="%s" xmlns:epub="http://www.idpf.org/2007/ops"><par><text src="%s#%s"/><audio src="narration.mp3" clipBegin="0s" clipEnd="1s"/></par></seq></body></smil>`, chapterName, chapterName, anchor))
		}
		manifest += `<item id="audio" href="narration.mp3" media-type="audio/mpeg"/>`
		audio, err := fixtureMP3()
		if err != nil {
			return nil, err
		}
		extra["EPUB/narration.mp3"] = audio
	}

	files := map[string][]byte{
		"META-INF/container.xml": []byte(`<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`),
		"EPUB/package.opf":       []byte(fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">%s</dc:identifier><dc:title>%s</dc:title><dc:creator>Lab Author</dc:creator><dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>%s</metadata><manifest>%s</manifest><spine>%s</spine></package>`, spec.identifier, spec.title, metadata.String(), manifest, spine.String())),
		"EPUB/nav.xhtml":         []byte(fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>%s</ol></nav></body></html>`, navigation.String())),
		"EPUB/cover.png":         cover,
	}
	for name, data := range extra {
		files[name] = data
	}
	return deterministicZip("application/epub+zip", files)
}

func buildCBZ(rtl bool) ([]byte, error) {
	pageOne, err := fixturePNG(color.RGBA{R: 32, G: 190, B: 165, A: 255}, color.RGBA{R: 22, G: 28, B: 45, A: 255})
	if err != nil {
		return nil, err
	}
	pageTwo, err := fixturePNG(color.RGBA{R: 238, G: 89, B: 93, A: 255}, color.RGBA{R: 250, G: 211, B: 90, A: 255})
	if err != nil {
		return nil, err
	}
	series, title, manga := "Lab Comics", "The First Signal", "No"
	if rtl {
		series, title, manga = "Lab Manga", "The Rightward Signal", "YesAndRightToLeft"
	}
	info := fmt.Sprintf(`<?xml version="1.0" encoding="utf-8"?><ComicInfo><Title>%s</Title><Series>%s</Series><Number>1</Number><Volume>1</Volume><Year>2026</Year><Writer>Lab Author</Writer><LanguageISO>en</LanguageISO><Manga>%s</Manga><Pages><Page Image="0" Type="FrontCover"/><Page Image="1"/></Pages></ComicInfo>`, title, series, manga)
	return deterministicZip("", map[string][]byte{
		"001.png":       pageOne,
		"002.png":       pageTwo,
		"ComicInfo.xml": []byte(info),
	})
}

func deterministicZip(mimetype string, files map[string][]byte) ([]byte, error) {
	var output bytes.Buffer
	writer := zip.NewWriter(&output)
	modified := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	if mimetype != "" {
		header := &zip.FileHeader{Name: "mimetype", Method: zip.Store}
		entry, err := writer.CreateHeader(header)
		if err != nil {
			return nil, err
		}
		if _, err := io.WriteString(entry, mimetype); err != nil {
			return nil, err
		}
	}
	names := make([]string, 0, len(files))
	for name := range files {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		header := &zip.FileHeader{Name: name, Method: zip.Deflate}
		header.SetModTime(modified)
		entry, err := writer.CreateHeader(header)
		if err != nil {
			return nil, err
		}
		if _, err := entry.Write(files[name]); err != nil {
			return nil, err
		}
	}
	if err := writer.Close(); err != nil {
		return nil, err
	}
	return output.Bytes(), nil
}

func fixturePNG(background, accent color.RGBA) ([]byte, error) {
	canvas := image.NewRGBA(image.Rect(0, 0, 360, 540))
	for y := 0; y < 540; y++ {
		for x := 0; x < 360; x++ {
			canvas.SetRGBA(x, y, background)
		}
	}
	for y := 60; y < 480; y++ {
		for x := 45; x < 315; x++ {
			if (x/30+y/45)%2 == 0 {
				canvas.SetRGBA(x, y, accent)
			}
		}
	}
	var output bytes.Buffer
	if err := png.Encode(&output, canvas); err != nil {
		return nil, err
	}
	return output.Bytes(), nil
}

func buildPDF(withText bool) []byte {
	objects := []string{
		`<< /Type /Catalog /Pages 2 0 R >>`,
		`<< /Type /Pages /Kids [3 0 R] /Count 1 >>`,
	}
	content := "0.15 0.7 0.65 rg 72 420 468 240 re f\n0.95 0.75 0.2 rg 120 500 372 80 re f\n"
	resources := `<< >>`
	if withText {
		content += "BT /F1 24 Tf 72 720 Td (The Searchable Map) Tj ET\nBT /F1 14 Tf 72 690 Td (Pocket DS generated fixture) Tj ET\n"
		resources = `<< /Font << /F1 5 0 R >> >>`
	}
	objects = append(objects,
		fmt.Sprintf(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources %s /Contents 4 0 R >>`, resources),
		fmt.Sprintf("<< /Length %d >>\nstream\n%sendstream", len(content), content),
	)
	if withText {
		objects = append(objects, `<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>`)
	}
	var output bytes.Buffer
	output.WriteString("%PDF-1.4\n%generated\n")
	offsets := make([]int, len(objects)+1)
	for index, object := range objects {
		offsets[index+1] = output.Len()
		fmt.Fprintf(&output, "%d 0 obj\n%s\nendobj\n", index+1, object)
	}
	xref := output.Len()
	fmt.Fprintf(&output, "xref\n0 %d\n0000000000 65535 f \n", len(objects)+1)
	for index := 1; index <= len(objects); index++ {
		fmt.Fprintf(&output, "%010d 00000 n \n", offsets[index])
	}
	fmt.Fprintf(&output, "trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n", len(objects)+1, xref)
	return output.Bytes()
}

func fixtureMP3() ([]byte, error) {
	audio, err := base64.StdEncoding.DecodeString(fixtureMP3Base64)
	if err != nil {
		return nil, err
	}
	return retagFixtureMP3(audio, map[string]string{
		"TIT2": "The Clockwork Island",
		"TPE1": "Lab Author",
		"TALB": "The Clockwork Island",
	})
}

func retagFixtureMP3(audio []byte, fields map[string]string) ([]byte, error) {
	if len(audio) < 10 || string(audio[:3]) != "ID3" {
		return nil, fmt.Errorf("fixture MP3 has no ID3 header")
	}
	oldTagSize := decodeSynchsafe(audio[6:10])
	audioStart := 10 + oldTagSize
	if audioStart > len(audio) {
		return nil, fmt.Errorf("fixture MP3 ID3 size exceeds file length")
	}

	var frames bytes.Buffer
	ids := []string{"TIT2", "TPE1", "TALB"}
	if fields["TRCK"] != "" {
		ids = append(ids, "TRCK")
	}
	for _, id := range ids {
		textValue := fields[id]
		payload := append([]byte{3}, []byte(textValue)...)
		frames.WriteString(id)
		frames.Write(encodeSynchsafe(len(payload)))
		frames.Write([]byte{0, 0})
		frames.Write(payload)
	}

	var tagged bytes.Buffer
	tagged.Write([]byte{'I', 'D', '3', 4, 0, 0})
	tagged.Write(encodeSynchsafe(frames.Len()))
	tagged.Write(frames.Bytes())
	tagged.Write(audio[audioStart:])
	return tagged.Bytes(), nil
}

func decodeSynchsafe(value []byte) int {
	return int(value[0]&0x7f)<<21 | int(value[1]&0x7f)<<14 | int(value[2]&0x7f)<<7 | int(value[3]&0x7f)
}

func encodeSynchsafe(value int) []byte {
	return []byte{byte(value >> 21 & 0x7f), byte(value >> 14 & 0x7f), byte(value >> 7 & 0x7f), byte(value & 0x7f)}
}

// AudiobookFixtureFile is one generated audio file of an audiobook folder.
type AudiobookFixtureFile struct {
	Name string
	// Track is the number the file's own tags give it; 0 when it has none.
	Track int
	Size  int64
}

// AudiobookFixture describes a generated audiobook folder.
type AudiobookFixture struct {
	Dir      string // the folder on this machine
	Relative string // the same, slash-separated, relative to the root it was generated under
	// Files are in the order Storyteller's manifest would list them, which is
	// not always the story's.
	Files []AudiobookFixtureFile
	// Others are files in the folder that are not audio.
	Others []string
}

// GenerateTrackedAudiobook writes the folder of a book split into five MP3s
// whose names do not sort in the story's order, as Dark Matter's did: the file
// with no suffix is its first track by its tags and sorts after the others. The
// names are the kind that break paths (spaces, brackets, a percent sign, an
// ampersand, a hash, an apostrophe, accents, Hebrew, an upper-case extension)
// and every one is a legal Windows name. The audio in each is the lab's
// one-second narration, retagged so that no two files have the same bytes.
func GenerateTrackedAudiobook(root string) (AudiobookFixture, error) {
	const title = "Fixture Odyssey"
	// The manifest order is a plausible localeCompare one, declared rather than
	// computed: a test needs it fixed, not faithful to ICU.
	listed := []struct {
		name  string
		track int
	}{
		{"Part 5 - 100% Pure & Co., It's #5 [Ünïcode] פרק.mp3", 5},
		{"Fixture Odyssey (1).mp3", 2},
		{"Fixture Odyssey (2).mp3", 3},
		{"Fixture Odyssey (3).MP3", 4},
		{"Fixture Odyssey.mp3", 1},
	}
	book, err := newAudiobookFolder(root, "audiobooks/"+title)
	if err != nil {
		return AudiobookFixture{}, err
	}
	base, err := base64.StdEncoding.DecodeString(fixtureMP3Base64)
	if err != nil {
		return AudiobookFixture{}, err
	}
	for _, file := range listed {
		audio, err := retagFixtureMP3(base, map[string]string{
			"TIT2": fmt.Sprintf("%s, part %d", title, file.track),
			"TPE1": "Fixture Author",
			"TALB": title,
			"TRCK": fmt.Sprintf("%d/%d", file.track, len(listed)),
		})
		if err != nil {
			return AudiobookFixture{}, err
		}
		if err := book.write(file.name, audio); err != nil {
			return AudiobookFixture{}, err
		}
		book.Files = append(book.Files, AudiobookFixtureFile{Name: file.name, Track: file.track, Size: int64(len(audio))})
	}
	return book.AudiobookFixture, nil
}

// GenerateM4BAudiobook writes the folder of a book that is one M4B, beside a
// cover and a note. Storyteller reads a folder with exactly one .m4b as that
// file's chapters and ignores the rest. The M4B's bytes are stored, not audio: a
// box header and a body of a known pattern, so a test can check any byte range
// the hub serves without decoding anything.
func GenerateM4BAudiobook(root string) (AudiobookFixture, error) {
	book, err := newAudiobookFolder(root, "audiobooks/Fixture Chapters")
	if err != nil {
		return AudiobookFixture{}, err
	}
	m4b := fixtureM4B(96 << 10)
	if err := book.write("Fixture Chapters.m4b", m4b); err != nil {
		return AudiobookFixture{}, err
	}
	book.Files = append(book.Files, AudiobookFixtureFile{Name: "Fixture Chapters.m4b", Size: int64(len(m4b))})
	for name, body := range map[string]string{
		"cover.jpg": "\xff\xd8\xff\xe0 a cover that is not an image",
		"notes.txt": "A folder Storyteller reads as one audiobook.\n",
	} {
		if err := book.write(name, []byte(body)); err != nil {
			return AudiobookFixture{}, err
		}
		book.Others = append(book.Others, name)
	}
	sort.Strings(book.Others)
	return book.AudiobookFixture, nil
}

type audiobookFolder struct {
	AudiobookFixture
}

func newAudiobookFolder(root, relative string) (*audiobookFolder, error) {
	dir := filepath.Join(root, filepath.FromSlash(relative))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	return &audiobookFolder{AudiobookFixture{Dir: dir, Relative: relative}}, nil
}

func (f *audiobookFolder) write(name string, data []byte) error {
	return os.WriteFile(filepath.Join(f.Dir, name), data, 0o644)
}

// fixtureM4B is an ftyp box naming the brand and an mdat box that fills the rest
// of size bytes with a pattern in which no two nearby offsets agree.
func fixtureM4B(size int) []byte {
	brands := []byte("M4B \x00\x00\x00\x00M4B mp42isom")
	var out bytes.Buffer
	_ = binary.Write(&out, binary.BigEndian, uint32(8+len(brands)))
	out.WriteString("ftyp")
	out.Write(brands)
	body := size - out.Len() - 8
	_ = binary.Write(&out, binary.BigEndian, uint32(8+body))
	out.WriteString("mdat")
	for i := 0; i < body; i++ {
		out.WriteByte(byte(i*7 + i/251))
	}
	return out.Bytes()
}

// FixtureNarration is one narrated source file of an aligned book as Storyteller
// cuts it: a source over its maximum track length (two hours) is split into
// chunks, each its own audio file in the edition.
type FixtureNarration struct {
	// ChunkMs is each chunk's narrated length: where its last sentence ends.
	ChunkMs []int64
	// Sentences is how many sentences each chunk narrates.
	Sentences []int
	// Chapters says how the narration's sentences, in the order they are spoken
	// across its chunks, are divided among the edition's text documents: each
	// number is how many sentences the next chapter holds, and together they are
	// all of them. A chapter may begin in one chunk and end in the next, as the
	// real ones do. Empty is one chapter for the whole narration.
	Chapters []int
}

type AlignedEPUBOptions struct {
	Narrations []FixtureNarration
	// PackageDir is the folder the package document lives in ("OEBPS"); empty puts
	// it at the root of the archive. Every path in the SMIL is relative to it.
	PackageDir string
	// AudioBytes is the size of each stored audio entry: filler, not audio.
	AudioBytes int
	// SpineOrder is the order the package lists its chapters in, as indexes into
	// them in the order they are spoken (the first chapter of the first narration
	// is 0). Empty is the order they are spoken in. A book's text and its
	// narration need not agree: a heading that sits among the front matter may be
	// spoken in the middle of the audio.
	SpineOrder []int
	// Layout names the files; its zero value is the plain one.
	Layout AlignedLayout
	// Contents is the table of contents the edition lists, nested as written. Empty
	// is every chapter once, in the order the package lists them, titled "Part N" and
	// all at one level.
	Contents []FixtureContent
	// ContentsIn says which document holds it: the EPUB 3 navigation document (the
	// zero value), an NCX alone, as an EPUB 2 book has, or both.
	ContentsIn FixtureContentsIn
	// Documents are text documents that nothing narrates: a cover, a copyright page, a
	// chapter's heading that is only a picture.
	Documents []FixtureDocument
	// Anchors are ids on elements that carry no narration (a heading's id), for a
	// contents entry to point at.
	Anchors []FixtureAnchor
}

// FixtureContent is one entry of a generated edition's table of contents. It
// points into the text document of chapter Chapter (the first spoken is 1) or, when
// that is 0, into the Document of that name, at Fragment when it has one.
type FixtureContent struct {
	Title    string
	Chapter  int
	Document string
	Fragment string
	Children []FixtureContent
}

// FixtureContentsIn is where a generated edition keeps its table of contents.
type FixtureContentsIn int

const (
	// ContentsInNav is an EPUB 3 navigation document.
	ContentsInNav FixtureContentsIn = iota
	// ContentsInNCX is an NCX and no navigation document.
	ContentsInNCX
	// ContentsInBoth is both; the NCX's titles begin "NCX " so that a test can tell
	// which of the two a reader took.
	ContentsInBoth
)

// FixtureDocument is a text document that is not narrated, listed in the package's
// reading order just ahead of the chapter numbered Before (0 puts it ahead of every
// chapter, -1 after the last).
type FixtureDocument struct {
	Name   string
	Before int
}

// FixtureAnchor is an empty element with an id, put in chapter Chapter just ahead of
// its sentence Before (the first is 0).
type FixtureAnchor struct {
	ID      string
	Chapter int
	Before  int
}

// AlignedLayout is where an edition keeps its files and what it calls them. The
// zero value is text/partNNNN.xhtml, smil/partNNNN.smil and Audio/NNNNN-CCCCC.mp3.
type AlignedLayout struct {
	// AudioExt is the audio files' extension with its dot (".mp4"); the package
	// says audio/mp4 for it and audio/mpeg otherwise.
	AudioExt string
	// OverlayDir and TextDir are folders inside the package folder, and TextAtRoot
	// puts the text documents in the package folder itself, as a Calibre book does.
	OverlayDir string
	TextDir    string
	TextAtRoot bool
	// TextExt is the text documents' extension with its dot.
	TextExt string
	// ChapterName is a chapter's file name before its extension, from its number
	// (the first chapter spoken is 1).
	ChapterName func(chapter int) string
	// EncodedRefs writes the references in the package and the overlays as URL
	// references (a space as %20, a bracket as %5B), where the default writes the
	// name as it is. The names in the archive are the same either way.
	EncodedRefs bool
}

// FixturePar is one narrated sentence, as the SMIL says it, in millisecond
// terms: the truth a reader of the edition is tested against.
type FixturePar struct {
	Text     string // zip path of the text document
	Fragment string
	Audio    string // zip path of the audio file
	BeginMs  int64
	EndMs    int64
}

type FixtureChunk struct {
	Entry    string
	Source   int
	Chunk    int
	LengthMs int64
}

type AlignedEPUBFixture struct {
	Path    string
	Package string // zip path of the package document
	Pars    []FixturePar
	Audio   []string
	Chunks  []FixtureChunk
}

// fixtureChapter is one text document and its overlay while an edition is built.
type fixtureChapter struct {
	number            int
	textHref          string // inside the package folder
	smilHref          string
	smil, body        strings.Builder
	sentences, wanted int
}

// GenerateAlignedEPUB writes a read-along edition shaped as Storyteller's are
// (audio stored, files named NNNNN-CCCCC, one SMIL per chapter with one <par> per
// sentence, gapless) and says exactly what it holds. The same moment is written
// in every way a clock value can be: 12.500s, 0:00:12.500, 00:12.500, 12500ms,
// 0.208333min, 0.003472h, a bare number and npt=.
func GenerateAlignedEPUB(path string, options AlignedEPUBOptions) (AlignedEPUBFixture, error) {
	dir := options.PackageDir
	rel := func(name string) string { // a path inside the package folder
		if dir == "" {
			return name
		}
		return dir + "/" + name
	}
	layout := options.Layout
	if layout.AudioExt == "" {
		layout.AudioExt = ".mp3"
	}
	audioType := "audio/mpeg"
	if layout.AudioExt == ".mp4" || layout.AudioExt == ".m4a" || layout.AudioExt == ".m4b" {
		audioType = "audio/mp4"
	}
	if layout.OverlayDir == "" {
		layout.OverlayDir = "smil"
	}
	if layout.TextDir == "" && !layout.TextAtRoot {
		layout.TextDir = "text"
	}
	if layout.TextExt == "" {
		layout.TextExt = ".xhtml"
	}
	if layout.ChapterName == nil {
		layout.ChapterName = func(chapter int) string { return fmt.Sprintf("part%04d", chapter) }
	}
	// ref writes a path as a reference to it from a document in another folder.
	ref := func(href string) string {
		if !layout.EncodedRefs {
			return href
		}
		parts := strings.Split(href, "/")
		for i, part := range parts {
			parts[i] = url.PathEscape(part)
		}
		return strings.Join(parts, "/")
	}

	fixture := AlignedEPUBFixture{Path: path, Package: rel("content.opf")}
	files := []zipFileSpec{}
	var manifest strings.Builder
	if options.ContentsIn != ContentsInNCX {
		manifest.WriteString(`<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>`)
	}
	if options.ContentsIn != ContentsInNav {
		manifest.WriteString(`<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>`)
	}
	for i, document := range options.Documents {
		fmt.Fprintf(&manifest, `<item id="doc%d" href="%s" media-type="application/xhtml+xml"/>`, i, ref(document.Name))
	}
	parIndex := 0
	var chapters []*fixtureChapter
	for sourceIndex, narration := range options.Narrations {
		source := sourceIndex + 1
		sizes := narration.Chapters
		if len(sizes) == 0 {
			total := 0
			for _, count := range narration.Sentences {
				total += count
			}
			sizes = []int{total}
		}
		var current *fixtureChapter
		nextChapter := 0
		var made []*fixtureChapter
		for chunkIndex, length := range narration.ChunkMs {
			chunk := chunkIndex + 1
			audioHref := fmt.Sprintf("Audio/%05d-%05d%s", source, chunk, layout.AudioExt)
			audioEntry := rel(audioHref)
			count := narration.Sentences[chunkIndex]
			step := length / int64(count)
			fixture.Audio = append(fixture.Audio, audioEntry)
			fixture.Chunks = append(fixture.Chunks, FixtureChunk{Entry: audioEntry, Source: source, Chunk: chunk, LengthMs: length})
			fmt.Fprintf(&manifest, `<item id="au%d-%d" href="%s" media-type="%s"/>`, source, chunk, ref(audioHref), audioType)
			filler := make([]byte, options.AudioBytes)
			for i := range filler {
				filler[i] = byte(i*13 + source*7 + chunk)
			}
			files = append(files, zipFileSpec{name: audioEntry, data: filler, store: true})
			for k := 0; k < count; k++ {
				begin, end := int64(k)*step, int64(k+1)*step
				if k == count-1 {
					end = length
				}
				if current == nil || current.sentences == current.wanted {
					if nextChapter >= len(sizes) {
						return AlignedEPUBFixture{}, fmt.Errorf("the chapters of narration %d hold fewer sentences than it narrates", source)
					}
					current = &fixtureChapter{number: len(chapters) + len(made) + 1, wanted: sizes[nextChapter]}
					base := layout.ChapterName(current.number)
					if layout.TextAtRoot {
						current.textHref = base + layout.TextExt
					} else {
						current.textHref = layout.TextDir + "/" + base + layout.TextExt
					}
					current.smilHref = layout.OverlayDir + "/" + base + ".smil"
					made = append(made, current)
					nextChapter++
				}
				fragment := fmt.Sprintf("id%d-s%d", current.number, current.sentences)
				current.sentences++
				for _, anchor := range options.Anchors {
					if anchor.Chapter == current.number && anchor.Before == current.sentences-1 {
						fmt.Fprintf(&current.body, `<a id="%s"/>`, anchor.ID)
					}
				}
				fmt.Fprintf(&current.body, `<span id="%s">Sentence %d of part %d.</span> `, fragment, current.sentences, current.number)
				// From the overlay's folder to the text and to the audio.
				fmt.Fprintf(&current.smil, `<par id="p%d"><text src="../%s#%s"/><audio src="../%s" clipBegin="%s" clipEnd="%s"/></par>`,
					parIndex, ref(current.textHref), fragment, ref(audioHref), fixtureClock(begin, parIndex), fixtureClock(end, parIndex+3))
				parIndex++
				fixture.Pars = append(fixture.Pars, FixturePar{Text: rel(current.textHref), Fragment: fragment, Audio: audioEntry, BeginMs: begin, EndMs: end})
			}
		}
		if nextChapter != len(sizes) || (current != nil && current.sentences != current.wanted) {
			return AlignedEPUBFixture{}, fmt.Errorf("the chapters of narration %d hold more sentences than it narrates", source)
		}
		for _, chapter := range made {
			files = append(files,
				zipFileSpec{name: rel(chapter.textHref), data: []byte(fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Part %d</title></head><body><p>%s</p></body></html>`, chapter.number, chapter.body.String()))},
				zipFileSpec{name: rel(chapter.smilHref), data: []byte(fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?><smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops" version="3.0"><body><seq id="s%d" epub:textref="../%s" epub:type="bodymatter chapter">%s</seq></body></smil>`, chapter.number, ref(chapter.textHref), chapter.smil.String()))},
			)
			fmt.Fprintf(&manifest, `<item id="ch%d" href="%s" media-type="application/xhtml+xml" media-overlay="ov%d"/><item id="ov%d" href="%s" media-type="application/smil+xml"/>`,
				chapter.number, ref(chapter.textHref), chapter.number, chapter.number, ref(chapter.smilHref))
		}
		chapters = append(chapters, made...)
	}

	order := options.SpineOrder
	if len(order) == 0 {
		order = make([]int, len(chapters))
		for i := range order {
			order[i] = i
		}
	}
	if len(order) != len(chapters) {
		return AlignedEPUBFixture{}, fmt.Errorf("the spine lists %d chapters of %d", len(order), len(chapters))
	}
	var spine strings.Builder
	// A document that nothing narrates sits just ahead of the chapter it names.
	documentsBefore := func(number int) {
		for i, document := range options.Documents {
			if document.Before == number {
				fmt.Fprintf(&spine, `<itemref idref="doc%d"/>`, i)
			}
		}
	}
	documentsBefore(0)
	var everyChapter []FixtureContent
	for _, index := range order {
		if index < 0 || index >= len(chapters) {
			return AlignedEPUBFixture{}, fmt.Errorf("the spine names chapter %d of %d", index, len(chapters))
		}
		chapter := chapters[index]
		documentsBefore(chapter.number)
		fmt.Fprintf(&spine, `<itemref idref="ch%d"/>`, chapter.number)
		everyChapter = append(everyChapter, FixtureContent{Title: fmt.Sprintf("Part %d", chapter.number), Chapter: chapter.number})
	}
	documentsBefore(-1)

	// The contents: where an entry points is written from the document it is in, which
	// is the package folder for the navigation document and the NCX alike.
	contents := options.Contents
	if len(contents) == 0 {
		contents = everyChapter
	}
	var contentsErr error
	href := func(entry FixtureContent) string {
		target := ""
		switch {
		case entry.Chapter > 0 && entry.Chapter <= len(chapters):
			target = ref(chapters[entry.Chapter-1].textHref)
		case entry.Chapter == 0 && entry.Document != "" && fixtureHasDocument(options.Documents, entry.Document):
			target = ref(entry.Document)
		default:
			contentsErr = fmt.Errorf("the contents entry %q points at nothing", entry.Title)
		}
		if entry.Fragment != "" {
			target += "#" + entry.Fragment
		}
		return target
	}
	navList := fixtureNavList(contents, href)
	ncxPoints := fixtureNCXPoints(contents, href, "")
	if options.ContentsIn == ContentsInBoth {
		ncxPoints = fixtureNCXPoints(contents, href, "NCX ")
	}
	if contentsErr != nil {
		return AlignedEPUBFixture{}, contentsErr
	}
	spineElement := "<spine>"
	if options.ContentsIn != ContentsInNav {
		spineElement = `<spine toc="ncx">`
	}

	container := fmt.Sprintf(`<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="%s" media-type="application/oebps-package+xml"/></rootfiles></container>`, fixture.Package)
	pack := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">%saligned</dc:identifier><dc:title>Aligned fixture</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest>%s</manifest>%s%s</spine></package>`, FixtureIdentifierPrefix, manifest.String(), spineElement, spine.String())
	head := []zipFileSpec{
		{name: "META-INF/container.xml", data: []byte(container)},
		{name: fixture.Package, data: []byte(pack)},
	}
	if options.ContentsIn != ContentsInNCX {
		nav := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc">%s</nav></body></html>`, navList)
		head = append(head, zipFileSpec{name: rel("nav.xhtml"), data: []byte(nav)})
	}
	if options.ContentsIn != ContentsInNav {
		ncx := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="%saligned"/></head><docTitle><text>Aligned fixture</text></docTitle><navMap>%s</navMap></ncx>`, FixtureIdentifierPrefix, ncxPoints)
		head = append(head, zipFileSpec{name: rel("toc.ncx"), data: []byte(ncx)})
	}
	for _, document := range options.Documents {
		head = append(head, zipFileSpec{name: rel(document.Name), data: []byte(fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>%s</title></head><body><p>%s</p></body></html>`, html.EscapeString(document.Name), html.EscapeString(document.Name)))})
	}
	data, err := writeZip("application/epub+zip", append(head, files...))
	if err != nil {
		return AlignedEPUBFixture{}, err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return AlignedEPUBFixture{}, err
	}
	return fixture, os.WriteFile(path, data, 0o644)
}

// fixtureNavList writes entries as the nested lists of a navigation document.
func fixtureNavList(entries []FixtureContent, href func(FixtureContent) string) string {
	if len(entries) == 0 {
		return ""
	}
	var out strings.Builder
	out.WriteString("<ol>")
	for _, entry := range entries {
		fmt.Fprintf(&out, `<li><a href="%s">%s</a>%s</li>`, href(entry), html.EscapeString(entry.Title), fixtureNavList(entry.Children, href))
	}
	out.WriteString("</ol>")
	return out.String()
}

// fixtureNCXPoints writes entries as the navPoints of an NCX, each parent ahead of
// the points inside it and numbered in the order they are written.
func fixtureNCXPoints(entries []FixtureContent, href func(FixtureContent) string, titlePrefix string) string {
	order := 0
	var write func(entries []FixtureContent) string
	write = func(entries []FixtureContent) string {
		var out strings.Builder
		for _, entry := range entries {
			order++
			number := order
			target := href(entry)
			fmt.Fprintf(&out, `<navPoint id="np%d" playOrder="%d"><navLabel><text>%s</text></navLabel><content src="%s"/>`, number, number, html.EscapeString(titlePrefix+entry.Title), target)
			out.WriteString(write(entry.Children))
			out.WriteString("</navPoint>")
		}
		return out.String()
	}
	return write(entries)
}

func fixtureHasDocument(documents []FixtureDocument, name string) bool {
	for _, document := range documents {
		if document.Name == name {
			return true
		}
	}
	return false
}

// fixtureClock writes a moment as a SMIL clock value, in a form chosen by n.
func fixtureClock(ms int64, n int) string {
	seconds := float64(ms) / 1000
	h, m, s := ms/3_600_000, (ms/60_000)%60, float64(ms%60_000)/1000
	switch n % 8 {
	case 0:
		return fmt.Sprintf("%.3fs", seconds)
	case 1:
		return fmt.Sprintf("%d:%02d:%06.3f", h, m, s)
	case 2:
		return fmt.Sprintf("%02d:%06.3f", ms/60_000, s)
	case 3:
		return fmt.Sprintf("%dms", ms)
	case 4:
		return fmt.Sprintf("%.9fmin", float64(ms)/60_000)
	case 5:
		return fmt.Sprintf("%.12fh", float64(ms)/3_600_000)
	case 6:
		return fmt.Sprintf("%.3f", seconds)
	default:
		return fmt.Sprintf("npt=%.3fs", seconds)
	}
}

type zipFileSpec struct {
	name  string
	data  []byte
	store bool // kept as is rather than compressed
}

// writeZip writes an archive with a stored mimetype first, then the files in the
// order given, each with a fixed time so the bytes are the same every run.
func writeZip(mimetype string, files []zipFileSpec) ([]byte, error) {
	var output bytes.Buffer
	writer := zip.NewWriter(&output)
	modified := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	entry, err := writer.CreateHeader(&zip.FileHeader{Name: "mimetype", Method: zip.Store})
	if err != nil {
		return nil, err
	}
	if _, err := io.WriteString(entry, mimetype); err != nil {
		return nil, err
	}
	for _, file := range files {
		header := &zip.FileHeader{Name: file.name, Method: zip.Deflate}
		if file.store {
			header.Method = zip.Store
		}
		header.SetModTime(modified)
		entry, err := writer.CreateHeader(header)
		if err != nil {
			return nil, err
		}
		if _, err := entry.Write(file.data); err != nil {
			return nil, err
		}
	}
	if err := writer.Close(); err != nil {
		return nil, err
	}
	return output.Bytes(), nil
}

const fixtureMP3Base64 = "SUQzBAAAAAAASFRJVDIAAAAcAAADQ2xvY2t3b3JrIElzbGFuZCBuYXJyYXRpb24AVFNTRQAAAA4AAANMYXZmNjIuMy4xMDAAAAAAAAAAAAAAAP/zWMAAAAAAAAAAAABJbmZvAAAADwAAAB4AAAkkABsbGyMjIysrKzMzMzM7OztCQkJKSkpKUlJSWlpaYmJiYmpqanJycnp6enqBgYGJiYmRkZGRmZmZoaGhqampqbGxsbm5ucDAwMDIyMjQ0NDY2NjY4ODg6Ojo8PDw8Pj4+P///wAAAABMYXZjNjIuMTEAAAAAAAAAAAAAAAAkAsAAAAAAAAAJJFHIIbsAAAAAAAAAAAAAAP/zKMQAC8ACzb9BGAKpAGS4f/gAA+D4Pg+fBCD4Pgg45Lg+D4P8EHYPn/+UD/Bw5iAH9YOHMgD/Ajuf6GlKBf/+DQBPX3j9/f/zKMQMEDDmiAGbkAAwovOEZgYZHGKIFFDxxUB8g3hD4QHGLegA6h3MQIc4c4o/5FSKmReL3/oomIiPfWCoiPfwVf//+5Gnav/zKMQGDkh2OAHeEABuQFwjAFAmAQNRgbAumFgPKacAwxv+kDGK+LoYcwV5hJCRhcHUwLgBUfXqfq1Miv////SqpXeXaX9CoP/zKMQHDWBqMAAHtiwICAEzADAuMBYG8wWRbjJq8oNUEeYwRgXTHQQ4azMrNQUjIitOis8Mb//////+qv///OMP29rCRABDAP/zKMQMDsByLADn+ICGzCg6Mcoc0pnjDQUJQxvQIyMDPAtTM5rNTQAxyRAMT0DGnvxL6TAPf///93/7av///ckfF0ldIsjw+P/zKMQMDMhuMADfuIByQBqc9IONKUIY56AMjD1BPMcnEwVcBCdCQGqPPTJK9Qz///////ZV///90z+s6SGLcgkFGBBCYbKxmP/zKMQTDLBqNADntoAlZlC5MmrsMaYK4HxhAmbTeGPGwKMU1nFi1kez////vf///VPFIGZkhzCgOMCisw4ZjLNJMhDW00dRv//zKMQbC7BuNADntoEwhAhDRkA4GdMmIw40Um8kXllsMf///Ufeh0WSothw4Aks1ISPRVzScOjOdkI0w9gYDIByMS28AHMUBf/zKMQnDHhuMADfuIAtt6I/MVw9////0P///VK7TOUhS4QAA5gkNGJCaZziRlwXdGxyLUYOAFoFIDXNsxlBARUnS40Vtcg1sf/zKMQwC5huNADntoEjgVkKTgjALBAERgGgsmBsJQYseGhmCDIGC6DebdafDEaEYLK1uQuct2Aj///////a///9zEfjzzK8DP/zKMQ8DHhuOAAHtCwHMUADORc5hoM3dIw3agnjC5BPMYlMxNDgCTguAW4ROYrgg///////9dX///3Kn1Z0kMXdMBBDDhUy1P/zKMRFDMhqOADfuIAjhPczM6szbgFFMIcBggLzT/QxJKMQAU6ndjVL0t////0KtyZ1lpFQAEYAQCoFpgDA1GBOK6YhnH5kmv/zKMRMDJhuNADftoA65gqA/GfFRwqSZQAkR4oW9kjnLYW////6Kv///U28ElbMgPAQwZGJmnHB5OCaMT85ytiKmHkD0ZOOhv/zKMRUDJBuNAAHtixPrphA1mAAQre+kTpKcPf///9N///9Slwl2oOlmjCAMxkUM+Qzq/Yz9K1jh7FEMLQAcKEYyZNTCZhMJv/zKMRcDOhuMADfuIAGSJcqM0x0N////97///7egZlqVJCBiEMiIbAk/mDMoYDig0GB/BDxgIYEaaKSnODA8zFCcjC2eDp+wP/zKMRjDPhqMADfuIBH//////+y///7sQbnADbgoAGFQeYyFRmsynAJ4au+QR3ODcGJ6FuZ1TpoTbGKUUYVCaEhlcMRuksAT//zKMRqDXBuMADn9oD///7v/2VV///7sBLRROARjYM+qMDgCEwdQVjEeE9NkPcE9WBlxomsw2hzMf2MOKgw8HSyywrtSkgDP//zKMRvDphyKADnuID///3//oqd/////3L///5POquovcKAshDxVIZIkgtzzAnUq8wXoJpMAgAmjSBo7MYM+ABphIgZisKkFv/zKMRvD+hqJADHuGTC3////TX///1XiFPGFAwAAGIiZlx0cHmmYBEibOYkJhUAzGdJxp/SYgggYIYm/lPSVxz///1HW5LlQP/zKMRqDRhuLADn9oAIQDAohMpBjWCk+O2NQaJA6uw6DD4ASMPGwxZjzAqBMEhBHllUMzRMz////3f/t3X///7OuMsUuqIQUP/zKMRwCzhuOADftoGIPiEgARImJeMYLsk3GGhhLg4BHmfgZ1zCZ6MhjEPAq/oTR2Aj///////a///90kbnImnIBQMwwWMrQ//zKMR+DehqLADfuIA3jmMsWZY18BQDCfBtM9SzWNYxU4BQuuiH5ZT2Az////0//9v///3HnhZUmEJAIkNBCKaeFHlM5pEq+v/zKMSBDXhuLADn9oBzYA/mHKBOYlMpgmzBY5AQFqVOjEqoYf//////+tX///5TO6xJBMFgKFwoCBmYJOxjy/mPH+GaEQ/5gf/zKMSGDPhuOADftoAINg8onHwhlxIEJKETawNO2xb////o//Uq///9St/JO2AvOYACBhUWmNjUaTqJmhbMm32OaYYgTBlgxP/zKMSNDRBqMADfuIBoeAmKCOAhomu4kPyynzE1///9SiGGFpMInmAKAyYCoABgkA4GGQOkaEBLxuYjbmIOEGYDYOpg+iDhUP/zKMSTDUhuMADntoBrMDcAtDd6n4ljAhUON11bYXC4bAUDAYAAD/31yW5fhhMIZlpmthFoRMzrOmYoSQaohzEwMmLDME+Bg//zKMSYDFhyMADnuIEEAQFC/npuggJREdDHkG+zp2GVIYTpFin+gzp2NiZLDF47/KsCv/KsCpVn/4VJCg8kKISeNI0jqjl9EP/zKMShDZBqPAFeAAGQkKFKU0Tpi1YS2k5cy2j0mTETyHIczQDAIKSciRI7JFHIQVkFPBTYpvArxtVMQU1FMy4xMDBVVVVVVf/zKMSlGDki3l+aoAJVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/zKMR/DUimRAHPMAFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVQ=="

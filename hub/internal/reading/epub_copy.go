package reading

import (
	"archive/zip"
	"bytes"
	"compress/flate"
	"crypto/sha256"
	"encoding/xml"
	"errors"
	"hash"
	"io"
	"path"
	"regexp"
	"sort"
	"strings"
)

// ErrNotAnEPUB is a file that is not a zip archive the hub can copy from.
var ErrNotAnEPUB = errors.New("the file is not a readable EPUB archive")

// ErrCopyTooLarge is a copy whose rewritten parts are more than the caller will hold.
var ErrCopyTooLarge = errors.New("the reading copy is more than the hub will hold in memory")

// What the copy of one book does, as options. The zero value copies every entry as
// it was.
type CopyOptions struct {
	// OmitAudio leaves out the entries with an audio extension, which they are
	// never read. A read-along edition is mostly audio (293 MB, of which 0.96 MB is
	// text), and an app that takes the narration from the hub's tracks wants the
	// words first.
	OmitAudio bool
	// Restyle makes the book's font sizes follow the reader's text size, and puts
	// two columns within reach of a narrow screen (epub_css.go, epub_html.go).
	Restyle bool
	// MaxHeld is the most a plan keeps in memory: the entries it rewrote, its zip
	// headers and the small entries copied as they were. Zero is no limit.
	MaxHeld int64
}

// CopyReport says what a copy did, in counts and in entry names (never a path).
type CopyReport struct {
	// Omitted are the audio entries left out.
	Omitted []string
	// FontSizes is how many font-size declarations became rem, in all the book's
	// stylesheets, <style> blocks and style attributes.
	FontSizes int
	// LineHeights is how many line-height declarations in px or pt became rem, in the
	// same places: a line height that stayed absolute would no longer fit the text
	// it is set for once that scales.
	LineHeights int
	// Styled is how many documents were given the two-column style.
	Styled int
	// Languages is how many documents were given the package's language, because
	// their <html> had neither lang nor xml:lang.
	Languages int
	// FontsDecoded is how many obfuscated fonts were written as the fonts they are,
	// each under the book's own identifier (see epub_fonts.go). Their entries are
	// gone from META-INF/encryption.xml, and the file with them when they were all
	// it listed.
	FontsDecoded int
	// KeysRecovered is how many of those fonts' keys are no identifier of the book and
	// were worked out from the font itself (epub_fontkey.go).
	KeysRecovered int
	// Edited is how many entries have other bytes than they had.
	Edited int
	// FixedLayout: the package is pre-paginated, whose pages are laid out by the
	// publisher and which the reader neither scales nor puts in columns, so none
	// of it was restyled.
	FixedLayout bool
	// Left are stylesheets and documents that were not edited, and why.
	Left []LeftAlone
}

// LeftAlone is a stylesheet or document copied as it was.
type LeftAlone struct {
	Name   string
	Reason string // "encoding", "too_large" or "unreadable"
}

// What a plan holds of the source rather than of its own: an entry whose
// compressed bytes are more than this stays in the file it came from and is read
// from there when it is served (an illustration, an embedded font). Smaller ones are
// copied into memory with the rest. Every content document is rewritten, so the text
// of a book is held whatever this is; it is the pictures that this keeps out of
// memory (Oathbringer's 182 pictures are 33 MB of a 34 MB file). A variable so a
// test can lower it.
var heldEntryBytes = int64(64 << 10)

// What a stylesheet or document may be, unpacked, to be rewritten. A variable so a
// test can lower it.
var maxRestyleBytes = int64(32 << 20)

// EPUBCopy is a copy of an EPUB made once and read many times: the bytes of the
// whole new archive, in order, as pieces that are either held in memory (headers,
// the rewritten entries, the directory) or a range of the source file (an entry
// copied as it was, compressed bytes and all). Its size and SHA-256 are those of
// the archive as served, so a validator made from them is a validator of the bytes
// a client receives. The same source and options always give the same bytes.
type EPUBCopy struct {
	Size   int64
	SHA256 [sha256.Size]byte
	Report CopyReport

	pieces []copyPiece
	// held is the bytes the pieces keep in memory.
	held int64
}

// Held is how much the copy keeps in memory; the rest is read from the source.
func (c *EPUBCopy) Held() int64 { return c.held }

type copyPiece struct {
	end  int64  // where the piece ends in the copy
	mem  []byte // the piece, when it is held
	from int64  // otherwise, where it begins in the source
}

// Reader serves the copy from src, which must be the file the copy was planned
// from (the same bytes: the caller names it by path, size and time). It is
// seekable and reads concurrently.
func (c *EPUBCopy) Reader(src io.ReaderAt) *io.SectionReader {
	return io.NewSectionReader(copyReaderAt{c, src}, 0, c.Size)
}

type copyReaderAt struct {
	copy *EPUBCopy
	src  io.ReaderAt
}

func (r copyReaderAt) ReadAt(p []byte, offset int64) (int, error) {
	if offset < 0 {
		return 0, errors.New("negative offset")
	}
	pieces := r.copy.pieces
	read := 0
	for read < len(p) {
		at := offset + int64(read)
		if at >= r.copy.Size {
			return read, io.EOF
		}
		i := sort.Search(len(pieces), func(i int) bool { return pieces[i].end > at })
		start := int64(0)
		if i > 0 {
			start = pieces[i-1].end
		}
		within := at - start
		want := min(int64(len(p)-read), pieces[i].end-at)
		if pieces[i].mem != nil {
			copy(p[read:read+int(want)], pieces[i].mem[within:])
			read += int(want)
			continue
		}
		got, err := r.src.ReadAt(p[read:read+int(want)], pieces[i].from+within)
		read += got
		if int64(got) < want {
			if err == nil || err == io.EOF {
				err = io.ErrUnexpectedEOF
			}
			return read, err
		}
	}
	return read, nil
}

// copySink takes what a zip.Writer writes and keeps it as pieces: bytes that the
// writer generated are held, and bytes it was told are a verbatim copy of a range
// of the source are only noted.
type copySink struct {
	sum    hash.Hash
	size   int64
	pieces []copyPiece
	held   int64
	limit  int64
	// raw is the range of the source that the next bytes written are a copy of.
	rawFrom, rawLeft int64
}

func (s *copySink) Write(p []byte) (int, error) {
	total := len(p)
	s.sum.Write(p)
	for len(p) > 0 {
		if s.rawLeft > 0 {
			n := min(int64(len(p)), s.rawLeft)
			// One entry arrives in several writes; they are one piece.
			if last := len(s.pieces) - 1; last >= 0 && s.pieces[last].mem == nil && s.sourceEnd(last) == s.rawFrom {
				s.pieces[last].end += n
			} else {
				s.pieces = append(s.pieces, copyPiece{end: s.size + n, from: s.rawFrom})
			}
			s.size += n
			s.rawFrom += n
			s.rawLeft -= n
			p = p[n:]
			continue
		}
		if s.limit > 0 && s.held+int64(len(p)) > s.limit {
			return 0, ErrCopyTooLarge
		}
		s.held += int64(len(p))
		start := s.size
		s.size += int64(len(p))
		if last := len(s.pieces) - 1; last >= 0 && s.pieces[last].mem != nil && s.pieces[last].end == start {
			s.pieces[last].mem = append(s.pieces[last].mem, p...)
			s.pieces[last].end = s.size
		} else {
			s.pieces = append(s.pieces, copyPiece{end: s.size, mem: append([]byte(nil), p...)})
		}
		p = nil
	}
	return total, nil
}

// sourceEnd is where in the source the source piece i ends.
func (s *copySink) sourceEnd(i int) int64 {
	start := int64(0)
	if i > 0 {
		start = s.pieces[i-1].end
	}
	return s.pieces[i].from + s.pieces[i].end - start
}

// expectRaw says that the next n bytes written are the source's from, from+n.
func (s *copySink) expectRaw(from, n int64) { s.rawFrom, s.rawLeft = from, n }

// PlanReadingEPUB reads an EPUB once and plans the copy of it that the options
// ask for: the same entries in the same order, each as it was, except audio when
// that is asked to be left out (never read), and stylesheets and content
// documents when a restyle is (unpacked, rewritten and deflated). The result is
// small however large the book is, since an entry that is only copied is a range of
// the source.
//
// An entry copied as it was keeps its compressed bytes and its header, so the
// mimetype stays stored and first. A rewritten one is deflated with the header it
// had (name, time, comment, mode). Entry names, their order, the package, the
// navigation and the SMIL are the original's, so every href, element id and
// locator, and the read-along alignment, mean what they meant.
//
// The whole of the source is read once, for the SHA-256, apart from the audio that
// is left out.
func PlanReadingEPUB(src io.ReaderAt, size int64, options CopyOptions) (*EPUBCopy, error) {
	archive, err := zip.NewReader(src, size)
	if err != nil || len(archive.File) > maxEntries {
		return nil, ErrNotAnEPUB
	}
	sink := &copySink{sum: sha256.New(), limit: options.MaxHeld}
	writer := zip.NewWriter(sink)
	if err := writer.SetComment(archive.Comment); err != nil {
		return nil, err
	}
	var report CopyReport
	var kinds map[string]entryKind
	var language string
	var fonts *fontPlan
	if options.Restyle {
		facts := classifyEntries(archive)
		kinds, language, report.FixedLayout = facts.kinds, facts.language, facts.fixedLayout
		if !report.FixedLayout {
			var err error
			if fonts, err = planFonts(archive, facts.identifiers, &report); err != nil {
				return nil, err
			}
		}
	}
	for _, entry := range archive.File {
		if _, audio := AudioKindOf(entry.Name); audio && options.OmitAudio {
			report.Omitted = append(report.Omitted, entry.Name)
			continue
		}
		if fonts != nil {
			done, err := fonts.write(writer, entry, &report)
			if err != nil {
				return nil, err
			}
			if done {
				continue
			}
		}
		if kind := kinds[entry.Name]; kind != kindOther && !report.FixedLayout {
			done, err := restyleEntry(writer, entry, kind, language, &report)
			if err != nil {
				return nil, err
			}
			if done {
				continue
			}
		}
		if err := copyEntry(writer, sink, entry); err != nil {
			return nil, err
		}
	}
	if err := writer.Close(); err != nil {
		return nil, err
	}
	copied := &EPUBCopy{Size: sink.size, Report: report, pieces: sink.pieces, held: sink.held}
	copy(copied.SHA256[:], sink.sum.Sum(nil))
	return copied, nil
}

// WriteReadingEPUB writes the copy PlanReadingEPUB plans to dst, and says what it
// did. It is the same bytes as serving the plan, since that is how it is made.
func WriteReadingEPUB(dst io.Writer, src io.ReaderAt, size int64, options CopyOptions) (CopyReport, error) {
	options.MaxHeld = 0
	plan, err := PlanReadingEPUB(src, size, options)
	if err != nil {
		return CopyReport{}, err
	}
	if _, err := io.Copy(dst, plan.Reader(src)); err != nil {
		return CopyReport{}, err
	}
	return plan.Report, nil
}

// copyEntry copies an entry as it was: its header and its compressed bytes, with
// nothing decompressed or recompressed.
func copyEntry(writer *zip.Writer, sink *copySink, entry *zip.File) error {
	if strings.HasSuffix(entry.Name, "/") {
		// A folder has no bytes. Some archivers still give one the two bytes of an
		// empty deflate stream (most of this library's books do), which a raw copy
		// cannot carry (the writer refuses "to write to directory"), so it is written
		// as what it means: an empty stored entry with the name, time and mode it had.
		_, err := writer.CreateHeader(&zip.FileHeader{
			Name: entry.Name, Comment: entry.Comment, NonUTF8: entry.NonUTF8, Method: zip.Store,
			ModifiedDate: entry.ModifiedDate, ModifiedTime: entry.ModifiedTime, ExternalAttrs: entry.ExternalAttrs,
		})
		return err
	}
	if int64(entry.CompressedSize64) <= heldEntryBytes {
		return writer.Copy(entry)
	}
	offset, err := entry.DataOffset()
	if err != nil {
		return ErrNotAnEPUB
	}
	raw, err := entry.OpenRaw()
	if err != nil {
		return ErrNotAnEPUB
	}
	header := entry.FileHeader
	out, err := writer.CreateRaw(&header)
	if err != nil {
		return err
	}
	// The writer buffers: what it has written so far is the header, and the next
	// bytes are the entry's.
	if err := writer.Flush(); err != nil {
		return err
	}
	sink.expectRaw(offset, int64(entry.CompressedSize64))
	if _, err := io.Copy(out, raw); err != nil {
		return err
	}
	if err := writer.Flush(); err != nil {
		return err
	}
	if sink.rawLeft != 0 {
		return ErrNotAnEPUB
	}
	return nil
}

// entryKind is what an entry of a book is for the restyle.
type entryKind int

const (
	kindOther entryKind = iota
	kindSheet
	kindDocument
)

// packageFacts is what the package of a book says that the copy needs.
type packageFacts struct {
	// kinds says which entries are stylesheets and which are content documents.
	kinds map[string]entryKind
	// fixedLayout: the package is pre-paginated.
	fixedLayout bool
	// language is the language the package gives the book, or empty when it gives
	// none that is plausible: see validLanguage.
	language string
	// identifiers are the package's dc:identifier values: the unique identifier (the
	// one `unique-identifier` points at) first, then the others as the package lists
	// them. The key of an obfuscated font is made of one of them (epub_fonts.go).
	identifiers []string
}

// classifyEntries says which entries are stylesheets and which are content
// documents. The package's manifest says what each file is, by media type; an
// entry that it does not list, or lists without a type, is taken by its extension.
// A package that cannot be read at all leaves the extension to decide everything.
// It also reads the language the package gives the book and its identifiers.
func classifyEntries(archive *zip.Reader) packageFacts {
	byName := make(map[string]*zip.File, len(archive.File))
	for _, entry := range archive.File {
		byName[entry.Name] = entry
	}
	var facts packageFacts
	declared := map[string]string{} // zip path -> media type
	sawLanguage := false
	if container, err := readXMLEntry(byName, "META-INF/container.xml"); err == nil {
		var packages []string
		scanLenientXML(container, func(start xml.StartElement, _ string) {
			if start.Name.Local == "rootfile" {
				if resolved, _, ok := resolveRef("", attribute(start, "full-path")); ok {
					packages = append(packages, resolved)
				}
			}
		})
		for _, packagePath := range packages {
			document, err := readXMLEntry(byName, packagePath)
			if err != nil {
				continue
			}
			var uniqueID, unique string
			var others []string
			scanLenientXML(document, func(start xml.StartElement, text string) {
				switch start.Name.Local {
				case "package":
					uniqueID = attribute(start, "unique-identifier")
				case "identifier":
					switch {
					case strings.TrimSpace(text) == "":
					case unique == "" && uniqueID != "" && attribute(start, "id") == uniqueID:
						unique = text
					default:
						others = append(others, text)
					}
				case "language":
					// The package's first dc:language, and only that: a second one is
					// another language of the book, and a first that is not a language
					// tag is no reason to take the second.
					if !sawLanguage {
						sawLanguage = true
						facts.language = validLanguage(text)
					}
				case "item":
					if href, _, ok := resolveRef(packagePath, attribute(start, "href")); ok {
						declared[href] = strings.ToLower(strings.TrimSpace(strings.SplitN(attribute(start, "media-type"), ";", 2)[0]))
					}
				case "meta":
					if attribute(start, "property") == "rendition:layout" && strings.EqualFold(strings.TrimSpace(text), "pre-paginated") {
						facts.fixedLayout = true
					}
				}
			})
			if unique != "" {
				facts.identifiers = append(facts.identifiers, unique)
			}
			facts.identifiers = append(facts.identifiers, others...)
		}
	}
	kinds := make(map[string]entryKind, len(archive.File))
	for _, entry := range archive.File {
		if strings.HasSuffix(entry.Name, "/") {
			continue
		}
		mediaType := declared[entry.Name]
		switch {
		case mediaType == "text/css":
			kinds[entry.Name] = kindSheet
		case mediaType == "application/xhtml+xml" || mediaType == "text/html":
			kinds[entry.Name] = kindDocument
		case mediaType != "":
			// The package says it is something else, whatever it is called.
		default:
			switch strings.ToLower(path.Ext(entry.Name)) {
			case ".css":
				kinds[entry.Name] = kindSheet
			case ".xhtml", ".html", ".htm", ".xht":
				kinds[entry.Name] = kindDocument
			}
		}
	}
	facts.kinds = kinds
	return facts
}

// languageTag is the shape of a BCP 47 language tag: a language of two or three
// letters and any number of subtags of one to eight letters and digits (script,
// region, variants, extensions, private use), joined by hyphens.
var languageTag = regexp.MustCompile(`^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*$`)

// validLanguage is the package's dc:language as it can go on a document's <html>,
// or empty. Without a language WebKit and Chrome do not hyphenate; with a wrong one
// they hyphenate wrongly, so what is not plausibly a tag (a name, an underscore, a
// locale of the platform) is not used, and neither are the tags that say there is
// no language (und, zxx, mul). A plausible tag holds only letters, digits and
// hyphens, so it needs no escaping in an attribute. Case is kept as written.
func validLanguage(value string) string {
	value = strings.TrimSpace(value)
	if len(value) > 35 || !languageTag.MatchString(value) {
		return ""
	}
	switch strings.ToLower(strings.SplitN(value, "-", 2)[0]) {
	case "und", "zxx", "mul":
		return ""
	}
	return value
}

// scanLenientXML visits the start elements of a package or container, with the text
// that directly follows each. Real packages have a DOCTYPE, an undeclared entity or
// a charset that the decoder would refuse; those are no reason to give up the
// manifest, so it decodes loosely and stops at the first error with what it has.
func scanLenientXML(data []byte, visit func(start xml.StartElement, text string)) {
	decoder := xml.NewDecoder(bytes.NewReader(data))
	decoder.Strict = false
	decoder.CharsetReader = func(_ string, input io.Reader) (io.Reader, error) { return input, nil }
	var pending *xml.StartElement
	flush := func(text string) {
		if pending != nil {
			visit(*pending, text)
			pending = nil
		}
	}
	for {
		token, err := decoder.Token()
		if err != nil {
			flush("")
			return
		}
		switch t := token.(type) {
		case xml.StartElement:
			flush("")
			start := t.Copy()
			pending = &start
		case xml.CharData:
			if pending != nil {
				flush(string(t))
			}
		default:
			flush("")
		}
	}
}

// restyleEntry rewrites one stylesheet or content document into the archive. It
// answers false, having written nothing, for an entry that is to be copied as it
// was: one that holds nothing to change, or that cannot safely be edited (and then
// the report says why).
func restyleEntry(writer *zip.Writer, entry *zip.File, kind entryKind, language string, report *CopyReport) (bool, error) {
	if entry.UncompressedSize64 > uint64(maxRestyleBytes) {
		report.Left = append(report.Left, LeftAlone{entry.Name, leftTooLarge})
		return false, nil
	}
	stream, err := entry.Open()
	if err != nil {
		report.Left = append(report.Left, LeftAlone{entry.Name, leftUnread})
		return false, nil
	}
	data, err := io.ReadAll(io.LimitReader(stream, maxRestyleBytes+1))
	stream.Close()
	if err != nil {
		if isDamagedEntry(err) {
			report.Left = append(report.Left, LeftAlone{entry.Name, leftUnread})
			return false, nil
		}
		// The source itself failed to be read (a cancelled request, a disk).
		return false, err
	}
	if int64(len(data)) > maxRestyleBytes {
		report.Left = append(report.Left, LeftAlone{entry.Name, leftTooLarge})
		return false, nil
	}
	var rewritten []byte
	var result documentResult
	if kind == kindSheet {
		rewritten, result = restyleSheet(data)
	} else {
		rewritten, result = restyleDocument(data, language)
	}
	if result.left != "" {
		report.Left = append(report.Left, LeftAlone{entry.Name, result.left})
		return false, nil
	}
	if bytes.Equal(rewritten, data) {
		return false, nil
	}
	if err := writeRewritten(writer, entry, zip.Deflate, rewritten); err != nil {
		return false, err
	}
	report.FontSizes += result.fontSizes
	report.LineHeights += result.lineHeights
	if result.styled {
		report.Styled++
	}
	if result.language {
		report.Languages++
	}
	report.Edited++
	return true, nil
}

// writeRewritten writes data as the entry's new content, under the header it had
// (name, time, comment, mode) and the given method.
func writeRewritten(writer *zip.Writer, entry *zip.File, method uint16, data []byte) error {
	out, err := writer.CreateHeader(&zip.FileHeader{
		Name: entry.Name, Comment: entry.Comment, NonUTF8: entry.NonUTF8,
		Method:       method,
		ModifiedDate: entry.ModifiedDate, ModifiedTime: entry.ModifiedTime,
		ExternalAttrs: entry.ExternalAttrs,
	})
	if err != nil {
		return err
	}
	_, err = out.Write(data)
	return err
}

// isDamagedEntry: the entry's own bytes are wrong (a bad checksum, a stream that
// does not inflate), as opposed to the file under the archive failing to be read.
func isDamagedEntry(err error) bool {
	var corrupt flate.CorruptInputError
	return errors.Is(err, zip.ErrChecksum) || errors.Is(err, zip.ErrFormat) || errors.Is(err, zip.ErrAlgorithm) ||
		errors.Is(err, io.ErrUnexpectedEOF) || errors.As(err, &corrupt)
}

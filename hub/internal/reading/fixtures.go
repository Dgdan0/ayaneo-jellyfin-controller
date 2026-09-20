package reading

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

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
				return buildEPUB(epubSpec{title: "The Clockwork Island", identifier: "urn:pocketds:fixture:clockwork-island", readaloud: false})
			},
		},
		{
			name: "The Readaloud Signal",
			role: FixtureReadaloud,
			kind: MediaBook,
			path: "Books/Lab Author/Lab Stories/02 - The Readaloud Signal/The Readaloud Signal.epub",
			data: func() ([]byte, error) {
				return buildEPUB(epubSpec{title: "The Readaloud Signal", identifier: "urn:pocketds:fixture:readaloud-signal", readaloud: true})
			},
		},
		{
			name: "The Readaloud Journey",
			role: FixtureReadaloud,
			kind: MediaBook,
			path: "Books/Lab Author/Lab Stories/05 - The Readaloud Journey/The Readaloud Journey.epub",
			data: func() ([]byte, error) {
				return buildEPUB(epubSpec{
					title: "The Readaloud Journey", identifier: "urn:pocketds:fixture:readaloud-journey",
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
	for _, id := range []string{"TIT2", "TPE1", "TALB"} {
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

const fixtureMP3Base64 = "SUQzBAAAAAAASFRJVDIAAAAcAAADQ2xvY2t3b3JrIElzbGFuZCBuYXJyYXRpb24AVFNTRQAAAA4AAANMYXZmNjIuMy4xMDAAAAAAAAAAAAAAAP/zWMAAAAAAAAAAAABJbmZvAAAADwAAAB4AAAkkABsbGyMjIysrKzMzMzM7OztCQkJKSkpKUlJSWlpaYmJiYmpqanJycnp6enqBgYGJiYmRkZGRmZmZoaGhqampqbGxsbm5ucDAwMDIyMjQ0NDY2NjY4ODg6Ojo8PDw8Pj4+P///wAAAABMYXZjNjIuMTEAAAAAAAAAAAAAAAAkAsAAAAAAAAAJJFHIIbsAAAAAAAAAAAAAAP/zKMQAC8ACzb9BGAKpAGS4f/gAA+D4Pg+fBCD4Pgg45Lg+D4P8EHYPn/+UD/Bw5iAH9YOHMgD/Ajuf6GlKBf/+DQBPX3j9/f/zKMQMEDDmiAGbkAAwovOEZgYZHGKIFFDxxUB8g3hD4QHGLegA6h3MQIc4c4o/5FSKmReL3/oomIiPfWCoiPfwVf//+5Gnav/zKMQGDkh2OAHeEABuQFwjAFAmAQNRgbAumFgPKacAwxv+kDGK+LoYcwV5hJCRhcHUwLgBUfXqfq1Miv////SqpXeXaX9CoP/zKMQHDWBqMAAHtiwICAEzADAuMBYG8wWRbjJq8oNUEeYwRgXTHQQ4azMrNQUjIitOis8Mb//////+qv///OMP29rCRABDAP/zKMQMDsByLADn+ICGzCg6Mcoc0pnjDQUJQxvQIyMDPAtTM5rNTQAxyRAMT0DGnvxL6TAPf///93/7av///ckfF0ldIsjw+P/zKMQMDMhuMADfuIByQBqc9IONKUIY56AMjD1BPMcnEwVcBCdCQGqPPTJK9Qz///////ZV///90z+s6SGLcgkFGBBCYbKxmP/zKMQTDLBqNADntoAlZlC5MmrsMaYK4HxhAmbTeGPGwKMU1nFi1kez////vf///VPFIGZkhzCgOMCisw4ZjLNJMhDW00dRv//zKMQbC7BuNADntoEwhAhDRkA4GdMmIw40Um8kXllsMf///Ufeh0WSothw4Aks1ISPRVzScOjOdkI0w9gYDIByMS28AHMUBf/zKMQnDHhuMADfuIAtt6I/MVw9////0P///VK7TOUhS4QAA5gkNGJCaZziRlwXdGxyLUYOAFoFIDXNsxlBARUnS40Vtcg1sf/zKMQwC5huNADntoEjgVkKTgjALBAERgGgsmBsJQYseGhmCDIGC6DebdafDEaEYLK1uQuct2Aj///////a///9zEfjzzK8DP/zKMQ8DHhuOAAHtCwHMUADORc5hoM3dIw3agnjC5BPMYlMxNDgCTguAW4ROYrgg///////9dX///3Kn1Z0kMXdMBBDDhUy1P/zKMRFDMhqOADfuIAjhPczM6szbgFFMIcBggLzT/QxJKMQAU6ndjVL0t////0KtyZ1lpFQAEYAQCoFpgDA1GBOK6YhnH5kmv/zKMRMDJhuNADftoA65gqA/GfFRwqSZQAkR4oW9kjnLYW////6Kv///U28ElbMgPAQwZGJmnHB5OCaMT85ytiKmHkD0ZOOhv/zKMRUDJBuNAAHtixPrphA1mAAQre+kTpKcPf///9N///9Slwl2oOlmjCAMxkUM+Qzq/Yz9K1jh7FEMLQAcKEYyZNTCZhMJv/zKMRcDOhuMADfuIAGSJcqM0x0N////97///7egZlqVJCBiEMiIbAk/mDMoYDig0GB/BDxgIYEaaKSnODA8zFCcjC2eDp+wP/zKMRjDPhqMADfuIBH//////+y///7sQbnADbgoAGFQeYyFRmsynAJ4au+QR3ODcGJ6FuZ1TpoTbGKUUYVCaEhlcMRuksAT//zKMRqDXBuMADn9oD///7v/2VV///7sBLRROARjYM+qMDgCEwdQVjEeE9NkPcE9WBlxomsw2hzMf2MOKgw8HSyywrtSkgDP//zKMRvDphyKADnuID///3//oqd/////3L///5POquovcKAshDxVIZIkgtzzAnUq8wXoJpMAgAmjSBo7MYM+ABphIgZisKkFv/zKMRvD+hqJADHuGTC3////TX///1XiFPGFAwAAGIiZlx0cHmmYBEibOYkJhUAzGdJxp/SYgggYIYm/lPSVxz///1HW5LlQP/zKMRqDRhuLADn9oAIQDAohMpBjWCk+O2NQaJA6uw6DD4ASMPGwxZjzAqBMEhBHllUMzRMz////3f/t3X///7OuMsUuqIQUP/zKMRwCzhuOADftoGIPiEgARImJeMYLsk3GGhhLg4BHmfgZ1zCZ6MhjEPAq/oTR2Aj///////a///90kbnImnIBQMwwWMrQ//zKMR+DehqLADfuIA3jmMsWZY18BQDCfBtM9SzWNYxU4BQuuiH5ZT2Az////0//9v///3HnhZUmEJAIkNBCKaeFHlM5pEq+v/zKMSBDXhuLADn9oBzYA/mHKBOYlMpgmzBY5AQFqVOjEqoYf//////+tX///5TO6xJBMFgKFwoCBmYJOxjy/mPH+GaEQ/5gf/zKMSGDPhuOADftoAINg8onHwhlxIEJKETawNO2xb////o//Uq///9St/JO2AvOYACBhUWmNjUaTqJmhbMm32OaYYgTBlgxP/zKMSNDRBqMADfuIBoeAmKCOAhomu4kPyynzE1///9SiGGFpMInmAKAyYCoABgkA4GGQOkaEBLxuYjbmIOEGYDYOpg+iDhUP/zKMSTDUhuMADntoBrMDcAtDd6n4ljAhUON11bYXC4bAUDAYAAD/31yW5fhhMIZlpmthFoRMzrOmYoSQaohzEwMmLDME+Bg//zKMSYDFhyMADnuIEEAQFC/npuggJREdDHkG+zp2GVIYTpFin+gzp2NiZLDF47/KsCv/KsCpVn/4VJCg8kKISeNI0jqjl9EP/zKMShDZBqPAFeAAGQkKFKU0Tpi1YS2k5cy2j0mTETyHIczQDAIKSciRI7JFHIQVkFPBTYpvArxtVMQU1FMy4xMDBVVVVVVf/zKMSlGDki3l+aoAJVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/zKMR/DUimRAHPMAFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVQ=="

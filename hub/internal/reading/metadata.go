package reading

import (
	"archive/zip"
	"encoding/xml"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

const maxMetadataBytes = 4 << 20

// ExtractAssetMetadata reads only the small metadata members required for a
// migration decision. It never extracts an archive to disk.
func ExtractAssetMetadata(name string, kind MediaKind) (AssetMetadata, []MigrationIssue) {
	lower := strings.ToLower(filepath.Base(name))
	switch lower {
	case "comicinfo.xml":
		metadata, err := readComicInfoFile(name)
		if err != nil {
			return AssetMetadata{}, []MigrationIssue{{Code: "invalid_sidecar", Message: err.Error()}}
		}
		return metadata, nil
	case "metadata.opf", "content.opf":
		metadata, err := readOPFFile(name)
		if err != nil {
			return AssetMetadata{}, []MigrationIssue{{Code: "invalid_sidecar", Message: err.Error()}}
		}
		return metadata, nil
	}

	extension := strings.ToLower(filepath.Ext(name))
	switch {
	case kind == MediaBook && extension == ".epub":
		metadata, err := readEPUBMetadata(name)
		if err != nil {
			return AssetMetadata{}, []MigrationIssue{{Code: "invalid_epub", Message: err.Error()}}
		}
		return metadata, nil
	case (kind == MediaComic || kind == MediaManga) && (extension == ".cbz" || extension == ".zip"):
		metadata, found, err := readComicArchiveMetadata(name)
		if err != nil {
			return AssetMetadata{}, []MigrationIssue{{Code: "invalid_archive", Message: err.Error()}}
		}
		if found {
			return metadata, nil
		}
	}
	return AssetMetadata{}, nil
}

type comicInfoXML struct {
	XMLName     xml.Name `xml:"ComicInfo"`
	Title       string   `xml:"Title"`
	Series      string   `xml:"Series"`
	Number      string   `xml:"Number"`
	Volume      string   `xml:"Volume"`
	Year        int      `xml:"Year"`
	Writer      string   `xml:"Writer"`
	Penciller   string   `xml:"Penciller"`
	LanguageISO string   `xml:"LanguageISO"`
}

func readComicInfoFile(name string) (AssetMetadata, error) {
	file, err := os.Open(name)
	if err != nil {
		return AssetMetadata{}, err
	}
	defer file.Close()
	return decodeComicInfo(io.LimitReader(file, maxMetadataBytes))
}

func decodeComicInfo(reader io.Reader) (AssetMetadata, error) {
	var document comicInfoXML
	if err := xml.NewDecoder(reader).Decode(&document); err != nil {
		return AssetMetadata{}, fmt.Errorf("parse ComicInfo.xml: %w", err)
	}
	if document.XMLName.Local != "ComicInfo" {
		return AssetMetadata{}, fmt.Errorf("ComicInfo.xml has root %q", document.XMLName.Local)
	}
	authors := splitContributors(document.Writer)
	authors = appendUnique(authors, splitContributors(document.Penciller)...)
	return AssetMetadata{
		Title: strings.TrimSpace(document.Title), Series: strings.TrimSpace(document.Series),
		Number: strings.TrimSpace(document.Number), Volume: strings.TrimSpace(document.Volume),
		Year: document.Year, Language: strings.TrimSpace(document.LanguageISO), Authors: authors,
	}, nil
}

func readComicArchiveMetadata(name string) (AssetMetadata, bool, error) {
	archive, err := zip.OpenReader(name)
	if err != nil {
		return AssetMetadata{}, false, fmt.Errorf("open comic archive: %w", err)
	}
	defer archive.Close()
	for _, entry := range archive.File {
		if !strings.EqualFold(filepath.Base(filepath.ToSlash(entry.Name)), "ComicInfo.xml") {
			continue
		}
		reader, err := entry.Open()
		if err != nil {
			return AssetMetadata{}, false, fmt.Errorf("open embedded ComicInfo.xml: %w", err)
		}
		metadata, decodeErr := decodeComicInfo(io.LimitReader(reader, maxMetadataBytes))
		closeErr := reader.Close()
		if decodeErr != nil {
			return AssetMetadata{}, false, decodeErr
		}
		if closeErr != nil {
			return AssetMetadata{}, false, closeErr
		}
		return metadata, true, nil
	}
	return AssetMetadata{}, false, nil
}

type epubContainerXML struct {
	RootFiles []struct {
		FullPath string `xml:"full-path,attr"`
	} `xml:"rootfiles>rootfile"`
}

type opfPackageXML struct {
	XMLName          xml.Name `xml:"package"`
	UniqueIdentifier string   `xml:"unique-identifier,attr"`
	Metadata         struct {
		Identifiers []struct {
			ID    string `xml:"id,attr"`
			Value string `xml:",chardata"`
		} `xml:"identifier"`
		Titles   []string `xml:"title"`
		Creators []string `xml:"creator"`
		Language []string `xml:"language"`
		Meta     []struct {
			Property string `xml:"property,attr"`
			Name     string `xml:"name,attr"`
			Content  string `xml:"content,attr"`
			Value    string `xml:",chardata"`
		} `xml:"meta"`
	} `xml:"metadata"`
}

func readOPFFile(name string) (AssetMetadata, error) {
	file, err := os.Open(name)
	if err != nil {
		return AssetMetadata{}, err
	}
	defer file.Close()
	return decodeOPF(io.LimitReader(file, maxMetadataBytes))
}

func readEPUBMetadata(name string) (AssetMetadata, error) {
	archive, err := zip.OpenReader(name)
	if err != nil {
		return AssetMetadata{}, fmt.Errorf("open EPUB: %w", err)
	}
	defer archive.Close()

	containerEntry := findZipEntry(archive.File, "META-INF/container.xml")
	if containerEntry == nil {
		return AssetMetadata{}, fmt.Errorf("EPUB has no META-INF/container.xml")
	}
	containerReader, err := containerEntry.Open()
	if err != nil {
		return AssetMetadata{}, err
	}
	var container epubContainerXML
	decodeErr := xml.NewDecoder(io.LimitReader(containerReader, maxMetadataBytes)).Decode(&container)
	closeErr := containerReader.Close()
	if decodeErr != nil {
		return AssetMetadata{}, fmt.Errorf("parse EPUB container: %w", decodeErr)
	}
	if closeErr != nil {
		return AssetMetadata{}, closeErr
	}
	if len(container.RootFiles) == 0 {
		return AssetMetadata{}, fmt.Errorf("EPUB container has no package document")
	}
	packagePath := cleanArchiveMember(container.RootFiles[0].FullPath)
	if packagePath == "" {
		return AssetMetadata{}, fmt.Errorf("EPUB package path is unsafe")
	}
	packageEntry := findZipEntry(archive.File, packagePath)
	if packageEntry == nil {
		return AssetMetadata{}, fmt.Errorf("EPUB package document %q is missing", packagePath)
	}
	packageReader, err := packageEntry.Open()
	if err != nil {
		return AssetMetadata{}, err
	}
	metadata, decodeErr := decodeOPF(io.LimitReader(packageReader, maxMetadataBytes))
	closeErr = packageReader.Close()
	if decodeErr != nil {
		return AssetMetadata{}, decodeErr
	}
	if closeErr != nil {
		return AssetMetadata{}, closeErr
	}
	return metadata, nil
}

func decodeOPF(reader io.Reader) (AssetMetadata, error) {
	var document opfPackageXML
	if err := xml.NewDecoder(reader).Decode(&document); err != nil {
		return AssetMetadata{}, fmt.Errorf("parse OPF: %w", err)
	}
	if document.XMLName.Local != "package" {
		return AssetMetadata{}, fmt.Errorf("OPF has root %q", document.XMLName.Local)
	}
	metadata := AssetMetadata{Identifiers: make(map[string]string)}
	if len(document.Metadata.Titles) > 0 {
		metadata.Title = strings.TrimSpace(document.Metadata.Titles[0])
	}
	metadata.Authors = trimNonEmpty(document.Metadata.Creators)
	if len(document.Metadata.Language) > 0 {
		metadata.Language = strings.TrimSpace(document.Metadata.Language[0])
	}
	for _, identifier := range document.Metadata.Identifiers {
		value := strings.TrimSpace(identifier.Value)
		lower := strings.ToLower(value)
		switch {
		case strings.Contains(lower, "isbn") || looksLikeISBN(value):
			if isbn := normalizeISBN(value); isbn != "" {
				metadata.Identifiers["isbn"] = isbn
			}
		case strings.Contains(lower, "asin"):
			metadata.Identifiers["asin"] = strings.TrimSpace(value[strings.LastIndex(lower, "asin")+4:])
		case identifier.ID == document.UniqueIdentifier && value != "":
			metadata.Identifiers["edition"] = value
		}
	}
	for _, meta := range document.Metadata.Meta {
		property := strings.ToLower(strings.TrimSpace(meta.Property))
		name := strings.ToLower(strings.TrimSpace(meta.Name))
		value := strings.TrimSpace(meta.Value)
		if value == "" {
			value = strings.TrimSpace(meta.Content)
		}
		switch property {
		case "belongs-to-collection", "calibre:series":
			metadata.Series = value
		case "group-position", "calibre:series_index":
			metadata.Number = value
		}
		switch name {
		case "calibre:series":
			metadata.Series = value
		case "calibre:series_index":
			metadata.Number = value
		}
	}
	if len(metadata.Identifiers) == 0 {
		metadata.Identifiers = nil
	}
	return metadata, nil
}

func findZipEntry(entries []*zip.File, wanted string) *zip.File {
	wanted = strings.TrimPrefix(filepath.ToSlash(wanted), "/")
	for _, entry := range entries {
		if strings.EqualFold(strings.TrimPrefix(filepath.ToSlash(entry.Name), "/"), wanted) {
			return entry
		}
	}
	return nil
}

func cleanArchiveMember(value string) string {
	portable := filepath.ToSlash(strings.TrimSpace(value))
	if portable == "" || strings.HasPrefix(portable, "/") || strings.Contains(portable, "../") || strings.HasPrefix(portable, "..") {
		return ""
	}
	return portable
}

func splitContributors(value string) []string {
	return trimNonEmpty(strings.FieldsFunc(value, func(r rune) bool { return r == ',' || r == ';' }))
}

func trimNonEmpty(values []string) []string {
	result := make([]string, 0, len(values))
	for _, value := range values {
		if trimmed := strings.TrimSpace(value); trimmed != "" {
			result = append(result, trimmed)
		}
	}
	return result
}

func appendUnique(values []string, additions ...string) []string {
	seen := make(map[string]bool, len(values)+len(additions))
	for _, value := range values {
		seen[strings.ToLower(value)] = true
	}
	for _, value := range additions {
		if !seen[strings.ToLower(value)] {
			seen[strings.ToLower(value)] = true
			values = append(values, value)
		}
	}
	return values
}

func normalizeISBN(value string) string {
	lower := strings.ToLower(strings.TrimSpace(value))
	if index := strings.LastIndex(lower, "isbn"); index >= 0 {
		value = value[index+4:]
	}
	var builder strings.Builder
	for _, character := range strings.ToUpper(value) {
		if character >= '0' && character <= '9' || character == 'X' {
			builder.WriteRune(character)
		}
	}
	result := builder.String()
	if len(result) != 10 && len(result) != 13 {
		return ""
	}
	return result
}

func looksLikeISBN(value string) bool {
	cleaned := normalizeISBN(value)
	return len(cleaned) == 10 || len(cleaned) == 13
}

package reading

import (
	"archive/zip"
	"errors"
	"io"
)

// ErrNotAnEPUB is a file that is not a zip archive the hub can copy from.
var ErrNotAnEPUB = errors.New("the file is not a readable EPUB archive")

// WriteSlimEPUB writes an EPUB to dst without its audio: every entry in the
// order it had, as it was, except those with an audio extension. A read-along
// edition is mostly audio (293 MB, of which 0.96 MB is text), and an app that
// takes its narration from the hub's tracks wants the text and the SMIL first.
//
// The copy is raw: an entry's compressed bytes go across as they are, with its
// own header, so nothing is decompressed or recompressed, the mimetype stays
// stored and first, and the same source gives the same bytes every time (which
// a resumed download relies on). The audio entries are not read at all, so the
// edition is never held in memory: only its zip directory is. The names of what
// was left out come back.
func WriteSlimEPUB(dst io.Writer, src io.ReaderAt, size int64) ([]string, error) {
	archive, err := zip.NewReader(src, size)
	if err != nil || len(archive.File) > maxEntries {
		return nil, ErrNotAnEPUB
	}
	writer := zip.NewWriter(dst)
	if err := writer.SetComment(archive.Comment); err != nil {
		return nil, err
	}
	var omitted []string
	for _, entry := range archive.File {
		if _, audio := AudioKindOf(entry.Name); audio {
			omitted = append(omitted, entry.Name)
			continue
		}
		if err := writer.Copy(entry); err != nil {
			return nil, err
		}
	}
	if err := writer.Close(); err != nil {
		return nil, err
	}
	return omitted, nil
}

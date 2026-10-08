package api

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"ayaneohub/internal/config"
	readingdomain "ayaneohub/internal/reading"
)

// The read-along edition without its audio: its text and its SMIL, a megabyte
// of a 293 MB file, so a reader opens on the words at once and takes the
// narration from the hub's tracks.

// columnStyleElement is the style that the reading copy puts in a document's head.
var columnStyleElement = regexp.MustCompile(`<style type="text/css">@media screen and \(min-width: 30em\) \{[^<]*\}</style>`)

// rootLanguage is what the reading copy puts on a document's <html> from the package's dc:language,
// which the generated editions give as English.
var rootLanguage = regexp.MustCompile(` lang="en" xml:lang="en"`)

func (e *audioEnv) slimPath() string {
	return "/v1/reading/works/" + e.child + "/publications/12/file?format=readaloud&audio=omit"
}

func (e *audioEnv) slim(headers map[string]string) *httptest.ResponseRecorder {
	e.t.Helper()
	return e.trackAt(http.MethodGet, e.slimPath(), headers)
}

// epubWatch counts what the hub does with the edition: how often it is opened and
// closed, and how many bytes of it are read.
type epubWatch struct {
	opened, closed *atomic.Int32
	bytes          *atomic.Int64
}

type readCounter struct {
	readingdomain.ReadOnlyFile
	closed *atomic.Int32
	bytes  *atomic.Int64
}

func (c *readCounter) ReadAt(p []byte, offset int64) (int, error) {
	n, err := c.ReadOnlyFile.ReadAt(p, offset)
	c.bytes.Add(int64(n))
	return n, err
}

func (c *readCounter) Read(p []byte) (int, error) {
	n, err := c.ReadOnlyFile.Read(p)
	c.bytes.Add(int64(n))
	return n, err
}

func (c *readCounter) Close() error {
	c.closed.Add(1)
	return c.ReadOnlyFile.Close()
}

func (e *audioEnv) watchEPUB() epubWatch {
	watch := epubWatch{opened: new(atomic.Int32), closed: new(atomic.Int32), bytes: new(atomic.Int64)}
	e.server.openEPUB = func(roots []config.MediaRemovalRoot, service, remote string) (readingdomain.MediaFile, error) {
		file, err := readingdomain.ResolveEPUBFile(roots, service, remote)
		if err != nil {
			return file, err
		}
		watch.opened.Add(1)
		file.ReadOnlyFile = &readCounter{ReadOnlyFile: file.ReadOnlyFile, closed: watch.closed, bytes: watch.bytes}
		return file, nil
	}
	return watch
}

func zipNames(t *testing.T, data []byte) ([]string, *zip.Reader) {
	t.Helper()
	reader, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatalf("not a zip: %v", err)
	}
	names := make([]string, len(reader.File))
	for i, entry := range reader.File {
		names[i] = entry.Name
	}
	return names, reader
}

func zipEntry(t *testing.T, reader *zip.Reader, name string) []byte {
	t.Helper()
	for _, entry := range reader.File {
		if entry.Name == name {
			stream, err := entry.Open()
			if err != nil {
				t.Fatal(err)
			}
			defer stream.Close()
			data, err := io.ReadAll(stream)
			if err != nil {
				t.Fatalf("%s: %v", name, err)
			}
			return data
		}
	}
	t.Fatalf("no %s in the archive", name)
	return nil
}

func TestSlimReadaloudIsTheEditionWithoutItsAudio(t *testing.T) {
	env := newAlignedTrackedEnv(t, alignedOptions{audioBytes: 256 << 10})
	response := env.slim(nil)
	if response.Code != http.StatusOK {
		t.Fatalf("slim = %d: %s", response.Code, response.Body.String())
	}
	body := response.Body.Bytes()
	names, reduced := zipNames(t, body)

	originalBytes, err := os.ReadFile(env.build.epub.Path)
	if err != nil {
		t.Fatal(err)
	}
	originalNames, original := zipNames(t, originalBytes)
	audio := map[string]bool{}
	for _, name := range env.build.epub.Audio {
		audio[name] = true
	}
	var want []string
	for _, name := range originalNames {
		if !audio[name] {
			want = append(want, name)
		}
	}
	if strings.Join(names, "\n") != strings.Join(want, "\n") {
		t.Fatalf("entries\n%q\nwant\n%q", names, want)
	}
	if reduced.File[0].Name != "mimetype" || reduced.File[0].Method != zip.Store || string(zipEntry(t, reduced, "mimetype")) != "application/epub+zip" {
		t.Fatalf("the archive does not open on a stored mimetype: %+v", reduced.File[0].FileHeader)
	}
	// Every entry is as it was, but for the one <style> that each document has
	// been given in its head (the reading copy, reading_epub_copy.go).
	for _, name := range want {
		got, wasOriginally := zipEntry(t, reduced, name), zipEntry(t, original, name)
		if strings.HasSuffix(name, ".xhtml") {
			if !rootLanguage.Match(got) {
				t.Errorf("%s has no language", name)
			}
			got = rootLanguage.ReplaceAll(columnStyleElement.ReplaceAll(got, nil), nil)
		}
		if !bytes.Equal(got, wasOriginally) {
			t.Errorf("%s changed", name)
		}
	}
	if !columnStyleElement.Match(zipEntry(t, reduced, "OEBPS/text/part0001.xhtml")) {
		t.Error("a chapter of the slim edition has no column style")
	}
	if len(body)*10 > len(originalBytes) {
		t.Fatalf("the slim edition is %d bytes of %d: the audio is in it", len(body), len(originalBytes))
	}

	sum := sha256.Sum256(body)
	header := response.Header()
	if header.Get("Content-Type") != "application/epub+zip" || header.Get("Content-Length") != itoa(len(body)) || header.Get("Accept-Ranges") != "bytes" {
		t.Errorf("type %q length %q ranges %q", header.Get("Content-Type"), header.Get("Content-Length"), header.Get("Accept-Ranges"))
	}
	if !regexp.MustCompile(`^"[0-9a-f]{32}"$`).MatchString(header.Get("ETag")) || header.Get("Last-Modified") == "" {
		t.Errorf("etag %q, last modified %q", header.Get("ETag"), header.Get("Last-Modified"))
	}
	if header.Get("X-Reading-Content-Hash") != "sha256:"+hex.EncodeToString(sum[:]) {
		t.Errorf("hash %q", header.Get("X-Reading-Content-Hash"))
	}
	if header.Get("Cache-Control") != "private, no-store" || header.Get("X-Content-Type-Options") != "nosniff" {
		t.Errorf("cache %q nosniff %q", header.Get("Cache-Control"), header.Get("X-Content-Type-Options"))
	}
	var everything strings.Builder
	for name, values := range header {
		everything.WriteString(name + ": " + strings.Join(values, ",") + "\n")
	}
	for _, bad := range []string{env.root, filepath.Base(filepath.Dir(env.root)), "aligned.epub", "/library", "Fixture"} {
		if strings.Contains(everything.String(), bad) {
			t.Errorf("a header holds %q:\n%s", bad, everything.String())
		}
	}
	if env.state.fileCalls != 0 {
		t.Fatalf("Storyteller's own file route was called %d times", env.state.fileCalls)
	}
}

func TestSlimReadaloudAnswersRangesAndConditionsLikeAFile(t *testing.T) {
	env := newAlignedTrackedEnv(t, alignedOptions{})
	whole := env.slim(nil)
	body, etag := whole.Body.Bytes(), whole.Header().Get("ETag")

	// Resumed in two pieces it is the file it began as.
	first := env.slim(map[string]string{"Range": "bytes=0-99"})
	second := env.slim(map[string]string{"Range": "bytes=100-"})
	if first.Code != http.StatusPartialContent || second.Code != http.StatusPartialContent ||
		first.Header().Get("Content-Range") != "bytes 0-99/"+itoa(len(body)) || !bytes.Equal(append(append([]byte(nil), first.Body.Bytes()...), second.Body.Bytes()...), body) {
		t.Fatalf("pieces = %d %q, %d", first.Code, first.Header().Get("Content-Range"), second.Code)
	}
	if first.Header().Get("ETag") != etag {
		t.Fatalf("a piece has another etag: %q", first.Header().Get("ETag"))
	}
	// The end of an archive is where its directory is.
	tail := env.slim(map[string]string{"Range": "bytes=-22"})
	if tail.Code != http.StatusPartialContent || !bytes.HasPrefix(tail.Body.Bytes(), []byte("PK\x05\x06")) {
		t.Fatalf("the last 22 bytes = %d %q", tail.Code, tail.Body.Bytes())
	}
	if got := env.slim(map[string]string{"Range": "bytes=99999999-"}); got.Code != http.StatusRequestedRangeNotSatisfiable {
		t.Fatalf("past the end = %d", got.Code)
	}
	if got := env.slim(map[string]string{"Range": "bytes=0-1,5-6"}); got.Code != http.StatusBadRequest {
		t.Fatalf("two ranges = %d", got.Code)
	}
	if got := env.slim(map[string]string{"Range": "bytes=0-9", "If-Range": `"another"`}); got.Code != http.StatusOK || !bytes.Equal(got.Body.Bytes(), body) {
		t.Fatalf("a stale If-Range = %d", got.Code)
	}
	if got := env.slim(map[string]string{"Range": "bytes=0-9", "If-Range": etag}); got.Code != http.StatusPartialContent {
		t.Fatalf("a current If-Range = %d", got.Code)
	}
	if got := env.slim(map[string]string{"If-None-Match": etag}); got.Code != http.StatusNotModified || got.Body.Len() != 0 {
		t.Fatalf("not modified = %d with %d bytes", got.Code, got.Body.Len())
	}
	head := env.trackAt(http.MethodHead, env.slimPath(), nil)
	if head.Code != http.StatusOK || head.Body.Len() != 0 || head.Header().Get("Content-Length") != itoa(len(body)) {
		t.Fatalf("HEAD = %d, %d bytes, length %q", head.Code, head.Body.Len(), head.Header().Get("Content-Length"))
	}
	// The same bytes every time.
	if again := env.slim(nil); !bytes.Equal(again.Body.Bytes(), body) || again.Header().Get("ETag") != etag {
		t.Fatal("the slim edition changed between two requests")
	}
}

// The slim edition is a choice made with a query; the file route's other uses
// are as they were.
func TestSlimReadaloudLeavesTheFullFileAlone(t *testing.T) {
	env := newAlignedTrackedEnv(t, alignedOptions{})
	env.state.noFiles = false
	env.state.readaloud = true
	base := "/v1/reading/works/" + env.child + "/publications/12/file"
	full := env.get(base + "?format=readaloud")
	if full.Code != http.StatusPartialContent || full.Body.String() != "4567" || env.state.formatSeen != "readaloud" || env.state.fileCalls != 1 {
		t.Fatalf("the full edition = %d %q (format %q, %d calls)", full.Code, full.Body.String(), env.state.formatSeen, env.state.fileCalls)
	}
	if slim := env.slim(nil); slim.Code != http.StatusOK || env.state.fileCalls != 1 {
		t.Fatalf("the slim edition = %d with %d calls to Storyteller", slim.Code, env.state.fileCalls)
	}
	for name, query := range map[string]string{
		"another value":             "?format=readaloud&audio=keep",
		"a value in the wrong case": "?format=readaloud&audio=OMIT",
		"no format":                 "?audio=omit",
		"the ebook":                 "?format=ebook&audio=omit",
		"the audiobook archive":     "?format=audiobook&audio=omit",
	} {
		if got := env.get(base + query); got.Code != http.StatusBadRequest || decodeAudioError(t, got).Code != CodeInvalidRequest {
			t.Errorf("%s = %d: %s", name, got.Code, got.Body.String())
		}
	}
	if env.state.fileCalls != 1 {
		t.Fatalf("a refused request reached Storyteller")
	}
}

// What it costs: a pass over the directory and the text, once for as long as the
// file is the same, and none of the audio read at all.
func TestSlimReadaloudIsBuiltOnceFromTheTextAndNeverReadsTheAudio(t *testing.T) {
	env := newAlignedTrackedEnv(t, alignedOptions{audioBytes: 4 << 20})
	watch := env.watchEPUB()
	info, err := os.Stat(env.build.epub.Path)
	if err != nil {
		t.Fatal(err)
	}
	first := env.slim(nil)
	if first.Code != http.StatusOK {
		t.Fatalf("slim = %d", first.Code)
	}
	built := watch.bytes.Load()
	if built > 512<<10 || built*20 > info.Size() {
		t.Fatalf("building read %d bytes of a %d byte edition: the audio was read", built, info.Size())
	}
	for i := 0; i < 3; i++ {
		env.slim(nil)
	}
	if got := watch.bytes.Load(); got != built {
		t.Fatalf("three more requests read %d more bytes: the slim edition was built again", got-built)
	}
	if watch.opened.Load() != 4 || watch.closed.Load() != 4 {
		t.Fatalf("opened %d and closed %d for four requests", watch.opened.Load(), watch.closed.Load())
	}

	// A new edition under the same name is a new slim edition.
	rewriteAlignedEPUB(t, env.build.epub.Path, func(opf string) string {
		return strings.Replace(opf, "Aligned fixture", "Aligned fixture, second edition", 1)
	})
	second := env.slim(nil)
	if second.Code != http.StatusOK || second.Header().Get("ETag") == first.Header().Get("ETag") || bytes.Equal(second.Body.Bytes(), first.Body.Bytes()) {
		t.Fatalf("a changed edition was served as it was: %d %q", second.Code, second.Header().Get("ETag"))
	}
	if !bytes.Contains(zipEntry(t, mustZip(t, second.Body.Bytes()), "OEBPS/content.opf"), []byte("second edition")) {
		t.Fatal("the new text is not in the slim edition")
	}
	if watch.bytes.Load() <= built {
		t.Fatal("a changed edition was not read again")
	}
}

func mustZip(t *testing.T, data []byte) *zip.Reader {
	t.Helper()
	_, reader := zipNames(t, data)
	return reader
}

// Many apps asking at once make one pass.
func TestSlimReadaloudIsBuiltOnceForAnyNumberOfAskers(t *testing.T) {
	one := newAlignedTrackedEnv(t, alignedOptions{audioBytes: 1 << 20})
	single := one.watchEPUB()
	one.slim(nil)
	perBuild := single.bytes.Load()

	many := newAlignedTrackedEnv(t, alignedOptions{audioBytes: 1 << 20})
	watch := many.watchEPUB()
	bodies := make([][]byte, 8)
	var wg sync.WaitGroup
	for i := range bodies {
		wg.Add(1)
		go func() {
			defer wg.Done()
			bodies[i] = many.slim(nil).Body.Bytes()
		}()
	}
	within(t, "eight requests", wg.Wait)
	for i, body := range bodies {
		if !bytes.Equal(body, bodies[0]) || len(body) == 0 {
			t.Fatalf("asker %d was given %d bytes that differ from the first's", i, len(body))
		}
	}
	if got := watch.bytes.Load(); got != perBuild {
		t.Fatalf("eight askers read %d bytes, one build reads %d", got, perBuild)
	}
	if watch.opened.Load() != 8 || watch.closed.Load() != 8 {
		t.Fatalf("opened %d and closed %d", watch.opened.Load(), watch.closed.Load())
	}
}

func TestSlimReadaloudSaysWhyItCannotBeServed(t *testing.T) {
	large := func(t *testing.T) {
		previous := maxEPUBCopyBytes
		t.Cleanup(func() { maxEPUBCopyBytes = previous })
		maxEPUBCopyBytes = 1000
	}
	for _, test := range []struct {
		name     string
		options  alignedOptions
		prepare  func(t *testing.T)
		reason   string
		wantCode int
	}{
		{"the edition is not on the disk", alignedOptions{epub: func(path string) { os.Remove(path) }}, nil, "missing_file", http.StatusConflict},
		{"the edition's folder is mapped nowhere", alignedOptions{readaloud: `{"uuid":"a","filepath":"/data/assets/Book/aligned.epub","status":"ALIGNED"}`}, nil, "unmapped_root", http.StatusConflict},
		{"the edition is not an archive", alignedOptions{epub: func(path string) { os.WriteFile(path, []byte("not a zip archive"), 0o644) }}, nil, "unreadable", http.StatusConflict},
		{"the edition is a track", alignedOptions{readaloud: `{"uuid":"a","filepath":"/library/audiobooks/Fixture Odyssey/Fixture Odyssey.mp3","status":"ALIGNED"}`}, nil, "unreadable", http.StatusConflict},
		{"the edition's path climbs out of its folder", alignedOptions{readaloud: `{"uuid":"a","filepath":"/library/../../etc/aligned.epub","status":"ALIGNED"}`}, nil, "unmapped_root", http.StatusConflict},
		{"the edition has no path", alignedOptions{readaloud: `{"uuid":"a","filepath":"","status":"ALIGNED"}`}, nil, "unreadable", http.StatusConflict},
		{"its text alone is more than the hub will hold", alignedOptions{}, large, "unsupported_layout", http.StatusConflict},
		{"the edition is still being aligned", alignedOptions{readaloud: `{"uuid":"a","filepath":"/library/audiobooks/Fixture Odyssey/aligned.epub","status":"PROCESSING"}`}, nil, "", http.StatusNotFound},
	} {
		t.Run(test.name, func(t *testing.T) {
			if test.prepare != nil {
				test.prepare(t)
			}
			env := newAlignedTrackedEnv(t, test.options)
			watch := env.watchEPUB()
			response := env.slim(nil)
			if response.Code != test.wantCode {
				t.Fatalf("status = %d: %s", response.Code, response.Body.String())
			}
			got := decodeAudioError(t, response)
			if test.reason != "" && (got.Code != "audio_not_streamable" || got.Reason != test.reason || got.Retryable || got.Message == "") {
				t.Fatalf("error = %+v, want audio_not_streamable / %s", got, test.reason)
			}
			for _, bad := range []string{env.root, "aligned.epub", "/library", "etc", "zip archive"} {
				if strings.Contains(response.Body.String(), bad) {
					t.Errorf("the error holds %q: %s", bad, response.Body.String())
				}
			}
			if watch.opened.Load() != watch.closed.Load() {
				t.Fatalf("opened %d and closed %d", watch.opened.Load(), watch.closed.Load())
			}
		})
	}

	// A book that never had an edition, and a token that may not read.
	plain := newTrackedAudioEnv(t)
	if got := plain.slim(nil); got.Code != http.StatusNotFound {
		t.Fatalf("a book with no read-along edition = %d", got.Code)
	}
	denied := newAudioEnv(t, audioEnvOptions{scopes: []string{"read"}}, func(string) audioBuild { return audioBuild{} })
	if got := denied.trackAt(http.MethodGet, "/v1/reading/works/rw_00000000000000000000000000000000/publications/12/file?format=readaloud&audio=omit", nil); got.Code != http.StatusForbidden {
		t.Fatalf("without the scope = %d", got.Code)
	}
}

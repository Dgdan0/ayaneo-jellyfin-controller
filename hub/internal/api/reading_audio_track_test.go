package api

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"ayaneohub/internal/config"
	readingdomain "ayaneohub/internal/reading"
)

func (e *audioEnv) trackPath(work string, index string, rev string) string {
	path := "/v1/reading/works/" + work + "/publications/12/audio/tracks/" + index
	if rev != "" {
		path += "?rev=" + rev
	}
	return path
}

// track asks for one track's bytes, with the given extra headers.
func (e *audioEnv) track(method string, index int, rev string, headers map[string]string) *httptest.ResponseRecorder {
	e.t.Helper()
	return e.trackAt(method, e.trackPath(e.child, strconv.Itoa(index), rev), headers)
}

func (e *audioEnv) trackAt(method, path string, headers map[string]string) *httptest.ResponseRecorder {
	e.t.Helper()
	request := httptest.NewRequest(method, path, nil)
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	for name, value := range headers {
		request.Header.Set(name, value)
	}
	recorder := httptest.NewRecorder()
	e.handler.ServeHTTP(recorder, request)
	return recorder
}

func (e *audioEnv) bytesOf(name string) []byte {
	e.t.Helper()
	data, err := os.ReadFile(e.file(name))
	if err != nil {
		e.t.Fatal(err)
	}
	return data
}

// The generated M4B is 96 KiB of a known pattern: a track big enough for ranges
// to mean something.
func newByteRangeEnv(t *testing.T) (*audioEnv, ReadingAudioManifest, []byte) {
	t.Helper()
	env := newM4BAudioEnv(t, chapteredLinks)
	manifest := env.manifest()
	return env, manifest, env.bytesOf("Fixture Chapters.m4b")
}

func TestAudioTrackServesTheWholeFileWithItsValidatorsAndNothingElse(t *testing.T) {
	env := newTrackedAudioEnv(t)
	manifest := env.manifest()
	for index, name := range trackedTagOrder {
		response := env.track(http.MethodGet, index, manifest.Revision, nil)
		want := env.bytesOf(name)
		if response.Code != http.StatusOK || response.Body.String() != string(want) {
			t.Fatalf("track %d = %d, %d bytes of %d", index, response.Code, response.Body.Len(), len(want))
		}
		kind, _ := readingdomain.AudioKindOf(name)
		header := response.Header()
		for name, value := range map[string]string{
			"Content-Type":           kind.MIME,
			"Content-Length":         strconv.Itoa(len(want)),
			"Accept-Ranges":          "bytes",
			"ETag":                   manifest.Tracks[index].ETag,
			"Cache-Control":          "private, max-age=3600",
			"X-Content-Type-Options": "nosniff",
		} {
			if header.Get(name) != value {
				t.Errorf("track %d: %s = %q, want %q", index, name, header.Get(name), value)
			}
		}
		info, _ := os.Stat(env.file(name))
		if header.Get("Last-Modified") != info.ModTime().UTC().Format(http.TimeFormat) {
			t.Errorf("track %d: Last-Modified = %q", index, header.Get("Last-Modified"))
		}
		for _, leak := range []string{"Content-Disposition", "Set-Cookie", "X-Internal-Path"} {
			if header.Get(leak) != "" {
				t.Errorf("track %d has a %s header", index, leak)
			}
		}
	}
	if env.state.fileCalls != 0 {
		t.Fatalf("Storyteller's archive was built %d times", env.state.fileCalls)
	}

	m4b, m4bManifest, stored := newByteRangeEnv(t)
	response := m4b.track(http.MethodGet, 0, m4bManifest.Revision, nil)
	if response.Code != http.StatusOK || response.Header().Get("Content-Type") != "audio/mp4" || response.Body.String() != string(stored) {
		t.Fatalf("m4b = %d %q, %d bytes", response.Code, response.Header().Get("Content-Type"), response.Body.Len())
	}
}

func TestAudioTrackRangeTable(t *testing.T) {
	env, manifest, stored := newByteRangeEnv(t)
	size := len(stored)
	for _, test := range []struct {
		header       string
		status       int
		from, to     int // the bytes of the file expected, to exclusive
		contentRange string
	}{
		{"bytes=0-0", 206, 0, 1, fmt.Sprintf("bytes 0-0/%d", size)},
		{"bytes=0-", 206, 0, size, fmt.Sprintf("bytes 0-%d/%d", size-1, size)},
		{"bytes=-1", 206, size - 1, size, fmt.Sprintf("bytes %d-%d/%d", size-1, size-1, size)},
		{"bytes=-500000", 206, 0, size, fmt.Sprintf("bytes 0-%d/%d", size-1, size)},
		{"bytes=100-199", 206, 100, 200, fmt.Sprintf("bytes 100-199/%d", size)},
		{fmt.Sprintf("bytes=%d-", size-4), 206, size - 4, size, fmt.Sprintf("bytes %d-%d/%d", size-4, size-1, size)},
		{fmt.Sprintf("bytes=%d-%d", size-1, size+500), 206, size - 1, size, fmt.Sprintf("bytes %d-%d/%d", size-1, size-1, size)},
		{fmt.Sprintf("bytes=%d-", size), 416, 0, 0, fmt.Sprintf("bytes */%d", size)},
		{"bytes=999999-", 416, 0, 0, fmt.Sprintf("bytes */%d", size)},
		{"bytes=5-2", 416, 0, 0, ""},
	} {
		t.Run(test.header, func(t *testing.T) {
			response := env.track(http.MethodGet, 0, manifest.Revision, map[string]string{"Range": test.header})
			if response.Code != test.status {
				t.Fatalf("status = %d: %s", response.Code, response.Body.String())
			}
			if got := response.Header().Get("Content-Range"); got != test.contentRange {
				t.Errorf("Content-Range = %q, want %q", got, test.contentRange)
			}
			if test.status == 206 {
				if response.Body.String() != string(stored[test.from:test.to]) {
					t.Errorf("the body is not bytes %d to %d of the file", test.from, test.to)
				}
				if response.Header().Get("Content-Length") != strconv.Itoa(test.to-test.from) ||
					response.Header().Get("ETag") != manifest.Tracks[0].ETag || response.Header().Get("Content-Type") != "audio/mp4" {
					t.Errorf("a partial answer lost its headers: %v", response.Header())
				}
			}
		})
	}

	// The hub takes one range, which is all a player asks for.
	for _, header := range []string{"bytes=0-1,5-6", "bytes=0-1, 5-6", "bytes=abc", "bytes=", "bytes=-", "items=0-1", "bytes=0-1-2", "0-1"} {
		response := env.track(http.MethodGet, 0, manifest.Revision, map[string]string{"Range": header})
		if response.Code != http.StatusBadRequest || decodeAudioError(t, response).Code != CodeInvalidRequest {
			t.Errorf("Range %q = %d: %s", header, response.Code, response.Body.String())
		}
	}
}

func TestAudioTrackIfRangeAndConditionalRequests(t *testing.T) {
	env, manifest, stored := newByteRangeEnv(t)
	etag := manifest.Tracks[0].ETag
	lastModified := env.track(http.MethodGet, 0, manifest.Revision, nil).Header().Get("Last-Modified")
	if lastModified == "" {
		t.Fatal("no Last-Modified")
	}
	older := time.Now().Add(-240 * time.Hour).UTC().Format(http.TimeFormat)
	later := time.Now().Add(240 * time.Hour).UTC().Format(http.TimeFormat)

	for _, test := range []struct {
		name    string
		headers map[string]string
		status  int
		bytes   int
	}{
		{"If-Range matches, so the range is honoured", map[string]string{"Range": "bytes=10-19", "If-Range": etag}, 206, 10},
		{"If-Range is a stale tag, so the whole file comes", map[string]string{"Range": "bytes=10-19", "If-Range": `"stale"`}, 200, len(stored)},
		{"If-Range is a weak tag, which never matches", map[string]string{"Range": "bytes=10-19", "If-Range": "W/" + etag}, 200, len(stored)},
		{"If-Range is the modified time", map[string]string{"Range": "bytes=10-19", "If-Range": lastModified}, 206, 10},
		{"If-Range is an older date", map[string]string{"Range": "bytes=10-19", "If-Range": older}, 200, len(stored)},
		{"If-None-Match is the tag", map[string]string{"If-None-Match": etag}, 304, 0},
		{"If-None-Match is a list holding the tag", map[string]string{"If-None-Match": `"other", ` + etag}, 304, 0},
		{"If-None-Match is another tag", map[string]string{"If-None-Match": `"other"`}, 200, len(stored)},
		{"If-Modified-Since is the modified time", map[string]string{"If-Modified-Since": lastModified}, 304, 0},
		{"If-Modified-Since is later", map[string]string{"If-Modified-Since": later}, 304, 0},
		{"If-Modified-Since is older", map[string]string{"If-Modified-Since": older}, 200, len(stored)},
	} {
		t.Run(test.name, func(t *testing.T) {
			response := env.track(http.MethodGet, 0, manifest.Revision, test.headers)
			if response.Code != test.status || response.Body.Len() != test.bytes {
				t.Fatalf("status %d with %d bytes, want %d with %d", response.Code, response.Body.Len(), test.status, test.bytes)
			}
			if test.status == 304 && (response.Header().Get("ETag") != etag || response.Header().Get("Content-Length") != "") {
				t.Errorf("a 304 keeps its tag and carries no length: %v", response.Header())
			}
		})
	}

	long := env.track(http.MethodGet, 0, manifest.Revision, map[string]string{"Range": "bytes=0-1", "If-Range": strings.Repeat("a", 513)})
	if long.Code != http.StatusBadRequest {
		t.Errorf("an overlong If-Range = %d", long.Code)
	}
}

func TestAudioTrackAnswersHEADWithHeadersAndNoBody(t *testing.T) {
	env, manifest, stored := newByteRangeEnv(t)
	response := env.track(http.MethodHead, 0, manifest.Revision, nil)
	if response.Code != http.StatusOK || response.Body.Len() != 0 {
		t.Fatalf("HEAD = %d with %d bytes", response.Code, response.Body.Len())
	}
	for name, want := range map[string]string{
		"Content-Length": strconv.Itoa(len(stored)), "Content-Type": "audio/mp4", "ETag": manifest.Tracks[0].ETag, "Accept-Ranges": "bytes",
	} {
		if got := response.Header().Get(name); got != want {
			t.Errorf("HEAD %s = %q, want %q", name, got, want)
		}
	}
	ranged := env.track(http.MethodHead, 0, manifest.Revision, map[string]string{"Range": "bytes=0-9"})
	if ranged.Code != http.StatusPartialContent || ranged.Body.Len() != 0 || ranged.Header().Get("Content-Range") != fmt.Sprintf("bytes 0-9/%d", len(stored)) {
		t.Errorf("ranged HEAD = %d %q", ranged.Code, ranged.Header().Get("Content-Range"))
	}
}

func TestAudioTrackNeedsTheRevisionTheManifestGave(t *testing.T) {
	env := newTrackedAudioEnv(t)
	manifest := env.manifest()
	for _, rev := range []string{"", "xyz", "0123456789A", "0123456789abc", "0123456789ab%20", "0123456789%41b", "../../../etc"} {
		response := env.trackAt(http.MethodGet, env.trackPath(env.child, "0", rev), nil)
		if response.Code != http.StatusBadRequest || decodeAudioError(t, response).Code != CodeInvalidRequest {
			t.Errorf("rev %q = %d: %s", rev, response.Code, response.Body.String())
		}
	}
	stale := env.track(http.MethodGet, 0, "000000000000", nil)
	got := decodeAudioError(t, stale)
	if stale.Code != http.StatusPreconditionFailed || got.Code != "audio_changed" || got.Message == "" || got.Retryable {
		t.Fatalf("a stale revision = %d %+v", stale.Code, got)
	}
	if ok := env.track(http.MethodGet, 0, manifest.Revision, nil); ok.Code != http.StatusOK {
		t.Fatalf("the manifest's own revision = %d", ok.Code)
	}
}

// The manifest is held for a minute; the files can change inside it. A track
// request is checked against the file, not only against the held list, so a
// rescan cannot play another file under an old index.
func TestAudioTrackNoticesAFileThatChangedInsideTheMinute(t *testing.T) {
	for name, change := range map[string]func(env *audioEnv){
		"it grew": func(env *audioEnv) { env.append("Fixture Odyssey (1).mp3", "a few more bytes") },
		"its time changed": func(env *audioEnv) {
			os.Chtimes(env.file("Fixture Odyssey (1).mp3"), time.Now(), time.Now().Add(-3*time.Hour))
		},
		"another file in its place": func(env *audioEnv) {
			if err := os.WriteFile(env.file("replacement.tmp"), []byte("a different file, longer than it was before it was swapped"), 0o644); err != nil {
				t.Fatal(err)
			}
			if err := os.Rename(env.file("replacement.tmp"), env.file("Fixture Odyssey (1).mp3")); err != nil {
				t.Fatal(err)
			}
		},
	} {
		t.Run(name, func(t *testing.T) {
			env := newTrackedAudioEnv(t)
			old := env.manifest()
			change(env)

			response := env.track(http.MethodGet, 1, old.Revision, nil)
			if response.Code != http.StatusPreconditionFailed || decodeAudioError(t, response).Code != "audio_changed" {
				t.Fatalf("a changed file under the held revision = %d with %d bytes", response.Code, response.Body.Len())
			}
			// The held list was dropped, so the manifest now says what is on disk.
			fresh := env.manifest()
			if fresh.Revision == old.Revision {
				t.Fatalf("the manifest was not rebuilt after the file changed")
			}
			again := env.track(http.MethodGet, 1, fresh.Revision, nil)
			if again.Code != http.StatusOK || again.Body.String() != string(env.bytesOf("Fixture Odyssey (1).mp3")) {
				t.Fatalf("the new revision = %d", again.Code)
			}
		})
	}
}

func TestAudioTrackRefusesIndexesThatAreNotTracks(t *testing.T) {
	env := newTrackedAudioEnv(t)
	manifest := env.manifest()
	for index, want := range map[string]int{
		"0": 200, "4": 200,
		"5": 404, "7": 404, "100000": 404,
		"abc": 400, "-1": 400, "01": 400, "+1": 400, "1.0": 400, "0x1": 400, "1e1": 400, "%20": 400, "99999999999999999999": 400,
	} {
		response := env.trackAt(http.MethodGet, env.trackPath(env.child, index, manifest.Revision), nil)
		if response.Code != want {
			t.Errorf("track %q = %d, want %d", index, response.Code, want)
		}
	}
	if got := env.get(env.trackPath(env.child, "", manifest.Revision)).Code; got != http.StatusNotFound {
		t.Errorf("no track number = %d", got)
	}
}

func TestAudioTrackSaysWhyItsFileCannotBeServed(t *testing.T) {
	env := newTrackedAudioEnv(t)
	manifest := env.manifest()
	if err := os.Remove(env.file("Fixture Odyssey (2).mp3")); err != nil {
		t.Fatal(err)
	}
	response := env.track(http.MethodGet, 2, manifest.Revision, nil)
	got := decodeAudioError(t, response)
	if response.Code != http.StatusConflict || got.Code != "audio_not_streamable" || got.Reason != "missing_file" {
		t.Fatalf("a file that vanished = %d %+v", response.Code, got)
	}
	// And the held list is gone with it: the next manifest tells the same.
	if again := env.get(env.audioPath(env.child)); again.Code != http.StatusConflict {
		t.Fatalf("the manifest after the file vanished = %d", again.Code)
	}
}

func TestAudioTrackNeedsTheReadingScopeAndABookThatIsTheWorks(t *testing.T) {
	noScope := newAudioEnv(t, audioEnvOptions{scopes: []string{"read"}}, func(root string) audioBuild { return audioBuild{} })
	response := noScope.trackAt(http.MethodGet, noScope.trackPath("rw_00000000000000000000000000000000", "0", "0123456789ab"), nil)
	if response.Code != http.StatusForbidden || decodeAudioError(t, response).Code != CodeForbiddenScope {
		t.Fatalf("without the scope = %d: %s", response.Code, response.Body.String())
	}

	env := newTrackedAudioEnv(t)
	manifest := env.manifest()
	for path, want := range map[string]int{
		env.trackPath(env.child, "0", manifest.Revision):                                                                200,
		env.trackPath(env.collection, "0", manifest.Revision):                                                           200,
		"/v1/reading/works/" + env.child + "/publications/999/audio/tracks/0?rev=" + manifest.Revision:                  404,
		"/v1/reading/works/not-a-work/publications/12/audio/tracks/0?rev=" + manifest.Revision:                          400,
		"/v1/reading/works/rw_00000000000000000000000000000000/publications/12/audio/tracks/0?rev=" + manifest.Revision: 404,
	} {
		if got := env.get(path).Code; got != want {
			t.Errorf("%s = %d, want %d", path, got, want)
		}
	}
	none := newAudioEnv(t, audioEnvOptions{}, func(string) audioBuild { return audioBuild{} })
	if got := none.track(http.MethodGet, 0, "0123456789ab", nil); got.Code != http.StatusNotFound {
		t.Errorf("a book with no audiobook = %d", got.Code)
	}
}

// The book's record is held a day, so a listener keeps hearing the book when
// Storyteller restarts: the bytes come from the hub's own files.
func TestAudioTrackKeepsPlayingWhileStorytellerIsDown(t *testing.T) {
	env := newTrackedAudioEnv(t)
	manifest := env.manifest()
	env.upstream.Close()
	response := env.track(http.MethodGet, 0, manifest.Revision, nil)
	if response.Code != http.StatusOK || response.Body.String() != string(env.bytesOf(trackedTagOrder[0])) {
		t.Fatalf("with Storyteller down = %d", response.Code)
	}
}

func TestAudioTrackSaysNothingOfTheHostInAnyHeaderOrBody(t *testing.T) {
	env := newTrackedAudioEnv(t)
	manifest := env.manifest()
	forbidden := []string{env.root, filepath.Base(filepath.Dir(env.root)), "/library", `\\`, "Fixture", ".mp3", ".MP3", "Ünï", "פרק"}
	for _, file := range env.build.book.Files {
		forbidden = append(forbidden, file.Name)
	}
	check := func(what string, response *httptest.ResponseRecorder) {
		t.Helper()
		var all strings.Builder
		for name, values := range response.Header() {
			all.WriteString(name + ": " + strings.Join(values, ",") + "\n")
		}
		// The bytes of a track are the book's, not the host's; only what is not the
		// file itself is searched.
		if response.Code != http.StatusOK && response.Code != http.StatusPartialContent {
			all.WriteString(response.Body.String())
		}
		for _, bad := range forbidden {
			if strings.Contains(all.String(), bad) {
				t.Errorf("%s: holds %q:\n%s", what, bad, all.String())
			}
		}
	}
	check("200", env.track(http.MethodGet, 0, manifest.Revision, nil))
	check("206", env.track(http.MethodGet, 0, manifest.Revision, map[string]string{"Range": "bytes=0-9"}))
	check("304", env.track(http.MethodGet, 0, manifest.Revision, map[string]string{"If-None-Match": manifest.Tracks[0].ETag}))
	check("416", env.track(http.MethodGet, 0, manifest.Revision, map[string]string{"Range": "bytes=999999999-"}))
	check("412", env.track(http.MethodGet, 0, "000000000000", nil))
	check("400", env.track(http.MethodGet, 0, "", nil))
	check("404", env.track(http.MethodGet, 9, manifest.Revision, nil))
	if err := os.Remove(env.file(trackedTagOrder[1])); err != nil {
		t.Fatal(err)
	}
	check("409", env.track(http.MethodGet, 1, manifest.Revision, nil))
}

// closeCounter is a read-only handle that counts its being let go.
type closeCounter struct {
	readingdomain.ReadOnlyFile
	closed *atomic.Int32
}

func (c *closeCounter) Close() error {
	c.closed.Add(1)
	return c.ReadOnlyFile.Close()
}

// watchHandles makes every file the hub opens count its opening and its closing.
func (e *audioEnv) watchHandles() (opened, closed *atomic.Int32) {
	opened, closed = new(atomic.Int32), new(atomic.Int32)
	e.server.openMedia = func(roots []config.MediaRemovalRoot, service, remote string) (readingdomain.MediaFile, error) {
		file, err := readingdomain.ResolveMediaFile(roots, service, remote)
		if err != nil {
			return file, err
		}
		opened.Add(1)
		file.ReadOnlyFile = &closeCounter{ReadOnlyFile: file.ReadOnlyFile, closed: closed}
		return file, nil
	}
	return opened, closed
}

func TestAudioTrackLetsGoOfItsFileWhateverTheAnswer(t *testing.T) {
	env, manifest, _ := newByteRangeEnv(t)
	opened, closed := env.watchHandles()
	env.forget()
	manifest = env.manifest()
	for name, test := range map[string]struct {
		method  string
		rev     string
		headers map[string]string
	}{
		"whole":         {http.MethodGet, manifest.Revision, nil},
		"a range":       {http.MethodGet, manifest.Revision, map[string]string{"Range": "bytes=5-50"}},
		"not modified":  {http.MethodGet, manifest.Revision, map[string]string{"If-None-Match": manifest.Tracks[0].ETag}},
		"unsatisfiable": {http.MethodGet, manifest.Revision, map[string]string{"Range": "bytes=99999999-"}},
		"head":          {http.MethodHead, manifest.Revision, nil},
		"stale":         {http.MethodGet, "000000000000", nil},
	} {
		before := opened.Load()
		response := env.track(test.method, 0, test.rev, test.headers)
		if response.Code == 0 {
			t.Fatalf("%s: no answer", name)
		}
		if opened.Load() != closed.Load() {
			t.Errorf("%s: %d handles opened, %d closed", name, opened.Load(), closed.Load())
		}
		// Only a request that had the right revision opens the track at all.
		if name != "stale" && opened.Load() == before {
			t.Errorf("%s: the track was never opened", name)
		}
	}
	// A file that changed is opened, found changed, and let go.
	env.append("Fixture Chapters.m4b", "xx")
	if response := env.track(http.MethodGet, 0, manifest.Revision, nil); response.Code != http.StatusPreconditionFailed {
		t.Fatalf("changed = %d", response.Code)
	}
	if opened.Load() != closed.Load() {
		t.Errorf("after a changed file: %d handles opened, %d closed", opened.Load(), closed.Load())
	}
}

// slowClient is a ResponseWriter for a client that has read the first chunk
// and is waiting. It lets the test cancel the request, or break the pipe, at the
// moment a transfer is half sent.
type slowClient struct {
	header  http.Header
	status  int
	written atomic.Int64
	started chan struct{}
	release chan struct{}
	failAt  int64
	once    atomic.Bool
}

func (c *slowClient) Header() http.Header  { return c.header }
func (c *slowClient) WriteHeader(code int) { c.status = code }
func (c *slowClient) Write(p []byte) (int, error) {
	if c.once.CompareAndSwap(false, true) {
		close(c.started)
		<-c.release
	}
	if c.failAt > 0 && c.written.Load() >= c.failAt {
		return 0, fmt.Errorf("broken pipe")
	}
	c.written.Add(int64(len(p)))
	return len(p), nil
}

func TestAudioTrackLetsGoWhenTheClientCancelsOrTheConnectionBreaks(t *testing.T) {
	env := newM4BAudioEnv(t, chapteredLinks)
	// A track long enough that a few chunks are a fraction of it.
	env.append("Fixture Chapters.m4b", strings.Repeat("0123456789abcdef", 128<<10)) // 2 MiB
	env.forget()
	opened, closed := env.watchHandles()
	manifest := env.manifest()
	size := manifest.Tracks[0].Bytes

	serve := func(ctx context.Context, client *slowClient) {
		request := httptest.NewRequest(http.MethodGet, env.trackPath(env.child, "0", manifest.Revision), nil).WithContext(ctx)
		request.Header.Set("Authorization", "Bearer "+libraryTestToken)
		env.handler.ServeHTTP(client, request)
	}
	run := func(name string, act func(cancel context.CancelFunc, client *slowClient), failAt int64) {
		t.Run(name, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			client := &slowClient{header: http.Header{}, started: make(chan struct{}), release: make(chan struct{}), failAt: failAt}
			done := make(chan struct{})
			go func() {
				serve(ctx, client)
				close(done)
			}()
			select {
			case <-client.started:
			case <-time.After(patient):
				t.Fatal("the transfer never began")
			}
			act(cancel, client)
			select {
			case <-done:
			case <-time.After(patient):
				t.Fatal("the handler is still holding the file")
			}
			if opened.Load() != closed.Load() {
				t.Errorf("%d handles opened, %d closed", opened.Load(), closed.Load())
			}
			if sent := client.written.Load(); sent >= size/2 {
				t.Errorf("%d of %d bytes were sent to a client that had gone", sent, size)
			}
		})
	}
	run("the client cancels", func(cancel context.CancelFunc, client *slowClient) {
		cancel()
		close(client.release)
	}, 0)
	run("the connection breaks", func(_ context.CancelFunc, client *slowClient) {
		close(client.release)
	}, 64<<10)
}

// Over a real connection, with the server's own write timeout far shorter than
// the transfer. The deadline's arithmetic is pinned in stream_deadline_test.go
// with an injected clock; these show that the route uses it, and assert on
// outcome rather than on how fast (see timing_test.go).

// stallingTrackEnv is a track of 8 MiB behind a real server whose write timeout
// is far shorter than the transfer, under the given stall window.
func stallingTrackEnv(t *testing.T, window time.Duration) (env *audioEnv, manifest ReadingAudioManifest, url string, opened, closed *atomic.Int32) {
	t.Helper()
	env = newM4BAudioEnv(t, chapteredLinks)
	env.append("Fixture Chapters.m4b", strings.Repeat("0123456789abcdef", 512<<10)) // 8 MiB
	env.forget()
	opened, closed = env.watchHandles()
	env.server.audioStall = stallPolicy{Window: window, Step: 64 << 10}
	manifest = env.manifest()

	server := httptest.NewUnstartedServer(env.handler)
	server.Config.WriteTimeout = stallingServerWriteTimeout
	server.Config.ConnState = smallBuffers
	server.Start()
	t.Cleanup(server.Close)
	return env, manifest, server.URL + env.trackPath(env.child, "0", manifest.Revision), opened, closed
}

func getTrack(t *testing.T, url string) *http.Response {
	t.Helper()
	request, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	response, err := slowReaderClient().Do(request)
	if err != nil {
		t.Fatal(err)
	}
	return response
}

func TestAudioTrackToASlowButMovingListenerOutlivesTheServersWriteTimeout(t *testing.T) {
	// The window is far longer than the pause between this client's reads.
	_, manifest, url, opened, closed := stallingTrackEnv(t, patient)
	response := getTrack(t, url)
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", response.StatusCode)
	}
	buffer := make([]byte, 256<<10)
	total, reads := int64(0), 0
	for {
		n, err := response.Body.Read(buffer)
		total += int64(n)
		reads++
		if err != nil {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if total != manifest.Tracks[0].Bytes {
		t.Fatalf("heard %d of %d bytes", total, manifest.Tracks[0].Bytes)
	}
	// The sleeps are a floor that no machine can undercut, and it is longer than
	// the server's write timeout: a transfer under that timeout would have been cut.
	if pacing := time.Duration(reads-1) * 5 * time.Millisecond; pacing <= stallingServerWriteTimeout {
		t.Fatalf("%d reads is too few to outlive the server's %v write timeout", reads, stallingServerWriteTimeout)
	}
	eventually(t, "the file to be let go", func() bool { return opened.Load() == closed.Load() })
}

func TestAudioTrackToAListenerWhoStoppedReadingIsLetGoWithItsFile(t *testing.T) {
	// A short window is the thing waited out; the test waits as long as it takes.
	_, _, url, opened, closed := stallingTrackEnv(t, 300*time.Millisecond)
	response := getTrack(t, url)
	defer response.Body.Close()
	if _, err := io.ReadFull(response.Body, make([]byte, 64<<10)); err != nil {
		t.Fatal(err)
	}
	// Nothing more is read, and the connection stays open and quiet.
	eventually(t, "the file to be let go", func() bool { return opened.Load() == closed.Load() })
}

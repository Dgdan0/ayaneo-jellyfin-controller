package repackage

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// The queue and the cache behind it: one repackage at a time, a finished file kept
// until it is released or has gone unfetched for long enough, a cap that makes the
// queue wait rather than throw away a file nobody has fetched yet, and a restart
// that keeps what was finished. The builds here are fakes; the real ffmpeg is in
// run_test.go.

const unit = 1000 // a fake file is a few units of this many bytes

var idN atomic.Int64

// newID is a grant id: 32 hex digits.
func newID() string { return fmt.Sprintf("%032x", idN.Add(1)) }

type clock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *clock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *clock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
}

type rig struct {
	t     *testing.T
	dir   string
	clock *clock
	m     *Manager
}

func newRig(t *testing.T, mutate func(*Options)) *rig {
	t.Helper()
	r := &rig{t: t, dir: t.TempDir(), clock: &clock{now: time.Date(2026, 10, 7, 12, 0, 0, 0, time.UTC)}}
	r.m = r.open(mutate)
	return r
}

func (r *rig) open(mutate func(*Options)) *Manager {
	r.t.Helper()
	options := Options{
		Dir: r.dir, MaxBytes: 5 * unit, MaxAge: 48 * time.Hour, Now: r.clock.Now,
		FreeBytes: func(string) (uint64, bool) { return 0, false },
		Tick:      time.Hour,
	}
	if mutate != nil {
		mutate(&options)
	}
	m, err := NewManager(options)
	if err != nil {
		r.t.Fatal(err)
	}
	r.t.Cleanup(m.Close)
	return m
}

// fileOf is a build that writes size bytes of one repeated byte, reporting some
// progress, after waiting for gate (when there is one).
func fileOf(size int, fill byte, gate <-chan struct{}, started chan<- string) BuildFunc {
	return func(ctx context.Context, job Job) error {
		job.Progress(10)
		if started != nil {
			started <- job.ID
		}
		if gate != nil {
			select {
			case <-gate:
			case <-ctx.Done():
				return ctx.Err()
			}
		}
		job.Progress(60)
		return os.WriteFile(job.Out, bytes.Repeat([]byte{fill}, size), 0o600)
	}
}

func spec(signature string, size int, build BuildFunc) Spec {
	return Spec{Signature: signature, EstimateBytes: int64(size), Build: build}
}

func eventually(t *testing.T, what string, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for !condition() {
		if time.Now().After(deadline) {
			t.Fatalf("gave up waiting until %s", what)
		}
		time.Sleep(2 * time.Millisecond)
	}
}

func (r *rig) status(id string) Status {
	r.t.Helper()
	status, found := r.m.Status(id)
	if !found {
		r.t.Fatalf("%s is not known", id)
	}
	return status
}

func (r *rig) waitFor(id string, state State) Status {
	r.t.Helper()
	eventually(r.t, id[len(id)-4:]+" is "+string(state), func() bool {
		status, found := r.m.Status(id)
		return found && status.State == state
	})
	return r.status(id)
}

func readAll(t *testing.T, a *Artifact) []byte {
	t.Helper()
	data, err := io.ReadAll(a)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func filesIn(t *testing.T, dir string) []string {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, entry := range entries {
		names = append(names, entry.Name())
	}
	return names
}

func TestARepackageRunsAndItsFileIsServedWithItsSizeAndETag(t *testing.T) {
	r := newRig(t, nil)
	id := newID()
	first := r.m.Ensure(id, spec("plan-a", 2*unit, fileOf(2*unit, 'x', nil, nil)))
	if first.State != StateQueued && first.State != StatePreparing {
		t.Fatalf("the first answer was %s", first.State)
	}
	ready := r.waitFor(id, StateReady)
	if ready.SizeBytes != 2*unit || ready.Percent != 100 || ready.QueuePosition != 0 {
		t.Fatalf("ready = %+v", ready)
	}
	if len(ready.ETag) < 10 || ready.ETag[0] != '"' || ready.ETag[len(ready.ETag)-1] != '"' || ready.ETag[1] == 'W' {
		t.Fatalf("etag = %q, want a strong quoted validator", ready.ETag)
	}
	artifact, found := r.m.Open(id)
	if !found {
		t.Fatal("a ready file could not be opened")
	}
	defer artifact.Close()
	if artifact.Size != 2*unit || artifact.ETag != ready.ETag {
		t.Fatalf("artifact = %d %q", artifact.Size, artifact.ETag)
	}
	if data := readAll(t, artifact); !bytes.Equal(data, bytes.Repeat([]byte{'x'}, 2*unit)) {
		t.Fatalf("the file holds %d bytes of something else", len(data))
	}
	// Only the finished file and its record are in the folder.
	if names := filesIn(t, r.dir); len(names) != 2 {
		t.Fatalf("folder = %v, want the file and its record", names)
	}
}

func TestRepackagesRunOneAtATimeInTheOrderAsked(t *testing.T) {
	r := newRig(t, func(o *Options) { o.MaxBytes = 100 * unit })
	started := make(chan string, 8)
	gates := []chan struct{}{make(chan struct{}), make(chan struct{}), make(chan struct{})}
	ids := []string{newID(), newID(), newID()}
	var running, peak atomic.Int32
	for at, id := range ids {
		build := fileOf(unit, 'a', gates[at], started)
		wrapped := func(ctx context.Context, job Job) error {
			now := running.Add(1)
			for {
				seen := peak.Load()
				if now <= seen || peak.CompareAndSwap(seen, now) {
					break
				}
			}
			defer running.Add(-1)
			return build(ctx, job)
		}
		r.m.Ensure(id, spec("p", unit, wrapped))
	}
	if first := <-started; first != ids[0] {
		t.Fatalf("started %s first, want the first asked", first)
	}
	// While the first runs the others wait in line, numbered from 1.
	if status := r.status(ids[0]); status.State != StatePreparing || status.QueuePosition != 0 || status.Percent != 10 {
		t.Fatalf("the running one = %+v", status)
	}
	if status := r.status(ids[1]); status.State != StateQueued || status.QueuePosition != 1 || status.Percent != 0 {
		t.Fatalf("the next one = %+v", status)
	}
	if status := r.status(ids[2]); status.State != StateQueued || status.QueuePosition != 2 {
		t.Fatalf("the last one = %+v", status)
	}
	select {
	case other := <-started:
		t.Fatalf("%s started while another was running", other)
	case <-time.After(60 * time.Millisecond):
	}
	for at := range ids {
		if at > 0 {
			if next := <-started; next != ids[at] {
				t.Fatalf("started %s, want %s", next, ids[at])
			}
		}
		close(gates[at])
		r.waitFor(ids[at], StateReady)
	}
	if peak.Load() != 1 {
		t.Fatalf("%d repackages ran at once", peak.Load())
	}
}

func TestProgressIsReportedWhileItRunsAndNeverReachesOneHundredBeforeTheFileIsReady(t *testing.T) {
	r := newRig(t, nil)
	gate := make(chan struct{})
	id := newID()
	r.m.Ensure(id, spec("p", unit, func(ctx context.Context, job Job) error {
		job.Progress(10)
		job.Progress(250) // more than the whole: the last of it is not reported
		<-gate
		return os.WriteFile(job.Out, make([]byte, unit), 0o600)
	}))
	eventually(t, "progress arrives", func() bool { return r.status(id).Percent > 0 })
	if status := r.status(id); status.Percent != 99 || status.State != StatePreparing {
		t.Fatalf("while running = %+v, want 99 percent", status)
	}
	close(gate)
	if status := r.waitFor(id, StateReady); status.Percent != 100 {
		t.Fatalf("ready = %+v", status)
	}
}

func TestAFailureIsStickyAndNamedUntilItIsRetried(t *testing.T) {
	r := newRig(t, nil)
	id := newID()
	failing := spec("p", unit, func(ctx context.Context, job Job) error {
		_ = os.WriteFile(job.Out, []byte("half"), 0o600)
		return &JobError{Code: "source_missing", Message: "The file for this item is not on the media PC."}
	})
	r.m.Ensure(id, failing)
	failed := r.waitFor(id, StateFailed)
	if failed.Err == nil || failed.Err.Code != "source_missing" || failed.Err.Retryable {
		t.Fatalf("failure = %+v", failed.Err)
	}
	if names := filesIn(t, r.dir); len(names) != 0 {
		t.Fatalf("a failed job left %v behind", names)
	}
	// Asking again does not start it again.
	if again := r.m.Ensure(id, spec("p", unit, fileOf(unit, 'b', nil, nil))); again.State != StateFailed {
		t.Fatalf("asking again after a failure gave %s", again.State)
	}
	// A retry does.
	r.m.Retry(id, spec("p", unit, fileOf(unit, 'b', nil, nil)))
	if ready := r.waitFor(id, StateReady); ready.Err != nil {
		t.Fatalf("a retried job still carries %+v", ready.Err)
	}
	// Retrying what is not failed changes nothing.
	r.m.Retry(id, spec("p", unit, fileOf(unit, 'c', nil, nil)))
	artifact, _ := r.m.Open(id)
	defer artifact.Close()
	if data := readAll(t, artifact); data[0] != 'b' {
		t.Fatalf("a retry of a finished file rebuilt it: %q", data[:1])
	}
}

func TestAnErrorThatIsNotTheJobsOwnIsAnInternalFailureThatMayBeRetried(t *testing.T) {
	r := newRig(t, nil)
	id := newID()
	r.m.Ensure(id, spec("p", unit, func(ctx context.Context, job Job) error { return errors.New("the path C:\\secret\\film.mkv broke") }))
	failed := r.waitFor(id, StateFailed)
	if failed.Err.Code != "internal" || !failed.Err.Retryable {
		t.Fatalf("failure = %+v", failed.Err)
	}
	if bytes.Contains([]byte(failed.Err.Message), []byte("secret")) {
		t.Fatalf("the message carries the error's own text: %q", failed.Err.Message)
	}
}

func TestReleaseDeletesTheFileCancelsARunningJobAndForgetsAFailure(t *testing.T) {
	r := newRig(t, func(o *Options) { o.MaxBytes = 100 * unit })
	done, running, failed := newID(), newID(), newID()
	r.m.Ensure(done, spec("p", unit, fileOf(unit, 'd', nil, nil)))
	r.waitFor(done, StateReady)

	started := make(chan string, 1)
	cancelled := make(chan struct{})
	r.m.Ensure(running, spec("p", unit, func(ctx context.Context, job Job) error {
		started <- job.ID
		<-ctx.Done()
		close(cancelled)
		return ctx.Err()
	}))
	<-started
	r.m.Ensure(failed, spec("p", unit, fileOf(unit, 'f', nil, nil)))
	// failed is queued behind the running one; release it before it runs.
	r.m.Release(failed)
	if _, found := r.m.Status(failed); found {
		t.Fatal("a released queued job is still known")
	}

	r.m.Release(running)
	select {
	case <-cancelled:
	case <-time.After(5 * time.Second):
		t.Fatal("releasing a running job did not stop it")
	}
	if _, found := r.m.Status(running); found {
		t.Fatal("a released running job is still known")
	}

	r.m.Release(done)
	if _, found := r.m.Status(done); found {
		t.Fatal("a released file is still known")
	}
	eventually(t, "the folder is empty", func() bool { return len(filesIn(t, r.dir)) == 0 })
	// Releasing what is not there is fine.
	r.m.Release(newID())
}

func TestAFileBeingServedSurvivesItsReleaseUntilTheLastReaderLetsGo(t *testing.T) {
	r := newRig(t, nil)
	id := newID()
	r.m.Ensure(id, spec("p", 2*unit, fileOf(2*unit, 'z', nil, nil)))
	r.waitFor(id, StateReady)
	artifact, _ := r.m.Open(id)
	r.m.Release(id)
	if data := readAll(t, artifact); len(data) != 2*unit {
		t.Fatalf("a released file being read gave %d bytes", len(data))
	}
	artifact.Close()
	eventually(t, "the released file is deleted once nobody reads it", func() bool { return len(filesIn(t, r.dir)) == 0 })
	if _, found := r.m.Open(id); found {
		t.Fatal("a released file can be opened")
	}
}

func TestARestartKeepsFinishedFilesAndThrowsAwayHalfWrittenOnes(t *testing.T) {
	r := newRig(t, nil)
	kept := newID()
	r.m.Ensure(kept, spec("p", 2*unit, fileOf(2*unit, 'k', nil, nil)))
	before := r.waitFor(kept, StateReady)
	r.m.Close()

	// What a crash leaves, and what is not the hub's.
	half, orphanFile, orphanRecord, work := newID(), newID(), newID(), newID()
	write := func(name, body string) {
		if err := os.WriteFile(filepath.Join(r.dir, name), []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	write(half+".mp4.part", "half a film")
	write(orphanFile+".mp4", "a film with no record")
	write(orphanRecord+".json", `{"version":1}`)
	if err := os.MkdirAll(filepath.Join(r.dir, work+".work"), 0o700); err != nil {
		t.Fatal(err)
	}
	write(work+".work/sub-5.srt", "1\n")
	write("notes.txt", "not ours")
	write("movie.mp4", "not ours either")

	restarted := r.open(nil)
	status, found := restarted.Status(kept)
	if !found || status.State != StateReady || status.SizeBytes != 2*unit || status.ETag != before.ETag {
		t.Fatalf("after a restart %+v (found %v), want the same ready file %q", status, found, before.ETag)
	}
	artifact, found := restarted.Open(kept)
	if !found {
		t.Fatal("the kept file cannot be opened")
	}
	artifact.Close()
	for _, gone := range []string{half + ".mp4.part", orphanFile + ".mp4", orphanRecord + ".json", work + ".work"} {
		if _, err := os.Stat(filepath.Join(r.dir, gone)); err == nil {
			t.Errorf("%s survived the restart", gone)
		}
	}
	for _, mine := range []string{"notes.txt", "movie.mp4"} {
		if _, err := os.Stat(filepath.Join(r.dir, mine)); err != nil {
			t.Errorf("%s, which is not the hub's, was removed: %v", mine, err)
		}
	}
	if _, found := restarted.Status(half); found {
		t.Error("a half-written file is a known grant")
	}
}

func TestARestartRefusesAFileThatDoesNotMatchItsRecord(t *testing.T) {
	r := newRig(t, nil)
	id := newID()
	r.m.Ensure(id, spec("p", 2*unit, fileOf(2*unit, 'k', nil, nil)))
	r.waitFor(id, StateReady)
	r.m.Close()
	if err := os.WriteFile(filepath.Join(r.dir, id+".mp4"), []byte("shorter"), 0o600); err != nil {
		t.Fatal(err)
	}
	restarted := r.open(nil)
	if _, found := restarted.Status(id); found {
		t.Fatal("a file of the wrong size was taken for the finished one")
	}
	if names := filesIn(t, r.dir); len(names) != 0 {
		t.Fatalf("a mismatched pair was left: %v", names)
	}
}

func TestCloseStopsARunningJobAndLeavesNoHalfWrittenFile(t *testing.T) {
	r := newRig(t, nil)
	started := make(chan string, 1)
	id := newID()
	r.m.Ensure(id, spec("p", unit, func(ctx context.Context, job Job) error {
		_ = os.WriteFile(job.Out, []byte("half"), 0o600)
		started <- job.ID
		<-ctx.Done()
		return ctx.Err()
	}))
	<-started
	r.m.Close()
	if names := filesIn(t, r.dir); len(names) != 0 {
		t.Fatalf("closing left %v", names)
	}
	// Closing twice is fine.
	r.m.Close()
}

func TestTheQueueWaitsWhenTheCacheIsFullOfFilesNobodyHasFetched(t *testing.T) {
	r := newRig(t, func(o *Options) { o.MaxBytes = 5 * unit })
	a, b, c := newID(), newID(), newID()
	r.m.Ensure(a, spec("p", 2*unit, fileOf(2*unit, 'a', nil, nil)))
	r.waitFor(a, StateReady)
	r.m.Ensure(b, spec("p", 2*unit, fileOf(2*unit, 'b', nil, nil)))
	r.waitFor(b, StateReady)
	// 4 of 5 units used by two files nobody has fetched: a third of 2 would not fit.
	started := make(chan string, 1)
	r.m.Ensure(c, spec("p", 2*unit, fileOf(2*unit, 'c', nil, started)))
	select {
	case <-started:
		t.Fatal("a job started while the cache was full of unfetched files")
	case <-time.After(80 * time.Millisecond):
	}
	if status := r.status(c); status.State != StateQueued || status.QueuePosition != 1 {
		t.Fatalf("waiting job = %+v", status)
	}
	// Nothing was thrown away to make room.
	for _, id := range []string{a, b} {
		if r.status(id).State != StateReady {
			t.Fatalf("%s was evicted unfetched", id)
		}
	}

	// The app fetches the first file in full; the oldest fetched file makes room.
	artifact, _ := r.m.Open(a)
	readAll(t, artifact)
	artifact.Close()
	r.waitFor(c, StateReady)
	if _, found := r.m.Status(a); found {
		t.Error("the fetched file was kept while a job waited for its room")
	}
	if r.status(b).State != StateReady {
		t.Error("the unfetched file was removed")
	}
}

func TestOnlyAFileFetchedInFullIsSpentForRoom(t *testing.T) {
	r := newRig(t, func(o *Options) { o.MaxBytes = 3 * unit })
	a, b := newID(), newID()
	r.m.Ensure(a, spec("p", 2*unit, fileOf(2*unit, 'a', nil, nil)))
	r.waitFor(a, StateReady)
	started := make(chan string, 1)
	r.m.Ensure(b, spec("p", 2*unit, fileOf(2*unit, 'b', nil, started)))

	// The first half twice is not the whole file.
	for i := 0; i < 2; i++ {
		artifact, _ := r.m.Open(a)
		buffer := make([]byte, unit)
		if _, err := io.ReadFull(artifact, buffer); err != nil {
			t.Fatal(err)
		}
		artifact.Close()
	}
	select {
	case <-started:
		t.Fatal("a half-fetched file was spent for room")
	case <-time.After(80 * time.Millisecond):
	}
	// The second half, on another request, completes it.
	artifact, _ := r.m.Open(a)
	if _, err := artifact.Seek(unit, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	readAll(t, artifact)
	artifact.Close()
	r.waitFor(b, StateReady)
}

func TestAFileInTheMiddleOfBeingServedIsNeverSpentForRoom(t *testing.T) {
	r := newRig(t, func(o *Options) { o.MaxBytes = 3 * unit })
	a, b := newID(), newID()
	r.m.Ensure(a, spec("p", 2*unit, fileOf(2*unit, 'a', nil, nil)))
	r.waitFor(a, StateReady)
	artifact, _ := r.m.Open(a)
	readAll(t, artifact) // fetched in full, but still open
	started := make(chan string, 1)
	r.m.Ensure(b, spec("p", 2*unit, fileOf(2*unit, 'b', nil, started)))
	select {
	case <-started:
		t.Fatal("a file that is being served was spent for room")
	case <-time.After(80 * time.Millisecond):
	}
	artifact.Close()
	r.waitFor(b, StateReady)
}

func TestAJobLargerThanTheWholeCacheStillRunsWhenTheCacheIsEmpty(t *testing.T) {
	r := newRig(t, func(o *Options) { o.MaxBytes = 2 * unit })
	id := newID()
	r.m.Ensure(id, spec("p", 5*unit, fileOf(5*unit, 'x', nil, nil)))
	if status := r.waitFor(id, StateReady); status.SizeBytes != 5*unit {
		t.Fatalf("status = %+v", status)
	}
}

func TestAFileNobodyFetchesAgesOutAndFetchingKeepsItAlive(t *testing.T) {
	r := newRig(t, nil)
	idle, used := newID(), newID()
	r.m.Ensure(idle, spec("p", unit, fileOf(unit, 'i', nil, nil)))
	r.waitFor(idle, StateReady)
	r.m.Ensure(used, spec("p", unit, fileOf(unit, 'u', nil, nil)))
	r.waitFor(used, StateReady)

	r.clock.Advance(40 * time.Hour)
	artifact, _ := r.m.Open(used) // asked about at 40 hours
	artifact.Close()
	r.clock.Advance(10 * time.Hour) // 50 hours since ready, 10 since the last fetch
	r.m.Sweep()
	if _, found := r.m.Status(idle); found {
		t.Error("a file nobody fetched in 50 hours is still kept")
	}
	if _, found := r.m.Status(used); !found {
		t.Error("a file fetched 10 hours ago was removed")
	}
	r.clock.Advance(40 * time.Hour)
	r.m.Sweep()
	if _, found := r.m.Status(used); found {
		t.Error("a file unfetched for 50 hours is still kept")
	}
	if names := filesIn(t, r.dir); len(names) != 0 {
		t.Fatalf("aged-out files left %v", names)
	}
}

func TestAFileBeingServedDoesNotAgeOut(t *testing.T) {
	r := newRig(t, nil)
	id := newID()
	r.m.Ensure(id, spec("p", unit, fileOf(unit, 'i', nil, nil)))
	r.waitFor(id, StateReady)
	artifact, _ := r.m.Open(id)
	r.clock.Advance(100 * time.Hour)
	r.m.Sweep()
	if _, found := r.m.Status(id); !found {
		t.Fatal("a file with a download in progress was aged out")
	}
	artifact.Close()
}

func TestAQueuedJobNobodyAsksAboutIsDroppedAfterTheAge(t *testing.T) {
	r := newRig(t, nil)
	block := make(chan struct{})
	started := make(chan string, 1)
	running, waiting, asked := newID(), newID(), newID()
	r.m.Ensure(running, spec("p", unit, fileOf(unit, 'r', block, started)))
	<-started
	r.m.Ensure(waiting, spec("p", unit, fileOf(unit, 'w', nil, nil)))
	r.m.Ensure(asked, spec("p", unit, fileOf(unit, 'a', nil, nil)))

	r.clock.Advance(47 * time.Hour)
	r.m.Status(asked) // the app asks about one of them
	r.m.Ensure(asked, spec("p", unit, fileOf(unit, 'a', nil, nil)))
	r.clock.Advance(2 * time.Hour)
	r.m.Sweep()
	if _, found := r.m.Status(waiting); found {
		t.Error("a queued job nobody asked about for 49 hours was kept")
	}
	if _, found := r.m.Status(asked); !found {
		t.Error("a queued job that was asked about was dropped")
	}
	close(block)
}

func TestADifferentPlanRebuildsAFinishedFile(t *testing.T) {
	r := newRig(t, nil)
	id := newID()
	r.m.Ensure(id, spec("plan-a", unit, fileOf(unit, 'a', nil, nil)))
	first := r.waitFor(id, StateReady)
	// The same plan: nothing to do.
	if again := r.m.Ensure(id, spec("plan-a", unit, fileOf(unit, 'x', nil, nil))); again.State != StateReady || again.ETag != first.ETag {
		t.Fatalf("the same plan gave %+v", again)
	}
	// Another plan: a different file.
	r.m.Ensure(id, spec("plan-b", unit, fileOf(unit, 'b', nil, nil)))
	eventually(t, "the file is rebuilt", func() bool {
		status := r.status(id)
		return status.State == StateReady && status.ETag != first.ETag
	})
	artifact, _ := r.m.Open(id)
	defer artifact.Close()
	if data := readAll(t, artifact); data[0] != 'b' {
		t.Fatalf("the rebuilt file holds %q", data[:1])
	}
}

func TestTooLittleDiskFailsAJobAsDiskFullInsteadOfFillingTheDrive(t *testing.T) {
	r := newRig(t, func(o *Options) {
		o.Reserve = unit
		o.FreeBytes = func(string) (uint64, bool) { return 2 * unit, true }
	})
	big := newID()
	r.m.Ensure(big, spec("p", 5*unit, fileOf(5*unit, 'x', nil, nil)))
	failed := r.waitFor(big, StateFailed)
	if failed.Err.Code != "disk_full" || !failed.Err.Retryable {
		t.Fatalf("failure = %+v", failed.Err)
	}
	small := newID()
	r.m.Ensure(small, spec("p", unit/2, fileOf(unit/2, 'x', nil, nil)))
	r.waitFor(small, StateReady)
}

func TestTheBuildIsGivenAWorkFolderThatIsRemovedAfterwards(t *testing.T) {
	r := newRig(t, nil)
	id := newID()
	seen := make(chan string, 1)
	r.m.Ensure(id, spec("p", unit, func(ctx context.Context, job Job) error {
		if err := os.WriteFile(filepath.Join(job.Work, "sub.srt"), []byte("1\n"), 0o600); err != nil {
			return err
		}
		seen <- job.Work
		return os.WriteFile(job.Out, make([]byte, unit), 0o600)
	}))
	work := <-seen
	r.waitFor(id, StateReady)
	eventually(t, "the work folder is gone", func() bool {
		_, err := os.Stat(work)
		return err != nil
	})
	if filepath.Dir(work) != r.dir {
		t.Fatalf("the work folder %s is not inside the cache", work)
	}
}

func TestABuildThatWritesNothingIsAFailureNotAFile(t *testing.T) {
	r := newRig(t, nil)
	id := newID()
	r.m.Ensure(id, spec("p", unit, func(ctx context.Context, job Job) error { return nil }))
	failed := r.waitFor(id, StateFailed)
	if failed.Err.Code != "internal" {
		t.Fatalf("failure = %+v", failed.Err)
	}
}

func TestAskingAboutManyGrantsAtOnceIsSafe(t *testing.T) {
	r := newRig(t, func(o *Options) { o.MaxBytes = 1000 * unit })
	var wait sync.WaitGroup
	for worker := 0; worker < 8; worker++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			for i := 0; i < 20; i++ {
				id := fmt.Sprintf("%032x", 0xabc000+i)
				r.m.Ensure(id, spec("p", unit, fileOf(unit, 'x', nil, nil)))
				r.m.Status(id)
				if artifact, found := r.m.Open(id); found {
					_, _ = io.Copy(io.Discard, artifact)
					artifact.Close()
				}
				if i%7 == 0 {
					r.m.Release(id)
				}
				r.m.Sweep()
			}
		}()
	}
	wait.Wait()
}

func TestSpansSayWhenTheWholeFileHasBeenCovered(t *testing.T) {
	var spans spanSet
	if spans.covers(10) {
		t.Fatal("nothing covers a file")
	}
	spans.add(0, 4)
	spans.add(4, 6) // touches the first
	spans.add(8, 10)
	if spans.covers(10) {
		t.Fatal("a gap was taken for covered")
	}
	spans.add(5, 9) // overlaps both sides of the gap
	if !spans.covers(10) {
		t.Fatalf("spans %v do not cover 0-10", spans)
	}
	if len(spans) != 1 {
		t.Fatalf("spans are not merged: %v", spans)
	}
	spans.add(3, 3) // nothing
	spans.add(-5, 2)
	if !spans.covers(10) || !spans.covers(0) {
		t.Fatal("covers regressed")
	}
	var empty spanSet
	if !empty.covers(0) {
		t.Fatal("an empty file is covered by nothing, which is all of it")
	}
}

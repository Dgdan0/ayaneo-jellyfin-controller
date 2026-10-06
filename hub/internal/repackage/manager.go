package repackage

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"sync"
	"time"
)

// State is where one grant's MP4 stands.
type State string

const (
	StateQueued    State = "queued"
	StatePreparing State = "preparing"
	StateReady     State = "ready"
	StateFailed    State = "failed"
)

// JobError is why a repackage failed, in words an app can act on. Message is
// written for a person and never carries ffmpeg's own text, which names files.
type JobError struct {
	Code      string
	Message   string
	Retryable bool
}

func (e *JobError) Error() string { return e.Code + ": " + e.Message }

func asJobError(err error) *JobError {
	var job *JobError
	if errors.As(err, &job) {
		copied := *job
		return &copied
	}
	return &JobError{Code: "internal", Message: "The file could not be prepared on the media PC.", Retryable: true}
}

// Status is one grant's file as an app is told of it.
type Status struct {
	State   State
	Percent int
	// QueuePosition is 1 for the job that runs next, and 0 for one that is
	// running, finished or failed.
	QueuePosition int
	SizeBytes     int64     // when ready
	ETag          string    // when ready
	Err           *JobError // when failed
}

// Job is what a build is given.
type Job struct {
	ID string
	// Out is where to write the MP4. It is not yet the finished file's name: the
	// manager renames it when the build returns without an error.
	Out string
	// Work is an empty scratch folder, removed when the build returns.
	Work     string
	Progress func(percent int)
}

// BuildFunc makes the file. It returns a *JobError for a failure an app can act
// on, and any other error is an internal failure.
type BuildFunc func(ctx context.Context, job Job) error

// Spec is what to build and how to tell it from another plan.
type Spec struct {
	Signature     string
	EstimateBytes int64
	Build         BuildFunc
}

// Options configure a Manager.
type Options struct {
	Dir      string
	MaxBytes int64
	MaxAge   time.Duration
	// Reserve is the free space left on the drive after a job: a repackage that
	// would take the drive below it is failed rather than started.
	Reserve   int64
	Now       func() time.Time
	FreeBytes func(dir string) (free uint64, known bool)
	// Tick is how often the worker looks for what has aged out.
	Tick time.Duration
}

type entry struct {
	id      string
	spec    Spec
	state   State
	percent int
	err     *JobError
	seq     uint64
	touched time.Time
	cancel  context.CancelFunc

	signature string
	size      int64
	etag      string
	readyAt   time.Time
	lastUsed  time.Time
	fetched   bool
	covered   spanSet
	opens     int
	// dead is a released file that is still being read: the last reader to let go
	// deletes it.
	dead bool
}

// Manager is the queue and the cache of finished files: one repackage runs at a
// time, in the order they were asked for, and a finished file is kept until it is
// released, until it has gone unfetched for MaxAge, or (once fetched in full and
// idle) until the cache needs its room.
//
// Only files named for a grant id are ever created or deleted in Dir, so a cache
// pointed at a folder that holds other things leaves them alone.
type Manager struct {
	options Options
	ctx     context.Context
	stop    context.CancelFunc
	wake    chan struct{}
	done    sync.WaitGroup
	once    sync.Once

	mu      sync.Mutex
	entries map[string]*entry
	seq     uint64
	closed  bool
}

// What the manager owns in its folder.
var (
	ownedFile = regexp.MustCompile(`^[0-9a-f]{32}\.(mp4|json|mp4\.part|json\.tmp)$`)
	ownedWork = regexp.MustCompile(`^[0-9a-f]{32}\.work$`)
	recordID  = regexp.MustCompile(`^([0-9a-f]{32})\.json$`)
)

type record struct {
	Version   int    `json:"version"`
	ID        string `json:"id"`
	Signature string `json:"signature"`
	Size      int64  `json:"size"`
	ETag      string `json:"etag"`
	ReadyAt   int64  `json:"readyAt"`
	LastUsed  int64  `json:"lastUsed"`
	Fetched   bool   `json:"fetched"`
}

// NewManager opens the cache folder, keeps the finished files in it, throws away
// what a crash left half-written, and starts the worker.
func NewManager(options Options) (*Manager, error) {
	if options.Dir == "" {
		return nil, errors.New("the repackage cache has no folder")
	}
	if options.MaxBytes <= 0 {
		options.MaxBytes = 20 << 30
	}
	if options.MaxAge <= 0 {
		options.MaxAge = 48 * time.Hour
	}
	if options.Reserve <= 0 {
		options.Reserve = 1 << 30
	}
	if options.Now == nil {
		options.Now = time.Now
	}
	if options.FreeBytes == nil {
		options.FreeBytes = diskFree
	}
	if options.Tick <= 0 {
		options.Tick = time.Minute
	}
	if err := os.MkdirAll(options.Dir, 0o700); err != nil {
		return nil, fmt.Errorf("the repackage cache folder: %w", err)
	}
	ctx, stop := context.WithCancel(context.Background())
	m := &Manager{
		options: options, ctx: ctx, stop: stop, wake: make(chan struct{}, 1),
		entries: map[string]*entry{},
	}
	m.recover()
	m.done.Add(1)
	go m.loop()
	return m, nil
}

func (m *Manager) path(id, suffix string) string { return filepath.Join(m.options.Dir, id+suffix) }

// recover keeps each finished file whose record agrees with it and removes what
// else is the manager's: half-written files, files without a record, records
// without a file, and scratch folders.
func (m *Manager) recover() {
	names, err := os.ReadDir(m.options.Dir)
	if err != nil {
		return
	}
	kept := map[string]bool{}
	for _, name := range names {
		if name.IsDir() {
			if ownedWork.MatchString(name.Name()) {
				_ = os.RemoveAll(filepath.Join(m.options.Dir, name.Name()))
			}
			continue
		}
		match := recordID.FindStringSubmatch(name.Name())
		if match == nil {
			continue
		}
		id := match[1]
		body, err := os.ReadFile(m.path(id, ".json"))
		var saved record
		info, statErr := os.Stat(m.path(id, ".mp4"))
		if err != nil || json.Unmarshal(body, &saved) != nil || statErr != nil ||
			saved.Version != 1 || saved.ID != id || saved.Size != info.Size() || saved.Size <= 0 || saved.ETag == "" {
			continue
		}
		kept[id] = true
		m.entries[id] = &entry{
			id: id, state: StateReady, percent: 100, signature: saved.Signature, size: saved.Size, etag: saved.ETag,
			readyAt: time.UnixMilli(saved.ReadyAt), lastUsed: time.UnixMilli(saved.LastUsed), fetched: saved.Fetched,
			touched: m.options.Now(),
		}
	}
	for _, name := range names {
		if name.IsDir() || !ownedFile.MatchString(name.Name()) {
			continue
		}
		if id := name.Name()[:32]; !kept[id] {
			_ = os.Remove(filepath.Join(m.options.Dir, name.Name()))
		} else if name.Name() != id+".mp4" && name.Name() != id+".json" {
			_ = os.Remove(filepath.Join(m.options.Dir, name.Name()))
		}
	}
}

func (m *Manager) nudge() {
	select {
	case m.wake <- struct{}{}:
	default:
	}
}

// Ensure queues the grant's file unless it is already queued, running, finished
// or failed (a failure stays until Retry), and says where it stands. Asking is
// what keeps a queued job from being dropped for want of interest.
func (m *Manager) Ensure(id string, spec Spec) Status {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.options.Now()
	current := m.entries[id]
	switch {
	case current == nil:
		m.queueLocked(id, spec, now)
	case current.state == StateReady && current.signature != spec.Signature && current.opens == 0:
		// The plan changed since the file was made: it is not the file that was promised.
		m.removeLocked(current)
		m.queueLocked(id, spec, now)
	case current.state == StateQueued || current.state == StatePreparing:
		current.spec = spec
		current.touched = now
	default:
		current.touched = now
	}
	return m.statusLocked(m.entries[id])
}

func (m *Manager) queueLocked(id string, spec Spec, now time.Time) {
	m.seq++
	m.entries[id] = &entry{id: id, spec: spec, state: StateQueued, seq: m.seq, touched: now}
	m.nudge()
}

// Retry queues a failed job again. Any other state is left as it is.
func (m *Manager) Retry(id string, spec Spec) Status {
	m.mu.Lock()
	current := m.entries[id]
	if current != nil && current.state == StateFailed {
		m.seq++
		current.spec, current.state, current.err, current.percent = spec, StateQueued, nil, 0
		current.seq, current.touched = m.seq, m.options.Now()
		m.nudge()
		status := m.statusLocked(current)
		m.mu.Unlock()
		return status
	}
	m.mu.Unlock()
	return m.Ensure(id, spec)
}

// Status says where a grant's file stands, or that the manager knows of none.
func (m *Manager) Status(id string) (Status, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	current := m.entries[id]
	if current == nil {
		return Status{}, false
	}
	return m.statusLocked(current), true
}

func (m *Manager) statusLocked(e *entry) Status {
	status := Status{State: e.state, Percent: e.percent}
	switch e.state {
	case StateQueued:
		status.QueuePosition = 1
		for _, other := range m.entries {
			if other.state == StateQueued && other.seq < e.seq {
				status.QueuePosition++
			}
		}
	case StateReady:
		status.SizeBytes, status.ETag = e.size, e.etag
	case StateFailed:
		if e.err != nil {
			copied := *e.err
			status.Err = &copied
		}
	}
	return status
}

// Release forgets a grant's file: a queued or running job is stopped, a failure
// is forgotten, and a finished file is deleted (when its last reader lets go, if
// someone is still reading it).
func (m *Manager) Release(id string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if current := m.entries[id]; current != nil {
		m.removeLocked(current)
	}
	m.nudge()
}

// removeLocked drops an entry and what it holds.
func (m *Manager) removeLocked(e *entry) {
	delete(m.entries, e.id)
	if e.cancel != nil {
		e.cancel()
	}
	if e.state != StateReady {
		return
	}
	if e.opens > 0 {
		e.dead = true
		return
	}
	m.deleteFilesLocked(e.id)
}

func (m *Manager) deleteFilesLocked(id string) {
	_ = os.Remove(m.path(id, ".mp4"))
	_ = os.Remove(m.path(id, ".json"))
	_ = os.Remove(m.path(id, ".json.tmp"))
}

// Sweep removes what has aged out. The worker does it on a timer; it is exported
// for a caller that wants it now.
func (m *Manager) Sweep() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.sweepLocked()
}

func (m *Manager) sweepLocked() {
	now := m.options.Now()
	for _, e := range m.entries {
		switch e.state {
		case StateReady:
			if e.opens == 0 && now.Sub(e.lastUsed) > m.options.MaxAge {
				m.removeLocked(e)
			}
		case StateQueued, StateFailed:
			if now.Sub(e.touched) > m.options.MaxAge {
				m.removeLocked(e)
			}
		}
	}
	m.nudge()
}

func (m *Manager) usedLocked() int64 {
	var used int64
	for _, e := range m.entries {
		if e.state == StateReady {
			used += e.size
		}
	}
	return used
}

// roomLocked makes room for a job of the given size by spending files that have
// been fetched in full and are idle, least recently used first. It never spends
// one that has not been fetched, or that is being read. If that is not enough the
// job waits, unless the cache holds nothing at all: a single file larger than the
// whole cache still gets made.
func (m *Manager) roomLocked(need int64) bool {
	used := m.usedLocked()
	if used+need <= m.options.MaxBytes {
		return true
	}
	var spendable []*entry
	holding := 0
	for _, e := range m.entries {
		if e.state != StateReady {
			continue
		}
		holding++
		if e.fetched && e.opens == 0 {
			spendable = append(spendable, e)
		}
	}
	sort.Slice(spendable, func(i, j int) bool { return spendable[i].lastUsed.Before(spendable[j].lastUsed) })
	for _, e := range spendable {
		used -= e.size
		holding--
		m.removeLocked(e)
		if used+need <= m.options.MaxBytes {
			return true
		}
	}
	return holding == 0
}

// running is a job the worker has taken.
type running struct {
	entry *entry
	spec  Spec
	ctx   context.Context
}

// take picks the next job that can run: the oldest queued one, if there is room
// for it and the drive has the space. A job the drive cannot hold is failed and
// the next is looked at.
func (m *Manager) take() *running {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return nil
	}
	m.sweepLocked()
	for {
		var next *entry
		for _, e := range m.entries {
			if e.state == StateQueued && (next == nil || e.seq < next.seq) {
				next = e
			}
		}
		if next == nil || !m.roomLocked(next.spec.EstimateBytes) {
			return nil
		}
		if free, known := m.options.FreeBytes(m.options.Dir); known &&
			free < uint64(max(0, next.spec.EstimateBytes))+uint64(m.options.Reserve) {
			next.state, next.err = StateFailed, &JobError{
				Code: "disk_full", Message: "There is not enough room on the media PC's drive to prepare this file.", Retryable: true,
			}
			continue
		}
		ctx, cancel := context.WithCancel(m.ctx)
		next.state, next.percent, next.cancel = StatePreparing, 0, cancel
		return &running{entry: next, spec: next.spec, ctx: ctx}
	}
}

func (m *Manager) loop() {
	defer m.done.Done()
	ticker := time.NewTicker(m.options.Tick)
	defer ticker.Stop()
	for {
		if m.ctx.Err() != nil {
			return
		}
		if job := m.take(); job != nil {
			m.run(job)
			continue
		}
		select {
		case <-m.ctx.Done():
			return
		case <-m.wake:
		case <-ticker.C:
			m.Sweep()
		}
	}
}

// run builds one file and settles its entry.
func (m *Manager) run(job *running) {
	e := job.entry
	out, work := m.path(e.id, ".mp4.part"), m.path(e.id, ".work")
	_ = os.RemoveAll(work)
	_ = os.Remove(out)
	buildErr := os.MkdirAll(work, 0o700)
	if buildErr == nil {
		buildErr = job.spec.Build(job.ctx, Job{ID: e.id, Out: out, Work: work, Progress: func(percent int) {
			m.mu.Lock()
			if m.entries[e.id] == e && e.state == StatePreparing {
				e.percent = min(99, max(0, percent))
			}
			m.mu.Unlock()
		}})
	}
	_ = os.RemoveAll(work)

	m.mu.Lock()
	defer m.mu.Unlock()
	e.cancel = nil
	if m.entries[e.id] != e || m.closed || job.ctx.Err() != nil {
		// Released or shut down while it ran: nothing of it is wanted.
		_ = os.Remove(out)
		return
	}
	size := int64(0)
	if buildErr == nil {
		info, err := os.Stat(out)
		if err != nil || info.Size() <= 0 {
			buildErr = errors.New("the build wrote no file")
		} else {
			size = info.Size()
		}
	}
	if buildErr != nil {
		_ = os.Remove(out)
		e.state, e.err = StateFailed, asJobError(buildErr)
		if e.err.Code == "internal" {
			slog.Warn("a repackage failed", "grant", e.id, "error", buildErr)
		}
		return
	}
	_ = os.Remove(m.path(e.id, ".mp4"))
	if err := os.Rename(out, m.path(e.id, ".mp4")); err != nil {
		_ = os.Remove(out)
		e.state, e.err = StateFailed, &JobError{Code: "internal", Message: "The file could not be saved on the media PC.", Retryable: true}
		slog.Warn("a repackaged file could not be moved into place", "grant", e.id, "error", err)
		return
	}
	now := m.options.Now()
	e.state, e.percent, e.err = StateReady, 100, nil
	e.signature, e.size, e.readyAt, e.lastUsed = job.spec.Signature, size, now, now
	e.fetched, e.covered, e.touched = false, nil, now
	sum := sha256.Sum256([]byte(fmt.Sprintf("%s|%s|%d|%d", e.id, e.signature, e.size, now.UnixNano())))
	e.etag = `"` + hex.EncodeToString(sum[:16]) + `"`
	m.saveLocked(e)
	m.roomAfterBuildLocked()
}

// roomAfterBuildLocked spends fetched, idle files while the cache is over its cap.
func (m *Manager) roomAfterBuildLocked() {
	if m.usedLocked() > m.options.MaxBytes {
		m.roomLocked(0)
	}
}

// saveLocked writes a finished file's record, best effort: without it the file is
// not recognised after a restart, which costs a rebuild.
func (m *Manager) saveLocked(e *entry) {
	body, err := json.Marshal(record{
		Version: 1, ID: e.id, Signature: e.signature, Size: e.size, ETag: e.etag,
		ReadyAt: e.readyAt.UnixMilli(), LastUsed: e.lastUsed.UnixMilli(), Fetched: e.fetched,
	})
	if err != nil {
		return
	}
	temporary := m.path(e.id, ".json.tmp")
	if err := os.WriteFile(temporary, body, 0o600); err != nil {
		return
	}
	if err := os.Rename(temporary, m.path(e.id, ".json")); err != nil {
		_ = os.Remove(temporary)
	}
}

// Close stops the worker, stops a running job and removes its half-written file.
// Finished files stay for the next start.
func (m *Manager) Close() {
	m.once.Do(func() {
		m.mu.Lock()
		m.closed = true
		for _, e := range m.entries {
			if e.cancel != nil {
				e.cancel()
			}
		}
		m.mu.Unlock()
		m.stop()
		m.done.Wait()
	})
}

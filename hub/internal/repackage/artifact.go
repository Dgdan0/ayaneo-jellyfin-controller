package repackage

import (
	"io"
	"os"
	"sort"
)

// span is a run of bytes, start inclusive and end exclusive.
type span struct{ start, end int64 }

// spanSet is the bytes of a file that have been sent, as the fewest runs that
// hold them: a file counts as fetched when they cover all of it, which a client
// that asks for the first half twice has not done.
type spanSet []span

func (s *spanSet) add(start, end int64) {
	start = max(0, start)
	if end <= start {
		return
	}
	// A transfer reads on from where it stopped, which is nearly every call.
	if last := len(*s) - 1; last >= 0 && start >= (*s)[last].start && start <= (*s)[last].end {
		(*s)[last].end = max((*s)[last].end, end)
		return
	}
	merged := append(*s, span{start, end})
	sort.Slice(merged, func(i, j int) bool { return merged[i].start < merged[j].start })
	out := merged[:1]
	for _, next := range merged[1:] {
		last := &out[len(out)-1]
		if next.start <= last.end {
			last.end = max(last.end, next.end)
		} else {
			out = append(out, next)
		}
	}
	*s = out
}

func (s spanSet) covers(size int64) bool {
	if size <= 0 {
		return true
	}
	return len(s) == 1 && s[0].start <= 0 && s[0].end >= size
}

// Artifact is a finished file held open for reading. It is an io.ReadSeeker, so
// http.ServeContent can answer ranges from it, and it keeps count of which bytes
// were sent. The file cannot be removed to make room while it is open.
type Artifact struct {
	Size int64
	ETag string

	file     *os.File
	owner    *Manager
	entry    *entry
	position int64
	sent     spanSet
	closed   bool
}

// Open holds a finished file for a transfer.
func (m *Manager) Open(id string) (*Artifact, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	current := m.entries[id]
	if current == nil || current.state != StateReady {
		return nil, false
	}
	file, err := os.Open(m.path(id, ".mp4"))
	if err != nil {
		m.removeLocked(current)
		return nil, false
	}
	if info, err := file.Stat(); err != nil || info.Size() != current.size {
		file.Close()
		m.removeLocked(current)
		return nil, false
	}
	now := m.options.Now()
	current.opens++
	current.lastUsed, current.touched = now, now
	return &Artifact{Size: current.size, ETag: current.etag, file: file, owner: m, entry: current}, true
}

func (a *Artifact) Read(p []byte) (int, error) {
	n, err := a.file.Read(p)
	if n > 0 {
		a.sent.add(a.position, a.position+int64(n))
		a.position += int64(n)
	}
	return n, err
}

func (a *Artifact) Seek(offset int64, whence int) (int64, error) {
	position, err := a.file.Seek(offset, whence)
	if err == nil {
		a.position = position
	}
	return position, err
}

// Close lets the file go. What was sent counts towards the file having been
// fetched, and the file is as recently used as now.
func (a *Artifact) Close() error {
	if a.closed {
		return nil
	}
	a.closed = true
	err := a.file.Close()
	m := a.owner
	m.mu.Lock()
	defer m.mu.Unlock()
	e := a.entry
	e.opens--
	e.lastUsed = m.options.Now()
	for _, run := range a.sent {
		e.covered.add(run.start, run.end)
	}
	if !e.fetched && e.covered.covers(e.size) {
		e.fetched = true
	}
	switch {
	case e.dead && e.opens == 0:
		m.deleteFilesLocked(e.id)
	case !e.dead:
		m.saveLocked(e)
	}
	m.nudge()
	return err
}

var _ io.ReadSeekCloser = (*Artifact)(nil)

package api

import (
	"errors"
	"net/http"
	"time"
)

// JSON responses keep the server's ordinary write budget. Authenticated media
// and EPUB transfers may take much longer over a remote connection.
func prepareLongStream(w http.ResponseWriter) error {
	err := http.NewResponseController(w).SetWriteDeadline(time.Time{})
	if errors.Is(err, http.ErrNotSupported) {
		// ResponseRecorder in handler tests has no network write deadline.
		return nil
	}
	return err
}

// stallPolicy says how a long transfer is kept from outliving the person it is
// for. prepareLongStream lifts the deadline altogether, which is right for a
// transfer that is the whole point of the request, but it lets a phone that
// stopped reading (screen off, out of range, app killed) hold the handler, and
// whatever the handler has open, until the TCP connection itself gives up.
type stallPolicy struct {
	// Window is how long a write may go without progress before the connection
	// is given up on.
	Window time.Duration
	// Step is how many bytes written count as progress.
	Step int
}

// Five minutes is longer than a player will wait on a connection it has paused
// (it reconnects at the right offset), and 128 KB is a few seconds of audio.
var defaultStallPolicy = stallPolicy{Window: 5 * time.Minute, Step: 128 << 10}

// streamUntilStalled replaces the server's absolute write deadline for this
// response with one that only a stall can reach: a window from now, put back to
// a full window each time Step more bytes have been written. A slow client that
// keeps reading is never cut off, however long it takes; one that stops is.
func streamUntilStalled(w http.ResponseWriter, policy stallPolicy) (http.ResponseWriter, error) {
	controller := http.NewResponseController(w)
	stalling := &stallWriter{ResponseWriter: w, policy: policy, now: time.Now, set: controller.SetWriteDeadline}
	if err := stalling.arm(); err != nil {
		if errors.Is(err, http.ErrNotSupported) {
			// ResponseRecorder in handler tests has no network write deadline.
			return w, nil
		}
		return nil, err
	}
	return stalling, nil
}

// stallWriter moves the write deadline as bytes go out. It has no ReadFrom, so
// io.Copy reads through its own buffer and every write passes through here.
type stallWriter struct {
	http.ResponseWriter
	policy  stallPolicy
	now     func() time.Time
	set     func(time.Time) error
	written int
}

// Unwrap keeps net/http.ResponseController features reachable through it.
func (s *stallWriter) Unwrap() http.ResponseWriter { return s.ResponseWriter }

func (s *stallWriter) arm() error { return s.set(s.now().Add(s.policy.Window)) }

func (s *stallWriter) Write(p []byte) (int, error) {
	n, err := s.ResponseWriter.Write(p)
	s.written += n
	if s.written >= s.policy.Step {
		s.written = 0
		// A deadline that cannot be moved leaves the transfer under the one it
		// has; that is not a reason to stop writing.
		_ = s.arm()
	}
	return n, err
}

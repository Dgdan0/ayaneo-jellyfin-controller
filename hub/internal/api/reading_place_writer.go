package api

// Which device wrote a reading place (#62), so an app that finds a newer one can say where it came from ("You listened
// further on ipad-dan"). Storyteller keeps no writer, only a stamp, so the hub notes the token label of its own write
// beside the stamp it gave it, in memory: a place whose stamp is not the noted one was written by something else
// (Storyteller's own app, or a hub that has since restarted) and names nobody, rather than the wrong device.

import (
	"net/http"
	"strconv"
	"sync"
)

type placeWriter struct {
	label string
	stamp int64
}

type placeWriters struct {
	mu   sync.Mutex
	last map[string]placeWriter
}

func newPlaceWriters() *placeWriters { return &placeWriters{last: map[string]placeWriter{}} }

// note records that [label] wrote the place of a source's book, stamped [stamp].
func (p *placeWriters) note(source, id, label string, stamp int64) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.last[source+":"+id] = placeWriter{label: label, stamp: stamp}
}

// of is who wrote the place stamped [stamp], when it is the one that was noted.
func (p *placeWriters) of(source, id string, stamp int64) (string, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	writer, found := p.last[source+":"+id]
	if !found || writer.stamp != stamp {
		return "", false
	}
	return writer.label, true
}

// placeDevice is who wrote the place held for a Storyteller book, stamped [stamp], and whether that is the asker.
func (s *Server) placeDevice(r *http.Request, bookID, stamp int64) (device string, byThisDevice bool) {
	label, known := s.placeWriters.of("storyteller", strconv.FormatInt(bookID, 10), stamp)
	if !known {
		return "", false
	}
	return label, label == TokenFrom(r.Context()).Label
}

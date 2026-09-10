package api

import (
	"context"
	"net/http"
	"time"
)

// handleJellyfinLibraryScan starts the same asynchronous library scan exposed
// by Jellyfin's administration dashboard. The handheld receives no Jellyfin
// credential and cannot select an arbitrary upstream operation.
func (s *Server) handleJellyfinLibraryScan(w http.ResponseWriter, r *http.Request) {
	if !s.requireControl(w, r) {
		return
	}
	if s.jellyfin == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "jellyfin", Message: "Jellyfin is not configured",
		})
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()
	if err := s.jellyfin.RefreshLibrary(ctx); err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}

	// Everything describing library membership or Home rows may now change.
	s.cache.InvalidatePrefix("home:")
	s.cache.InvalidatePrefix("library:")
	s.cache.InvalidatePrefix("detail:")
	s.cache.InvalidatePrefix("search:")
	s.watchLibraryScan()
	writeJSON(w, http.StatusAccepted, map[string]any{"ok": true, "action": "scan_library"})
}

// Jellyfin answers as soon as the scan starts. Refresh the provider-id index a
// few times while the asynchronous task is likely to be importing new titles,
// while collapsing repeated button presses into one watcher.
func (s *Server) watchLibraryScan() {
	s.libraryScanMu.Lock()
	if s.libraryScanWatch {
		s.libraryScanMu.Unlock()
		return
	}
	s.libraryScanWatch = true
	s.libraryScanMu.Unlock()

	go func() {
		defer func() {
			s.libraryScanMu.Lock()
			s.libraryScanWatch = false
			s.libraryScanMu.Unlock()
		}()
		// These are intervals from the previous sweep: 10, 30 and 60 seconds
		// after the scan began.
		for _, wait := range []time.Duration{10 * time.Second, 20 * time.Second, 30 * time.Second} {
			timer := time.NewTimer(wait)
			<-timer.C
			s.sweepIndex(context.Background())
		}
	}()
}

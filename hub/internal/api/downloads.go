package api

import (
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// activityID is a parsed item id: either a torrent or an *arr queue row.
//
// The app never constructs these, it only echoes back what /v1/activity gave it,
// but they still arrive over the network and are parsed strictly. An id that
// reaches a delete endpoint deserves the same suspicion as any other input.
type activityID struct {
	IsTorrent bool
	Hash      string
	Service   string
	QueueID   int
}

func parseActivityID(raw string) (activityID, error) {
	if hash, ok := strings.CutPrefix(raw, "qbit:"); ok {
		hash = strings.ToLower(strings.TrimSpace(hash))
		if len(hash) < 20 || !isHex(hash) {
			return activityID{}, fmt.Errorf("%q is not a torrent hash", hash)
		}
		return activityID{IsTorrent: true, Hash: hash}, nil
	}
	parts := strings.Split(raw, ":")
	if len(parts) == 3 && parts[1] == "queue" {
		if parts[0] != "radarr" && parts[0] != "sonarr" {
			return activityID{}, fmt.Errorf("unknown service %q", parts[0])
		}
		id, err := strconv.Atoi(parts[2])
		if err != nil || id <= 0 {
			return activityID{}, fmt.Errorf("%q is not a queue id", parts[2])
		}
		return activityID{Service: parts[0], QueueID: id}, nil
	}
	return activityID{}, fmt.Errorf("%q is not an activity id", raw)
}

func isHex(s string) bool {
	for _, c := range s {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
			return false
		}
	}
	return len(s) > 0
}

// requireControl gates every mutating download action.
func (s *Server) requireControl(w http.ResponseWriter, r *http.Request) bool {
	if TokenFrom(r.Context()).HasScope("control") {
		return true
	}
	writeError(w, r, http.StatusForbidden, Error{
		Code:    CodeForbiddenScope,
		Message: "this device is not allowed to control downloads",
	})
	return false
}

func (s *Server) handleDownloadStop(w http.ResponseWriter, r *http.Request) {
	s.torrentAction(w, r, "stop")
}

func (s *Server) handleDownloadStart(w http.ResponseWriter, r *http.Request) {
	s.torrentAction(w, r, "start")
}

func (s *Server) torrentAction(w http.ResponseWriter, r *http.Request, action string) {
	if !s.requireControl(w, r) {
		return
	}
	id, err := parseActivityID(r.PathValue("id"))
	if err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: err.Error()})
		return
	}
	if !id.IsTorrent {
		writeError(w, r, http.StatusBadRequest, Error{
			Code:    CodeInvalidRequest,
			Message: "only a download-client item can be started or stopped",
		})
		return
	}
	if s.qbittorrent == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "qbittorrent",
			Message: "qBittorrent is not configured",
		})
		return
	}

	ctx, cancel := timeoutFor(r, 15*time.Second)
	defer cancel()

	if action == "stop" {
		err = s.qbittorrent.Stop(ctx, id.Hash)
	} else {
		err = s.qbittorrent.Start(ctx, id.Hash)
	}
	if err != nil {
		writeUpstreamError(w, r, "qbittorrent", err)
		return
	}
	// The cached activity snapshot now describes the old state.
	s.cache.Invalidate("activity")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "action": action})
}

// handleDownloadDelete removes a transfer.
//
// deleteFiles is opt-in and never the default: deleting the data is not
// recoverable, and a mis-tapped button on a handheld should not be able to do
// it silently.
func (s *Server) handleDownloadDelete(w http.ResponseWriter, r *http.Request) {
	if !s.requireControl(w, r) {
		return
	}
	id, err := parseActivityID(r.PathValue("id"))
	if err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: err.Error()})
		return
	}
	if !id.IsTorrent || s.qbittorrent == nil {
		writeError(w, r, http.StatusBadRequest, Error{
			Code:    CodeInvalidRequest,
			Message: "only a download-client item can be deleted here",
		})
		return
	}
	deleteFiles := r.URL.Query().Get("deleteFiles") == "true"

	ctx, cancel := timeoutFor(r, 15*time.Second)
	defer cancel()

	if err := s.qbittorrent.Delete(ctx, deleteFiles, id.Hash); err != nil {
		writeUpstreamError(w, r, "qbittorrent", err)
		return
	}
	s.cache.Invalidate("activity")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "deletedFiles": deleteFiles})
}

// handleQueueRemove drops an *arr queue row.
//
// This is the fix for the most common failure on the screen. Three independent
// switches, because they mean genuinely different things:
//
//   - removeFromClient: also delete the transfer from qBittorrent
//   - blocklist: never grab this release again -- the right answer for a fake or
//     broken release, and the wrong one for a transient network failure
//   - search: immediately look for a replacement
func (s *Server) handleQueueRemove(w http.ResponseWriter, r *http.Request) {
	if !s.requireControl(w, r) {
		return
	}
	service := r.PathValue("service")
	client, ok := s.arrs[service]
	if !ok {
		writeError(w, r, http.StatusBadRequest, Error{
			Code:    CodeInvalidRequest,
			Message: "unknown service " + service,
		})
		return
	}
	queueID, err := strconv.Atoi(r.PathValue("queueId"))
	if err != nil || queueID <= 0 {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "queueId must be a positive number",
		})
		return
	}

	query := r.URL.Query()
	removeFromClient := query.Get("removeFromClient") != "false" // default true
	blocklist := query.Get("blocklist") == "true"
	search := query.Get("search") == "true"

	ctx, cancel := timeoutFor(r, 20*time.Second)
	defer cancel()

	if err := client.RemoveFromQueue(ctx, queueID, removeFromClient, blocklist, search); err != nil {
		writeUpstreamError(w, r, service, err)
		return
	}
	s.cache.Invalidate("activity")
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":        true,
		"blocklist": blocklist,
		"search":    search,
	})
}

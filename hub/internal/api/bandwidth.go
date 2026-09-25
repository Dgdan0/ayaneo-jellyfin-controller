package api

import (
	"ayaneohub/internal/adapters/qbittorrent"
	"encoding/json"
	"io"
	"net/http"
	"time"
)

func (s *Server) handleBandwidth(w http.ResponseWriter, r *http.Request) {
	if s.qbittorrent == nil {
		writeError(w, r, 404, Error{Code: CodeNotFound, Message: "qBittorrent is not configured"})
		return
	}
	ctx, cancel := timeoutFor(r, 15*time.Second)
	defer cancel()
	state, err := s.qbittorrent.Bandwidth(ctx)
	if err != nil {
		writeUpstreamError(w, r, "qbittorrent", err)
		return
	}
	state.CanControl = TokenFrom(r.Context()).HasScope("control")
	writeJSON(w, 200, state)
}
func (s *Server) handleSetBandwidth(w http.ResponseWriter, r *http.Request) {
	if !s.requireControl(w, r) {
		return
	}
	var change qbittorrent.BandwidthChange
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 2048))
	decoder.DisallowUnknownFields()
	err := decoder.Decode(&change)
	if err == nil {
		var extra any
		if decoder.Decode(&extra) != io.EOF {
			err = io.ErrUnexpectedEOF
		}
	}
	if err == nil {
		err = change.Validate()
	}
	if err != nil {
		writeError(w, r, 400, Error{Code: CodeInvalidRequest, Message: "Provide a valid mode or limits in bytes/sec (0 = unlimited, max 1 GiB/sec)"})
		return
	}
	if s.qbittorrent == nil {
		writeError(w, r, 404, Error{Code: CodeNotFound, Message: "qBittorrent is not configured"})
		return
	}
	ctx, cancel := timeoutFor(r, 20*time.Second)
	defer cancel()
	state, err := s.qbittorrent.SetBandwidth(ctx, change)
	if err != nil {
		writeError(w, r, 502, Error{Code: CodeUpstreamDown, Message: "Could not verify bandwidth settings. Some changes may have applied; refresh before retrying."})
		return
	}
	state.CanControl = true
	writeJSON(w, 200, state)
}
func (s *Server) handlePriority(w http.ResponseWriter, r *http.Request) {
	if !s.requireControl(w, r) {
		return
	}
	id, err := parseActivityID(r.PathValue("id"))
	if err != nil || !id.IsTorrent {
		writeError(w, r, 400, Error{Code: CodeInvalidRequest, Message: "a valid torrent transfer is required"})
		return
	}
	if s.qbittorrent == nil {
		writeError(w, r, 404, Error{Code: CodeNotFound, Message: "qBittorrent is not configured"})
		return
	}
	action := "priority_up"
	if r.Pattern == "POST /v1/downloads/{id}/priority_down" {
		action = "priority_down"
	}
	ctx, cancel := timeoutFor(r, 20*time.Second)
	defer cancel()
	state, err := s.qbittorrent.Bandwidth(ctx)
	if err != nil {
		writeUpstreamError(w, r, "qbittorrent", err)
		return
	}
	if !state.QueueingEnabled {
		writeError(w, r, 409, Error{Code: CodeInvalidRequest, Message: "Torrent queueing is disabled in qBittorrent"})
		return
	}
	priority, err := s.qbittorrent.ChangePriority(ctx, id.Hash, action)
	if err != nil {
		writeUpstreamError(w, r, "qbittorrent", err)
		return
	}
	s.cache.Invalidate("activity")
	writeJSON(w, 200, map[string]any{"ok": true, "action": action, "priority": priority})
}

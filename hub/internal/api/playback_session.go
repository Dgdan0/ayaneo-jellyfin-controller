package api

// Playback sessions: their store, track/version changes, progress events,
// and closing them -- including the abandoned ones.

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"ayaneohub/internal/adapters/jellyfin"
)

func randomPlaybackID() (string, error) {
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return hex.EncodeToString(raw), nil
}

func (s *Server) addPlaybackSession(session *playbackSession) {
	s.playbackMu.Lock()
	s.playbackSessions[session.ID] = session
	s.playbackMu.Unlock()
	session.Timer = time.AfterFunc(s.playbackTTL, func() {
		s.playbackMu.Lock()
		current := s.playbackSessions[session.ID]
		if current == session {
			delete(s.playbackSessions, session.ID)
			s.revokeCastGrantsLocked(session.ID)
		}
		s.playbackMu.Unlock()
		if current == session {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			s.finishPlaybackSession(ctx, session)
		}
	})
}

func (s *Server) playbackSessionForRequest(
	w http.ResponseWriter, r *http.Request,
) (*playbackSession, bool) {
	id := r.PathValue("sessionId")
	if !isHex32(id) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad playback session id"})
		return nil, false
	}
	s.playbackMu.Lock()
	session := s.playbackSessions[id]
	s.playbackMu.Unlock()
	owner := TokenFrom(r.Context()).Label
	requestedUser := strings.TrimSpace(r.Header.Get(jellyfinUserHeader))
	if requestedUser == "" && s.jellyfin != nil {
		requestedUser = s.jellyfin.UserID()
	}
	if session == nil || session.Owner != owner || requestedUser != session.UserID {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such playback session"})
		return nil, false
	}
	if session.Timer != nil {
		session.Timer.Reset(s.playbackTTL)
	}
	return session, true
}

func (s *Server) handlePlaybackSelect(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	var body PlaybackSelectBody
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, playbackBodyLimit)).Decode(&body); err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid playback selection"})
		return
	}
	if body.PositionMillis < 0 || (body.MaxBitrate != nil && (*body.MaxBitrate < 0 || *body.MaxBitrate > maxPlaybackBitrate)) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid playback selection"})
		return
	}
	session.mu.Lock()
	defer session.mu.Unlock()
	previousPlaySession := ""
	if session.Info != nil {
		previousPlaySession = session.Info.PlaySessionID
	}
	session.Prepare.PositionMillis = body.PositionMillis
	session.Prepare.StartMode = "resume"
	if body.MediaSourceID != nil {
		session.Prepare.MediaSourceID = *body.MediaSourceID
	}
	if body.AudioStreamIndex != nil {
		session.Prepare.AudioStreamIndex = body.AudioStreamIndex
	}
	if body.SubtitleStreamIndex != nil {
		session.Prepare.SubtitleStreamIndex = body.SubtitleStreamIndex
	}
	if body.MaxBitrate != nil {
		session.Prepare.MaxBitrate = *body.MaxBitrate
	}
	if body.ForceTranscode != nil {
		session.Prepare.ForceTranscode = *body.ForceTranscode
	}
	ctx, cancel := timeoutFor(r, 30*time.Second)
	defer cancel()
	plan, err := s.negotiatePlayback(ctx, session)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	if previousPlaySession != "" && previousPlaySession != session.Info.PlaySessionID {
		go func() {
			cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 8*time.Second)
			defer cleanupCancel()
			_ = session.Client.CloseTranscode(cleanupCtx, session.DeviceID, previousPlaySession)
		}()
	}
	writeJSON(w, http.StatusOK, plan)
}

func (s *Server) handlePlaybackEvent(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	var body PlaybackEventBody
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<10)).Decode(&body); err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid playback event"})
		return
	}
	allowed := map[string]bool{"started": true, "progress": true, "paused": true, "unpaused": true, "seek": true, "stopped": true}
	if !allowed[body.Type] || body.Sequence < 1 || body.PositionMillis < 0 || body.Volume < 0 || body.Volume > 100 {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid playback event"})
		return
	}
	session.mu.Lock()
	defer session.mu.Unlock()
	if body.Sequence <= session.LastSequence {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "ignored": true})
		return
	}
	maxPosition := session.Plan.DurationMillis + 300_000
	if maxPosition > 300_000 && body.PositionMillis > maxPosition {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "playback position is outside the item"})
		return
	}
	event := jellyfin.PlaybackEvent{
		ItemID: session.Item.ID, MediaSourceID: session.Source.ID,
		PlaySessionID: session.Info.PlaySessionID, PositionTicks: body.PositionMillis * 10_000,
		IsPaused: body.Paused || body.Type == "paused", IsMuted: body.Muted,
		VolumeLevel: body.Volume, AudioStreamIndex: session.Plan.SelectedAudioIndex,
		SubtitleStreamIndex: session.Plan.SelectedSubtitleIndex, PlayMethod: session.Plan.PlayMethod,
		CanSeek: true, EventName: playbackEventName(body.Type),
	}
	endpoint := "/Sessions/Playing/Progress"
	if body.Type == "started" {
		endpoint = "/Sessions/Playing"
	} else if body.Type == "stopped" {
		endpoint = "/Sessions/Playing/Stopped"
	}
	ctx, cancel := timeoutFor(r, 12*time.Second)
	defer cancel()
	if err := session.Client.SendPlaybackEvent(ctx, endpoint, event); err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	// The session report above keeps Jellyfin's dashboard honest; it does not
	// save anything for the user. The position is saved here.
	if body.Type != "started" {
		report := watchReport{
			ItemID: session.Item.ID, PositionTicks: body.PositionMillis * 10_000,
			RuntimeTicks: session.Plan.DurationMillis * 10_000, Final: body.Type == "stopped", At: time.Now(),
		}
		if err := s.recordWatchPosition(ctx, session.Client, report); err != nil {
			if report.Final {
				// Unacknowledged, so the app retries the stop rather than
				// losing where the user stopped.
				writeUpstreamError(w, r, "jellyfin", err)
				return
			}
			slog.Warn("playback position was not saved", "error", err)
		}
	}
	session.LastSequence = body.Sequence
	session.LastPositionMillis = body.PositionMillis
	if body.Type == "started" {
		session.Started = true
	}
	if body.Type == "stopped" {
		session.Stopped = true
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func playbackEventName(kind string) string {
	switch kind {
	case "paused":
		return "Pause"
	case "unpaused":
		return "Unpause"
	case "seek":
		return "TimeUpdate"
	default:
		return "TimeUpdate"
	}
}

func (s *Server) handlePlaybackDelete(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	s.playbackMu.Lock()
	if current := s.playbackSessions[session.ID]; current == session {
		delete(s.playbackSessions, session.ID)
		s.revokeCastGrantsLocked(session.ID)
	}
	if session.Timer != nil {
		session.Timer.Stop()
	}
	s.playbackMu.Unlock()
	ctx, cancel := timeoutFor(r, 12*time.Second)
	defer cancel()
	s.finishPlaybackSession(ctx, session)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) finishPlaybackSession(ctx context.Context, session *playbackSession) {
	session.mu.Lock()
	defer session.mu.Unlock()
	if session.Started && !session.Stopped && session.Info != nil {
		event := jellyfin.PlaybackEvent{
			ItemID: session.Item.ID, MediaSourceID: session.Source.ID,
			PlaySessionID:       session.Info.PlaySessionID,
			PositionTicks:       session.LastPositionMillis * 10_000,
			AudioStreamIndex:    session.Plan.SelectedAudioIndex,
			SubtitleStreamIndex: session.Plan.SelectedSubtitleIndex,
			PlayMethod:          session.Plan.PlayMethod, CanSeek: true, VolumeLevel: 100,
		}
		if err := session.Client.SendPlaybackEvent(ctx, "/Sessions/Playing/Stopped", event); err != nil {
			slog.Warn("playback stop report failed", "error", err)
		}
		if err := s.recordWatchPosition(ctx, session.Client, watchReport{
			ItemID: session.Item.ID, PositionTicks: session.LastPositionMillis * 10_000,
			RuntimeTicks: session.Plan.DurationMillis * 10_000, Final: true, At: time.Now(),
		}); err != nil {
			slog.Warn("abandoned playback position was not saved", "error", err)
		}
		session.Stopped = true
	}
	if session.Info != nil {
		if err := session.Client.CloseTranscode(ctx, session.DeviceID, session.Info.PlaySessionID); err != nil {
			slog.Debug("transcode cleanup did not complete", "error", err)
		}
	}
}

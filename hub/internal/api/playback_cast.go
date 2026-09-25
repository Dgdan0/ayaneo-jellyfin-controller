package api

import (
	"crypto/rand"
	"encoding/base64"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const castGrantTTL = 8 * time.Hour

// The receiver receives only these resource URLs. It never receives the
// app's bearer token, Jellyfin credentials, or a raw Jellyfin stream URL.
type PlaybackCastGrantResponse struct {
	MediaURL     string            `json:"mediaUrl"`
	MIMEType     string            `json:"mimeType"`
	SubtitleURLs map[string]string `json:"subtitleUrls"`
	ExpiresAt    time.Time         `json:"expiresAt"`
}

type playbackCastGrant struct {
	ID        string
	SessionID string
	ExpiresAt time.Time
	Timer     *time.Timer
}

func randomCastGrantID() (string, error) {
	bytes := make([]byte, 32)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(bytes), nil
}

func (s *Server) handleCastGrant(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	id, err := randomCastGrantID()
	if err != nil {
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "could not create TV stream"})
		return
	}
	session.mu.Lock()
	plan := session.Plan
	subtitles := make(map[string]string)
	for _, track := range plan.SubtitleTracks {
		codec := strings.ToLower(track.Codec)
		if track.ExternalURL != "" && (codec == "srt" || codec == "subrip" || codec == "vtt" || codec == "webvtt") {
			key := strconv.Itoa(track.Index)
			subtitles[key] = "/v1/cast/" + id + "/subtitles/" + key
		}
	}
	session.mu.Unlock()
	if plan.MediaURL == "" {
		writeError(w, r, http.StatusConflict, Error{Code: CodeInvalidRequest, Message: "no playable media in this session"})
		return
	}
	mediaURL := "/v1/cast/" + id + "/stream"
	if strings.Contains(plan.MediaURL, "/hls/") {
		mediaURL = strings.Replace(plan.MediaURL,
			"/v1/playback/sessions/"+session.ID+"/hls/", "/v1/cast/"+id+"/hls/", 1)
	}
	grant := &playbackCastGrant{ID: id, SessionID: session.ID, ExpiresAt: time.Now().Add(castGrantTTL)}
	s.playbackMu.Lock()
	if s.playbackSessions[session.ID] != session {
		s.playbackMu.Unlock()
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such playback session"})
		return
	}
	// A new transfer or reconnect invalidates earlier capability URLs.
	s.revokeCastGrantsLocked(session.ID)
	s.castGrants[id] = grant
	grant.Timer = time.AfterFunc(castGrantTTL, func() {
		s.playbackMu.Lock()
		if s.castGrants[id] == grant {
			delete(s.castGrants, id)
		}
		s.playbackMu.Unlock()
	})
	s.playbackMu.Unlock()
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, PlaybackCastGrantResponse{
		MediaURL: mediaURL, MIMEType: plan.MIMEType, SubtitleURLs: subtitles, ExpiresAt: grant.ExpiresAt,
	})
}

// playbackMu must be held.
func (s *Server) revokeCastGrantsLocked(sessionID string) {
	for id, grant := range s.castGrants {
		if grant.SessionID == sessionID {
			delete(s.castGrants, id)
			if grant.Timer != nil {
				grant.Timer.Stop()
			}
		}
	}
}

func (s *Server) castSession(w http.ResponseWriter, r *http.Request) (*playbackSession, *playbackCastGrant, bool) {
	id := r.PathValue("grantId")
	s.playbackMu.Lock()
	grant := s.castGrants[id]
	var session *playbackSession
	if grant != nil && time.Now().Before(grant.ExpiresAt) {
		session = s.playbackSessions[grant.SessionID]
		if session != nil && session.Timer != nil {
			session.Timer.Reset(s.playbackTTL)
		}
	}
	s.playbackMu.Unlock()
	if session == nil {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such TV stream"})
		return nil, nil, false
	}
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Accept-Encoding, Range, If-Range, If-None-Match")
	w.Header().Set("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges, Content-Type")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	return session, grant, true
}

func (s *Server) handleCastOptions(w http.ResponseWriter, r *http.Request) {
	if _, _, ok := s.castSession(w, r); ok {
		w.WriteHeader(http.StatusNoContent)
	}
}

func (s *Server) handleCastStream(w http.ResponseWriter, r *http.Request) {
	session, grant, ok := s.castSession(w, r)
	if !ok {
		return
	}
	session.mu.Lock()
	resource := session.Source.DirectStreamURL
	if resource == "" {
		query := url.Values{
			"static": {"true"}, "mediaSourceId": {session.Source.ID},
			"playSessionId": {session.Info.PlaySessionID}, "deviceId": {session.DeviceID},
		}
		resource = "/Videos/" + session.Item.ID + "/stream?" + query.Encode()
	}
	client, itemID := session.Client, session.Item.ID
	session.mu.Unlock()
	if !validPlaybackResource(resource, itemID) {
		writeError(w, r, http.StatusBadGateway, Error{Code: CodeUpstreamDown, Message: "invalid TV stream"})
		return
	}
	s.proxyPlaybackResourceWithPrefix(w, r, client, resource, false, itemID,
		"/v1/cast/"+grant.ID+"/hls/")
}

func (s *Server) handleCastHLS(w http.ResponseWriter, r *http.Request) {
	session, grant, ok := s.castSession(w, r)
	if !ok {
		return
	}
	decoded, err := base64.RawURLEncoding.DecodeString(r.PathValue("resource"))
	if err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad HLS resource"})
		return
	}
	resource := string(decoded)
	session.mu.Lock()
	client, itemID := session.Client, session.Item.ID
	session.mu.Unlock()
	if !validPlaybackResource(resource, itemID) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad HLS resource"})
		return
	}
	s.proxyPlaybackResourceWithPrefix(w, r, client, resource, true, itemID,
		"/v1/cast/"+grant.ID+"/hls/")
}

func (s *Server) handleCastSubtitle(w http.ResponseWriter, r *http.Request) {
	session, _, ok := s.castSession(w, r)
	if !ok {
		return
	}
	trackID := r.PathValue("trackId")
	session.mu.Lock()
	resource := session.Subtitles[trackID]
	client, itemID := session.Client, session.Item.ID
	trackAllowed := false
	for _, track := range session.Plan.SubtitleTracks {
		codec := strings.ToLower(track.Codec)
		if strconv.Itoa(track.Index) == trackID && track.ExternalURL != "" &&
			(codec == "srt" || codec == "subrip" || codec == "vtt" || codec == "webvtt") {
			trackAllowed = true
			break
		}
	}
	session.mu.Unlock()
	if !trackAllowed || resource == "" || !validPlaybackResource(resource, itemID) {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such TV subtitle"})
		return
	}
	response, err := client.OpenResource(r.Context(), http.MethodGet, resource, nil)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	defer response.Body.Close()
	if response.StatusCode >= 400 {
		w.WriteHeader(response.StatusCode)
		return
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maxSubtitleBytes+1))
	if err != nil || len(body) > maxSubtitleBytes {
		writeError(w, r, http.StatusBadGateway, Error{Code: CodeUpstreamDown, Message: "TV subtitle is unavailable"})
		return
	}
	// The Default Media Receiver accepts WebVTT text tracks, not SRT. Keep
	// caption text untouched and translate only timestamp separators.
	if !strings.HasPrefix(strings.TrimSpace(string(body)), "WEBVTT") {
		body = subtitleTimestamp.ReplaceAllFunc(body, func(value []byte) []byte {
			return []byte(strings.ReplaceAll(string(value), ",", "."))
		})
		body = append([]byte("WEBVTT\n\n"), body...)
	}
	w.Header().Set("Content-Type", "text/vtt; charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(body)))
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(body)
}

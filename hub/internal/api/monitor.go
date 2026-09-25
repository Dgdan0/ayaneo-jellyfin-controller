package api

import (
	"ayaneohub/internal/adapters/jellyfin"
	"ayaneohub/internal/hoststats"
	"net/http"
	"sync"
	"time"
)

type monitorSession struct {
	Title  string `json:"title"`
	Device string `json:"device"`
	Client string `json:"client"`
	Method string `json:"method"`
	Paused bool   `json:"paused"`
}

func (s *Server) handleMonitor(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := timeoutFor(r, 10*time.Second)
	defer cancel()
	var host hoststats.Snapshot
	containers := []hoststats.Container{}
	sessions := []monitorSession{}
	var dockerWarning, sessionWarning string
	var wait sync.WaitGroup
	wait.Add(2)
	go func() { defer wait.Done(); host = hoststats.Collect(ctx) }()
	go func() {
		defer wait.Done()
		values, err := hoststats.Containers(ctx)
		if err != nil {
			dockerWarning = err.Error()
		} else {
			containers = values
		}
	}()
	// Playback is scoped to the selected profile; only aggregate host/container information is global.
	if s.jellyfin != nil {
		c, ok := s.jellyfinForRequest(w, r)
		if !ok {
			wait.Wait()
			return
		}
		values, err := c.Sessions(ctx)
		if err != nil {
			sessionWarning = "Jellyfin sessions unavailable"
		} else {
			sessions = monitorSessions(values, c.UserID())
		}
	} else {
		sessionWarning = "Jellyfin is not configured"
	}
	wait.Wait()
	writeJSON(w, 200, map[string]any{"host": host, "containers": containers, "sessions": sessions, "dockerWarning": dockerWarning, "sessionWarning": sessionWarning, "checkedAt": time.Now().UTC()})
}

func monitorSessions(values []jellyfin.Session, userID string) []monitorSession {
	result := []monitorSession{}
	for _, v := range values {
		if userID == "" || v.UserID != userID || v.NowPlayingItem == nil {
			continue
		}
		result = append(result, monitorSession{Title: v.NowPlayingItem.Name, Device: v.DeviceName, Client: v.Client, Method: v.PlayState.PlayMethod, Paused: v.PlayState.IsPaused})
	}
	return result
}

package api

import (
	"context"
	"net/http"
	"strings"
	"time"

	"ayaneohub/internal/adapters/jellyfin"
	"ayaneohub/internal/cache"
)

// JellyfinUser is intentionally small. Password policy, permissions and every
// other administrative field in Jellyfin's UserDto do not belong on a profile
// picker.
type JellyfinUser struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Selected bool   `json:"selected"`
}

type UsersResponse struct {
	Users   []JellyfinUser `json:"users"`
	Partial []Partial      `json:"partial"`
	Cache   CacheInfo      `json:"cache"`
}

func (s *Server) handleUsers(w http.ResponseWriter, r *http.Request) {
	if s.jellyfin == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "jellyfin", Message: "Jellyfin is not configured",
		})
		return
	}
	selectedID := strings.TrimSpace(r.Header.Get(jellyfinUserHeader))
	if selectedID != "" && !isHex32(selectedID) {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "bad Jellyfin user id",
		})
		return
	}
	if selectedID == "" {
		selectedID = s.jellyfin.UserID()
	}
	w.Header().Add("Vary", jellyfinUserHeader)

	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()
	users, meta, err := cache.Fetch(ctx, s.cache, "jellyfin:users", cache.ServiceOptions,
		func(ctx context.Context) ([]jellyfin.User, error) {
			return s.jellyfin.Users(ctx)
		})
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}

	out := UsersResponse{Users: []JellyfinUser{}, Partial: []Partial{}, Cache: cacheInfoFrom(meta)}
	for _, user := range users {
		if user.Policy.IsDisabled || user.ID == "" || user.Name == "" {
			continue
		}
		out.Users = append(out.Users, JellyfinUser{
			ID: user.ID, Name: user.Name, Selected: strings.EqualFold(user.ID, selectedID),
		})
	}
	writeJSON(w, http.StatusOK, out)
}

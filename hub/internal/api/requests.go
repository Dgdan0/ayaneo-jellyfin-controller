package api

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"ayaneohub/internal/adapters/jellyseerr"
	"ayaneohub/internal/cache"
)

// handleMediaDetail is the screen that answers "where is my thing?".
func (s *Server) handleMediaDetail(w http.ResponseWriter, r *http.Request) {
	key, err := ParseMediaKey(r.PathValue("key"))
	if err != nil {
		writeError(w, r, http.StatusBadRequest, Error{
			Code:    CodeInvalidRequest,
			Message: err.Error(),
		})
		return
	}
	client := s.jellyseerr
	if client == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code:    CodeUpstreamDown,
			Service: "jellyseerr",
			Message: "Jellyseerr is not configured",
		})
		return
	}

	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()

	cacheKey := "detail:" + key.String()
	detail, meta, err := cache.Fetch(ctx, s.cache, cacheKey, cache.Availability,
		func(ctx context.Context) (*jellyseerr.Detail, error) {
			return client.Detail(ctx, key.JellyseerrType(), key.ID)
		})
	if err != nil {
		writeUpstreamError(w, r, "jellyseerr", err)
		return
	}

	scopes := TokenFrom(r.Context()).Scopes
	availability := availabilityFor(detail.MediaInfo)

	out := MediaDetail{
		Media: MediaRef{
			Key:   key.String(),
			Type:  key.Type,
			Title: detail.DisplayTitle(),
			Year:  yearOf(detail.ReleaseDate, detail.FirstAirDate),
			IDs:   MediaID{Tmdb: key.ID},
		},
		Overview:       detail.Overview,
		RuntimeMinutes: detail.Runtime,
		Rating:         detail.VoteAverage,
		Seasons:        detail.NumberOfSeasons,
		Episodes:       detail.NumberOfEpisodes,
		Availability:   availability,
		Cast:           castFrom(detail.Credits),
		Pipeline:       buildPipeline(detail.MediaInfo, key.Type),
		Actions:        actionsFor(availability, scopes),
		Partial:        []Partial{},
		Cache:          cacheInfoFrom(meta),
	}
	if detail.PosterPath != "" {
		out.Media.Poster = imagePrefix + "/w342" + detail.PosterPath
	}
	if detail.BackdropPath != "" {
		out.Media.Backdrop = imagePrefix + "/w780" + detail.BackdropPath
	}
	for _, g := range detail.Genres {
		out.Genres = append(out.Genres, g.Name)
	}
	out.TrailerKey, out.TrailerURL = detail.Trailer()
	for _, season := range detail.Seasons2() {
		// Season 0 is Specials. Kept, because you can legitimately want to
		// search for them, but it is never a default anywhere.
		if season.EpisodeCount == 0 {
			continue
		}
		out.SeasonList = append(out.SeasonList, SeasonOption{
			Number:       season.SeasonNumber,
			Name:         season.Name,
			EpisodeCount: season.EpisodeCount,
			Year:         yearOf(season.AirDate),
		})
	}
	if info := detail.MediaInfo; info != nil {
		out.JellyfinItemID = info.JellyfinMediaID
		if info.TvdbID != nil {
			out.Media.IDs.Tvdb = *info.TvdbID
		}
		out.Media.IDs.Imdb = info.ImdbID
	}
	if ext := detail.ExternalIDs; ext != nil {
		if out.Media.IDs.Imdb == "" {
			out.Media.IDs.Imdb = ext.ImdbID
		}
		if out.Media.IDs.Tvdb == 0 && ext.TvdbID != nil {
			out.Media.IDs.Tvdb = *ext.TvdbID
		}
	}

	writeJSON(w, http.StatusOK, out)
}

type createRequestBody struct {
	Key string `json:"key"`
	// "all" or a list of season numbers. Ignored for movies.
	Seasons any  `json:"seasons,omitempty"`
	Is4k    bool `json:"is4k,omitempty"`
	// All optional, all from GET /v1/requests/options. Omitting them keeps the
	// old behaviour exactly: Jellyseerr applies the server's own defaults.
	ProfileID  int    `json:"profileId,omitempty"`
	RootFolder string `json:"rootFolder,omitempty"`
	ServerID   *int   `json:"serverId,omitempty"`
}

type createRequestResponse struct {
	RequestID    int      `json:"requestId"`
	State        string   `json:"state"`
	Message      string   `json:"message"`
	Availability string   `json:"availability"`
	Pipeline     Pipeline `json:"pipeline"`
}

// handleCreateRequest asks Jellyseerr for a title.
//
// Never retried, here or in the client: a timeout does not mean it did not
// happen, and a duplicate request is a real annoyance to unpick by hand.
func (s *Server) handleCreateRequest(w http.ResponseWriter, r *http.Request) {
	token := TokenFrom(r.Context())
	if !token.HasScope("request") {
		writeError(w, r, http.StatusForbidden, Error{
			Code:    CodeForbiddenScope,
			Message: "this device is not allowed to request titles",
		})
		return
	}

	var body createRequestBody
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<10)).Decode(&body); err != nil {
		writeError(w, r, http.StatusBadRequest, Error{
			Code:    CodeInvalidRequest,
			Message: "body must be JSON with a key",
		})
		return
	}
	key, err := ParseMediaKey(body.Key)
	if err != nil {
		writeError(w, r, http.StatusBadRequest, Error{
			Code:    CodeInvalidRequest,
			Message: err.Error(),
		})
		return
	}
	client := s.jellyseerr
	if client == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code:    CodeUpstreamDown,
			Service: "jellyseerr",
			Message: "Jellyseerr is not configured",
		})
		return
	}

	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()

	payload := jellyseerr.RequestBody{
		MediaType: key.JellyseerrType(),
		// The TMDB id, not Jellyseerr's internal media id. Getting this wrong is
		// the classic mistake with this endpoint and yields a confusing 500.
		MediaID: key.ID,
		Is4k:    body.Is4k,
	}
	if key.Type == "series" {
		// A series with no seasons named is rejected, so default to everything.
		if body.Seasons == nil {
			payload.Seasons = "all"
		} else {
			payload.Seasons = body.Seasons
		}
	}
	// Sent only when the caller actually chose something. A zero profileId is
	// not "the default" to Jellyseerr, it is an invalid profile, so the field
	// has to be absent rather than empty.
	if body.ProfileID > 0 {
		payload.ProfileID = &body.ProfileID
	}
	if body.RootFolder != "" {
		payload.RootFolder = body.RootFolder
	}
	if body.ServerID != nil {
		payload.ServerID = body.ServerID
	}

	created, err := client.CreateRequest(ctx, payload)
	if err != nil {
		writeRequestError(w, r, err)
		return
	}

	// Every cached search page and the detail entry now describe this title
	// wrongly. Dropping them means the screen the user returns to shows the
	// request they just made rather than "not in library".
	s.cache.InvalidatePrefix("search:")
	s.cache.Invalidate("detail:" + key.String())

	out := createRequestResponse{
		RequestID:    created.ID,
		State:        requestStateName(created.Status),
		Message:      requestMessage(created.Status),
		Availability: availabilityFor(created.Media),
		Pipeline:     buildPipeline(created.Media, key.Type),
	}
	writeJSON(w, http.StatusCreated, out)
}

func requestStateName(status int) string {
	switch status {
	case jellyseerr.RequestPending:
		return "pending"
	case jellyseerr.RequestApproved:
		return "approved"
	case jellyseerr.RequestDeclined:
		return "declined"
	case jellyseerr.RequestFailed:
		return "failed"
	case jellyseerr.RequestCompleted:
		return "completed"
	}
	return "unknown"
}

func requestMessage(status int) string {
	switch status {
	case jellyseerr.RequestPending:
		return "Requested — waiting for approval"
	case jellyseerr.RequestApproved:
		return "Approved — looking for a release"
	case jellyseerr.RequestDeclined:
		return "Declined"
	case jellyseerr.RequestFailed:
		return "The request failed"
	case jellyseerr.RequestCompleted:
		return "Already in your library"
	}
	return "Requested"
}

// writeRequestError turns Jellyseerr's typed failures into something the app can
// say out loud.
//
// "You already requested this" and "you are over quota" are both 409s upstream,
// and both deserve better than a generic error toast.
func writeRequestError(w http.ResponseWriter, r *http.Request, err error) {
	text := strings.ToLower(err.Error())
	switch {
	case strings.Contains(text, "duplicate"), strings.Contains(text, "already exists"):
		writeError(w, r, http.StatusConflict, Error{
			Code:    "duplicate_request",
			Service: "jellyseerr",
			Message: "You have already requested this",
		})
	case strings.Contains(text, "quota"):
		writeError(w, r, http.StatusConflict, Error{
			Code:    "quota_exceeded",
			Service: "jellyseerr",
			Message: "You are out of requests for now",
		})
	case strings.Contains(text, "blocklist"):
		writeError(w, r, http.StatusConflict, Error{
			Code:    "blocklisted",
			Service: "jellyseerr",
			Message: "This title is blocklisted",
		})
	case strings.Contains(text, "no seasons"):
		writeError(w, r, http.StatusBadRequest, Error{
			Code:    CodeInvalidRequest,
			Service: "jellyseerr",
			Message: "No seasons available to request",
		})
	default:
		writeUpstreamError(w, r, "jellyseerr", err)
	}
}

func yearOf(dates ...string) int {
	for _, date := range dates {
		if len(date) >= 4 {
			year := 0
			ok := true
			for _, c := range date[:4] {
				if c < '0' || c > '9' {
					ok = false
					break
				}
				year = year*10 + int(c-'0')
			}
			if ok {
				return year
			}
		}
	}
	return 0
}

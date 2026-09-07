package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"ayaneohub/internal/adapters/arr"
	"ayaneohub/internal/adapters/jellyseerr"
	"ayaneohub/internal/cache"
)

// Interactive search: the manual release picker, as Radarr and Sonarr have it.
//
// The reason this exists is visible on this very stack. Gran Torino sat in
// Radarr with no file and nothing obviously wrong; an interactive search
// returned 100 releases of which **every single one** was refused, 99 of them
// carrying "Quality for release in queue already meets cutoff". From the
// outside that looks like the app is broken. This screen is where a stack tells
// you what it is actually doing.

// Release is what the app is allowed to see.
//
// Deliberately not arr.Release. Two of that struct's fields must never leave
// this machine:
//
//   - downloadUrl embeds the Prowlarr API key in clear text -- measured here,
//     40 of 40 releases in one search carried it.
//   - guid is a magnet URI with the full tracker list, up to 1.2 KB apiece.
//
// So the app gets an opaque id and sends it back to grab. That also means a
// token cannot ask the hub to fetch an arbitrary URL, which handing over the
// real thing would allow.
type Release struct {
	ID       string `json:"id"`
	Title    string `json:"title"`
	Indexer  string `json:"indexer"`
	Quality  string `json:"quality"`
	Protocol string `json:"protocol"`

	SizeBytes int64 `json:"sizeBytes"`
	Seeders   int   `json:"seeders"`
	Leechers  int   `json:"leechers"`
	AgeDays   int   `json:"ageDays"`

	ReleaseGroup string   `json:"releaseGroup,omitempty"`
	Languages    []string `json:"languages,omitempty"`
	Freeleech    bool     `json:"freeleech,omitempty"`
	Score        int      `json:"score"`

	// Rejected releases are *shown*, not hidden. On this stack 100 of 100 were
	// rejected, and a screen that hid them would have been an empty screen with
	// no explanation -- which is exactly the state the user was already in.
	Rejected   bool     `json:"rejected"`
	Rejections []string `json:"rejections,omitempty"`
}

type ReleasesResponse struct {
	Key      string    `json:"key"`
	Title    string    `json:"title"`
	Service  string    `json:"service"`
	Season   int       `json:"season,omitempty"`
	Releases []Release `json:"releases"`
	Accepted int       `json:"accepted"`
	Partial  []Partial `json:"partial"`
	Cache    CacheInfo `json:"cache"`
}

// handleReleases runs an interactive search.
//
// Slow on purpose: it asks every indexer. 1-4s here with one indexer, and a
// stack with a dozen takes proportionally longer, so the app gives this its own
// screen with its own spinner rather than blocking a menu on it.
func (s *Server) handleReleases(w http.ResponseWriter, r *http.Request) {
	key, err := ParseMediaKey(r.PathValue("key"))
	if err != nil {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: err.Error(),
		})
		return
	}
	season := 0
	if raw := r.URL.Query().Get("season"); raw != "" {
		season, err = strconv.Atoi(raw)
		if err != nil || season < 0 {
			writeError(w, r, http.StatusBadRequest, Error{
				Code: CodeInvalidRequest, Message: "season must be a whole number",
			})
			return
		}
	}

	// An interactive search is genuinely slow, so it gets its own generous
	// budget rather than the default request timeout.
	ctx, cancel := timeoutFor(r, 120*time.Second)
	defer cancel()

	target, err := s.resolveArrTarget(ctx, key, season)
	if err != nil {
		writeTargetError(w, r, err)
		return
	}

	releases, meta, err := cache.Fetch(ctx, s.cache, target.cacheKey(), cache.Releases,
		func(ctx context.Context) ([]arr.Release, error) {
			if target.isMovie {
				return target.client.MovieReleases(ctx, target.id)
			}
			return target.client.SeasonReleases(ctx, target.id, target.season)
		})
	if err != nil {
		writeUpstreamError(w, r, target.service, err)
		return
	}

	out := ReleasesResponse{
		Key:      key.String(),
		Title:    target.title,
		Service:  target.service,
		Season:   season,
		Releases: make([]Release, 0, len(releases)),
		Partial:  []Partial{},
		Cache:    cacheInfoFrom(meta),
	}
	for i := range releases {
		out.Releases = append(out.Releases, publicRelease(&releases[i]))
		if !releases[i].Rejected {
			out.Accepted++
		}
	}
	sortReleases(out.Releases)
	writeJSON(w, http.StatusOK, out)
}

// publicRelease strips everything that must not leave the hub.
func publicRelease(src *arr.Release) Release {
	return Release{
		ID:           releaseID(src),
		Title:        src.Title,
		Indexer:      src.Indexer,
		Quality:      src.QualityName(),
		Protocol:     src.Protocol,
		SizeBytes:    src.Size,
		Seeders:      src.Seeders,
		Leechers:     src.Leechers,
		AgeDays:      src.Age,
		ReleaseGroup: src.ReleaseGroup,
		Languages:    src.LanguageNames(),
		Freeleech:    src.Freeleech(),
		Score:        src.CustomFormatScore,
		Rejected:     src.Rejected,
		// Verbatim. "Quality for release in queue already meets cutoff" is a
		// sentence that tells you exactly what to do; any paraphrase of it
		// would tell you less.
		Rejections: src.Rejections,
	}
}

// releaseID is a stable, opaque handle.
//
// Derived from the guid rather than the position in the list, so it survives
// the search being re-run -- which it will be, since a grab arriving after the
// cache expired has to search again before it can resolve anything.
func releaseID(src *arr.Release) string {
	sum := sha256.Sum256([]byte(strconv.Itoa(src.IndexerID) + "\x00" + src.GUID))
	return hex.EncodeToString(sum[:8])
}

// sortReleases puts what you would actually grab at the top.
//
// Acceptable releases first, then by seeders. Rejected ones keep their upstream
// order underneath, because Radarr's own ordering already reflects its quality
// preferences and second-guessing it here would be inventing an opinion.
func sortReleases(list []Release) {
	sort.SliceStable(list, func(i, j int) bool {
		if list[i].Rejected != list[j].Rejected {
			return !list[i].Rejected
		}
		if list[i].Rejected {
			return false
		}
		return list[i].Seeders > list[j].Seeders
	})
}

type grabBody struct {
	ReleaseID string `json:"releaseId"`
	Season    int    `json:"season,omitempty"`
}

// handleGrab sends one chosen release for download.
func (s *Server) handleGrab(w http.ResponseWriter, r *http.Request) {
	if !TokenFrom(r.Context()).HasScope("request") {
		writeError(w, r, http.StatusForbidden, Error{
			Code:    CodeForbiddenScope,
			Message: "this device is not allowed to grab releases",
		})
		return
	}
	key, err := ParseMediaKey(r.PathValue("key"))
	if err != nil {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: err.Error(),
		})
		return
	}
	var body grabBody
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&body); err != nil ||
		body.ReleaseID == "" {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "body must be JSON with a releaseId",
		})
		return
	}

	ctx, cancel := timeoutFor(r, 120*time.Second)
	defer cancel()

	target, err := s.resolveArrTarget(ctx, key, body.Season)
	if err != nil {
		writeTargetError(w, r, err)
		return
	}

	// Re-reads the cached search, and re-runs it if that has expired -- which
	// is not merely convenient. The *arr resolves a grab against *its* copy of
	// the same search, so an id from a stale search would not resolve there
	// either; searching again refreshes both sides at once.
	releases, _, err := cache.Fetch(ctx, s.cache, target.cacheKey(), cache.Releases,
		func(ctx context.Context) ([]arr.Release, error) {
			if target.isMovie {
				return target.client.MovieReleases(ctx, target.id)
			}
			return target.client.SeasonReleases(ctx, target.id, target.season)
		})
	if err != nil {
		writeUpstreamError(w, r, target.service, err)
		return
	}

	var chosen *arr.Release
	for i := range releases {
		if releaseID(&releases[i]) == body.ReleaseID {
			chosen = &releases[i]
			break
		}
	}
	if chosen == nil {
		writeError(w, r, http.StatusNotFound, Error{
			Code:    "release_gone",
			Service: target.service,
			Message: "That release is no longer in the search results — search again",
		})
		return
	}

	if err := target.client.Grab(ctx, chosen.GUID, chosen.IndexerID); err != nil {
		writeUpstreamError(w, r, target.service, err)
		return
	}

	// The queue now has something new in it, and the title's availability
	// changed. Both screens the user might go to next were about to lie.
	s.cache.Invalidate("activity")
	s.cache.Invalidate("detail:" + key.String())

	writeJSON(w, http.StatusOK, map[string]any{
		"ok":      true,
		"title":   chosen.Title,
		"quality": chosen.QualityName(),
		"service": target.service,
	})
}

// arrTarget is the *arr item an interactive search runs against.
type arrTarget struct {
	client  *arr.Client
	service string
	id      int
	isMovie bool
	season  int
	title   string
	key     string
}

func (t arrTarget) cacheKey() string {
	if t.isMovie {
		return "releases:" + t.key
	}
	return "releases:" + t.key + ":s" + strconv.Itoa(t.season)
}

// notInArrError means the title exists but the *arr has never heard of it,
// which is an ordinary state rather than a fault: you cannot search for
// releases of something that has not been added yet.
type notInArrError struct {
	service string
	title   string
}

func (e *notInArrError) Error() string {
	// Title-cased: the service name is being read as a name in a sentence, not
	// as the config key it happens to also be.
	name := e.service
	if name != "" {
		name = strings.ToUpper(name[:1]) + name[1:]
	}
	return e.title + " is not in " + name + " yet"
}

// resolveArrTarget turns a media key into a Radarr movie id or a Sonarr series
// id.
//
// Asked of the *arr directly rather than read from Jellyseerr's
// `mediaInfo.externalServiceId`, which is null for anything not added through
// Jellyseerr -- verified here on a series that is plainly in the library.
func (s *Server) resolveArrTarget(
	ctx context.Context, key MediaKey, season int,
) (*arrTarget, error) {
	service := "radarr"
	if key.Type == "series" {
		service = "sonarr"
	}
	client, ok := s.arrs[service]
	if !ok {
		return nil, &notConfiguredError{service: service}
	}

	if key.Type == "movie" {
		movie, err := client.MovieByTmdb(ctx, key.ID)
		if err != nil {
			return nil, err
		}
		if movie == nil {
			return nil, &notInArrError{service: service, title: "This film"}
		}
		return &arrTarget{
			client: client, service: service, id: movie.ID,
			isMovie: true, title: movie.Title, key: key.String(),
		}, nil
	}

	// Sonarr is TVDB-native, so a TMDB key has to be translated first. The
	// detail call already carries the tvdbId, and it is almost always cached by
	// the time anyone reaches this screen.
	tvdbID, err := s.tvdbFor(ctx, key)
	if err != nil {
		return nil, err
	}
	series, err := client.SeriesByTvdb(ctx, tvdbID)
	if err != nil {
		return nil, err
	}
	if series == nil {
		return nil, &notInArrError{service: service, title: "This series"}
	}
	return &arrTarget{
		client: client, service: service, id: series.ID,
		isMovie: false, season: season, title: series.Title, key: key.String(),
	}, nil
}

type notConfiguredError struct{ service string }

func (e *notConfiguredError) Error() string { return e.service + " is not configured" }

func writeTargetError(w http.ResponseWriter, r *http.Request, err error) {
	switch typed := err.(type) {
	case *notInArrError:
		writeError(w, r, http.StatusConflict, Error{
			Code:    "not_in_arr",
			Service: typed.service,
			Message: typed.Error() + " — request it first, then pick a release",
		})
	case *notConfiguredError:
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: typed.service, Message: typed.Error(),
		})
	default:
		writeUpstreamError(w, r, "arr", err)
	}
}

// tvdbFor translates a TMDB series key into the TVDB id Sonarr indexes by.
//
// Jellyseerr's detail carries it in two places and neither is reliable alone:
// mediaInfo.tvdbId is filled only for titles it manages, externalIds.tvdbId
// only when TMDB knows one. Both are checked before giving up.
func (s *Server) tvdbFor(ctx context.Context, key MediaKey) (int, error) {
	client := s.jellyseerr
	if client == nil {
		return 0, &notConfiguredError{service: "jellyseerr"}
	}
	detail, _, err := cache.Fetch(ctx, s.cache, "detail:"+key.String(), cache.Availability,
		func(ctx context.Context) (*jellyseerr.Detail, error) {
			return client.Detail(ctx, key.JellyseerrType(), key.ID)
		})
	if err != nil {
		return 0, err
	}
	if info := detail.MediaInfo; info != nil && info.TvdbID != nil && *info.TvdbID > 0 {
		return *info.TvdbID, nil
	}
	if ext := detail.ExternalIDs; ext != nil && ext.TvdbID != nil && *ext.TvdbID > 0 {
		return *ext.TvdbID, nil
	}
	return 0, &notInArrError{service: "sonarr", title: detail.DisplayTitle()}
}

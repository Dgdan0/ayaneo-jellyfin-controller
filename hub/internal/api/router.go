package api

import (
	"context"
	"log/slog"
	"net"
	"net/http"
	"sync"
	"time"

	"ayaneohub/internal/adapters/arr"
	"ayaneohub/internal/adapters/bazarr"
	"ayaneohub/internal/adapters/jellyfin"
	"ayaneohub/internal/adapters/jellyseerr"
	"ayaneohub/internal/adapters/qbittorrent"
	"ayaneohub/internal/auth"
	"ayaneohub/internal/cache"
	"ayaneohub/internal/config"
	"ayaneohub/internal/index"
)

// Version is stamped at build time with -ldflags.
var Version = "0.1.0-dev"

type Server struct {
	cfg             *config.Config
	tokens          *auth.Store
	limiter         *auth.Limiter
	playbackLimiter *auth.Limiter
	bans            *auth.BanList
	prober          *Prober
	trustedProxies  []*net.IPNet
	startedAt       time.Time

	// nil when the service is not configured or not enabled. Handlers check
	// rather than assume, so a hub with half a stack still serves what it can.
	jellyseerr  *jellyseerr.Client
	qbittorrent *qbittorrent.Client
	jellyfin    *jellyfin.Client
	bazarr      *bazarr.Client
	arrs        map[string]*arr.Client

	// The provider-id index, because Jellyfin has no provider-id query. A map
	// rather than a database: the whole library is 250 items and a full sweep
	// takes 362ms here. See internal/index.
	index *index.Index

	cache   *cache.Store
	images  *imageProxy
	offline *offlineStore

	playbackMu       sync.Mutex
	playbackSessions map[string]*playbackSession
	playbackTTL      time.Duration
	previewFrame     func(context.Context, string, int64) ([]byte, error)
	libraryScanMu    sync.Mutex
	libraryScanWatch bool
}

func NewServer(cfg *config.Config) *Server {
	s := &Server{
		cfg:             cfg,
		tokens:          auth.NewStore(cfg.Auth.Tokens),
		limiter:         auth.NewLimiter(cfg.Auth.RateLimit.RPM, cfg.Auth.RateLimit.Burst),
		playbackLimiter: auth.NewLimiter(playbackTransportRPM, playbackTransportBurst),
		bans: auth.NewBanList(
			cfg.Auth.AuthFailureBan.Attempts,
			cfg.Auth.AuthFailureBan.Window.OrDefault(time.Minute),
			cfg.Auth.AuthFailureBan.Ban.OrDefault(15*time.Minute),
		),
		prober:           NewProber(cfg),
		cache:            cache.New(),
		index:            index.New(),
		images:           newImageProxy(),
		offline:          newOfflineStore(cfg.Server.OfflineRegistry),
		playbackSessions: make(map[string]*playbackSession),
		playbackTTL:      30 * time.Minute,
		previewFrame:     extractPreviewFrame,
		startedAt:        time.Now(),
	}
	for _, cidr := range cfg.Server.TrustProxyCIDRs {
		if _, network, err := net.ParseCIDR(cidr); err == nil {
			s.trustedProxies = append(s.trustedProxies, network)
		}
	}

	s.arrs = map[string]*arr.Client{}
	for name, kind := range map[string]arr.Kind{"radarr": arr.Radarr, "sonarr": arr.Sonarr} {
		if svc, ok := cfg.Services[name]; ok && svc.Enabled {
			if client, err := arr.New(kind, svc); err != nil {
				slog.Error("arr adapter unavailable", "service", name, "error", err)
			} else {
				s.arrs[name] = client
			}
		}
	}
	if svc, ok := cfg.Services["jellyfin"]; ok && svc.Enabled {
		if client, err := jellyfin.New(svc); err != nil {
			slog.Error("jellyfin adapter unavailable", "error", err)
		} else {
			s.jellyfin = client
			if client.UserID() == "" {
				// Not fatal -- health, search and downloads all still work --
				// but every library and home row depends on it, so it is said
				// loudly once rather than failing quietly per request.
				slog.Warn("jellyfin has no user_id configured; " +
					"library and home rows will be unavailable")
			}
		}
	}
	if svc, ok := cfg.Services["qbittorrent"]; ok && svc.Enabled {
		if client, err := qbittorrent.New(svc); err != nil {
			slog.Error("qbittorrent adapter unavailable", "error", err)
		} else {
			s.qbittorrent = client
			// Settle stop/start versus pause/resume before anything needs it.
			go func() {
				ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
				defer cancel()
				if v, err := client.Probe(ctx); err == nil {
					slog.Info("qbittorrent", "app", v.App, "api", v.API)
				}
			}()
		}
	}

	if svc, ok := cfg.Services["jellyseerr"]; ok && svc.Enabled {
		client, err := jellyseerr.New(svc)
		if err != nil {
			// Config validation has already checked the URL, so this is close to
			// unreachable -- but a hub that starts without Jellyseerr and says so
			// beats one that panics on the first search.
			slog.Error("jellyseerr adapter unavailable", "error", err)
		} else {
			s.jellyseerr = client
		}
	}
	if svc, ok := cfg.Services["bazarr"]; ok && svc.Enabled {
		client, err := bazarr.New(svc)
		if err != nil {
			slog.Error("bazarr adapter unavailable", "error", err)
		} else {
			s.bazarr = client
		}
	}
	return s
}

// Handler builds the routing table.
//
// stdlib ServeMux rather than a router dependency: since Go 1.22 its patterns
// carry the method and typed path wildcards, which is the whole of what this
// API needs.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	// Unauthenticated on purpose, and deliberately says nothing: it exists so a
	// service watchdog can tell the process is alive without holding a
	// credential. Anything that reveals configuration belongs on /v1/health.
	mux.HandleFunc("GET /v1/health/live", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok\n"))
	})

	authed := http.NewServeMux()
	authed.HandleFunc("GET /v1/health", s.handleHealth)
	authed.HandleFunc("GET /v1/users", s.handleUsers)
	authed.HandleFunc("GET /v1/search", s.handleSearch)
	authed.HandleFunc("GET /v1/home", s.handleHome)
	authed.HandleFunc("GET /v1/library", s.handleLibrary)
	// One dispatcher because /{viewId}/items and /items/{itemId} are
	// intentionally symmetric and net/http correctly rejects them as ambiguous
	// wildcard patterns when registered separately.
	authed.HandleFunc("GET /v1/library/{path...}", s.handleLibraryRoute)
	authed.HandleFunc("POST /v1/library/items/{itemId}/state", s.handleLibraryState)
	authed.HandleFunc("GET /v1/library/series/{seriesId}/play-target", s.handleSeriesPlayTarget)
	authed.HandleFunc("POST /v1/playback/items/{itemId}/prepare", s.handlePlaybackPrepare)
	authed.HandleFunc("GET /v1/playback/sessions/{sessionId}/stream", s.handlePlaybackStream)
	authed.HandleFunc("GET /v1/playback/sessions/{sessionId}/hls/{resource}", s.handlePlaybackHLS)
	authed.HandleFunc("GET /v1/playback/sessions/{sessionId}/subtitles/{trackId}", s.handlePlaybackSubtitle)
	authed.HandleFunc("GET /v1/playback/sessions/{sessionId}/trickplay/{index}", s.handlePlaybackTrickplay)
	authed.HandleFunc("GET /v1/playback/sessions/{sessionId}/preview", s.handlePlaybackPreview)
	authed.HandleFunc("POST /v1/playback/sessions/{sessionId}/select", s.handlePlaybackSelect)
	authed.HandleFunc("POST /v1/playback/sessions/{sessionId}/events", s.handlePlaybackEvent)
	authed.HandleFunc("DELETE /v1/playback/sessions/{sessionId}", s.handlePlaybackDelete)
	authed.HandleFunc("GET /v1/offline/series/{seriesId}/selection", s.handleOfflineSelection)
	authed.HandleFunc("POST /v1/offline/prepare", s.handleOfflinePrepare)
	authed.HandleFunc("GET /v1/offline/grants/{grantId}/media", s.handleOfflineMedia)
	authed.HandleFunc("GET /v1/offline/grants/{grantId}/subtitles/{trackId}", s.handleOfflineSubtitle)
	authed.HandleFunc("POST /v1/offline/grants/{grantId}/renew", s.handleOfflineRenew)
	authed.HandleFunc("POST /v1/offline/progress/sync", s.handleOfflineProgressSync)
	authed.HandleFunc("GET /v1/img/jf/{itemId}/{imageType}", s.handleJellyfinImage)
	authed.HandleFunc("GET /v1/discover", s.handleDiscover)
	authed.HandleFunc("GET /v1/discover/{row}", s.handleDiscoverRow)
	authed.HandleFunc("GET /v1/media/{key}", s.handleMediaDetail)
	authed.HandleFunc("GET /v1/requests/options", s.handleRequestOptions)
	authed.HandleFunc("POST /v1/requests", s.handleCreateRequest)
	authed.HandleFunc("GET /v1/media/{key}/releases", s.handleReleases)
	authed.HandleFunc("GET /v1/media/{key}/release-targets", s.handleReleaseTargets)
	authed.HandleFunc("POST /v1/media/{key}/grab", s.handleGrab)
	authed.HandleFunc("GET /v1/person/{id}", s.handlePerson)
	authed.HandleFunc("GET /v1/activity", s.handleActivity)
	authed.HandleFunc("GET /v1/notifications", s.handleNotifications)
	authed.HandleFunc("POST /v1/manage/jellyfin/scan", s.handleJellyfinLibraryScan)
	authed.HandleFunc("POST /v1/downloads/{id}/stop", s.handleDownloadStop)
	authed.HandleFunc("POST /v1/downloads/{id}/start", s.handleDownloadStart)
	authed.HandleFunc("DELETE /v1/downloads/{id}", s.handleDownloadDelete)
	authed.HandleFunc("POST /v1/queue/{service}/{queueId}/remove", s.handleQueueRemove)
	authed.HandleFunc("GET /v1/img/tmdb/{size}/{file}", s.handleTmdbImage)

	mux.Handle("/v1/", s.withAuth(authed))

	// Anything else is not ours. Saying so plainly beats a stack of HTML.
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, r, http.StatusNotFound, Error{
			Code:    CodeNotFound,
			Message: "no such endpoint",
		})
	})

	return chain(mux, withRecover, withRequestID, withLogging)
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()

	var health HubHealth
	health.Hub.Version = Version
	health.Hub.UptimeSeconds = int64(time.Since(s.startedAt).Seconds())
	health.Hub.TokenCount = s.tokens.Count()
	health.Services = s.prober.ProbeAll(ctx)

	writeJSON(w, http.StatusOK, health)
}

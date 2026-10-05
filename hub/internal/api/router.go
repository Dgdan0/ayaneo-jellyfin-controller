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
	"ayaneohub/internal/adapters/bookkeeprr"
	"ayaneohub/internal/adapters/jellyfin"
	"ayaneohub/internal/adapters/jellyseerr"
	"ayaneohub/internal/adapters/kavita"
	"ayaneohub/internal/adapters/openlibrary"
	"ayaneohub/internal/adapters/qbittorrent"
	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/adapters/wikidata"
	"ayaneohub/internal/auth"
	"ayaneohub/internal/cache"
	"ayaneohub/internal/config"
	"ayaneohub/internal/index"
	readingdomain "ayaneohub/internal/reading"
)

// Version is stamped at build time with -ldflags.
var Version = "0.1.0-dev"

type Server struct {
	removalMu       sync.Mutex
	removalTickets  map[string]removalTicket
	cfg             *config.Config
	tokens          *auth.Store
	limiter         *auth.Limiter
	playbackLimiter *auth.Limiter
	artworkLimiter  *auth.Limiter
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
	bookkeeprr  *bookkeeprr.Client
	kavita      *kavita.Client
	storyteller *storyteller.Client
	openlibrary *openlibrary.Client
	wikidata    *wikidata.Client
	arrs        map[string]*arr.Client

	// The provider-id index, because Jellyfin has no provider-id query. A map
	// rather than a database: the whole library is 250 items and a full sweep
	// takes 362ms here. See internal/index.
	index *index.Index

	cache  *cache.Store
	images *imageProxy
	// Each artwork's Glass colours (GLASS_PLAN.md), worked out once and kept.
	colors                *artworkColorStore
	libraryOrder          *libraryOrderStore
	colorRequestBudget    time.Duration
	artworkMuxOnce        sync.Once
	artworkMuxHandler     http.Handler
	offline               *offlineStore
	readingCatalog        *readingdomain.CatalogStore
	readingCandidates     *readingCandidateStore
	readingReleaseTickets *readingReleaseTickets
	readingTransfers      *readingTransferStore
	readingSeriesPreviews *readingSeriesPreviewStore
	readingAcquisitions   *readingAcquisitionStore
	readingAlignments     *readingAlignmentStore

	playbackMu       sync.Mutex
	playbackSessions map[string]*playbackSession
	castGrants       map[string]*playbackCastGrant
	playbackTTL      time.Duration
	previewFrame     func(context.Context, string, int64) ([]byte, error)
	// ffprobe for the audiobook routes: the seam a test stubs, how long one
	// probe may take, the slots that bound how many run at once, and what has
	// been learned about each file as it was (see audio_probe.go).
	probeAudio   func(context.Context, string) (probedAudio, error)
	probeTimeout time.Duration
	probeSlots   chan struct{}
	probeMu      sync.Mutex
	probeCache   map[probeKey]probedAudio
	// openMedia opens an audiobook's file read-only through the media mapping
	// (reading.ResolveMediaFile), and is the seam a test wraps to watch handles.
	openMedia        func([]config.MediaRemovalRoot, string, string) (readingdomain.MediaFile, error)
	libraryScanMu    sync.Mutex
	libraryScanWatch bool
	readingScanMu    sync.Mutex
	readingWatchMu   sync.Mutex
	readingScanWatch bool
	subtitleMu       sync.Mutex
	subtitleTickets  map[string]subtitleTicket
}

func NewServer(cfg *config.Config) *Server {
	s := &Server{
		removalTickets:  map[string]removalTicket{},
		cfg:             cfg,
		tokens:          auth.NewStore(cfg.Auth.Tokens),
		limiter:         auth.NewLimiter(cfg.Auth.RateLimit.RPM, cfg.Auth.RateLimit.Burst),
		playbackLimiter: auth.NewLimiter(playbackTransportRPM, playbackTransportBurst),
		artworkLimiter:  auth.NewLimiter(artworkRPM, artworkBurst),
		bans: auth.NewBanList(
			cfg.Auth.AuthFailureBan.Attempts,
			cfg.Auth.AuthFailureBan.Window.OrDefault(time.Minute),
			cfg.Auth.AuthFailureBan.Ban.OrDefault(15*time.Minute),
		),
		prober:                NewProber(cfg),
		cache:                 cache.New(),
		index:                 index.New(),
		images:                newImageProxy(),
		colors:                newArtworkColorStore(artworkColorsPath(cfg.Server.OfflineRegistry)),
		libraryOrder:          newLibraryOrderStore(libraryOrderPath(cfg.Server.OfflineRegistry)),
		offline:               newOfflineStore(cfg.Server.OfflineRegistry),
		readingCatalog:        readingdomain.NewCatalogStore(cfg.Server.ReadingCatalog),
		readingCandidates:     newReadingCandidateStore(2000),
		readingReleaseTickets: newReadingReleaseTickets(),
		readingTransfers:      newReadingTransferStore(cfg.Server.ReadingTransfers),
		readingSeriesPreviews: newReadingSeriesPreviewStore(250),
		readingAcquisitions:   newReadingAcquisitionStore(readingAcquisitionPath(cfg.Server.ReadingTransfers)),
		readingAlignments:     newReadingAlignmentStore(readingAlignmentPath(cfg.Server.ReadingTransfers)),
		openlibrary:           openlibrary.New(""),
		wikidata:              wikidata.New(""),
		playbackSessions:      make(map[string]*playbackSession),
		castGrants:            make(map[string]*playbackCastGrant),
		playbackTTL:           30 * time.Minute,
		previewFrame:          extractPreviewFrame,
		probeAudio:            runFFprobe,
		probeTimeout:          audioProbeTimeout,
		probeSlots:            make(chan struct{}, audioProbeSlots),
		probeCache:            map[probeKey]probedAudio{},
		openMedia:             readingdomain.ResolveMediaFile,
		startedAt:             time.Now(),
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
	if svc, ok := cfg.Services["bookkeeprr"]; ok && svc.Enabled {
		client, err := bookkeeprr.New(svc)
		if err != nil {
			slog.Error("bookkeeprr adapter unavailable", "error", err)
		} else {
			s.bookkeeprr = client
		}
	}
	if svc, ok := cfg.Services["kavita"]; ok && svc.Enabled {
		client, err := kavita.New(svc)
		if err != nil {
			slog.Error("kavita adapter unavailable", "error", err)
		} else {
			s.kavita = client
		}
	}
	if svc, ok := cfg.Services["storyteller"]; ok && svc.Enabled {
		client, err := storyteller.New(svc)
		if err != nil {
			slog.Error("storyteller adapter unavailable", "error", err)
		} else {
			s.storyteller = client
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
	// Capability URLs for a Cast receiver. The TV has no hub bearer token; each
	// URL is random, scoped to one playback session, and revoked on stop.
	mux.HandleFunc("GET /v1/cast/{grantId}/stream", s.handleCastStream)
	mux.HandleFunc("HEAD /v1/cast/{grantId}/stream", s.handleCastStream)
	mux.HandleFunc("GET /v1/cast/{grantId}/hls/{resource}", s.handleCastHLS)
	mux.HandleFunc("GET /v1/cast/{grantId}/subtitles/{trackId}", s.handleCastSubtitle)
	mux.HandleFunc("OPTIONS /v1/cast/{grantId}/{resource...}", s.handleCastOptions)

	authed := http.NewServeMux()
	authed.HandleFunc("GET /v1/health", s.handleHealth)
	authed.HandleFunc("GET /v1/users", s.handleUsers)
	authed.HandleFunc("GET /v1/search", s.handleSearch)
	authed.HandleFunc("GET /v1/home", s.handleHome)
	authed.HandleFunc("GET /v1/library", s.handleLibrary)
	authed.HandleFunc("PUT /v1/library/order", s.handleLibraryOrder)
	// One dispatcher because /{viewId}/items and /items/{itemId} are
	// intentionally symmetric and net/http correctly rejects them as ambiguous
	// wildcard patterns when registered separately.
	authed.HandleFunc("GET /v1/library/{path...}", s.handleLibraryRoute)
	authed.HandleFunc("POST /v1/library/items/{itemId}/state", s.handleLibraryState)
	authed.HandleFunc("GET /v1/library/items/{itemId}/subtitles", s.handleSubtitles)
	authed.HandleFunc("POST /v1/library/items/{itemId}/subtitles/search", s.handleSubtitleSearch)
	authed.HandleFunc("POST /v1/library/items/{itemId}/subtitles/download", s.handleSubtitleDownload)
	authed.HandleFunc("POST /v1/library/items/{itemId}/subtitles/refresh", s.handleSubtitleRefresh)
	authed.HandleFunc("GET /v1/library/series/{seriesId}/play-target", s.handleSeriesPlayTarget)
	authed.HandleFunc("POST /v1/playback/items/{itemId}/prepare", s.handlePlaybackPrepare)
	authed.HandleFunc("GET /v1/playback/sessions/{sessionId}/stream", s.handlePlaybackStream)
	authed.HandleFunc("GET /v1/playback/sessions/{sessionId}/hls/{resource}", s.handlePlaybackHLS)
	authed.HandleFunc("GET /v1/playback/sessions/{sessionId}/subtitles/{trackId}", s.handlePlaybackSubtitle)
	authed.HandleFunc("GET /v1/playback/sessions/{sessionId}/trickplay/{index}", s.handlePlaybackTrickplay)
	authed.HandleFunc("GET /v1/playback/sessions/{sessionId}/preview", s.handlePlaybackPreview)
	authed.HandleFunc("POST /v1/playback/sessions/{sessionId}/select", s.handlePlaybackSelect)
	authed.HandleFunc("POST /v1/playback/sessions/{sessionId}/cast-grant", s.handleCastGrant)
	authed.HandleFunc("POST /v1/playback/sessions/{sessionId}/events", s.handlePlaybackEvent)
	authed.HandleFunc("DELETE /v1/playback/sessions/{sessionId}", s.handlePlaybackDelete)
	authed.HandleFunc("GET /v1/offline/series/{seriesId}/selection", s.handleOfflineSelection)
	authed.HandleFunc("POST /v1/offline/prepare", s.handleOfflinePrepare)
	authed.HandleFunc("GET /v1/offline/grants/{grantId}/media", s.handleOfflineMedia)
	authed.HandleFunc("GET /v1/offline/grants/{grantId}/subtitles/{trackId}", s.handleOfflineSubtitle)
	authed.HandleFunc("POST /v1/offline/grants/{grantId}/renew", s.handleOfflineRenew)
	authed.HandleFunc("POST /v1/offline/progress/sync", s.handleOfflineProgressSync)
	authed.HandleFunc("GET /v1/discover", s.handleDiscover)
	authed.HandleFunc("GET /v1/calendar", s.handleCalendar)
	authed.HandleFunc("GET /v1/discover/{row}", s.handleDiscoverRow)
	authed.HandleFunc("GET /v1/reading/discover", s.handleReadingDiscover)
	authed.HandleFunc("GET /v1/reading/discover/{row}", s.handleReadingDiscoverRow)
	authed.HandleFunc("GET /v1/reading/search", s.handleReadingSearch)
	authed.HandleFunc("GET /v1/reading/libraries", s.handleReadingLibraries)
	authed.HandleFunc("GET /v1/reading/lists", s.handleServerReadingLists)
	authed.HandleFunc("GET /v1/reading/lists/{listId}", s.handleServerReadingLists)
	authed.HandleFunc("GET /v1/reading/libraries/{libraryId}/items", s.handleReadingLibraryItems)
	authed.HandleFunc("GET /v1/reading/libraries/{libraryId}/authors", s.handleReadingAuthors)
	authed.HandleFunc("GET /v1/reading/resolve", s.handleReadingResolve)
	authed.HandleFunc("GET /v1/reading/works/{workId}", s.handleReadingWork)
	authed.HandleFunc("GET /v1/reading/works/{workId}/publications/{sourceItemId}", s.handleReadingPublication)
	authed.HandleFunc("GET /v1/reading/works/{workId}/publications/{sourceItemId}/pages/{page}", s.handleReadingPublicationPage)
	authed.HandleFunc("GET /v1/reading/works/{workId}/publications/{sourceItemId}/pages/{page}/thumb", s.handleReadingPageThumb)
	authed.HandleFunc("POST /v1/reading/works/{workId}/publications/{sourceItemId}/progress", s.handleReadingPublicationProgress)
	authed.HandleFunc("GET /v1/reading/works/{workId}/publications/{sourceItemId}/file", s.handleReadingEpubFile)
	authed.HandleFunc("GET /v1/reading/works/{workId}/publications/{sourceItemId}/audio", s.handleReadingAudioManifest)
	authed.HandleFunc("GET /v1/reading/works/{workId}/publications/{sourceItemId}/position", s.handleReadingEpubPosition)
	authed.HandleFunc("POST /v1/reading/works/{workId}/publications/{sourceItemId}/position", s.handleReadingEpubPosition)
	authed.HandleFunc("GET /v1/reading/requests/options", s.handleReadingRequestOptions)
	authed.HandleFunc("GET /v1/reading/requests/series-preview", s.handleReadingSeriesPreview)
	authed.HandleFunc("POST /v1/reading/requests", s.handleReadingCreateRequest)
	authed.HandleFunc("GET /v1/reading/requests/{seriesId}/releases", s.handleReadingReleases)
	authed.HandleFunc("POST /v1/reading/requests/{seriesId}/search", s.handleReadingReleaseSearch)
	authed.HandleFunc("POST /v1/reading/requests/{seriesId}/grab", s.handleReadingReleaseGrab)
	authed.HandleFunc("GET /v1/reading/downloads", s.handleReadingDownloads)
	authed.HandleFunc("POST /v1/reading/downloads/{transferId}/retry", s.handleReadingDownloadRetry)
	authed.HandleFunc("DELETE /v1/reading/downloads/{transferId}", s.handleReadingDownloadCancel)
	authed.HandleFunc("GET /v1/media/{key}", s.handleMediaDetail)
	authed.HandleFunc("GET /v1/requests/options", s.handleRequestOptions)
	authed.HandleFunc("POST /v1/requests", s.handleCreateRequest)
	authed.HandleFunc("GET /v1/media/{key}/releases", s.handleReleases)
	authed.HandleFunc("GET /v1/media/{key}/release-targets", s.handleReleaseTargets)
	authed.HandleFunc("POST /v1/media/{key}/grab", s.handleGrab)
	authed.HandleFunc("GET /v1/person/{id}", s.handlePerson)
	authed.HandleFunc("GET /v1/activity", s.handleActivity)
	authed.HandleFunc("GET /v1/downloads/bandwidth", s.handleBandwidth)
	authed.HandleFunc("GET /v1/manage/monitor", s.handleMonitor)
	authed.HandleFunc("POST /v1/downloads/bandwidth", s.handleSetBandwidth)
	authed.HandleFunc("POST /v1/downloads/{id}/priority_up", s.handlePriority)
	authed.HandleFunc("POST /v1/downloads/{id}/priority_down", s.handlePriority)
	authed.HandleFunc("GET /v1/notifications", s.handleNotifications)
	authed.HandleFunc("POST /v1/media/removal-preview", s.handleRemovalPreview)
	authed.HandleFunc("POST /v1/media/remove", s.handleMediaRemove)
	authed.HandleFunc("POST /v1/manage/jellyfin/scan", s.handleJellyfinLibraryScan)
	authed.HandleFunc("POST /v1/manage/reading/scan", s.handleReadingLibraryScan)
	authed.HandleFunc("POST /v1/downloads/{id}/stop", s.handleDownloadStop)
	authed.HandleFunc("POST /v1/downloads/{id}/start", s.handleDownloadStart)
	authed.HandleFunc("DELETE /v1/downloads/{id}", s.handleDownloadDelete)
	authed.HandleFunc("POST /v1/queue/{service}/{queueId}/remove", s.handleQueueRemove)

	// Artwork, from the one list artworkMux also serves for the hub's own use.
	for pattern, handler := range s.imageRoutes() {
		authed.HandleFunc(pattern, handler)
	}
	authed.HandleFunc("GET /v1/img/colors", s.handleArtworkColors)

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

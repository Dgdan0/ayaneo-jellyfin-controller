package api

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"

	"ayaneohub/internal/auth"
)

type ctxKey int

const (
	ctxRequestID ctxKey = iota
	ctxToken
)

func RequestIDFrom(ctx context.Context) string {
	id, _ := ctx.Value(ctxRequestID).(string)
	return id
}

func TokenFrom(ctx context.Context) auth.Token {
	tok, _ := ctx.Value(ctxToken).(auth.Token)
	return tok
}

type middleware func(http.Handler) http.Handler

const (
	// Media3 legitimately opens several ranges/segments and subtitle requests at
	// once. Giving those session-owned routes their own budget prevents normal
	// playback from consuming the much smaller interactive API allowance.
	playbackTransportRPM   = 3600
	playbackTransportBurst = 240

	// Artwork is one screen, not one action per poster: opening a 60-title
	// Library page asks for 60 images at once, and on the interactive budget
	// (burst 30) the rest came back 429 and stayed blank. Measured on the
	// Pocket DS on 2026-09-30 -- every refused request was an image.
	artworkRPM   = 1800
	artworkBurst = 150
)

func chain(h http.Handler, ms ...middleware) http.Handler {
	for i := len(ms) - 1; i >= 0; i-- {
		h = ms[i](h)
	}
	return h
}

func withRequestID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		buf := make([]byte, 8)
		_, _ = rand.Read(buf)
		id := hex.EncodeToString(buf)
		w.Header().Set("X-Request-Id", id)
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxRequestID, id)))
	})
}

// withRecover turns a panic into a 500 rather than a dropped connection, and
// keeps the stack out of the response.
func withRecover(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				slog.Error("panic serving request",
					"path", r.URL.Path, "panic", rec, "requestId", RequestIDFrom(r.Context()))
				writeError(w, r, http.StatusInternalServerError, Error{
					Code:      CodeInternal,
					Message:   "the hub hit an unexpected error",
					Retryable: true,
				})
			}
		}()
		next.ServeHTTP(w, r)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

// Keep net/http.ResponseController features available to long streaming routes.
func (s *statusRecorder) Unwrap() http.ResponseWriter { return s.ResponseWriter }

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

func withLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		slog.Info("request",
			"method", r.Method,
			"path", redactedRequestPath(r.URL.Path),
			"status", rec.status,
			"ms", time.Since(start).Milliseconds(),
			"token", TokenFrom(r.Context()).Label,
			"requestId", RequestIDFrom(r.Context()),
		)
	})
}

func redactedRequestPath(requestPath string) string {
	if strings.HasPrefix(requestPath, "/v1/cast/") {
		return "/v1/cast/[grant]"
	}
	return requestPath
}

// clientIP is the source to ban. X-Forwarded-For is honoured only from a proxy
// we were told to trust -- taking it from anyone lets a guesser spoof a new
// source per attempt and never get banned.
func (s *Server) clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if !s.isTrustedProxy(host) {
		return host
	}
	if forwarded := r.Header.Get("X-Forwarded-For"); forwarded != "" {
		// Left-most entry is the original client.
		for i, c := range forwarded {
			if c == ',' {
				return trimSpace(forwarded[:i])
			}
		}
		return trimSpace(forwarded)
	}
	return host
}

func trimSpace(s string) string {
	start, end := 0, len(s)
	for start < end && (s[start] == ' ' || s[start] == '\t') {
		start++
	}
	for end > start && (s[end-1] == ' ' || s[end-1] == '\t') {
		end--
	}
	return s[start:end]
}

func (s *Server) isTrustedProxy(host string) bool {
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	for _, cidr := range s.trustedProxies {
		if cidr.Contains(ip) {
			return true
		}
	}
	return false
}

// withAuth verifies the bearer token, then rate-limits per device.
//
// Order matters: the ban check comes first so a source that is locked out costs
// nothing, and the rate limiter is keyed on the token label rather than the IP
// so one busy device cannot starve another behind the same NAT.
func (s *Server) withAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		now := time.Now()
		ip := s.clientIP(r)

		if banned, until := s.bans.Banned(ip, now); banned {
			retry := int(time.Until(until).Seconds()) + 1
			slog.Warn("request from a banned source", "ip", ip, "path", r.URL.Path)
			writeError(w, r, http.StatusTooManyRequests, Error{
				Code:              CodeBanned,
				Message:           "too many failed attempts",
				Retryable:         true,
				RetryAfterSeconds: retry,
			})
			return
		}

		token, ok := s.tokens.Verify(auth.BearerFrom(r.Header.Get("Authorization")))
		if !ok {
			// Never log the presented credential: a typo'd real token would then
			// sit in the log file in clear text.
			if s.bans.Fail(ip, now) {
				slog.Warn("banning source after repeated auth failures", "ip", ip)
			} else {
				slog.Warn("rejected credential", "ip", ip, "path", r.URL.Path)
			}
			w.Header().Set("WWW-Authenticate", `Bearer realm="ayaneo-hub"`)
			writeError(w, r, http.StatusUnauthorized, Error{
				Code:    CodeUnauthorized,
				Message: "a valid bearer token is required",
			})
			return
		}
		s.bans.Succeed(ip)

		limiter := s.limiterFor(r)
		if limiter == nil || !limiter.Allow(token.Label, now) {
			writeError(w, r, http.StatusTooManyRequests, Error{
				Code:              CodeRateLimited,
				Message:           "slow down",
				Retryable:         true,
				RetryAfterSeconds: 2,
			})
			return
		}

		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxToken, token)))
	})
}

// requireScope is the one permission check. The token got past withAuth, so
// a refusal here is a missing scope (403), never a bad token (401) -- the app
// keeps working for everything else. Seven handlers wrote this by hand, half
// saying "this token cannot" and half "this device is not allowed to".
func requireScope(w http.ResponseWriter, r *http.Request, scope, action string) bool {
	if TokenFrom(r.Context()).HasScope(scope) {
		return true
	}
	writeError(w, r, http.StatusForbidden, Error{
		Code:    CodeForbiddenScope,
		Message: "this device is not allowed to " + action,
	})
	return false
}

// limiterFor picks the budget a request spends: session-bound playback and
// offline transfers, artwork, or the interactive screen budget. Each is still
// per token label and still behind authentication.
func (s *Server) limiterFor(r *http.Request) *auth.Limiter {
	path := r.URL.Path
	switch {
	case strings.HasPrefix(path, "/v1/playback/sessions/"), strings.HasPrefix(path, "/v1/offline/grants/"):
		return s.playbackLimiter
	case strings.HasPrefix(path, "/v1/img/"):
		return s.artworkLimiter
	case strings.HasPrefix(path, "/v1/reading/works/") && strings.Contains(path, "/publications/"):
		// A reader skims: each page turn is an image, and the scrubber and
		// prefetch ask for several at once. On the screen budget a fast skim
		// ran out like a Library grid did (#16). Pages and their thumbnails
		// are images, as artwork is; a book's file or an audio track is
		// transport. An audiobook's manifest and its place are screen-sized,
		// like progress, so only /audio/tracks/ is.
		if strings.Contains(path, "/pages/") {
			return s.artworkLimiter
		}
		if strings.HasSuffix(path, "/file") || strings.Contains(path, "/audio/tracks/") {
			return s.playbackLimiter
		}
		return s.limiter
	default:
		return s.limiter
	}
}

// timeoutFor bounds a handler by the server-wide request budget, so one slow
// upstream cannot hold a connection open indefinitely.
func timeoutFor(r *http.Request, d time.Duration) (context.Context, context.CancelFunc) {
	return context.WithTimeout(r.Context(), d)
}

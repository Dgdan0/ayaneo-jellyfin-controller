package api

import (
	"errors"
	"net/http"
	"strings"

	"ayaneohub/internal/httpx"
)

// imagePrefix is where poster paths point. The handheld never sees a TMDB URL,
// so images ride the one authenticated connection it already has.
const imagePrefix = "/v1/img/tmdb"

// writeUpstreamError maps an adapter failure onto an HTTP status the app can act
// on, without leaking the upstream URL -- which for some services carries the
// API key in a query parameter.
func writeUpstreamError(w http.ResponseWriter, r *http.Request, service string, err error) {
	var upstream *httpx.Error
	if !errors.As(err, &upstream) {
		writeError(w, r, http.StatusBadGateway, Error{
			Code:      CodeUpstreamDown,
			Service:   service,
			Message:   service + " did not answer",
			Retryable: true,
		})
		return
	}

	// Jellyseerr answers 500 with "Unable to retrieve movie." for a TMDB id it
	// cannot find, rather than 404. Passing that through tells the app a service
	// is broken when in fact the title simply does not exist, which sends the
	// user off checking their server for no reason.
	if upstream.Kind == httpx.KindUpstream5xx && upstream.Err != nil &&
		strings.Contains(strings.ToLower(upstream.Err.Error()), "unable to retrieve") {
		writeError(w, r, http.StatusNotFound, Error{
			Code:    CodeNotFound,
			Service: service,
			Message: "no such title",
		})
		return
	}

	switch upstream.Kind {
	case httpx.KindAuth:
		// Not retryable, and deliberately loud: the credential is wrong, and
		// retrying it forever is how you get banned by the service itself.
		writeError(w, r, http.StatusBadGateway, Error{
			Code:      "upstream_misconfigured",
			Service:   service,
			Message:   service + " rejected the hub's credential -- check its API key",
			Retryable: false,
		})
	case httpx.KindNotFound:
		writeError(w, r, http.StatusNotFound, Error{
			Code:    CodeNotFound,
			Service: service,
			Message: "not found",
		})
	case httpx.KindRateLimited:
		writeError(w, r, http.StatusTooManyRequests, Error{
			Code:              CodeRateLimited,
			Service:           service,
			Message:           service + " is rate limiting us",
			Retryable:         true,
			RetryAfterSeconds: 5,
		})
	case httpx.KindDecode:
		// Deliberately distinct from "not responding". The service answered
		// perfectly well and the hub could not read it, which is the hub's bug
		// -- and saying otherwise sends the user off restarting a healthy
		// service. Sonarr sending indexerFlags as a bitfield where Radarr sends
		// an array of strings landed exactly here.
		writeError(w, r, http.StatusBadGateway, Error{
			Code:      "upstream_unreadable",
			Service:   service,
			Message:   "the hub could not read " + service + "'s response",
			Retryable: false,
		})
	case httpx.KindBadRequest:
		writeError(w, r, http.StatusBadRequest, Error{
			Code:    CodeInvalidRequest,
			Service: service,
			Message: upstream.Error(),
		})
	default:
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code:              CodeUpstreamDown,
			Service:           service,
			Message:           service + " is not responding",
			Retryable:         upstream.Kind.Retryable(),
			RetryAfterSeconds: 10,
		})
	}
}

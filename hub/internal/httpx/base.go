// Package httpx is the machinery every service adapter shares.
//
// Deliberately shared machinery rather than a shared data interface: forcing six
// very different APIs behind one common model produces a lowest-common-
// denominator shape that throws away the things that make each one useful. What
// they genuinely have in common is a base URL, a credential, a timeout, and JSON.
package httpx

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// MaxBodyBytes caps a response we are willing to buffer.
//
// 1 MB rather than something tighter because Jellyfin's /System/Info embeds the
// full changelog of every installed plugin and runs to hundreds of kilobytes.
// A truncated body is not a short answer -- it is invalid JSON that parses to
// nothing, which is a maddening thing to debug.
const MaxBodyBytes = 1 << 20

// Error is a failed call, classified so callers can decide without string
// matching on the message.
type Error struct {
	Service string
	Status  int
	Kind    Kind
	Err     error
}

func (e *Error) Error() string {
	if e.Status > 0 {
		return fmt.Sprintf("%s: HTTP %d (%s)", e.Service, e.Status, e.Kind)
	}
	return fmt.Sprintf("%s: %s: %v", e.Service, e.Kind, e.Err)
}

func (e *Error) Unwrap() error { return e.Err }

type Kind string

const (
	KindTimeout      Kind = "timeout"
	KindRefused      Kind = "connection_refused"
	KindDNS          Kind = "dns"
	KindTLS          Kind = "tls"
	KindAuth         Kind = "auth"
	KindNotFound     Kind = "not_found"
	KindRateLimited  Kind = "rate_limited"
	KindUpstream5xx  Kind = "upstream_5xx"
	KindBadRequest   Kind = "bad_request"
	KindDecode       Kind = "decode"
	KindUnclassified Kind = "unclassified"
)

// Retryable reports whether trying the same call again could plausibly work.
//
// Auth failures are deliberately not retryable: retrying a rejected credential
// forever is how you get your own IP banned by the service you are talking to.
func (k Kind) Retryable() bool {
	switch k {
	case KindTimeout, KindRefused, KindUpstream5xx, KindRateLimited:
		return true
	}
	return false
}

type Base struct {
	name    string
	baseURL *url.URL
	client  *http.Client
	stream  *http.Client
	auth    Authenticator
	timeout time.Duration
	headers map[string]string
	maxBody int64
	log     *slog.Logger
}

// BaseURL is exposed so a caller can build the headers that must echo it.
func (b *Base) BaseURL() string { return b.baseURL.String() }

type Options struct {
	Name    string
	BaseURL string
	Auth    Authenticator
	Timeout time.Duration
	// Sent on every request. qBittorrent needs Referer and Origin to match its
	// own base URL or its CSRF check rejects the write, which is the single
	// most common way people fail to make its API work.
	Headers            map[string]string
	InsecureSkipVerify bool
	// MaxBody overrides MaxBodyBytes for this service. Radarr's interactive
	// search is the only endpoint in the system that legitimately returns
	// megabytes -- 100 releases carrying magnet URIs and tracker lists ran to
	// 500 KB for one ordinary film, and a title with more indexers would exceed
	// the default cap and fail to decode. Still bounded, just not at 1 MB.
	MaxBody int64
}

func New(opts Options) (*Base, error) {
	parsed, err := url.Parse(strings.TrimRight(opts.BaseURL, "/"))
	if err != nil {
		return nil, fmt.Errorf("%s: base_url: %w", opts.Name, err)
	}
	timeout := opts.Timeout
	if timeout == 0 {
		timeout = 8 * time.Second
	}
	maxBody := opts.MaxBody
	if maxBody <= 0 {
		maxBody = MaxBodyBytes
	}
	return &Base{
		name:    opts.Name,
		baseURL: parsed,
		auth:    opts.Auth,
		timeout: timeout,
		headers: opts.Headers,
		maxBody: maxBody,
		client:  clientFor(opts.InsecureSkipVerify, timeout),
		// A streaming response can legitimately remain open for hours. The
		// request context supplied by the handler is its lifetime; applying the
		// adapter's short JSON timeout here would cut every movie off after a few
		// seconds.
		stream: clientFor(opts.InsecureSkipVerify, 0),
		log:    slog.With("service", opts.Name),
	}, nil
}

// WithHeaders returns a shallow copy that adds request headers without
// changing the shared transport. Jellyfin playback uses this for its
// MediaBrowser device identity, while the API key remains owned by auth.
func (b *Base) WithHeaders(headers map[string]string) *Base {
	copied := *b
	copied.headers = make(map[string]string, len(b.headers)+len(headers))
	for name, value := range b.headers {
		copied.headers[name] = value
	}
	for name, value := range headers {
		copied.headers[name] = value
	}
	return &copied
}

func (b *Base) Name() string { return b.name }

// WithTimeout returns a shallow copy that waits longer (or less long).
//
// The per-service timeout is a hard cap applied inside every call, which is
// what stops one sick service hanging a screen. Interactive search legitimately
// needs far more than that -- it queries every indexer in series -- and a
// handler-level deadline cannot loosen a cap applied further down. Measured: a
// Sonarr season search exceeded the 8s default and surfaced as "sonarr is not
// responding", which was not true.
func (b *Base) WithTimeout(d time.Duration) *Base {
	copied := *b
	copied.timeout = d
	return &copied
}

// GetJSON fetches and decodes, applying the credential and the per-service
// timeout. A 401 or 403 gets exactly one re-auth attempt, which is what makes
// qBittorrent's expiring cookie session invisible to callers.
func (b *Base) GetJSON(ctx context.Context, path string, query url.Values, out any) error {
	return b.do(ctx, http.MethodGet, path, query, nil, out)
}

func (b *Base) PostJSON(ctx context.Context, path string, body, out any) error {
	return b.do(ctx, http.MethodPost, path, nil, body, out)
}

// Open starts an authenticated upstream request and leaves the response body
// open for the caller to stream. It deliberately has no adapter timeout: the
// caller's context owns the transfer. The caller must close the body.
func (b *Base) Open(
	ctx context.Context, method, path string, query url.Values, headers http.Header,
) (*http.Response, error) {
	target := *b.baseURL
	target.Path = strings.TrimRight(b.baseURL.Path, "/") + "/" + strings.TrimLeft(path, "/")
	if query != nil {
		target.RawQuery = encodeQuery(query)
	}
	req, err := http.NewRequestWithContext(ctx, method, target.String(), nil)
	if err != nil {
		return nil, &Error{Service: b.name, Kind: KindUnclassified, Err: err}
	}
	for name, value := range b.headers {
		req.Header.Set(name, value)
	}
	for name, values := range headers {
		for _, value := range values {
			req.Header.Add(name, value)
		}
	}
	if b.auth != nil {
		if err := b.auth.Apply(req); err != nil {
			return nil, &Error{Service: b.name, Kind: KindAuth, Err: err}
		}
	}
	resp, err := b.stream.Do(req)
	if err != nil {
		return nil, &Error{Service: b.name, Kind: kindForTransport(err), Err: err}
	}
	return resp, nil
}

func (b *Base) do(
	ctx context.Context, method, path string, query url.Values, body, out any,
) error {
	ctx, cancel := context.WithTimeout(ctx, b.timeout)
	defer cancel()

	resp, err := b.send(ctx, method, path, query, body)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		if b.auth != nil {
			retry, reauthErr := b.auth.Reauth(ctx, resp)
			if reauthErr == nil && retry {
				resp.Body.Close()
				resp, err = b.send(ctx, method, path, query, body)
				if err != nil {
					return err
				}
				defer resp.Body.Close()
			}
		}
	}

	if resp.StatusCode >= 400 {
		// Read a little of the body: these APIs put the actual reason in there,
		// and "HTTP 400" on its own has never helped anyone.
		snippet, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		e := &Error{Service: b.name, Status: resp.StatusCode, Kind: kindForStatus(resp.StatusCode)}
		if len(snippet) > 0 {
			e.Err = fmt.Errorf("%s", strings.TrimSpace(string(snippet)))
		}
		return e
	}

	if out == nil {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, b.maxBody))
		return nil
	}

	raw, err := io.ReadAll(io.LimitReader(resp.Body, b.maxBody))
	if err != nil {
		return &Error{Service: b.name, Kind: KindDecode, Err: err}
	}
	if err := json.Unmarshal(raw, out); err != nil {
		return &Error{Service: b.name, Kind: KindDecode, Err: err}
	}
	return nil
}

// encodeQuery is url.Values.Encode with spaces as %20 rather than +.
//
// Both are legal, but **Jellyseerr rejects the plus form with a bare 400**.
// Found the hard way: every single-word search worked and every multi-word one
// failed, which looked like a hub bug and was a space.
//
// The substitution is safe rather than approximate. url.QueryEscape encodes a
// literal plus as %2B, so any '+' left in the encoded output is, without
// exception, an encoded space.
func encodeQuery(query url.Values) string {
	return strings.ReplaceAll(query.Encode(), "+", "%20")
}

func (b *Base) send(
	ctx context.Context, method, path string, query url.Values, body any,
) (*http.Response, error) {
	target := *b.baseURL
	target.Path = strings.TrimRight(b.baseURL.Path, "/") + "/" + strings.TrimLeft(path, "/")
	if query != nil {
		target.RawQuery = encodeQuery(query)
	}

	var reader io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return nil, &Error{Service: b.name, Kind: KindDecode, Err: err}
		}
		reader = bytes.NewReader(encoded)
	}

	req, err := http.NewRequestWithContext(ctx, method, target.String(), reader)
	if err != nil {
		return nil, &Error{Service: b.name, Kind: KindUnclassified, Err: err}
	}
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	for name, value := range b.headers {
		req.Header.Set(name, value)
	}
	if b.auth != nil {
		if err := b.auth.Apply(req); err != nil {
			return nil, &Error{Service: b.name, Kind: KindAuth, Err: err}
		}
	}

	started := time.Now()
	resp, err := b.client.Do(req)
	if err != nil {
		// The URL is deliberately absent from the log: for services whose
		// credential travels in a query parameter, logging it leaks the key.
		b.log.Warn("upstream call failed", "method", method, "path", path, "error", err)
		return nil, &Error{Service: b.name, Kind: kindForTransport(err), Err: err}
	}
	b.log.Debug("upstream call",
		"method", method, "path", path,
		"status", resp.StatusCode, "ms", time.Since(started).Milliseconds())
	return resp, nil
}

func kindForStatus(status int) Kind {
	switch {
	case status == http.StatusUnauthorized, status == http.StatusForbidden:
		return KindAuth
	case status == http.StatusNotFound:
		return KindNotFound
	case status == http.StatusTooManyRequests:
		return KindRateLimited
	case status >= 500:
		return KindUpstream5xx
	case status >= 400:
		return KindBadRequest
	}
	return KindUnclassified
}

func kindForTransport(err error) Kind {
	if err == nil {
		return KindUnclassified
	}
	switch text := err.Error(); {
	case strings.Contains(text, "context deadline exceeded"),
		strings.Contains(text, "Client.Timeout"):
		return KindTimeout
	case strings.Contains(text, "connection refused"),
		strings.Contains(text, "No connection could be made"):
		return KindRefused
	case strings.Contains(text, "no such host"):
		return KindDNS
	case strings.Contains(text, "certificate"), strings.Contains(text, "tls:"):
		return KindTLS
	}
	return KindUnclassified
}

// GetText fetches a plain-text response. qBittorrent's version endpoints return
// bare strings rather than JSON.
func (b *Base) GetText(ctx context.Context, path string, out *string) error {
	ctx, cancel := context.WithTimeout(ctx, b.timeout)
	defer cancel()

	resp, err := b.send(ctx, http.MethodGet, path, nil, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		return &Error{Service: b.name, Status: resp.StatusCode, Kind: kindForStatus(resp.StatusCode)}
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, b.maxBody))
	if err != nil {
		return &Error{Service: b.name, Kind: KindDecode, Err: err}
	}
	*out = string(raw)
	return nil
}

// GetBytes fetches a binary body, for the image proxy.
//
// Goes through Base rather than a bare http.Client because a Jellyfin image
// needs both the credential and the self-signed-certificate allowance that
// this service is configured with; a plain client has neither.
//
// @return the body and its Content-Type.
func (b *Base) GetBytes(
	ctx context.Context, path string, query url.Values,
) ([]byte, string, error) {
	ctx, cancel := context.WithTimeout(ctx, b.timeout)
	defer cancel()

	resp, err := b.send(ctx, http.MethodGet, path, query, nil)
	if err != nil {
		return nil, "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		return nil, "", &Error{
			Service: b.name, Status: resp.StatusCode, Kind: kindForStatus(resp.StatusCode),
		}
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, b.maxBody))
	if err != nil {
		return nil, "", &Error{Service: b.name, Kind: KindDecode, Err: err}
	}
	contentType := resp.Header.Get("Content-Type")
	if contentType == "" {
		contentType = "image/jpeg"
	}
	return raw, contentType, nil
}

// PostForm submits application/x-www-form-urlencoded, which is the only body
// type qBittorrent's write endpoints accept.
func (b *Base) PostForm(ctx context.Context, path string, form url.Values, out any) error {
	ctx, cancel := context.WithTimeout(ctx, b.timeout)
	defer cancel()

	target := *b.baseURL
	target.Path = strings.TrimRight(b.baseURL.Path, "/") + "/" + strings.TrimLeft(path, "/")

	req, err := http.NewRequestWithContext(
		ctx, http.MethodPost, target.String(), strings.NewReader(form.Encode()))
	if err != nil {
		return &Error{Service: b.name, Kind: KindUnclassified, Err: err}
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	for name, value := range b.headers {
		req.Header.Set(name, value)
	}
	if b.auth != nil {
		if err := b.auth.Apply(req); err != nil {
			return &Error{Service: b.name, Kind: KindAuth, Err: err}
		}
	}

	resp, err := b.client.Do(req)
	if err != nil {
		b.log.Warn("upstream form post failed", "path", path, "error", err)
		return &Error{Service: b.name, Kind: kindForTransport(err), Err: err}
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		snippet, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		e := &Error{Service: b.name, Status: resp.StatusCode, Kind: kindForStatus(resp.StatusCode)}
		if len(snippet) > 0 {
			e.Err = fmt.Errorf("%s", strings.TrimSpace(string(snippet)))
		}
		return e
	}
	if out == nil {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, b.maxBody))
		return nil
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, b.maxBody))
	if err != nil {
		return &Error{Service: b.name, Kind: KindDecode, Err: err}
	}
	return json.Unmarshal(raw, out)
}

// Delete issues a DELETE with query parameters, which is how the *arr apps take
// their queue-removal options.
func (b *Base) Delete(ctx context.Context, path string, query url.Values) error {
	ctx, cancel := context.WithTimeout(ctx, b.timeout)
	defer cancel()

	resp, err := b.send(ctx, http.MethodDelete, path, query, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		snippet, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		e := &Error{Service: b.name, Status: resp.StatusCode, Kind: kindForStatus(resp.StatusCode)}
		if len(snippet) > 0 {
			e.Err = fmt.Errorf("%s", strings.TrimSpace(string(snippet)))
		}
		return e
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, b.maxBody))
	return nil
}

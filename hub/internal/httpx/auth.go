package httpx

import (
	"context"
	"crypto/tls"
	"net/http"
	"net/http/cookiejar"
	"time"
)

// Authenticator puts the credential on a request, and knows what to do when the
// service rejects it.
//
// Reauth exists for exactly one service: qBittorrent's older cookie session,
// which expires and has to be re-established mid-flight. Every other service
// here uses a static header, so its Reauth correctly does nothing -- retrying a
// rejected API key is pointless and, on some services, gets you banned.
type Authenticator interface {
	Apply(req *http.Request) error
	Reauth(ctx context.Context, resp *http.Response) (retry bool, err error)
}

// HeaderAuth sets one or more fixed headers.
type HeaderAuth struct {
	Headers map[string]string
}

func (h HeaderAuth) Apply(req *http.Request) error {
	for name, value := range h.Headers {
		if value != "" {
			req.Header.Set(name, value)
		}
	}
	return nil
}

func (HeaderAuth) Reauth(context.Context, *http.Response) (bool, error) { return false, nil }

// QueryAuth puts the credential in the query string.
//
// Only for URLs that get embedded somewhere a header cannot follow -- Jellyfin
// image and stream URLs. Anything using this must never have its URL logged.
type QueryAuth struct {
	Param string
	Value string
}

func (q QueryAuth) Apply(req *http.Request) error {
	if q.Value == "" {
		return nil
	}
	values := req.URL.Query()
	values.Set(q.Param, q.Value)
	req.URL.RawQuery = values.Encode()
	return nil
}

func (QueryAuth) Reauth(context.Context, *http.Response) (bool, error) { return false, nil }

// NoAuth is for services that need none -- qBittorrent on loopback, where
// "bypass authentication for clients on localhost" is on.
type NoAuth struct{}

func (NoAuth) Apply(*http.Request) error                            { return nil }
func (NoAuth) Reauth(context.Context, *http.Response) (bool, error) { return false, nil }

func clientFor(insecure bool, timeout time.Duration) *http.Client {
	client := &http.Client{Timeout: timeout, Jar: newJar()}
	if insecure {
		// Reached only for a service validation has confirmed is on loopback or
		// a private address. Jellyfin ships with HTTPS forced and a self-signed
		// certificate, so this is the difference between working and not.
		client.Transport = &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
		}
	}
	return client
}

// newJar gives every adapter a cookie jar.
//
// Only qBittorrent's session auth needs one, but its cookie name is not stable
// across versions -- SID on 4.x and 5.0, a QBT_SID_ prefix on newer builds -- so
// a jar is the only correct way to carry it. Handing every adapter one costs
// nothing and removes a special case.
func newJar() http.CookieJar {
	jar, err := cookiejar.New(nil)
	if err != nil {
		// cookiejar.New only errors on a bad PublicSuffixList, and nil is valid,
		// so this is unreachable. Carrying on without a jar is still correct for
		// every adapter except qBittorrent's cookie session.
		return nil
	}
	return jar
}

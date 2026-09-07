// Package auth verifies bearer tokens and throttles the people guessing them.
package auth

import (
	"crypto/subtle"
	"strings"
	"sync"
	"time"

	"ayaneohub/internal/config"
)

// Token is a credential that has already been verified.
//
// It carries no secret: once a request is authenticated the only things worth
// knowing are which device it was and what it is allowed to do.
type Token struct {
	Label  string
	Scopes []string
}

func (t Token) HasScope(scope string) bool {
	for _, s := range t.Scopes {
		if s == scope {
			return true
		}
	}
	return false
}

// Store holds the digests of every configured token.
type Store struct {
	entries []entry
}

type entry struct {
	label  string
	digest string
	scopes []string
}

func NewStore(tokens []config.TokenConfig) *Store {
	s := &Store{}
	for _, t := range tokens {
		digest := strings.ToLower(strings.TrimSpace(t.SHA256))
		if digest == "" {
			// Validation has already refused weak raw tokens, so a raw value
			// here is one someone chose to keep in the file.
			digest = config.HashToken(t.Raw.Reveal())
		}
		scopes := t.Scopes
		if len(scopes) == 0 {
			scopes = []string{"read", "request", "control", "play"}
		}
		s.entries = append(s.entries, entry{label: t.Label, digest: digest, scopes: scopes})
	}
	return s
}

// Verify checks a presented token against every configured digest.
//
// The comparison is constant-time and, more importantly, does not stop at the
// first match: returning early would make the response time depend on which
// token matched, which leaks their order. The cost of checking all of them is
// a handful of microseconds for a list this size.
func (s *Store) Verify(presented string) (Token, bool) {
	if presented == "" {
		return Token{}, false
	}
	digest := config.HashToken(presented)

	matched := 0
	var found entry
	for _, e := range s.entries {
		if subtle.ConstantTimeCompare([]byte(digest), []byte(e.digest)) == 1 {
			matched = 1
			found = e
		}
	}
	if matched == 0 {
		return Token{}, false
	}
	return Token{Label: found.label, Scopes: found.scopes}, true
}

func (s *Store) Count() int { return len(s.entries) }

// BearerFrom pulls the credential out of an Authorization header.
func BearerFrom(header string) string {
	const prefix = "Bearer "
	if len(header) <= len(prefix) || !strings.EqualFold(header[:len(prefix)], prefix) {
		return ""
	}
	return strings.TrimSpace(header[len(prefix):])
}

// Bucket is a token bucket with an injected clock, so the rate limiting is
// tested by advancing a variable rather than by sleeping.
type Bucket struct {
	capacity float64
	perSec   float64
	tokens   float64
	last     time.Time
}

func NewBucket(capacity int, perMinute int) *Bucket {
	return &Bucket{
		capacity: float64(capacity),
		perSec:   float64(perMinute) / 60.0,
		tokens:   float64(capacity),
	}
}

func (b *Bucket) Allow(now time.Time) bool {
	if b.last.IsZero() {
		b.last = now
	}
	elapsed := now.Sub(b.last).Seconds()
	if elapsed > 0 {
		b.tokens += elapsed * b.perSec
		if b.tokens > b.capacity {
			b.tokens = b.capacity
		}
		b.last = now
	}
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// Limiter is one bucket per key -- per token label for requests, so one busy
// device cannot starve another.
type Limiter struct {
	mu        sync.Mutex
	buckets   map[string]*Bucket
	capacity  int
	perMinute int
}

func NewLimiter(perMinute, burst int) *Limiter {
	return &Limiter{
		buckets:   map[string]*Bucket{},
		capacity:  burst,
		perMinute: perMinute,
	}
}

func (l *Limiter) Allow(key string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	b, ok := l.buckets[key]
	if !ok {
		b = NewBucket(l.capacity, l.perMinute)
		l.buckets[key] = b
	}
	return b.Allow(now)
}

// BanList locks out a source after repeated authentication failures.
//
// Separate from the request limiter on purpose. A valid client making too many
// requests is a client to slow down; a client presenting wrong tokens is
// someone guessing, and the right response is to stop answering them for a
// while rather than to answer slightly slower.
type BanList struct {
	mu       sync.Mutex
	failures map[string][]time.Time
	banned   map[string]time.Time
	attempts int
	window   time.Duration
	ban      time.Duration
}

func NewBanList(attempts int, window, ban time.Duration) *BanList {
	return &BanList{
		failures: map[string][]time.Time{},
		banned:   map[string]time.Time{},
		attempts: attempts,
		window:   window,
		ban:      ban,
	}
}

// Banned reports whether a source is currently locked out, and until when.
func (b *BanList) Banned(key string, now time.Time) (bool, time.Time) {
	b.mu.Lock()
	defer b.mu.Unlock()
	until, ok := b.banned[key]
	if !ok {
		return false, time.Time{}
	}
	if now.Before(until) {
		return true, until
	}
	delete(b.banned, key)
	delete(b.failures, key)
	return false, time.Time{}
}

// Fail records a rejected credential. @return true if this one triggered a ban.
func (b *BanList) Fail(key string, now time.Time) bool {
	b.mu.Lock()
	defer b.mu.Unlock()

	cutoff := now.Add(-b.window)
	kept := b.failures[key][:0]
	for _, t := range b.failures[key] {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	kept = append(kept, now)
	b.failures[key] = kept

	if len(kept) >= b.attempts {
		// Extend rather than replace, so hammering through a ban makes it worse
		// instead of resetting the clock.
		existing, alreadyBanned := b.banned[key]
		start := now
		if alreadyBanned && existing.After(now) {
			start = existing
		}
		b.banned[key] = start.Add(b.ban)
		b.failures[key] = nil
		return true
	}
	return false
}

// Succeed clears the failure history for a source that has now proven itself.
func (b *BanList) Succeed(key string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	delete(b.failures, key)
}

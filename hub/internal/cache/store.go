// Package cache is the hub's memory.
//
// It exists for one reason: the handheld is often on a phone connection where a
// round trip costs a few hundred milliseconds, and most of what it asks for did
// not change since it last asked. Measured on this stack, a cold Jellyseerr
// search takes ~590ms and a warm one ~11ms.
//
// Three behaviours beyond a plain TTL map, each earning its keep:
//
//   - **singleflight** -- ten concurrent requests for the same key produce one
//     upstream call, not ten. Without it, opening a screen that shows the same
//     title in three rows triples the load on Jellyseerr.
//   - **stale-while-revalidate** -- past its TTL, the cached value is served
//     immediately and refreshed in the background. A slow *arr then never blocks
//     a screen; it just means the next visit is fresher.
//   - **stale-if-error** -- when the upstream is down, serving something from ten
//     minutes ago and *saying so* beats an empty screen.
package cache

import (
	"context"
	"sync"
	"time"
)

// Spec is how long one kind of data stays useful.
type Spec struct {
	// Fresh is served without any upstream call.
	Fresh time.Duration
	// Stale is served immediately while a refresh runs behind it. Zero disables
	// revalidation, which is right for data where being out of date is a lie
	// rather than an inconvenience -- a torrent list, for instance.
	Stale time.Duration
	// IfError is how long a value may be served after the upstream starts
	// failing. Independent of Stale: a screen going blank because a service
	// blipped is worse than a screen that is politely out of date.
	IfError time.Duration
}

// Meta describes where a value came from, so the app can be honest about it.
type Meta struct {
	Hit   bool
	Age   time.Duration
	Stale bool
	// Set when the value was served only because the upstream failed.
	FromError bool
}

type entry struct {
	value    any
	storedAt time.Time
	failing  bool
}

type call struct {
	done  chan struct{}
	value any
	err   error
}

type Store struct {
	mu       sync.Mutex
	entries  map[string]*entry
	inflight map[string]*call
	// Injected so the tests advance a variable instead of sleeping.
	now func() time.Time
	// A crude bound. The hub caches screens and lookups, not a library, so this
	// is about not leaking on a long uptime rather than about memory pressure.
	maxEntries int
}

func New() *Store {
	return &Store{
		entries:    map[string]*entry{},
		inflight:   map[string]*call{},
		now:        time.Now,
		maxEntries: 2048,
	}
}

// WithClock is for tests.
func (s *Store) WithClock(now func() time.Time) *Store {
	s.now = now
	return s
}

// Do returns a cached value or calls fetch.
//
// fetch may be called on a background goroutine during revalidation, so it must
// not close over the request's context or its cancellation will kill the refresh
// for everyone.
func (s *Store) Do(
	ctx context.Context, key string, spec Spec, fetch func(context.Context) (any, error),
) (any, Meta, error) {
	now := s.now()

	s.mu.Lock()
	existing, found := s.entries[key]
	s.mu.Unlock()

	if found {
		age := now.Sub(existing.storedAt)
		switch {
		case age < spec.Fresh:
			return existing.value, Meta{Hit: true, Age: age}, nil

		case spec.Stale > 0 && age < spec.Fresh+spec.Stale:
			// Serve now, refresh behind. The caller never waits.
			s.revalidate(key, spec, fetch)
			return existing.value, Meta{Hit: true, Age: age, Stale: true}, nil
		}
	}

	value, err := s.fetchOnce(ctx, key, fetch)
	if err != nil {
		// The upstream is unhappy. If we still hold something and the policy
		// allows it, an old answer beats no answer.
		if found && spec.IfError > 0 && now.Sub(existing.storedAt) < spec.Fresh+spec.IfError {
			s.mu.Lock()
			existing.failing = true
			s.mu.Unlock()
			return existing.value, Meta{
				Hit: true, Age: now.Sub(existing.storedAt), Stale: true, FromError: true,
			}, nil
		}
		return nil, Meta{}, err
	}

	s.store(key, value)
	return value, Meta{Hit: false}, nil
}

// fetchOnce collapses concurrent misses for the same key into one call.
func (s *Store) fetchOnce(
	ctx context.Context, key string, fetch func(context.Context) (any, error),
) (any, error) {
	s.mu.Lock()
	if pending, ok := s.inflight[key]; ok {
		s.mu.Unlock()
		select {
		case <-pending.done:
			return pending.value, pending.err
		case <-ctx.Done():
			// This caller gave up; the shared call carries on for the others.
			return nil, ctx.Err()
		}
	}
	pending := &call{done: make(chan struct{})}
	s.inflight[key] = pending
	s.mu.Unlock()

	pending.value, pending.err = fetch(ctx)
	close(pending.done)

	s.mu.Lock()
	delete(s.inflight, key)
	s.mu.Unlock()

	return pending.value, pending.err
}

func (s *Store) revalidate(key string, spec Spec, fetch func(context.Context) (any, error)) {
	s.mu.Lock()
	if _, alreadyRunning := s.inflight[key]; alreadyRunning {
		s.mu.Unlock()
		return
	}
	s.mu.Unlock()

	go func() {
		// A fresh context: the request that triggered this has already been
		// answered and may be long gone, and its cancellation must not abort a
		// refresh that everyone else is waiting to benefit from.
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		value, err := s.fetchOnce(ctx, key, fetch)
		if err == nil {
			s.store(key, value)
		}
	}()
}

func (s *Store) store(key string, value any) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.entries) >= s.maxEntries {
		s.evictOldestLocked()
	}
	s.entries[key] = &entry{value: value, storedAt: s.now()}
}

// evictOldestLocked drops the single oldest entry.
//
// Not an LRU: tracking access order costs more than it saves for a cache this
// size, and the access pattern here is "a few screens, repeatedly" rather than
// anything that would defeat it.
func (s *Store) evictOldestLocked() {
	var oldestKey string
	var oldestAt time.Time
	for key, e := range s.entries {
		if oldestKey == "" || e.storedAt.Before(oldestAt) {
			oldestKey, oldestAt = key, e.storedAt
		}
	}
	if oldestKey != "" {
		delete(s.entries, oldestKey)
	}
}

// Invalidate drops a key. Called after a mutation, so the screen that follows a
// request shows the request rather than the cached "not in library".
func (s *Store) Invalidate(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.entries, key)
}

// InvalidatePrefix drops everything under a prefix -- every search page after a
// request is submitted, for instance.
func (s *Store) InvalidatePrefix(prefix string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for key := range s.entries {
		if len(key) >= len(prefix) && key[:len(prefix)] == prefix {
			delete(s.entries, key)
		}
	}
}

func (s *Store) Len() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.entries)
}

// Fetch is the typed wrapper callers actually use.
func Fetch[T any](
	ctx context.Context, s *Store, key string, spec Spec, fn func(context.Context) (T, error),
) (T, Meta, error) {
	var zero T
	value, meta, err := s.Do(ctx, key, spec, func(ctx context.Context) (any, error) {
		return fn(ctx)
	})
	if err != nil {
		return zero, meta, err
	}
	typed, ok := value.(T)
	if !ok {
		return zero, meta, nil
	}
	return typed, meta, nil
}

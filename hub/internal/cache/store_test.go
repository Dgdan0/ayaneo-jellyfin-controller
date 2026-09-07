package cache

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func fixedClock(start time.Time) (*Store, func(time.Duration)) {
	now := start
	s := New().WithClock(func() time.Time { return now })
	return s, func(d time.Duration) { now = now.Add(d) }
}

func counting(value string) (func(context.Context) (any, error), *atomic.Int32) {
	var calls atomic.Int32
	return func(context.Context) (any, error) {
		calls.Add(1)
		return value, nil
	}, &calls
}

func TestAFreshValueIsServedWithoutCallingUpstream(t *testing.T) {
	s, advance := fixedClock(time.Now())
	fetch, calls := counting("v1")
	spec := Spec{Fresh: time.Minute}

	if _, _, err := s.Do(context.Background(), "k", spec, fetch); err != nil {
		t.Fatal(err)
	}
	advance(30 * time.Second)
	value, meta, err := s.Do(context.Background(), "k", spec, fetch)
	if err != nil {
		t.Fatal(err)
	}
	if value != "v1" || !meta.Hit {
		t.Fatalf("value=%v meta=%+v", value, meta)
	}
	if calls.Load() != 1 {
		t.Fatalf("upstream called %d times, want 1", calls.Load())
	}
	if meta.Age != 30*time.Second {
		t.Fatalf("Age = %v", meta.Age)
	}
}

func TestAnExpiredValueIsRefetched(t *testing.T) {
	s, advance := fixedClock(time.Now())
	fetch, calls := counting("v1")
	spec := Spec{Fresh: time.Minute} // no stale window

	s.Do(context.Background(), "k", spec, fetch)
	advance(2 * time.Minute)
	if _, meta, _ := s.Do(context.Background(), "k", spec, fetch); meta.Hit {
		t.Error("an expired value was served as a hit")
	}
	if calls.Load() != 2 {
		t.Fatalf("upstream called %d times, want 2", calls.Load())
	}
}

func TestStaleIsServedImmediatelyAndRefreshedBehind(t *testing.T) {
	s, advance := fixedClock(time.Now())
	var calls atomic.Int32
	released := make(chan struct{})
	fetch := func(context.Context) (any, error) {
		n := calls.Add(1)
		if n > 1 {
			<-released // the background refresh blocks until we let it finish
		}
		return "v", nil
	}
	spec := Spec{Fresh: time.Minute, Stale: 5 * time.Minute}

	s.Do(context.Background(), "k", spec, fetch)
	advance(2 * time.Minute)

	// The caller must not wait on the slow refresh.
	done := make(chan Meta, 1)
	go func() {
		_, meta, _ := s.Do(context.Background(), "k", spec, fetch)
		done <- meta
	}()

	select {
	case meta := <-done:
		if !meta.Stale || !meta.Hit {
			t.Fatalf("expected a stale hit, got %+v", meta)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("the caller waited for the background refresh")
	}
	close(released)
}

func TestConcurrentMissesCollapseIntoOneCall(t *testing.T) {
	// Without this, a screen showing the same title in three rows triples the
	// load on the upstream for no benefit.
	s, _ := fixedClock(time.Now())
	var calls atomic.Int32
	start := make(chan struct{})
	fetch := func(context.Context) (any, error) {
		calls.Add(1)
		<-start
		return "v", nil
	}
	spec := Spec{Fresh: time.Minute}

	var wg sync.WaitGroup
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			s.Do(context.Background(), "k", spec, fetch)
		}()
	}
	time.Sleep(50 * time.Millisecond) // let them all arrive and block
	close(start)
	wg.Wait()

	if got := calls.Load(); got != 1 {
		t.Fatalf("upstream called %d times for 10 concurrent misses, want 1", got)
	}
}

func TestStaleIsServedWhenTheUpstreamFails(t *testing.T) {
	s, advance := fixedClock(time.Now())
	var fail atomic.Bool
	fetch := func(context.Context) (any, error) {
		if fail.Load() {
			return nil, errors.New("connection refused")
		}
		return "v1", nil
	}
	spec := Spec{Fresh: time.Minute, IfError: 10 * time.Minute}

	s.Do(context.Background(), "k", spec, fetch)
	fail.Store(true)
	advance(5 * time.Minute)

	value, meta, err := s.Do(context.Background(), "k", spec, fetch)
	if err != nil {
		t.Fatalf("expected the stale value, got error: %v", err)
	}
	if value != "v1" || !meta.FromError {
		t.Fatalf("value=%v meta=%+v", value, meta)
	}
}

func TestAFailureOutsideTheErrorWindowIsReported(t *testing.T) {
	// Being politely out of date has a limit. Past it, an error is honest.
	s, advance := fixedClock(time.Now())
	var fail atomic.Bool
	fetch := func(context.Context) (any, error) {
		if fail.Load() {
			return nil, errors.New("connection refused")
		}
		return "v1", nil
	}
	spec := Spec{Fresh: time.Minute, IfError: time.Minute}

	s.Do(context.Background(), "k", spec, fetch)
	fail.Store(true)
	advance(time.Hour)

	if _, _, err := s.Do(context.Background(), "k", spec, fetch); err == nil {
		t.Fatal("expected an error once the stale window closed")
	}
}

func TestNoStaleWindowMeansNeverServeStale(t *testing.T) {
	// Downloads use this: a torrent list from two minutes ago is not slightly
	// out of date, it is wrong.
	s, advance := fixedClock(time.Now())
	fetch, calls := counting("v")
	s.Do(context.Background(), "k", Downloads, fetch)
	advance(time.Minute)
	s.Do(context.Background(), "k", Downloads, fetch)
	if calls.Load() != 2 {
		t.Fatalf("Downloads served a stale value: %d calls", calls.Load())
	}
}

func TestAFailureWithNothingCachedIsAnError(t *testing.T) {
	s, _ := fixedClock(time.Now())
	_, _, err := s.Do(context.Background(), "k", Search, func(context.Context) (any, error) {
		return nil, errors.New("boom")
	})
	if err == nil {
		t.Fatal("expected the error to surface")
	}
}

func TestInvalidate(t *testing.T) {
	s, _ := fixedClock(time.Now())
	fetch, calls := counting("v")
	spec := Spec{Fresh: time.Hour}
	s.Do(context.Background(), "k", spec, fetch)
	s.Invalidate("k")
	s.Do(context.Background(), "k", spec, fetch)
	if calls.Load() != 2 {
		t.Fatalf("Invalidate did not drop the entry")
	}
}

func TestInvalidatePrefix(t *testing.T) {
	// After a request is submitted, every cached search page is now wrong about
	// that title's availability.
	s, _ := fixedClock(time.Now())
	fetch, _ := counting("v")
	spec := Spec{Fresh: time.Hour}
	s.Do(context.Background(), "search:dune:1", spec, fetch)
	s.Do(context.Background(), "search:dune:2", spec, fetch)
	s.Do(context.Background(), "detail:tmdb:movie:1", spec, fetch)

	s.InvalidatePrefix("search:")
	if s.Len() != 1 {
		t.Fatalf("expected only the detail entry to survive, %d remain", s.Len())
	}
}

func TestEvictionBoundsTheCache(t *testing.T) {
	s, advance := fixedClock(time.Now())
	s.maxEntries = 5
	fetch, _ := counting("v")
	for i := 0; i < 20; i++ {
		s.Do(context.Background(), string(rune('a'+i)), Spec{Fresh: time.Hour}, fetch)
		advance(time.Second) // distinct timestamps, so "oldest" is well defined
	}
	if s.Len() > 5 {
		t.Fatalf("cache grew to %d, cap is 5", s.Len())
	}
}

func TestFetchIsTyped(t *testing.T) {
	s, _ := fixedClock(time.Now())
	type payload struct{ Name string }
	got, _, err := Fetch(context.Background(), s, "k", Spec{Fresh: time.Minute},
		func(context.Context) (*payload, error) { return &payload{Name: "dune"}, nil })
	if err != nil {
		t.Fatal(err)
	}
	if got.Name != "dune" {
		t.Fatalf("got %+v", got)
	}
}

package api

import (
	"testing"
	"time"

	"ayaneohub/internal/cache"
)

func TestCacheSummaryKeepsTheWorstOfEveryPart(t *testing.T) {
	var freshness cacheSummary
	freshness.add(cache.Meta{Hit: true, Age: 30 * time.Second})
	// One row served from an old copy because its service was down.
	freshness.add(cache.Meta{Hit: true, Age: 4 * time.Minute, Stale: true, FromError: true})
	freshness.add(cache.Meta{Hit: false})
	got := freshness.result()
	want := CacheInfo{Hit: false, AgeSeconds: 240, Stale: true, Degraded: true}
	if got != want {
		t.Fatalf("summary = %+v, want %+v", got, want)
	}
}

func TestCacheSummaryWithNothingIsAFreshHit(t *testing.T) {
	var freshness cacheSummary
	if got := freshness.result(); got != (CacheInfo{Hit: true}) {
		t.Fatalf("empty summary = %+v", got)
	}
}

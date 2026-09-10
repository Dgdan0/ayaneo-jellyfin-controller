package auth

import (
	"testing"
	"time"

	"ayaneohub/internal/config"
)

const (
	pocketToken = "Kp7wZ2xQ9mR4tYbN6vC1sD8jH3gL5aF0"
	laptopToken = "Zt4rE8yU2iO6pA1sD3fG5hJ7kL9xC0vB"
)

func store() *Store {
	return NewStore([]config.TokenConfig{
		{Label: "pocketds", SHA256: config.HashToken(pocketToken), Scopes: []string{"read", "control"}},
		{Label: "laptop", SHA256: config.HashToken(laptopToken), Scopes: []string{"read"}},
	})
}

// --- verification ---------------------------------------------------------

func TestVerifyAcceptsAConfiguredToken(t *testing.T) {
	tok, ok := store().Verify(pocketToken)
	if !ok {
		t.Fatal("a configured token was rejected")
	}
	if tok.Label != "pocketds" {
		t.Fatalf("Label = %q, want pocketds", tok.Label)
	}
}

func TestVerifyPicksTheRightTokenFromSeveral(t *testing.T) {
	tok, ok := store().Verify(laptopToken)
	if !ok || tok.Label != "laptop" {
		t.Fatalf("got %+v ok=%v, want laptop", tok, ok)
	}
}

func TestVerifyRejectsAnythingElse(t *testing.T) {
	s := store()
	for _, bad := range []string{
		"",
		"wrong",
		pocketToken + "x",
		pocketToken[:len(pocketToken)-1],
		config.HashToken(pocketToken), // the digest is not the token
	} {
		if _, ok := s.Verify(bad); ok {
			t.Errorf("accepted %q", bad)
		}
	}
}

func TestScopesComeFromTheToken(t *testing.T) {
	pocket, _ := store().Verify(pocketToken)
	if !pocket.HasScope("control") {
		t.Error("pocketds should have control")
	}
	laptop, _ := store().Verify(laptopToken)
	if laptop.HasScope("control") {
		t.Error("laptop should be read-only")
	}
	if !laptop.HasScope("read") {
		t.Error("laptop should have read")
	}
}

func TestATokenWithNoScopesGetsThemAll(t *testing.T) {
	s := NewStore([]config.TokenConfig{{Label: "x", SHA256: config.HashToken(pocketToken)}})
	tok, _ := s.Verify(pocketToken)
	for _, scope := range []string{"read", "request", "control", "play", "download"} {
		if !tok.HasScope(scope) {
			t.Errorf("missing default scope %q", scope)
		}
	}
}

func TestARawTokenInConfigStillWorks(t *testing.T) {
	s := NewStore([]config.TokenConfig{{Label: "x", Raw: config.Secret(pocketToken)}})
	if _, ok := s.Verify(pocketToken); !ok {
		t.Fatal("a raw token in config was not usable")
	}
}

// --- header parsing -------------------------------------------------------

func TestBearerFrom(t *testing.T) {
	cases := map[string]string{
		"Bearer abc123":   "abc123",
		"bearer abc123":   "abc123", // schemes are case-insensitive per RFC 7235
		"Bearer  abc123 ": "abc123",
		"Basic abc123":    "",
		"abc123":          "",
		"Bearer":          "",
		"Bearer ":         "",
		"":                "",
	}
	for header, want := range cases {
		if got := BearerFrom(header); got != want {
			t.Errorf("BearerFrom(%q) = %q, want %q", header, got, want)
		}
	}
}

// --- rate limiting --------------------------------------------------------

func TestBucketAllowsTheBurstThenStops(t *testing.T) {
	now := time.Now()
	b := NewBucket(3, 60)
	for i := 0; i < 3; i++ {
		if !b.Allow(now) {
			t.Fatalf("request %d in the burst was refused", i+1)
		}
	}
	if b.Allow(now) {
		t.Fatal("the burst was exceeded and still allowed")
	}
}

func TestBucketRefillsOverTime(t *testing.T) {
	now := time.Now()
	b := NewBucket(2, 60) // one per second
	b.Allow(now)
	b.Allow(now)
	if b.Allow(now) {
		t.Fatal("allowed past the burst")
	}
	if !b.Allow(now.Add(time.Second)) {
		t.Fatal("a second later, one token should have refilled")
	}
}

func TestBucketDoesNotRefillPastCapacity(t *testing.T) {
	// An idle client must not bank an hour of requests and then spend them all
	// in one go.
	now := time.Now()
	b := NewBucket(3, 60)
	allowed := 0
	for i := 0; i < 10; i++ {
		if b.Allow(now.Add(time.Hour)) {
			allowed++
		}
	}
	if allowed != 3 {
		t.Fatalf("allowed %d after an idle hour, want the capacity of 3", allowed)
	}
}

func TestLimiterKeepsDevicesApart(t *testing.T) {
	// One busy device must not lock another one out.
	now := time.Now()
	l := NewLimiter(60, 2)
	l.Allow("pocketds", now)
	l.Allow("pocketds", now)
	if l.Allow("pocketds", now) {
		t.Fatal("pocketds should be limited by now")
	}
	if !l.Allow("laptop", now) {
		t.Fatal("laptop was punished for pocketds being busy")
	}
}

// --- auth failure bans ----------------------------------------------------

func TestBanAfterRepeatedFailures(t *testing.T) {
	now := time.Now()
	b := NewBanList(3, time.Minute, 15*time.Minute)

	if b.Fail("1.2.3.4", now) {
		t.Fatal("banned on the first failure")
	}
	b.Fail("1.2.3.4", now)
	if !b.Fail("1.2.3.4", now) {
		t.Fatal("the third failure should have triggered the ban")
	}

	banned, until := b.Banned("1.2.3.4", now)
	if !banned {
		t.Fatal("not reported as banned")
	}
	if !until.After(now.Add(14 * time.Minute)) {
		t.Fatalf("ban expires at %v, expected ~15 minutes out", until)
	}
}

func TestTheBanExpires(t *testing.T) {
	now := time.Now()
	b := NewBanList(2, time.Minute, 10*time.Minute)
	b.Fail("1.2.3.4", now)
	b.Fail("1.2.3.4", now)

	if banned, _ := b.Banned("1.2.3.4", now.Add(9*time.Minute)); !banned {
		t.Fatal("released early")
	}
	if banned, _ := b.Banned("1.2.3.4", now.Add(11*time.Minute)); banned {
		t.Fatal("never released")
	}
}

func TestFailuresOutsideTheWindowAreForgotten(t *testing.T) {
	// Three typos over a week is not an attack.
	now := time.Now()
	b := NewBanList(3, time.Minute, 10*time.Minute)
	b.Fail("1.2.3.4", now)
	b.Fail("1.2.3.4", now.Add(2*time.Minute))
	if b.Fail("1.2.3.4", now.Add(4*time.Minute)) {
		t.Fatal("banned on failures that were minutes apart")
	}
}

func TestHammeringThroughABanExtendsIt(t *testing.T) {
	// Otherwise a persistent guesser resets the clock by continuing to try.
	now := time.Now()
	b := NewBanList(2, time.Minute, 10*time.Minute)
	b.Fail("1.2.3.4", now)
	b.Fail("1.2.3.4", now)
	_, first := b.Banned("1.2.3.4", now)

	b.Fail("1.2.3.4", now.Add(time.Minute))
	b.Fail("1.2.3.4", now.Add(time.Minute))
	_, second := b.Banned("1.2.3.4", now.Add(time.Minute))

	if !second.After(first) {
		t.Fatalf("ban did not extend: %v then %v", first, second)
	}
}

func TestSuccessClearsTheFailureHistory(t *testing.T) {
	now := time.Now()
	b := NewBanList(3, time.Minute, 10*time.Minute)
	b.Fail("1.2.3.4", now)
	b.Fail("1.2.3.4", now)
	b.Succeed("1.2.3.4")
	if b.Fail("1.2.3.4", now) {
		t.Fatal("a successful login should have reset the count")
	}
}

func TestSourcesAreBannedIndependently(t *testing.T) {
	now := time.Now()
	b := NewBanList(2, time.Minute, 10*time.Minute)
	b.Fail("1.2.3.4", now)
	b.Fail("1.2.3.4", now)
	if banned, _ := b.Banned("5.6.7.8", now); banned {
		t.Fatal("an unrelated address was banned")
	}
}

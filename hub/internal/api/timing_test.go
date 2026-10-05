package api

import (
	"testing"
	"time"
)

// patient is how long a test waits for something that must happen when real
// time cannot be avoided (a socket deadline, a child process, a goroutine that
// has to be scheduled). It is far past any honest delay, so a machine that is
// busy with a build slows such a test down and cannot fail it. A test asserts
// on order and outcome, never on how fast: a lower bound that the clock itself
// guarantees (a timer never fires early) is fine, an upper bound is not.
const patient = 30 * time.Second

// eventually polls until condition holds. It fails only after patient, which a
// working hub never needs.
func eventually(t *testing.T, what string, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(patient)
	for !condition() {
		if time.Now().After(deadline) {
			t.Fatalf("still waiting for %s after %v", what, patient)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

// within runs work and fails if it has not finished after patient.
func within(t *testing.T, what string, work func()) {
	t.Helper()
	done := make(chan struct{})
	go func() {
		defer close(done)
		work()
	}()
	select {
	case <-done:
	case <-time.After(patient):
		t.Fatalf("%s did not finish within %v", what, patient)
	}
}

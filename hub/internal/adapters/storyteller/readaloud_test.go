package storyteller

import "testing"

func TestReadaloudAvailabilityRequiresFinishedAlignment(t *testing.T) {
	for _, status := range []string{"QUEUED", "PROCESSING", "ERROR", "FAILED", "UNKNOWN"} {
		if (&Readaloud{UUID: "book", Status: status}).Available() {
			t.Fatalf("%s advertised as playable", status)
		}
	}
	if !(&Readaloud{UUID: "book", Status: "ALIGNED"}).Available() {
		t.Fatal("aligned edition unavailable")
	}
	if (&Readaloud{UUID: "book", Status: "ALIGNED", Missing: true}).Available() {
		t.Fatal("missing edition playable")
	}
	var absent *Readaloud
	if absent.Available() {
		t.Fatal("nil edition playable")
	}
}

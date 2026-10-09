package api

// "You listened further on <device>" (#62): a place says which device wrote it, so the app that finds a newer one can
// name where it came from. A device is a token, and its label is what the owner called it when issuing it.

import (
	"encoding/json"
	"net/http"
	"testing"
)

func TestAPlaceKnowsWhichDeviceWroteItOnlyWhileItIsTheOneHeld(t *testing.T) {
	writers := newPlaceWriters()
	if _, known := writers.of("storyteller", "12", 1000); known {
		t.Fatal("nobody has written a place yet")
	}
	writers.note("storyteller", "12", "ipad-dan", 1000)
	if who, known := writers.of("storyteller", "12", 1000); !known || who != "ipad-dan" {
		t.Fatalf("who = %q, %v", who, known)
	}
	// Storyteller's own app wrote a later place, or the hub restarted and lost it: nobody is blamed for a place they did not write.
	if _, known := writers.of("storyteller", "12", 2000); known {
		t.Fatal("a later place is not the one that was noted")
	}
	writers.note("storyteller", "12", "pocketds", 3000)
	if who, _ := writers.of("storyteller", "12", 3000); who != "pocketds" {
		t.Fatalf("who = %q", who)
	}
	if _, known := writers.of("storyteller", "13", 3000); known {
		t.Fatal("another book was not written")
	}
}

func TestAnEpubPlaceNamesTheDeviceThatWroteItAndWhetherThatIsYou(t *testing.T) {
	env := newPositionEnv(t)
	read := func() ReadingEpubPosition {
		t.Helper()
		got := publicationRequest(env.handler, http.MethodGet, env.epubPath(), "")
		if got.Code != http.StatusOK {
			t.Fatalf("epub position = %d: %s", got.Code, got.Body.String())
		}
		var body ReadingEpubPosition
		if err := json.Unmarshal(got.Body.Bytes(), &body); err != nil {
			t.Fatal(err)
		}
		return body
	}
	if before := read(); before.Device != "" || before.ByThisDevice {
		t.Fatalf("no device wrote anything: %+v", before)
	}
	if got := env.epubWrite(readerPlace, ``); got.Code != http.StatusOK {
		t.Fatalf("write = %d: %s", got.Code, got.Body.String())
	}
	after := read()
	if after.Device != "reader" || !after.ByThisDevice {
		t.Fatalf("the place was written by this device, 'reader': %+v", after)
	}
	// The audio route says the same of the same place.
	env.write("Fixture Odyssey (1).mp3", 4000)
	if audio := env.read(); audio.Device != "reader" || !audio.ByThisDevice {
		t.Fatalf("audio position: %+v", audio)
	}
	// The reading of a place that was not the hub's write (Storyteller's own app wrote it) names nobody.
	env.positions.seed(`{"href":"Fixture Odyssey (2).mp3","locations":{"fragments":["t=1"],"totalProgression":0.2}}`, clockStart+90_000)
	if outside := read(); outside.Device != "" {
		t.Fatalf("a place written elsewhere: %+v", outside)
	}
}

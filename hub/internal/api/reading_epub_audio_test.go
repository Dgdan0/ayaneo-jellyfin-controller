package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"reflect"
	"sort"
	"testing"
)

// The text reader's route meets the listener's places. A book with a read-along
// edition is one whose audio and text are the same place, so a place a listener
// wrote is, to a reader of the text, the sentence being spoken there.

func (e *positionEnv) epubPath() string {
	return "/v1/reading/works/" + e.child + "/publications/12/position"
}

func (e *positionEnv) epubRead() ReadingEpubPosition {
	e.t.Helper()
	response := e.get(e.epubPath())
	if response.Code != http.StatusOK {
		e.t.Fatalf("EPUB position = %d: %s", response.Code, response.Body.String())
	}
	var body ReadingEpubPosition
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		e.t.Fatal(err)
	}
	return body
}

type textLocator struct {
	Href      string `json:"href"`
	Type      string `json:"type"`
	Locations struct {
		Fragments        []string `json:"fragments"`
		Progression      *float64 `json:"progression"`
		TotalProgression *float64 `json:"totalProgression"`
	} `json:"locations"`
}

func locatorOf(t *testing.T, raw json.RawMessage) textLocator {
	t.Helper()
	var locator textLocator
	if err := json.Unmarshal(raw, &locator); err != nil {
		t.Fatalf("locator %s: %v", raw, err)
	}
	return locator
}

func TestEpubPositionTurnsAnAudioPlaceIntoTheSentenceOfAnAlignedBook(t *testing.T) {
	env := newPositionEnv(t)
	for _, sentence := range env.sentences() {
		name := trackedTagOrder[sentence.track]
		env.write(name, sentence.offset+1)
		raw, stamped, _ := env.positions.stored()

		got := env.epubRead()
		locator := locatorOf(t, got.Locator)
		if locator.Href != sentence.href || !reflect.DeepEqual(locator.Locations.Fragments, []string{sentence.fragment}) || locator.Type != "application/xhtml+xml" {
			t.Fatalf("%s @ %d reads as %s, want %s#%s", name, sentence.offset+1, got.Locator, sentence.href, sentence.fragment)
		}
		// How far through the book, in the order the book is played.
		global := float64(env.position().GlobalMs) / 13_112_152
		if locator.Locations.TotalProgression == nil || !near(*locator.Locations.TotalProgression, global) {
			t.Fatalf("totalProgression %v, want %v", locator.Locations.TotalProgression, global)
		}
		if got.Timestamp != stamped || got.WorkID != env.child || got.SourceItemID != "12" {
			t.Fatalf("timestamp %d (Storyteller's %d), ids %q %q", got.Timestamp, stamped, got.WorkID, got.SourceItemID)
		}
		// Additively: the audio place it was is still there to read.
		if got.Audio == nil || got.Audio.TrackID != trackIDFor(name) || got.Audio.OffsetMs != sentence.offset+1 || !got.Audio.Exact ||
			got.Audio.Sentence == nil || got.Audio.Sentence.Fragment != sentence.fragment {
			t.Fatalf("audio hint = %+v", got.Audio)
		}
		// Reading changed nothing at Storyteller.
		if again, again2, _ := env.positions.stored(); string(again) != string(raw) || again2 != stamped {
			t.Fatalf("reading rewrote the place: %s @ %d", again, again2)
		}
	}
}

func TestEpubPositionLeavesAnythingItCannotBeExactAboutAsItIs(t *testing.T) {
	audio := `{"href":"Fixture Odyssey (1).mp3","type":"audio/mpeg","locations":{"fragments":["t=9.000"],"progression":0.01,"totalProgression":0.3}}`
	text := `{"href":"text/part0002.xhtml","type":"application/xhtml+xml","locations":{"fragments":["id2-s1"],"progression":0.2,"totalProgression":0.3}}`
	for _, test := range []struct {
		name   string
		env    func(t *testing.T) *positionEnv
		stored string
		probed int32 // files looked at to answer
	}{
		{"an audio place of a book with no read-along edition", func(t *testing.T) *positionEnv { return withPositions(newTrackedAudioEnv(t)) }, audio, 0},
		{"a text place of an aligned book", func(t *testing.T) *positionEnv { return newPositionEnv(t) }, text, 0},
		{"an audio place that is no file of the book", func(t *testing.T) *positionEnv { return newPositionEnv(t) },
			`{"href":"Renamed.mp3","type":"audio/mpeg","locations":{"fragments":["t=9.000"],"totalProgression":0.3}}`, 5},
		{"an audio place of an edition that cannot be mapped", func(t *testing.T) *positionEnv {
			return withPositions(newAlignedTrackedEnv(t, alignedOptions{epub: func(path string) { os.WriteFile(path, []byte("not a zip archive"), 0o644) }}))
		}, audio, 5},
		{"an audio place of a book whose files are gone", func(t *testing.T) *positionEnv {
			env := newPositionEnv(t)
			if err := os.Remove(env.file("Fixture Odyssey (2).mp3")); err != nil {
				t.Fatal(err)
			}
			return env
		}, audio, 0},
	} {
		t.Run(test.name, func(t *testing.T) {
			env := test.env(t)
			env.positions.seed(test.stored, clockStart-1)
			got := env.epubRead()
			if !sameJSON(got.Locator, json.RawMessage(test.stored)) || got.Audio != nil || got.Timestamp != clockStart-1 {
				t.Fatalf("read as %s with audio %+v at %d, want it as stored", got.Locator, got.Audio, got.Timestamp)
			}
			if probed := env.probed.Load(); probed != test.probed {
				t.Fatalf("answering looked at %d files, want %d", probed, test.probed)
			}
		})
	}
}

// What the old route said still holds when there is nothing to say.
func TestEpubPositionOfABookWithNoPlaceIsStillNull(t *testing.T) {
	env := newPositionEnv(t)
	got := env.epubRead()
	if string(got.Locator) != "null" || got.Audio != nil {
		t.Fatalf("an empty slot = %s %+v", got.Locator, got.Audio)
	}
	if body := env.get(env.epubPath()).Body.String(); !jsonHasNoKey(t, body, "audio") {
		t.Fatalf("an empty slot names an audio place: %s", body)
	}
}

func jsonHasNoKey(t *testing.T, body, key string) bool {
	t.Helper()
	var fields map[string]any
	if err := json.Unmarshal([]byte(body), &fields); err != nil {
		t.Fatal(err)
	}
	_, present := fields[key]
	return !present
}

func TestEpubPositionHasTheDocumentedFields(t *testing.T) {
	env := newPositionEnv(t)
	env.write("Fixture Odyssey (1).mp3", 4000)
	var body map[string]any
	if err := json.Unmarshal(env.get(env.epubPath()).Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	keys := []string{}
	for key := range body {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	if want := []string{"audio", "locator", "sourceItemId", "timestamp", "updatedAt", "workId"}; !reflect.DeepEqual(keys, want) {
		t.Fatalf("keys = %v, want %v", keys, want)
	}
}

// The reader was shown the sentence; the table holds the audio place. A write
// that says "I last saw this" is checked against either.
func TestEpubPositionBaseMayBeTheSentenceAnAudioPlaceWasShownAs(t *testing.T) {
	env := newPositionEnv(t)
	env.write("Fixture Odyssey (2).mp3", 1_500_000)
	raw, _, _ := env.positions.stored()
	shown := env.epubRead().Locator
	if string(shown) == string(raw) {
		t.Fatal("the reader was shown the audio locator")
	}
	next := `{"href":"OEBPS/text/part0004.xhtml","type":"application/xhtml+xml","locations":{"fragments":["id4-s2"],"totalProgression":0.7}}`
	post := func(expected string) int {
		body := fmt.Sprintf(`{"locator":%s,"timestamp":%d,"checkBase":true,"expectedLocator":%s}`, next, clockStart+1000, expected)
		return publicationRequest(env.handler, http.MethodPost, env.epubPath(), body).Code
	}
	if got := post(`{"href":"OEBPS/text/part0001.xhtml","locations":{"fragments":["id1-s0"]}}`); got != http.StatusConflict {
		t.Fatalf("another place as the base = %d", got)
	}
	if got := post(`null`); got != http.StatusConflict {
		t.Fatalf("null as the base of a held place = %d", got)
	}
	if got := post(string(shown)); got != http.StatusOK {
		t.Fatalf("the sentence that was shown = %d", got)
	}
	// The write was the text locator, as the reader sent it, and it is now what
	// the table holds.
	if held, stamped, _ := env.positions.stored(); !sameJSON(held, json.RawMessage(next)) || stamped != clockStart+1000 {
		t.Fatalf("held %s @ %d", held, stamped)
	}

	// The raw audio locator is still an acceptable base, as it always was.
	env.write("Fixture Odyssey (2).mp3", 1_600_000)
	raw, _, _ = env.positions.stored()
	body := fmt.Sprintf(`{"locator":%s,"timestamp":%d,"checkBase":true,"expectedLocator":%s}`, next, clockStart+9_000_000, raw)
	if got := publicationRequest(env.handler, http.MethodPost, env.epubPath(), body).Code; got != http.StatusOK {
		t.Fatalf("the locator as stored = %d", got)
	}

	// And a text place written there is read by a listener exactly: the loop closes.
	read := env.position()
	if read == nil || !read.Exact || read.Form != "text" || read.Sentence == nil || read.Sentence.Fragment != "id4-s2" {
		t.Fatalf("the listener reads %+v", read)
	}
}

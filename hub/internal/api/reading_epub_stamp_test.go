package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)

// One rule for the time a reading place is stamped with, on both routes: once the
// check has passed, the hub writes the later of its own clock and one after what
// Storyteller holds, and the app's clock plays no part. A phone running behind
// the PC would otherwise lose to a place the hub stamped a moment ago, and be told
// that another device had moved the book when none had.

// The text place a reader writes: a sentence of the read-along edition.
const readerPlace = `{"href":"OEBPS/text/part0004.xhtml","type":"application/xhtml+xml","locations":{"fragments":["id4-s2"],"totalProgression":0.7}}`

// epubWrite posts a place through the EPUB route. extra is more of the body, with
// its leading comma: the app's clock, or a base to check.
func (e *positionEnv) epubWrite(locator, extra string) *httptest.ResponseRecorder {
	e.t.Helper()
	return publicationRequest(e.handler, http.MethodPost, e.epubPath(), `{"locator":`+locator+extra+`}`)
}

func (e *positionEnv) heldStamp() int64 {
	e.t.Helper()
	_, stamp, _ := e.positions.stored()
	return stamp
}

func TestEpubPositionWriteIsNotRefusedForAClockThatRunsBehindTheHub(t *testing.T) {
	env := newPositionEnv(t)
	env.write("Fixture Odyssey (1).mp3", 4000)
	if got := env.heldStamp(); got != clockStart {
		t.Fatalf("the audio write was stamped %d, want the hub's clock %d", got, clockStart)
	}

	// The phone's clock is a minute behind the PC's.
	response := env.epubWrite(readerPlace, fmt.Sprintf(`,"timestamp":%d`, clockStart-60_000))
	if response.Code != http.StatusOK {
		t.Fatalf("a reader whose clock runs behind = %d: %s", response.Code, response.Body.String())
	}
	if env.positions.refused != 0 {
		t.Fatalf("Storyteller refused %d writes", env.positions.refused)
	}
	if held, stamped, _ := env.positions.stored(); !sameJSON(held, json.RawMessage(readerPlace)) || stamped != clockStart+1 {
		t.Fatalf("held %s @ %d, want the reader's place @ %d", held, stamped, clockStart+1)
	}

	// And back: the listener is not refused either, and the stamps only go up.
	env.write("Fixture Odyssey (2).mp3", 9000)
	if got := env.heldStamp(); got != clockStart+2 {
		t.Fatalf("the next audio write was stamped %d, want %d", got, clockStart+2)
	}
	if again := env.epubWrite(readerPlace, `,"timestamp":1`); again.Code != http.StatusOK || env.heldStamp() != clockStart+3 {
		t.Fatalf("a second reader write = %d, stamped %d", again.Code, env.heldStamp())
	}
}

func TestEpubPositionIsStampedAfterTheCheckWithTheLaterOfNowAndOneAfterWhatIsHeld(t *testing.T) {
	for _, test := range []struct {
		name   string
		stored int64 // 0: nothing is held
		want   int64
	}{
		{"nothing held", 0, clockStart},
		{"an older place", clockStart - 5000, clockStart},
		{"one written this millisecond", clockStart, clockStart + 1},
		{"a place stamped by a clock a minute ahead", clockStart + 60_000, clockStart + 60_001},
	} {
		t.Run(test.name, func(t *testing.T) {
			// What the app says the time is makes no difference, whatever it says, or
			// whether it says anything: an older app sends it, a newer one need not.
			for _, claimed := range []string{``, `,"timestamp":1`, `,"timestamp":0`, `,"timestamp":-5`,
				`,"timestamp":9000000000000000000`, fmt.Sprintf(`,"timestamp":%d`, clockStart-60_000), fmt.Sprintf(`,"timestamp":%d`, clockStart+60_000)} {
				env := newPositionEnv(t)
				if test.stored > 0 {
					env.positions.seed(`{"href":"Fixture Odyssey (2).mp3","locations":{"fragments":["t=1"],"totalProgression":0.2}}`, test.stored)
				}
				response := env.epubWrite(readerPlace, claimed)
				if response.Code != http.StatusOK {
					t.Fatalf("%q = %d: %s", claimed, response.Code, response.Body.String())
				}
				if got := env.heldStamp(); got != test.want {
					t.Fatalf("%q: stamped %d, want %d", claimed, got, test.want)
				}
				// The reply says what the place was stamped with.
				var reply map[string]any
				if err := json.Unmarshal(response.Body.Bytes(), &reply); err != nil {
					t.Fatal(err)
				}
				if reply["ok"] != true || reply["action"] != "save_epub_position" || reply["timestamp"] != float64(test.want) || len(reply) != 3 {
					t.Fatalf("%q: reply = %v", claimed, reply)
				}
			}
		})
	}
}

// What the clock never decided is still the person's: the base is the check, and
// Storyteller's own refusal of an older write is still a conflict.
func TestEpubPositionStillRefusesAWriteThatWouldLoseAnotherDevicesPlace(t *testing.T) {
	env := newPositionEnv(t)
	env.write("Fixture Odyssey (1).mp3", 4000)
	held, stamped, _ := env.positions.stored()
	writes := env.positions.posts

	refuse := func(what, extra string) {
		t.Helper()
		response := env.epubWrite(readerPlace, extra)
		got := decodeAudioError(t, response)
		if response.Code != http.StatusConflict || got.Code != "reading_position_conflict" || got.Service != "" || got.Retryable ||
			got.Message != "Reading progress changed on another device. Choose which position to continue from." {
			t.Fatalf("%s = %d %+v", what, response.Code, got)
		}
		if after, afterStamp, _ := env.positions.stored(); string(after) != string(held) || afterStamp != stamped || env.positions.posts != writes {
			t.Fatalf("%s changed the place: %s @ %d after %d writes", what, after, afterStamp, env.positions.posts)
		}
	}
	// A save that was made offline against the place before this one, arriving late:
	// however new its own clock says it is, its base is not the place held.
	refuse("an offline save against an old base", fmt.Sprintf(`,"timestamp":%d,"checkBase":true,"expectedLocator":{"href":"OEBPS/text/part0001.xhtml","locations":{"fragments":["id1-s0"]}}`, clockStart+10_000_000))
	refuse("a base of nothing", `,"checkBase":true,"expectedLocator":null`)
	refuse("no base at all, asked to check", `,"checkBase":true`)

	// The base that is the place held, as the reader was shown it, is let through.
	shown := env.epubRead().Locator
	response := env.epubWrite(readerPlace, `,"timestamp":1,"checkBase":true,"expectedLocator":`+string(shown))
	if response.Code != http.StatusOK || env.heldStamp() != stamped+1 {
		t.Fatalf("the base that was shown = %d, stamped %d", response.Code, env.heldStamp())
	}
}

// Another writer can still get in between the hub reading the place and writing
// it, and Storyteller then refuses the hub's older stamp: the same conflict.
func TestEpubPositionStorytellersOwnRefusalIsTheSameConflict(t *testing.T) {
	env := newPositionEnv(t)
	env.positions.seed(`{"href":"Fixture Odyssey (2).mp3","locations":{"fragments":["t=1"],"totalProgression":0.2}}`, clockStart-1)
	env.positions.race = func() {
		env.positions.seed(`{"href":"Fixture Odyssey (3).MP3","locations":{"fragments":["t=9"],"totalProgression":0.4}}`, clockStart+100_000)
	}
	response := env.epubWrite(readerPlace, `,"timestamp":`+fmt.Sprint(clockStart+500_000))
	got := decodeAudioError(t, response)
	if response.Code != http.StatusConflict || got.Code != "reading_position_conflict" || got.Service != "storyteller" || got.Message != "a newer reading position already exists" {
		t.Fatalf("a race = %d %+v", response.Code, got)
	}
	if env.positions.refused != 1 {
		t.Fatalf("Storyteller refused %d writes", env.positions.refused)
	}
}

// The field is accepted for the apps that send it, and nothing else is.
func TestEpubPositionStillTakesTheTimestampFieldAndNothingUnknown(t *testing.T) {
	env := newPositionEnv(t)
	if response := env.epubWrite(readerPlace, `,"timestamp":1700000001234`); response.Code != http.StatusOK {
		t.Fatalf("with the field = %d: %s", response.Code, response.Body.String())
	}
	if response := env.epubWrite(readerPlace, ``); response.Code != http.StatusOK {
		t.Fatalf("without it = %d: %s", response.Code, response.Body.String())
	}
	if response := env.epubWrite(readerPlace, `,"timestamp":1,"clock":"phone"`); response.Code != http.StatusBadRequest {
		t.Fatalf("an unknown field = %d", response.Code)
	}
}

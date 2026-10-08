package webvtt

import (
	"errors"
	"strings"
	"testing"
)

// The rules are in issue #45. Each test names the one it holds to.

func convert(t *testing.T, input string) Result {
	t.Helper()
	result, err := Convert([]byte(input))
	if err != nil {
		t.Fatalf("Convert(%q) = %v", input, err)
	}
	return result
}

func TestAnSRTBecomesWebVTTWithDotsAndTheSameWords(t *testing.T) {
	got := convert(t, "1\r\n00:00:01,000 --> 00:00:02,500\r\nHello there\r\n\r\n2\r\n01:02:03,004 --> 01:02:04,000\r\nTwo\r\nlines\r\n")
	want := "WEBVTT\n\n00:00:01.000 --> 00:00:02.500\nHello there\n\n01:02:03.004 --> 01:02:04.000\nTwo\nlines\n\n"
	if got.Text != want || got.Cues != 2 || got.Format != SRT {
		t.Fatalf("got %+v\nwant %q", got, want)
	}
}

func TestHebrewStaysInLogicalOrderWithItsMarksAndIsNotTouched(t *testing.T) {
	// A right-to-left mark at the start of a line, a Hebrew line and a Latin word
	// inside it: the converter adds, removes and reverses nothing.
	line := "\xE2\x80\x8Fשלום עולם, זה Daniel!"
	got := convert(t, "\xEF\xBB\xBF1\n00:00:00,500 --> 00:00:02,000\n"+line+"\n")
	if !strings.Contains(got.Text, "\n"+line+"\n") {
		t.Fatalf("the Hebrew line changed:\n%q", got.Text)
	}
	if strings.Contains(got.Text, "\xEF\xBB\xBF") {
		t.Errorf("the byte-order mark was kept")
	}
	if strings.Contains(got.Text, "align:") || strings.Contains(got.Text, "direction") {
		t.Errorf("a cue setting was written: %q", got.Text)
	}
}

func TestOnlyItalicBoldAndUnderlineStayAndEntitiesAreEscapedForWebVTT(t *testing.T) {
	got := convert(t, "1\n00:00:01,000 --> 00:00:02,000\n<i>Italic</i> <b>bold</b> <U>under</U> <font color=\"#ff0000\">red</font>{\\an8}\nTom &amp; Jerry &lt;3 a < b <3 &copy; AT&T<br/>next\n")
	body := strings.Split(got.Text, "\n")[3:]
	want := []string{
		"<i>Italic</i> <b>bold</b> <u>under</u> red",
		"Tom &amp; Jerry &lt;3 a &lt; b &lt;3 © AT&amp;T",
		"next",
	}
	for index, line := range want {
		if body[index] != line {
			t.Errorf("line %d = %q, want %q", index, body[index], line)
		}
	}
}

func TestTextThatLooksLikeATimingCannotEndACue(t *testing.T) {
	got := convert(t, "1\n00:00:01,000 --> 00:00:02,000\nit went 00:00:01,000 --> 00:00:02,000 and on\n\n\n\n\nstill this cue? no\n")
	if strings.Count(got.Text, "-->") != 1 || !strings.Contains(got.Text, "it went 00:00:01,000 --&gt; 00:00:02,000 and on") {
		t.Fatalf("got %q", got.Text)
	}
	if strings.Contains(got.Text, "still this cue") {
		t.Errorf("text after a blank line is another block, not this cue's: %q", got.Text)
	}
}

func TestCuesAreSortedAndOnesWithoutTextOrALengthAreDropped(t *testing.T) {
	got := convert(t, "2\n00:00:05,000 --> 00:00:06,000\nlate\n\n1\n00:00:01,000 --> 00:00:02,000\nearly\n\n3\n00:00:07,000 --> 00:00:07,000\nno length\n\n4\n00:00:08,000 --> 00:00:09,000\n\n\n5\n00:00:10,000 --> 00:00:09,000\nbackwards\n")
	if got.Cues != 2 || strings.Index(got.Text, "early") > strings.Index(got.Text, "late") ||
		strings.Contains(got.Text, "no length") || strings.Contains(got.Text, "backwards") {
		t.Fatalf("got %+v", got)
	}
}

func TestAnSRTWithNoBlankLinesBetweenItsCuesStillSplits(t *testing.T) {
	got := convert(t, "1\n00:00:01,000 --> 00:00:02,000\none\n2\n00:00:03,000 --> 00:00:04,000\ntwo\n00:00:05,000 --> 00:00:06,000\nthree\n")
	if got.Cues != 3 || !strings.Contains(got.Text, "\none\n\n") || !strings.Contains(got.Text, "\ntwo\n\n") || !strings.Contains(got.Text, "\nthree\n\n") {
		t.Fatalf("got %q", got.Text)
	}
}

func TestAShortFractionIsAFractionAndHoursMayBeLeftOut(t *testing.T) {
	got := convert(t, "1\n00:01,5 --> 00:03,25\nsecond-only clock\n")
	if !strings.Contains(got.Text, "00:00:01.500 --> 00:00:03.250\n") {
		t.Fatalf("got %q", got.Text)
	}
}

const sampleASS = `[Script Info]
Title: Test
ScriptType: v4.00+

[V4+ Styles]
Format: Name, Fontname, Fontsize
Style: Default,Arial,20

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:03.00,0:00:04.50,Default,,0,0,0,,{\an8\i1}Later{\i0}, with a comma\NSecond line
Comment: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,not a subtitle
Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,שלום\hעולם\nשוב
Dialogue: 1,0:00:05.00,0:00:06.00,Default,,0,0,0,,{\p1}m 0 0 l 100 0 100 100{\p0}Sign
Dialogue: 0,0:00:07.00,0:00:08.00,Default,,0,0,0,,{\p1}m 0 0 l 1 1
Dialogue: 0,0:00:09.00,0:00:09.00,Default,,0,0,0,,zero length
`

func TestAnASSEventsBecomePlainTextCuesInTimeOrder(t *testing.T) {
	got := convert(t, sampleASS)
	want := "WEBVTT\n\n" +
		"00:00:01.000 --> 00:00:02.000\nשלום עולם שוב\n\n" +
		"00:00:03.000 --> 00:00:04.500\nLater, with a comma\nSecond line\n\n" +
		"00:00:05.000 --> 00:00:06.000\nSign\n\n"
	if got.Text != want || got.Cues != 3 || got.Format != ASS {
		t.Fatalf("got %q\nwant %q", got.Text, want)
	}
}

func TestAnSSAFileUsesItsOwnColumnsAndAnEventsHeaderInAnyCase(t *testing.T) {
	got := convert(t, "[Script Info]\nScriptType: v4.00\n\n[eVeNtS]\nFormat: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: Marked=0,0:00:01.50,0:00:02.00,Default,,0,0,0,,An <angle> & ampersand\n")
	if !strings.Contains(got.Text, "00:00:01.500 --> 00:00:02.000\nAn &lt;angle&gt; &amp; ampersand\n") || got.Format != ASS {
		t.Fatalf("got %q", got.Text)
	}
}

func TestAnExistingWebVTTIsPassedThrough(t *testing.T) {
	input := "\xEF\xBB\xBFWEBVTT - a title\r\n\r\nSTYLE\r\n::cue { color: lime }\r\n\r\nNOTE a note\r\n\r\nintro\r\n00:00:01.000 --> 00:00:02.000 line:90% align:center\r\n<v Bob>Hello</v>\r\n\r\n\r\n"
	got := convert(t, input)
	want := "WEBVTT - a title\n\nSTYLE\n::cue { color: lime }\n\nNOTE a note\n\nintro\n00:00:01.000 --> 00:00:02.000 line:90% align:center\n<v Bob>Hello</v>\n"
	if got.Text != want || got.Cues != 1 || got.Format != VTT {
		t.Fatalf("got %q\nwant %q", got.Text, want)
	}
}

func TestAWebVTTWithOnlyAHeaderIsATrackWithNoCues(t *testing.T) {
	if got := convert(t, "WEBVTT\n\n"); got.Text != "WEBVTT\n" || got.Cues != 0 || got.Format != VTT {
		t.Fatalf("got %+v", got)
	}
}

func TestABlankFileIsATrackWithNoCuesAndNotAnError(t *testing.T) {
	for _, blank := range []string{"", " \r\n\t\n", "\xEF\xBB\xBF\n"} {
		if got := convert(t, blank); got.Text != "WEBVTT\n" || got.Cues != 0 || got.Format != Blank {
			t.Errorf("Convert(%q) = %+v", blank, got)
		}
	}
}

func TestAFileThatIsNotSubtitlesIsRefused(t *testing.T) {
	for _, input := range []string{
		"This is a letter, not a subtitle file.\nIt has no times.\n",
		"[Script Info]\n\n[Events]\nFormat: Start, End, Text\nnothing\n",
		"WEBVTTX\nnot a header\n",
		"1\n00:00:01,000 --> 00:00:02,000\n",
	} {
		if _, err := Convert([]byte(input)); !errors.Is(err, ErrNoCues) {
			t.Errorf("Convert(%q) = %v, want ErrNoCues", input, err)
		}
	}
}

func TestBytesThatAreNotUTF8BecomeTheReplacementCharacterAndControlsAreDropped(t *testing.T) {
	got := convert(t, "1\n00:00:01,000 --> 00:00:02,000\nbad \xE0\xFA byte\x00 and\x07 bell\n")
	if !strings.Contains(got.Text, "bad \xEF\xBF\xBD byte and bell\n") {
		t.Fatalf("got %q", got.Text)
	}
}

func TestClocksPastNinetyNineHoursAreWrittenWhole(t *testing.T) {
	if got := stamp(100*3_600_000 + 59*60_000 + 59_000 + 999); got != "100:59:59.999" {
		t.Errorf("stamp = %q", got)
	}
	if got := stamp(-5); got != "00:00:00.000" {
		t.Errorf("stamp(-5) = %q", got)
	}
}

func TestWhichLanguagesAreWrittenRightToLeft(t *testing.T) {
	for language, want := range map[string]bool{"heb": true, "ara": true, "fas": true, "per": true, "urd": true, "yid": true, "HEB": true,
		"eng": false, "fra": false, "und": false, "": false, "jpn": false} {
		if got := IsRTL(language); got != want {
			t.Errorf("IsRTL(%q) = %t", language, got)
		}
	}
}

// The same input must always give the same bytes: a signature is a hash of them.
func TestConversionIsDeterministic(t *testing.T) {
	first, second := convert(t, sampleASS), convert(t, sampleASS)
	if first.Text != second.Text {
		t.Fatal("two conversions differ")
	}
}

package arr

import (
	"encoding/json"
	"testing"
)

func TestIndexerFlagsRadarrArrayForm(t *testing.T) {
	var flags IndexerFlags
	if err := json.Unmarshal([]byte(`["G_Freeleech"]`), &flags); err != nil {
		t.Fatal(err)
	}
	if got := flags.Names(); len(got) != 1 || got[0] != "G_Freeleech" {
		t.Fatalf("got %v", got)
	}
}

func TestIndexerFlagsSonarrBitfieldForm(t *testing.T) {
	// Sonarr sends 1 where Radarr sends ["G_Freeleech"]. Same API version, same
	// field name; decoding the number into a []string failed and the hub
	// reported it as "sonarr is not responding".
	var flags IndexerFlags
	if err := json.Unmarshal([]byte(`1`), &flags); err != nil {
		t.Fatal(err)
	}
	if got := flags.Names(); len(got) != 1 || got[0] != "G_Freeleech" {
		t.Fatalf("got %v", got)
	}
}

func TestIndexerFlagsBitfieldCombines(t *testing.T) {
	var flags IndexerFlags
	if err := json.Unmarshal([]byte(`33`), &flags); err != nil { // 1 | 32
		t.Fatal(err)
	}
	got := flags.Names()
	if len(got) != 2 || got[0] != "G_Freeleech" || got[1] != "G_Internal" {
		t.Fatalf("got %v", got)
	}
}

func TestIndexerFlagsZeroAndNull(t *testing.T) {
	for _, raw := range []string{`0`, `null`, `[]`} {
		var flags IndexerFlags
		if err := json.Unmarshal([]byte(raw), &flags); err != nil {
			t.Fatalf("%s: %v", raw, err)
		}
		if len(flags.Names()) != 0 {
			t.Fatalf("%s gave %v", raw, flags.Names())
		}
	}
}

func TestIndexerFlagsUnknownShapeIsTolerated(t *testing.T) {
	// One odd field must not cost the caller the other forty releases in the
	// response.
	var flags IndexerFlags
	if err := json.Unmarshal([]byte(`{"weird":true}`), &flags); err != nil {
		t.Fatalf("should tolerate, got %v", err)
	}
	if len(flags.Names()) != 0 {
		t.Fatalf("got %v", flags.Names())
	}
}

func TestFreeleechReadsEitherForm(t *testing.T) {
	var radarr, sonarr Release
	if err := json.Unmarshal([]byte(`{"indexerFlags":["G_Freeleech"]}`), &radarr); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal([]byte(`{"indexerFlags":2}`), &sonarr); err != nil {
		t.Fatal(err)
	}
	if !radarr.Freeleech() {
		t.Error("radarr array form should read as freeleech")
	}
	if !sonarr.Freeleech() {
		t.Error("sonarr halfleech bit should read as freeleech")
	}
}

func TestReleaseDecodesASonarrSeasonPack(t *testing.T) {
	// Shape taken verbatim from this stack's Sonarr, trimmed.
	raw := `{"seriesId":2,"seasonNumber":1,"fullSeason":true,"indexerFlags":1,
	  "title":"Breaking Bad S01 2160p","size":57389893032,"seeders":113,
	  "leechers":28,"protocol":"torrent","age":7,"rejected":true,
	  "rejections":["Bluray-2160p is not wanted in profile"],
	  "languages":[{"id":1,"name":"English"}],
	  "quality":{"quality":{"id":19,"name":"Bluray-2160p","resolution":2160}}}`
	var release Release
	if err := json.Unmarshal([]byte(raw), &release); err != nil {
		t.Fatal(err)
	}
	if release.QualityName() != "Bluray-2160p" {
		t.Errorf("quality %q", release.QualityName())
	}
	if !release.FullSeason || release.SeasonNum != 1 {
		t.Errorf("season pack fields: full=%v season=%d", release.FullSeason, release.SeasonNum)
	}
	if !release.Freeleech() {
		t.Error("bit 1 should read as freeleech")
	}
	if len(release.Rejections) != 1 {
		t.Errorf("rejections %v", release.Rejections)
	}
}

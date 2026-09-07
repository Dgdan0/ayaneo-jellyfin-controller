package api

import "testing"

func TestParseActivityID(t *testing.T) {
	torrent, err := parseActivityID("qbit:ABC123DEF4567890ABCD")
	if err != nil {
		t.Fatal(err)
	}
	if !torrent.IsTorrent || torrent.Hash != "abc123def4567890abcd" {
		t.Fatalf("got %+v -- the hash should be lower-cased", torrent)
	}

	queue, err := parseActivityID("sonarr:queue:42")
	if err != nil {
		t.Fatal(err)
	}
	if queue.IsTorrent || queue.Service != "sonarr" || queue.QueueID != 42 {
		t.Fatalf("got %+v", queue)
	}
}

func TestActivityIDsAreParsedStrictly(t *testing.T) {
	// These reach a delete endpoint, so they get the same suspicion as any other
	// network input even though the app only ever echoes back what we sent it.
	for _, bad := range []string{
		"", "qbit:", "qbit:short", "qbit:zzzznothexzzzznothex",
		"sonarr:queue:0", "sonarr:queue:-1", "sonarr:queue:abc",
		"lidarr:queue:1", "sonarr:notqueue:1", "sonarr:queue:1:extra",
		"qbit:../../etc/passwd",
	} {
		if _, err := parseActivityID(bad); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
}

func TestIsHex(t *testing.T) {
	if !isHex("0123456789abcdef") {
		t.Error("valid hex rejected")
	}
	// Upper case is rejected because the caller lower-cases first; anything
	// still upper here did not come through that path.
	for _, bad := range []string{"", "ABCDEF", "xyz", "12 34", "12-34"} {
		if isHex(bad) {
			t.Errorf("accepted %q", bad)
		}
	}
}

package jellyfin

import "testing"

func TestLastPlayedMillisReadsJellyfinsSevenDigitFraction(t *testing.T) {
	// Jellyfin 10.11 writes ticks-precision fractions, which RFC3339Nano accepts.
	item := Item{UserData: &UserData{LastPlayedDate: "2026-09-30T20:14:05.1234567Z"}}
	if got, want := item.LastPlayedMillis(), int64(1790799245123); got != want {
		t.Fatalf("LastPlayedMillis = %d, want %d", got, want)
	}
}

func TestLastPlayedMillisIsZeroWithoutADate(t *testing.T) {
	for _, item := range []Item{{}, {UserData: &UserData{}}, {UserData: &UserData{LastPlayedDate: "yesterday"}}} {
		if got := item.LastPlayedMillis(); got != 0 {
			t.Fatalf("LastPlayedMillis(%+v) = %d, want 0", item.UserData, got)
		}
	}
}

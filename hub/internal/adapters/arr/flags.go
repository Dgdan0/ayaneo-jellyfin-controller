package arr

import (
	"encoding/json"
	"fmt"
)

// IndexerFlags is the one field Radarr and Sonarr disagree about.
//
// **Radarr sends an array of strings** -- `["G_Freeleech"]`. **Sonarr sends an
// integer bitfield** -- `1`. Same API version, same field name, same concept.
//
// Found the hard way: decoding Sonarr's number into a []string failed, and the
// hub reported the decode error as "sonarr is not responding" -- so a perfectly
// healthy service looked down, on a screen whose whole job is telling you which
// service is misbehaving.
type IndexerFlags struct {
	names []string
}

// Sonarr's IndexerFlags enum, which is a [Flags] bitfield.
var sonarrIndexerFlags = []struct {
	Bit  int
	Name string
}{
	{1, "G_Freeleech"},
	{2, "G_Halfleech"},
	{4, "G_DoubleUpload"},
	{8, "PTP_Golden"},
	{16, "PTP_Approved"},
	{32, "G_Internal"},
	{64, "G_Scene"},
	{128, "G_Nuked"},
}

func (f *IndexerFlags) UnmarshalJSON(data []byte) error {
	if len(data) == 0 || string(data) == "null" {
		f.names = nil
		return nil
	}
	// The array form first, because that is what Radarr sends and Radarr is
	// where most searches happen.
	var asStrings []string
	if err := json.Unmarshal(data, &asStrings); err == nil {
		f.names = asStrings
		return nil
	}
	var asBits int
	if err := json.Unmarshal(data, &asBits); err == nil {
		for _, flag := range sonarrIndexerFlags {
			if asBits&flag.Bit != 0 {
				f.names = append(f.names, flag.Name)
			}
		}
		return nil
	}
	// Anything else is tolerated rather than fatal: an unknown shape here must
	// not cost the caller the other forty releases in the response.
	f.names = nil
	return nil
}

func (f IndexerFlags) Names() []string { return f.names }

func (f IndexerFlags) String() string { return fmt.Sprint(f.names) }

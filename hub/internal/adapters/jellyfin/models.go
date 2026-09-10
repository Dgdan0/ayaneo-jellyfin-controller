package jellyfin

import "strconv"

// Jellyfin's wire format, trimmed to what this project uses.
//
// Field names are PascalCase because Jellyfin's are, and renaming them in the
// struct tags would make every future comparison against a captured response
// harder than it needs to be.

// TicksPerSecond is Jellyfin's time unit: 100-nanosecond intervals.
//
// Every duration and position on this API is in these, and forgetting to divide
// yields numbers around ten million that look like bytes.
const TicksPerSecond = 10_000_000

// ProviderIds is the map that makes the whole cross-service join possible.
//
// Measured on this install: 178 of 179 movies and 70 of 71 series carry a Tmdb
// id, and 67 of 71 series carry a Tvdb id. The single item with none is a
// mis-scanned file named after a release group.
type ProviderIds struct {
	Tmdb string `json:"Tmdb"`
	Tvdb string `json:"Tvdb"`
	Imdb string `json:"Imdb"`
}

type UserData struct {
	PlaybackPositionTicks int64   `json:"PlaybackPositionTicks"`
	PlayedPercentage      float64 `json:"PlayedPercentage"`
	PlayCount             int     `json:"PlayCount"`
	Played                bool    `json:"Played"`
	IsFavorite            bool    `json:"IsFavorite"`
	UnplayedItemCount     int     `json:"UnplayedItemCount"`
	LastPlayedDate        string  `json:"LastPlayedDate"`
}

// Item is a movie, series, season, episode or library view.
type Item struct {
	ID   string `json:"Id"`
	Name string `json:"Name"`
	Type string `json:"Type"`

	ProductionYear  int           `json:"ProductionYear"`
	RunTimeTicks    int64         `json:"RunTimeTicks"`
	Overview        string        `json:"Overview"`
	OriginalTitle   string        `json:"OriginalTitle"`
	ProviderIds     *ProviderIds  `json:"ProviderIds"`
	CommunityRating float64       `json:"CommunityRating"`
	CriticRating    float64       `json:"CriticRating"`
	OfficialRating  string        `json:"OfficialRating"`
	PremiereDate    string        `json:"PremiereDate"`
	Genres          []string      `json:"Genres"`
	Studios         []Studio      `json:"Studios"`
	People          []Person      `json:"People"`
	MediaSources    []MediaSource `json:"MediaSources"`

	// Present on an episode. Jellyfin calls the season number
	// ParentIndexNumber, which is not a name anyone guesses.
	SeriesName        string `json:"SeriesName"`
	SeriesID          string `json:"SeriesId"`
	SeasonID          string `json:"SeasonId"`
	ParentID          string `json:"ParentId"`
	ParentIndexNumber int    `json:"ParentIndexNumber"`
	IndexNumber       int    `json:"IndexNumber"`

	// Image tags. An episode's own Primary image is a screenshot from that
	// episode, which is often a wall or a dark corridor -- SeriesPrimaryImageTag
	// is the show's poster and is what a "continue watching" card wants.
	ImageTags             map[string]string                   `json:"ImageTags"`
	SeriesPrimaryImageTag string                              `json:"SeriesPrimaryImageTag"`
	BackdropImageTags     []string                            `json:"BackdropImageTags"`
	Trickplay             map[string]map[string]TrickplayInfo `json:"Trickplay"`

	UserData *UserData `json:"UserData"`

	// Only on a library view: "movies", "tvshows", "music"...
	CollectionType string `json:"CollectionType"`
}

type Studio struct {
	Name string `json:"Name"`
}

type Person struct {
	ID              string `json:"Id"`
	Name            string `json:"Name"`
	Role            string `json:"Role"`
	Type            string `json:"Type"`
	PrimaryImageTag string `json:"PrimaryImageTag"`
}

// TrickplayInfo describes one Jellyfin JPEG sprite-sheet resolution.
type TrickplayInfo struct {
	Width          int   `json:"Width"`
	Height         int   `json:"Height"`
	TileWidth      int   `json:"TileWidth"`
	TileHeight     int   `json:"TileHeight"`
	ThumbnailCount int   `json:"ThumbnailCount"`
	Interval       int64 `json:"Interval"`
}

// ItemsPage is the shape of a paged query. Note that /Items/Latest answers a
// bare array instead, which is a real inconsistency in this API.
type ItemsPage struct {
	Items            []Item `json:"Items"`
	TotalRecordCount int    `json:"TotalRecordCount"`
	StartIndex       int    `json:"StartIndex"`
}

// ImageInfo is Jellyfin's description of one item's stored image. Library
// folders use this to distinguish generated collage art under metadata/library
// from an explicit folder.jpg or folder.webp placed with the collection.
type ImageInfo struct {
	ImageType string `json:"ImageType"`
	Path      string `json:"Path"`
	Width     int    `json:"Width"`
	Height    int    `json:"Height"`
}

type SystemInfo struct {
	Version    string `json:"Version"`
	ServerName string `json:"ServerName"`
	ID         string `json:"Id"`
}

type User struct {
	ID     string `json:"Id"`
	Name   string `json:"Name"`
	Policy struct {
		IsDisabled bool `json:"IsDisabled"`
	} `json:"Policy"`
}

// PlaybackInfo is Jellyfin's negotiated answer for one device profile.
// Paths in this model never leave the hub; the API layer replaces them with
// session-bound proxy URLs.
type PlaybackInfo struct {
	MediaSources  []MediaSource `json:"MediaSources"`
	PlaySessionID string        `json:"PlaySessionId"`
	ErrorCode     string        `json:"ErrorCode"`
}

type MediaSource struct {
	ID                         string        `json:"Id"`
	Name                       string        `json:"Name"`
	Path                       string        `json:"Path"`
	Container                  string        `json:"Container"`
	Size                       int64         `json:"Size"`
	Bitrate                    int           `json:"Bitrate"`
	RunTimeTicks               int64         `json:"RunTimeTicks"`
	SupportsDirectPlay         bool          `json:"SupportsDirectPlay"`
	SupportsDirectStream       bool          `json:"SupportsDirectStream"`
	SupportsTranscoding        bool          `json:"SupportsTranscoding"`
	DirectStreamURL            string        `json:"DirectStreamUrl"`
	TranscodingURL             string        `json:"TranscodingUrl"`
	TranscodingContainer       string        `json:"TranscodingContainer"`
	TranscodingSubProtocol     string        `json:"TranscodingSubProtocol"`
	TranscodeReasons           []string      `json:"TranscodeReasons"`
	DefaultAudioStreamIndex    *int          `json:"DefaultAudioStreamIndex"`
	DefaultSubtitleStreamIndex *int          `json:"DefaultSubtitleStreamIndex"`
	MediaStreams               []MediaStream `json:"MediaStreams"`
}

type MediaStream struct {
	Index                  int     `json:"Index"`
	Type                   string  `json:"Type"`
	Codec                  string  `json:"Codec"`
	Profile                string  `json:"Profile"`
	Language               string  `json:"Language"`
	Title                  string  `json:"Title"`
	DisplayTitle           string  `json:"DisplayTitle"`
	ChannelLayout          string  `json:"ChannelLayout"`
	Channels               int     `json:"Channels"`
	Bitrate                int     `json:"BitRate"`
	Width                  int     `json:"Width"`
	Height                 int     `json:"Height"`
	AverageFrameRate       float64 `json:"AverageFrameRate"`
	VideoRange             string  `json:"VideoRange"`
	VideoRangeType         string  `json:"VideoRangeType"`
	IsDefault              bool    `json:"IsDefault"`
	IsForced               bool    `json:"IsForced"`
	IsHearingImpaired      bool    `json:"IsHearingImpaired"`
	IsExternal             bool    `json:"IsExternal"`
	IsTextSubtitleStream   bool    `json:"IsTextSubtitleStream"`
	SupportsExternalStream bool    `json:"SupportsExternalStream"`
	DeliveryMethod         string  `json:"DeliveryMethod"`
	DeliveryURL            string  `json:"DeliveryUrl"`
}

type PlaybackInfoRequest struct {
	UserID               string        `json:"UserId"`
	StartTimeTicks       int64         `json:"StartTimeTicks,omitempty"`
	AudioStreamIndex     *int          `json:"AudioStreamIndex,omitempty"`
	SubtitleStreamIndex  *int          `json:"SubtitleStreamIndex,omitempty"`
	MediaSourceID        string        `json:"MediaSourceId,omitempty"`
	MaxStreamingBitrate  *int          `json:"MaxStreamingBitrate,omitempty"`
	DeviceProfile        DeviceProfile `json:"DeviceProfile"`
	EnableDirectPlay     bool          `json:"EnableDirectPlay"`
	EnableDirectStream   bool          `json:"EnableDirectStream"`
	EnableTranscoding    bool          `json:"EnableTranscoding"`
	AllowVideoStreamCopy bool          `json:"AllowVideoStreamCopy"`
	AllowAudioStreamCopy bool          `json:"AllowAudioStreamCopy"`
}

type DeviceProfile struct {
	Name                string               `json:"Name"`
	MaxStreamingBitrate int                  `json:"MaxStreamingBitrate"`
	MaxStaticBitrate    int                  `json:"MaxStaticBitrate"`
	DirectPlayProfiles  []DirectPlayProfile  `json:"DirectPlayProfiles"`
	TranscodingProfiles []TranscodingProfile `json:"TranscodingProfiles"`
	CodecProfiles       []CodecProfile       `json:"CodecProfiles"`
	SubtitleProfiles    []SubtitleProfile    `json:"SubtitleProfiles"`
}

type DirectPlayProfile struct {
	Container  string `json:"Container"`
	AudioCodec string `json:"AudioCodec,omitempty"`
	VideoCodec string `json:"VideoCodec,omitempty"`
	Type       string `json:"Type"`
}

type TranscodingProfile struct {
	Container                 string `json:"Container"`
	Type                      string `json:"Type"`
	VideoCodec                string `json:"VideoCodec"`
	AudioCodec                string `json:"AudioCodec"`
	Protocol                  string `json:"Protocol"`
	Context                   string `json:"Context"`
	MaxAudioChannels          string `json:"MaxAudioChannels,omitempty"`
	MinSegments               int    `json:"MinSegments"`
	SegmentLength             int    `json:"SegmentLength"`
	BreakOnNonKeyFrames       bool   `json:"BreakOnNonKeyFrames"`
	EnableSubtitlesInManifest bool   `json:"EnableSubtitlesInManifest"`
}

// CodecProfile is kept intentionally small for the first player release. The
// runtime decoder list narrows direct-play codec names; unsupported profile or
// level combinations still fall through to Jellyfin's transcode plan.
type CodecProfile struct {
	Type       string `json:"Type"`
	Codec      string `json:"Codec,omitempty"`
	Conditions []any  `json:"Conditions"`
}

type SubtitleProfile struct {
	Format string `json:"Format"`
	Method string `json:"Method"`
}

type PlaybackEvent struct {
	ItemID              string `json:"ItemId"`
	MediaSourceID       string `json:"MediaSourceId,omitempty"`
	PlaySessionID       string `json:"PlaySessionId,omitempty"`
	PositionTicks       int64  `json:"PositionTicks"`
	IsPaused            bool   `json:"IsPaused"`
	IsMuted             bool   `json:"IsMuted"`
	VolumeLevel         int    `json:"VolumeLevel"`
	AudioStreamIndex    *int   `json:"AudioStreamIndex,omitempty"`
	SubtitleStreamIndex *int   `json:"SubtitleStreamIndex,omitempty"`
	PlayMethod          string `json:"PlayMethod"`
	CanSeek             bool   `json:"CanSeek"`
	EventName           string `json:"EventName,omitempty"`
}

// RuntimeSeconds converts out of ticks.
func (i Item) RuntimeSeconds() int {
	if i.RunTimeTicks <= 0 {
		return 0
	}
	return int(i.RunTimeTicks / TicksPerSecond)
}

// PositionSeconds is how far in the user got.
func (i Item) PositionSeconds() int {
	if i.UserData == nil || i.UserData.PlaybackPositionTicks <= 0 {
		return 0
	}
	return int(i.UserData.PlaybackPositionTicks / TicksPerSecond)
}

// Progress is 0..1, or 0 when there is nothing to report.
//
// Taken from PlayedPercentage when Jellyfin provides it, because it accounts
// for things the raw position does not -- a fully played item reports 100 even
// when its position has been reset.
func (i Item) Progress() float64 {
	if i.UserData == nil {
		return 0
	}
	if i.UserData.PlayedPercentage > 0 {
		return i.UserData.PlayedPercentage / 100
	}
	if i.RunTimeTicks > 0 && i.UserData.PlaybackPositionTicks > 0 {
		return float64(i.UserData.PlaybackPositionTicks) / float64(i.RunTimeTicks)
	}
	return 0
}

// DisplayTitle is what goes on a card.
//
// For an episode that is the *show's* name, because a row of episode titles
// tells you nothing -- "The Stake Out" is meaningless without "Seinfeld".
func (i Item) DisplayTitle() string {
	if i.Type == "Episode" && i.SeriesName != "" {
		return i.SeriesName
	}
	return i.Name
}

// Subtitle is the second line: "S1E2 · The Stake Out" for an episode, the year
// for anything else.
func (i Item) Subtitle() string {
	if i.Type == "Episode" {
		out := ""
		if i.ParentIndexNumber > 0 || i.IndexNumber > 0 {
			out = "S" + strconv.Itoa(i.ParentIndexNumber) + "E" + strconv.Itoa(i.IndexNumber)
		}
		if i.Name != "" {
			if out != "" {
				out += " · "
			}
			out += i.Name
		}
		return out
	}
	if i.ProductionYear > 0 {
		return strconv.Itoa(i.ProductionYear)
	}
	return ""
}

// PosterItemID and PosterTag say which image to ask for.
//
// An episode borrows its series' poster: its own Primary image is a still from
// that episode, which in a poster-shaped card is usually an unrecognisable
// dark frame.
func (i Item) PosterItemID() string {
	if i.Type == "Episode" && i.SeriesID != "" && i.SeriesPrimaryImageTag != "" {
		return i.SeriesID
	}
	return i.ID
}

func (i Item) PosterTag() string {
	if i.Type == "Episode" && i.SeriesPrimaryImageTag != "" {
		return i.SeriesPrimaryImageTag
	}
	if i.ImageTags != nil {
		return i.ImageTags["Primary"]
	}
	return ""
}

func (i Item) Tmdb() string {
	if i.ProviderIds == nil {
		return ""
	}
	return i.ProviderIds.Tmdb
}

func (i Item) Tvdb() string {
	if i.ProviderIds == nil {
		return ""
	}
	return i.ProviderIds.Tvdb
}

func (i Item) Imdb() string {
	if i.ProviderIds == nil {
		return ""
	}
	return i.ProviderIds.Imdb
}

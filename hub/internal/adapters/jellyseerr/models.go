package jellyseerr

// MediaStatus values, verified against the running instance rather than a blog
// post: several write-ups still list only five, and 6 and 7 are newer.
const (
	StatusUnknown            = 1
	StatusPending            = 2
	StatusProcessing         = 3
	StatusPartiallyAvailable = 4
	StatusAvailable          = 5
	StatusBlocklisted        = 6
	StatusDeleted            = 7
)

// MediaRequestStatus values.
const (
	RequestPending   = 1
	RequestApproved  = 2
	RequestDeclined  = 3
	RequestFailed    = 4
	RequestCompleted = 5
)

// SearchResponse is what /api/v1/search and the discover endpoints return.
type SearchResponse struct {
	Page         int      `json:"page"`
	TotalPages   int      `json:"totalPages"`
	TotalResults int      `json:"totalResults"`
	Results      []Result `json:"results"`
}

// Result is one search hit.
//
// Movies carry title/releaseDate, TV carries name/firstAirDate, and both may
// appear in one response -- so both spellings are decoded and the caller picks.
type Result struct {
	ID           int        `json:"id"` // the TMDB id, not Jellyseerr's own
	MediaType    string     `json:"mediaType"`
	Title        string     `json:"title"`
	Name         string     `json:"name"`
	OriginalName string     `json:"originalName"`
	Overview     string     `json:"overview"`
	ReleaseDate  string     `json:"releaseDate"`
	FirstAirDate string     `json:"firstAirDate"`
	PosterPath   string     `json:"posterPath"`
	BackdropPath string     `json:"backdropPath"`
	VoteAverage  float64    `json:"voteAverage"`
	Popularity   float64    `json:"popularity"`
	MediaInfo    *MediaInfo `json:"mediaInfo"`
}

// DisplayTitle picks whichever of the two title fields this type uses.
func (r Result) DisplayTitle() string {
	if r.Title != "" {
		return r.Title
	}
	return r.Name
}

// Year is the first four characters of whichever date field applies. Returns 0
// when unknown, which is common for unreleased titles.
func (r Result) Year() int {
	date := r.ReleaseDate
	if date == "" {
		date = r.FirstAirDate
	}
	if len(date) < 4 {
		return 0
	}
	year := 0
	for _, c := range date[:4] {
		if c < '0' || c > '9' {
			return 0
		}
		year = year*10 + int(c-'0')
	}
	return year
}

// MediaInfo is Jellyseerr's own record of a title, and it is the reason phase H1
// is useful on its own.
//
// It already carries the cross-service identifiers the join needs --
// externalServiceId is the Radarr/Sonarr internal id, jellyfinMediaId is the
// library item -- and downloadStatus is populated by Jellyseerr's own tracker
// polling the *arr queues. So availability and live progress are both available
// from this one adapter, before any of the others exist.
type MediaInfo struct {
	ID                  int               `json:"id"` // Jellyseerr's internal media id
	MediaType           string            `json:"mediaType"`
	TmdbID              int               `json:"tmdbId"`
	TvdbID              *int              `json:"tvdbId"`
	ImdbID              string            `json:"imdbId"`
	Status              int               `json:"status"`
	Status4k            int               `json:"status4k"`
	ServiceID           *int              `json:"serviceId"`
	ServiceID4k         *int              `json:"serviceId4k"`
	ExternalServiceID   *int              `json:"externalServiceId"`
	ExternalServiceID4k *int              `json:"externalServiceId4k"`
	JellyfinMediaID     string            `json:"jellyfinMediaId"`
	MediaAddedAt        string            `json:"mediaAddedAt"`
	DownloadStatus      []DownloadingItem `json:"downloadStatus"`
	DownloadStatus4k    []DownloadingItem `json:"downloadStatus4k"`
}

// DownloadingItem is a transfer in progress, as Jellyseerr sees it.
//
// SizeLeft and Size are bytes; TimeLeft is a *arr-formatted duration string
// rather than a number, so it is passed through as text rather than guessed at.
type DownloadingItem struct {
	ExternalID              int    `json:"externalId"`
	Size                    int64  `json:"size"`
	SizeLeft                int64  `json:"sizeLeft"`
	Status                  string `json:"status"`
	TimeLeft                string `json:"timeLeft"`
	EstimatedCompletionTime string `json:"estimatedCompletionTime"`
	Title                   string `json:"title"`
	DownloadID              string `json:"downloadId"`
}

// Progress is 0..1, or -1 when the size is unknown.
func (d DownloadingItem) Progress() float64 {
	if d.Size <= 0 {
		return -1
	}
	done := d.Size - d.SizeLeft
	if done < 0 {
		return 0
	}
	return float64(done) / float64(d.Size)
}

// Status is the Jellyseerr instance itself.
type Status struct {
	Version         string `json:"version"`
	CommitTag       string `json:"commitTag"`
	UpdateAvailable bool   `json:"updateAvailable"`
}

// RequestBody is the payload for POST /api/v1/request.
//
// MediaID is the **TMDB id**, not Jellyseerr's internal media id. Getting that
// wrong is the classic mistake with this endpoint and produces a confusing 500
// rather than a validation error.
type RequestBody struct {
	MediaType  string `json:"mediaType"`
	MediaID    int    `json:"mediaId"`
	TvdbID     *int   `json:"tvdbId,omitempty"`
	Seasons    any    `json:"seasons,omitempty"` // []int, or the string "all"
	Is4k       bool   `json:"is4k,omitempty"`
	ServerID   *int   `json:"serverId,omitempty"`
	ProfileID  *int   `json:"profileId,omitempty"`
	RootFolder string `json:"rootFolder,omitempty"`
	UserID     *int   `json:"userId,omitempty"`
}

// MediaRequest is a request as Jellyseerr stores it.
type MediaRequest struct {
	ID          int        `json:"id"`
	Status      int        `json:"status"`
	Media       *MediaInfo `json:"media"`
	Is4k        bool       `json:"is4k"`
	CreatedAt   string     `json:"createdAt"`
	UpdatedAt   string     `json:"updatedAt"`
	RequestedBy *User      `json:"requestedBy"`
	Seasons     []Season   `json:"seasons"`
}

type Season struct {
	SeasonNumber int `json:"seasonNumber"`
	Status       int `json:"status"`
}

type User struct {
	ID          int    `json:"id"`
	DisplayName string `json:"displayName"`
	Email       string `json:"email"`
}

type RequestPage struct {
	PageInfo struct {
		Pages    int `json:"pages"`
		PageSize int `json:"pageSize"`
		Results  int `json:"results"`
		Page     int `json:"page"`
	} `json:"pageInfo"`
	Results []MediaRequest `json:"results"`
}

// RequestCounts backs the badge on the Discover tab.
type RequestCounts struct {
	Pending    int `json:"pending"`
	Approved   int `json:"approved"`
	Processing int `json:"processing"`
	Available  int `json:"available"`
	Total      int `json:"total"`
	Declined   int `json:"declined"`
	Failed     int `json:"failed"`
}

// Credits is the cast and crew on a title.
type Credits struct {
	Cast []CastCredit `json:"cast"`
}

type CastCredit struct {
	ID          int    `json:"id"`
	Name        string `json:"name"`
	Character   string `json:"character"`
	ProfilePath string `json:"profilePath"`
	Order       int    `json:"order"`
}

// Person is a performer.
type Person struct {
	ID                 int    `json:"id"`
	Name               string `json:"name"`
	Biography          string `json:"biography"`
	ProfilePath        string `json:"profilePath"`
	KnownForDepartment string `json:"knownForDepartment"`
	Birthday           string `json:"birthday"`
}

// CombinedCredits is everything a person has appeared in, across film and TV.
//
// Note there is no mediaInfo here, unlike a search result -- so the hub cannot
// tell whether any of these are in the library without looking each one up.
type CombinedCredits struct {
	ID   int            `json:"id"`
	Cast []PersonCredit `json:"cast"`
}

type PersonCredit struct {
	ID           int     `json:"id"`
	MediaType    string  `json:"mediaType"`
	Title        string  `json:"title"`
	Name         string  `json:"name"`
	Character    string  `json:"character"`
	Overview     string  `json:"overview"`
	ReleaseDate  string  `json:"releaseDate"`
	FirstAirDate string  `json:"firstAirDate"`
	PosterPath   string  `json:"posterPath"`
	BackdropPath string  `json:"backdropPath"`
	VoteAverage  float64 `json:"voteAverage"`
	Popularity   float64 `json:"popularity"`
}

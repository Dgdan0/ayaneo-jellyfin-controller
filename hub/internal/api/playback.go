package api

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"ayaneohub/internal/adapters/jellyfin"
)

const (
	playbackBodyLimit  = 64 << 10
	maxPlaybackBitrate = 160_000_000
	maxSubtitleBytes   = 8 << 20
	maxSubtitleOffset  = 10 * 60 * 1000
)

type SeriesPlayTargetResponse struct {
	SeriesID string      `json:"seriesId"`
	Kind     string      `json:"kind"` // resume | next | start
	Item     LibraryItem `json:"item"`
}

type PlaybackPrepareBody struct {
	StartMode           string               `json:"startMode"`
	PositionMillis      int64                `json:"positionMillis,omitempty"`
	MediaSourceID       string               `json:"mediaSourceId,omitempty"`
	AudioStreamIndex    *int                 `json:"audioStreamIndex,omitempty"`
	SubtitleStreamIndex *int                 `json:"subtitleStreamIndex,omitempty"`
	MaxBitrate          int                  `json:"maxBitrate,omitempty"`
	ForceTranscode      bool                 `json:"forceTranscode,omitempty"`
	Device              PlaybackDevice       `json:"device"`
	Capabilities        PlaybackCapabilities `json:"capabilities"`
}

type PlaybackDevice struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Version string `json:"version"`
}

type PlaybackCapabilities struct {
	Width            int      `json:"width"`
	Height           int      `json:"height"`
	MaxAudioChannels int      `json:"maxAudioChannels"`
	VideoCodecs      []string `json:"videoCodecs"`
	AudioCodecs      []string `json:"audioCodecs"`
	HDRTypes         []string `json:"hdrTypes"`
}

type PlaybackPrepareResponse struct {
	SessionID             string             `json:"sessionId"`
	Item                  PlaybackItem       `json:"item"`
	PositionMillis        int64              `json:"positionMillis"`
	DurationMillis        int64              `json:"durationMillis"`
	MediaURL              string             `json:"mediaUrl"`
	MIMEType              string             `json:"mimeType"`
	PlayMethod            string             `json:"playMethod"`
	TranscodeReason       string             `json:"transcodeReason,omitempty"`
	Bitrate               int                `json:"bitrate,omitempty"`
	Width                 int                `json:"width,omitempty"`
	Height                int                `json:"height,omitempty"`
	FrameRate             float64            `json:"frameRate,omitempty"`
	HDR                   string             `json:"hdr,omitempty"`
	VideoCodec            string             `json:"videoCodec,omitempty"`
	AudioCodec            string             `json:"audioCodec,omitempty"`
	Sources               []PlaybackSource   `json:"sources"`
	AudioTracks           []PlaybackTrack    `json:"audioTracks"`
	SubtitleTracks        []PlaybackTrack    `json:"subtitleTracks"`
	SelectedMediaSourceID string             `json:"selectedMediaSourceId"`
	SelectedAudioIndex    *int               `json:"selectedAudioIndex,omitempty"`
	SelectedSubtitleIndex *int               `json:"selectedSubtitleIndex,omitempty"`
	PreviousItem          *PlaybackItem      `json:"previousItem,omitempty"`
	NextItem              *PlaybackItem      `json:"nextItem,omitempty"`
	Trickplay             *PlaybackTrickplay `json:"trickplay,omitempty"`
	PreviewURL            string             `json:"previewUrl,omitempty"`
	Chapters              []PlaybackChapter  `json:"chapters"`
	Segments              []PlaybackSegment  `json:"segments"`
}

type PlaybackTrickplay struct {
	TileURL        string `json:"tileUrl"`
	Width          int    `json:"width"`
	Height         int    `json:"height"`
	TileWidth      int    `json:"tileWidth"`
	TileHeight     int    `json:"tileHeight"`
	ThumbnailCount int    `json:"thumbnailCount"`
	IntervalMillis int64  `json:"intervalMillis"`
}

type PlaybackChapter struct {
	ID             string `json:"id"`
	Name           string `json:"name"`
	PositionMillis int64  `json:"positionMillis"`
}

type PlaybackSegment struct {
	ID          string `json:"id"`
	Type        string `json:"type"`
	StartMillis int64  `json:"startMillis"`
	EndMillis   int64  `json:"endMillis"`
}

type PlaybackItem struct {
	ID            string `json:"id"`
	Type          string `json:"type"`
	Title         string `json:"title"`
	SeriesTitle   string `json:"seriesTitle,omitempty"`
	SeriesID      string `json:"seriesId,omitempty"`
	SeasonID      string `json:"seasonId,omitempty"`
	SeasonNumber  int    `json:"seasonNumber,omitempty"`
	EpisodeNumber int    `json:"episodeNumber,omitempty"`
}

type PlaybackSource struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Container string `json:"container"`
	SizeBytes int64  `json:"sizeBytes,omitempty"`
	Bitrate   int    `json:"bitrate,omitempty"`
}

type PlaybackTrack struct {
	Index           int    `json:"index"`
	Type            string `json:"type"`
	Label           string `json:"label"`
	Language        string `json:"language,omitempty"`
	Codec           string `json:"codec,omitempty"`
	Channels        int    `json:"channels,omitempty"`
	ChannelLayout   string `json:"channelLayout,omitempty"`
	Default         bool   `json:"default,omitempty"`
	Forced          bool   `json:"forced,omitempty"`
	HearingImpaired bool   `json:"hearingImpaired,omitempty"`
	External        bool   `json:"external,omitempty"`
	ExternalURL     string `json:"externalUrl,omitempty"`
}

type PlaybackSelectBody struct {
	PositionMillis      int64   `json:"positionMillis"`
	MediaSourceID       *string `json:"mediaSourceId,omitempty"`
	AudioStreamIndex    *int    `json:"audioStreamIndex,omitempty"`
	SubtitleStreamIndex *int    `json:"subtitleStreamIndex,omitempty"`
	MaxBitrate          *int    `json:"maxBitrate,omitempty"`
	ForceTranscode      *bool   `json:"forceTranscode,omitempty"`
}

type PlaybackEventBody struct {
	Type           string `json:"type"`
	Sequence       int64  `json:"sequence"`
	PositionMillis int64  `json:"positionMillis"`
	Paused         bool   `json:"paused"`
	Muted          bool   `json:"muted"`
	Volume         int    `json:"volume"`
}

type playbackSession struct {
	mu sync.Mutex

	ID        string
	Owner     string
	UserID    string
	DeviceID  string
	Client    *jellyfin.Client
	Item      jellyfin.Item
	Prepare   PlaybackPrepareBody
	Info      *jellyfin.PlaybackInfo
	Source    jellyfin.MediaSource
	Plan      PlaybackPrepareResponse
	Subtitles map[string]string
	Segments  []jellyfin.MediaSegment

	LastPositionMillis int64
	LastSequence       int64
	Started            bool
	Stopped            bool
	Timer              *time.Timer
}

func (s *Server) requirePlay(w http.ResponseWriter, r *http.Request) bool {
	return requireScope(w, r, "play", "play media")
}

func (s *Server) handleSeriesPlayTarget(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	client, ok := s.jellyfinForRequest(w, r)
	if !ok {
		return
	}
	seriesID := r.PathValue("seriesId")
	if !isHex32(seriesID) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad series id"})
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()
	page, err := client.NextUpForSeries(ctx, seriesID, true)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	kind := "next"
	var target *jellyfin.Item
	if len(page.Items) > 0 {
		target = &page.Items[0]
		if target.PositionSeconds() >= 30 {
			kind = "resume"
		} else if target.ParentIndexNumber <= 1 && target.IndexNumber <= 1 &&
			(target.UserData == nil || target.UserData.PlayCount == 0) {
			kind = "start"
		}
	} else {
		all, loadErr := client.EpisodesFrom(ctx, seriesID, "", 300)
		if loadErr != nil {
			writeUpstreamError(w, r, "jellyfin", loadErr)
			return
		}
		for i := range all.Items {
			if all.Items[i].ParentIndexNumber > 0 {
				target = &all.Items[i]
				break
			}
		}
		if target == nil && len(all.Items) > 0 {
			target = &all.Items[0]
		}
		kind = "start"
	}
	if target == nil {
		writeError(w, r, http.StatusNotFound, Error{
			Code: CodeNotFound, Service: "jellyfin", Message: "this series has no playable episodes",
		})
		return
	}
	writeJSON(w, http.StatusOK, SeriesPlayTargetResponse{
		SeriesID: seriesID, Kind: kind, Item: libraryItemFrom(*target),
	})
}

func (s *Server) handlePlaybackPrepare(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	client, ok := s.jellyfinForRequest(w, r)
	if !ok {
		return
	}
	itemID := r.PathValue("itemId")
	if !isHex32(itemID) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad item id"})
		return
	}
	var body PlaybackPrepareBody
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, playbackBodyLimit)).Decode(&body); err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid playback options"})
		return
	}
	if err := validatePrepare(&body); err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: err.Error()})
		return
	}
	if body.Device.ID == "" {
		body.Device.ID = "pocketds-" + TokenFrom(r.Context()).Label
	}
	playbackClient := client.WithPlaybackDevice(body.Device.ID, body.Device.Name, body.Device.Version)
	ctx, cancel := timeoutFor(r, 30*time.Second)
	defer cancel()
	item, err := playbackClient.Item(ctx, itemID)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	if item.Type != "Movie" && item.Type != "Episode" {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "only movies and episodes can be played",
		})
		return
	}
	id, err := randomPlaybackID()
	if err != nil {
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "could not create playback session"})
		return
	}
	session := &playbackSession{
		ID: id, Owner: TokenFrom(r.Context()).Label, UserID: playbackClient.UserID(),
		DeviceID: body.Device.ID, Client: playbackClient, Item: *item, Prepare: body,
		Subtitles: make(map[string]string),
	}
	// A segment provider is optional in Jellyfin. Its absence cannot prevent a
	// movie or episode from starting, so preserve the successful play path.
	if segments, segmentErr := playbackClient.MediaSegments(ctx, itemID); segmentErr == nil {
		session.Segments = segments
	} else {
		slog.Debug("Jellyfin media segments unavailable", "item", itemID)
	}
	plan, err := s.negotiatePlayback(ctx, session)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	s.addPlaybackSession(session)
	writeJSON(w, http.StatusOK, plan)
}

func validatePrepare(body *PlaybackPrepareBody) error {
	if body.StartMode == "" {
		body.StartMode = "resume"
	}
	if body.StartMode != "resume" && body.StartMode != "restart" {
		return fmt.Errorf("startMode must be resume or restart")
	}
	if body.PositionMillis < 0 {
		return fmt.Errorf("positionMillis cannot be negative")
	}
	if body.MaxBitrate < 0 || body.MaxBitrate > maxPlaybackBitrate {
		return fmt.Errorf("maxBitrate is outside the supported range")
	}
	if body.Capabilities.Width < 0 || body.Capabilities.Width > 7680 ||
		body.Capabilities.Height < 0 || body.Capabilities.Height > 4320 {
		return fmt.Errorf("display dimensions are outside the supported range")
	}
	if body.Capabilities.MaxAudioChannels < 0 || body.Capabilities.MaxAudioChannels > 16 {
		return fmt.Errorf("maxAudioChannels is outside the supported range")
	}
	if body.AudioStreamIndex != nil && *body.AudioStreamIndex < 0 {
		return fmt.Errorf("audioStreamIndex cannot be negative")
	}
	if body.SubtitleStreamIndex != nil && *body.SubtitleStreamIndex < -1 {
		return fmt.Errorf("subtitleStreamIndex is invalid")
	}
	return nil
}

func (s *Server) negotiatePlayback(
	ctx context.Context, session *playbackSession,
) (PlaybackPrepareResponse, error) {
	durationMillis := session.Item.RunTimeTicks / 10_000
	positionMillis := playbackStartPosition(session.Item, session.Prepare, durationMillis)
	request := jellyfin.PlaybackInfoRequest{
		StartTimeTicks:       positionMillis * 10_000,
		AudioStreamIndex:     session.Prepare.AudioStreamIndex,
		SubtitleStreamIndex:  session.Prepare.SubtitleStreamIndex,
		MediaSourceID:        session.Prepare.MediaSourceID,
		DeviceProfile:        buildDeviceProfile(session.Prepare),
		EnableDirectPlay:     !session.Prepare.ForceTranscode,
		EnableDirectStream:   !session.Prepare.ForceTranscode,
		EnableTranscoding:    true,
		AllowVideoStreamCopy: !session.Prepare.ForceTranscode, AllowAudioStreamCopy: true,
	}
	if session.Prepare.MaxBitrate > 0 {
		request.MaxStreamingBitrate = &session.Prepare.MaxBitrate
	}
	info, err := session.Client.PlaybackInfo(ctx, session.Item.ID, request)
	if err != nil {
		return PlaybackPrepareResponse{}, err
	}
	if len(info.MediaSources) == 0 {
		return PlaybackPrepareResponse{}, fmt.Errorf("jellyfin: no playable media source")
	}
	source := info.MediaSources[0]
	if session.Prepare.MediaSourceID != "" {
		found := false
		for _, candidate := range info.MediaSources {
			if candidate.ID == session.Prepare.MediaSourceID {
				source, found = candidate, true
				break
			}
		}
		if !found {
			return PlaybackPrepareResponse{}, fmt.Errorf("jellyfin: selected media source is unavailable")
		}
	}
	session.Info = info
	session.Source = source
	session.Subtitles = make(map[string]string)
	plan := s.playbackPlan(ctx, session, positionMillis, durationMillis)
	session.Plan = plan
	session.LastPositionMillis = positionMillis
	return plan, nil
}

func playbackStartPosition(item jellyfin.Item, prepare PlaybackPrepareBody, durationMillis int64) int64 {
	positionMillis := prepare.PositionMillis
	if prepare.StartMode == "restart" {
		return 0
	}
	if positionMillis == 0 && item.UserData != nil {
		positionMillis = item.UserData.PlaybackPositionTicks / 10_000
	}
	// A watched item with a position is a rewatch and resumes like Jellyfin's
	// own clients; finishing or marking it watched clears the position.
	if positionMillis < 30_000 || durationMillis-positionMillis <= 30_000 {
		return 0
	}
	return positionMillis
}

var allowedVideoCodecs = map[string]bool{
	"h264": true, "hevc": true, "h265": true, "vp8": true, "vp9": true,
	"av1": true, "mpeg2video": true, "mpeg4": true,
}
var allowedAudioCodecs = map[string]bool{
	"aac": true, "mp3": true, "ac3": true, "eac3": true, "opus": true,
	"vorbis": true, "flac": true, "alac": true,
}

func allowedCSV(values []string, allowed map[string]bool, fallback string) string {
	out := make([]string, 0, len(values))
	seen := map[string]bool{}
	for _, value := range values {
		value = strings.ToLower(strings.TrimSpace(value))
		if value == "h265" {
			value = "hevc"
		}
		if allowed[value] && !seen[value] {
			seen[value] = true
			out = append(out, value)
		}
	}
	if len(out) == 0 {
		return fallback
	}
	return strings.Join(out, ",")
}

func buildDeviceProfile(body PlaybackPrepareBody) jellyfin.DeviceProfile {
	video := allowedCSV(body.Capabilities.VideoCodecs, allowedVideoCodecs, "h264")
	audio := allowedCSV(body.Capabilities.AudioCodecs, allowedAudioCodecs, "aac,mp3")
	maxBitrate := body.MaxBitrate
	if maxBitrate == 0 {
		maxBitrate = maxPlaybackBitrate
	}
	channels := body.Capabilities.MaxAudioChannels
	if channels <= 0 {
		channels = 2
	}
	directPlay := []jellyfin.DirectPlayProfile{}
	if !body.ForceTranscode {
		directPlay = []jellyfin.DirectPlayProfile{
			{Container: "mp4,m4v,mov", VideoCodec: video, AudioCodec: audio, Type: "Video"},
			{Container: "mkv,webm", VideoCodec: video, AudioCodec: audio, Type: "Video"},
			{Container: "ts,mpegts", VideoCodec: video, AudioCodec: audio, Type: "Video"},
		}
	}
	return jellyfin.DeviceProfile{
		Name: "Pocket DS Media3", MaxStreamingBitrate: maxBitrate, MaxStaticBitrate: maxPlaybackBitrate,
		DirectPlayProfiles: directPlay,
		TranscodingProfiles: []jellyfin.TranscodingProfile{{
			Container: "ts", Type: "Video", VideoCodec: "h264", AudioCodec: "aac",
			Protocol: "hls", Context: "Streaming", MaxAudioChannels: strconv.Itoa(channels),
			MinSegments: 1, SegmentLength: 6, BreakOnNonKeyFrames: true,
			EnableSubtitlesInManifest: true,
		}},
		CodecProfiles: []jellyfin.CodecProfile{},
		SubtitleProfiles: []jellyfin.SubtitleProfile{
			{Format: "srt", Method: "External"}, {Format: "subrip", Method: "External"},
			{Format: "vtt", Method: "External"}, {Format: "webvtt", Method: "External"},
			{Format: "ass", Method: "Encode"}, {Format: "ssa", Method: "Encode"},
			{Format: "pgssub", Method: "Encode"}, {Format: "dvdsub", Method: "Encode"},
		},
	}
}

func (s *Server) playbackPlan(
	ctx context.Context, session *playbackSession, positionMillis, durationMillis int64,
) PlaybackPrepareResponse {
	source := session.Source
	method := "DirectPlay"
	resource := source.DirectStreamURL
	if source.TranscodingURL != "" {
		method = "Transcode"
		resource = source.TranscodingURL
	} else if source.DirectStreamURL != "" {
		method = "DirectStream"
	}
	if resource == "" {
		query := url.Values{
			"static": {"true"}, "mediaSourceId": {source.ID},
			"playSessionId": {session.Info.PlaySessionID}, "deviceId": {session.DeviceID},
		}
		resource = "/Videos/" + session.Item.ID + "/stream?" + query.Encode()
	}
	if sanitized, err := sanitizePlaybackResource(resource); err == nil {
		resource = sanitized
	}
	mediaURL := "/v1/playback/sessions/" + session.ID + "/stream"
	if method == "Transcode" || strings.Contains(strings.ToLower(resource), ".m3u8") {
		mediaURL = "/v1/playback/sessions/" + session.ID + "/hls/" + encodePlaybackResource(resource)
	}
	sources := make([]PlaybackSource, 0, len(session.Info.MediaSources))
	for _, value := range session.Info.MediaSources {
		sources = append(sources, PlaybackSource{
			ID: value.ID, Name: value.Name, Container: firstContainer(value.Container),
			SizeBytes: value.Size, Bitrate: value.Bitrate,
		})
	}
	audio := []PlaybackTrack{}
	subtitles := []PlaybackTrack{}
	videoWidth, videoHeight, bitrate := 0, 0, source.Bitrate
	videoCodec, audioCodec := "", ""
	var frameRate float64
	var hdr string
	for _, stream := range source.MediaStreams {
		switch strings.ToLower(stream.Type) {
		case "video":
			if videoWidth == 0 {
				videoWidth, videoHeight, frameRate = stream.Width, stream.Height, stream.AverageFrameRate
				videoCodec = stream.Codec
				hdr = stream.VideoRangeType
				if stream.Bitrate > 0 {
					bitrate = stream.Bitrate
				}
			}
		case "audio":
			audio = append(audio, playbackTrack(stream, ""))
			if audioCodec == "" {
				audioCodec = stream.Codec
			}
		case "subtitle":
			externalURL := ""
			// Jellyfin's POST PlaybackInfo response can clear all three delivery
			// flags even for external SRT files (verified on 10.11.8). Its
			// subtitle stream endpoint still serves text streams, including an
			// extracted embedded stream, so codec type is the reliable contract.
			if isTextSubtitle(stream) {
				upstream := stream.DeliveryURL
				if upstream == "" || !validPlaybackResource(upstream, session.Item.ID) {
					ext := subtitleExtension(stream.Codec)
					upstream = fmt.Sprintf("/Videos/%s/%s/Subtitles/%d/0/Stream.%s",
						session.Item.ID, source.ID, stream.Index, ext)
				}
				if sanitized, err := sanitizePlaybackResource(upstream); err == nil {
					upstream = sanitized
				}
				if validPlaybackResource(upstream, session.Item.ID) {
					key := strconv.Itoa(stream.Index)
					session.Subtitles[key] = upstream
					externalURL = "/v1/playback/sessions/" + session.ID + "/subtitles/" + key
				}
			}
			subtitles = append(subtitles, playbackTrack(stream, externalURL))
		}
	}
	if method == "Transcode" {
		if value := playbackResourceQueryValue(resource, "VideoCodec"); value != "" {
			videoCodec = firstContainer(value)
		}
		if value := playbackResourceQueryValue(resource, "AudioCodec"); value != "" {
			audioCodec = firstContainer(value)
		}
	}
	selectedAudio := session.Prepare.AudioStreamIndex
	if selectedAudio == nil {
		selectedAudio = source.DefaultAudioStreamIndex
	}
	selectedSubtitle := session.Prepare.SubtitleStreamIndex
	if selectedSubtitle == nil {
		selectedSubtitle = source.DefaultSubtitleStreamIndex
	}
	var previous, next *PlaybackItem
	if strings.EqualFold(session.Item.Type, "Episode") && session.Item.SeriesID != "" {
		ctx, cancel := context.WithTimeout(ctx, 8*time.Second)
		defer cancel()
		if page, err := session.Client.AdjacentEpisodes(ctx, session.Item.SeriesID, session.Item.ID); err == nil {
			previous, next = adjacentPlaybackItems(session.Item, page.Items)
		}
	}
	chapters := playbackChapters(session.Item, durationMillis)
	return PlaybackPrepareResponse{
		SessionID: session.ID, Item: playbackItem(session.Item), PositionMillis: positionMillis,
		DurationMillis: durationMillis, MediaURL: mediaURL, MIMEType: playbackMIME(source, resource),
		PlayMethod: method, TranscodeReason: strings.Join(source.TranscodeReasons, ", "),
		Bitrate: bitrate, Width: videoWidth, Height: videoHeight, FrameRate: frameRate, HDR: hdr,
		VideoCodec: videoCodec, AudioCodec: audioCodec,
		Sources: sources, AudioTracks: audio, SubtitleTracks: subtitles,
		SelectedMediaSourceID: source.ID, SelectedAudioIndex: selectedAudio,
		SelectedSubtitleIndex: selectedSubtitle, PreviousItem: previous, NextItem: next,
		Trickplay:  playbackTrickplay(session),
		PreviewURL: "/v1/playback/sessions/" + session.ID + "/preview",
		Chapters:   chapters,
		Segments:   segmentsOrChapters(playbackSegments(session.Segments, durationMillis), chapters, durationMillis),
	}
}

func playbackChapters(item jellyfin.Item, durationMillis int64) []PlaybackChapter {
	values := make([]PlaybackChapter, 0, len(item.Chapters))
	seen := make(map[int64]bool)
	for index, chapter := range item.Chapters {
		position := chapter.StartPositionTicks / 10_000
		if position < 0 || (durationMillis > 0 && position >= durationMillis) || seen[position] {
			continue
		}
		seen[position] = true
		name := strings.TrimSpace(chapter.Name)
		if name == "" {
			name = fmt.Sprintf("Chapter %d", len(values)+1)
		}
		values = append(values, PlaybackChapter{ID: strconv.Itoa(index), Name: name, PositionMillis: position})
	}
	sort.SliceStable(values, func(i, j int) bool { return values[i].PositionMillis < values[j].PositionMillis })
	return values
}

func playbackSegments(segments []jellyfin.MediaSegment, durationMillis int64) []PlaybackSegment {
	values := make([]PlaybackSegment, 0, len(segments))
	for _, segment := range segments {
		start, end := segment.StartTicks/10_000, segment.EndTicks/10_000
		if start < 0 || end <= start || (durationMillis > 0 && start >= durationMillis) {
			continue
		}
		if durationMillis > 0 && end > durationMillis {
			end = durationMillis
		}
		kind := strings.TrimSpace(segment.Type)
		if kind == "" {
			kind = "Segment"
		}
		values = append(values, PlaybackSegment{ID: segment.ID, Type: kind, StartMillis: start, EndMillis: end})
	}
	sort.SliceStable(values, func(i, j int) bool { return values[i].StartMillis < values[j].StartMillis })
	return values
}

func playbackTrickplay(session *playbackSession) *PlaybackTrickplay {
	resolutions := session.Item.Trickplay[session.Source.ID]
	if len(resolutions) == 0 {
		for sourceID, candidate := range session.Item.Trickplay {
			if playbackIDsEqual(sourceID, session.Source.ID) {
				resolutions = candidate
				break
			}
		}
	}
	var selected jellyfin.TrickplayInfo
	bestDistance := int(^uint(0) >> 1)
	for _, candidate := range resolutions {
		if candidate.Width <= 0 || candidate.Height <= 0 || candidate.TileWidth <= 0 ||
			candidate.TileHeight <= 0 || candidate.ThumbnailCount <= 0 || candidate.Interval <= 0 {
			continue
		}
		distance := candidate.Width - 320
		if distance < 0 {
			distance = -distance
		}
		if distance < bestDistance {
			bestDistance = distance
			selected = candidate
		}
	}
	if selected.Width == 0 {
		return nil
	}
	return &PlaybackTrickplay{
		TileURL: "/v1/playback/sessions/" + session.ID + "/trickplay",
		Width:   selected.Width, Height: selected.Height,
		TileWidth: selected.TileWidth, TileHeight: selected.TileHeight,
		ThumbnailCount: selected.ThumbnailCount, IntervalMillis: selected.Interval,
	}
}

func adjacentPlaybackItems(current jellyfin.Item, candidates []jellyfin.Item) (*PlaybackItem, *PlaybackItem) {
	var previous, next *PlaybackItem
	for _, candidate := range candidates {
		if candidate.ID == current.ID {
			continue
		}
		order := compareEpisodeOrder(candidate, current)
		item := playbackItem(candidate)
		if order < 0 {
			if previous == nil || comparePlaybackOrder(item, *previous) > 0 {
				copy := item
				previous = &copy
			}
		} else if order > 0 && (next == nil || comparePlaybackOrder(item, *next) < 0) {
			copy := item
			next = &copy
		}
	}
	return previous, next
}

func compareEpisodeOrder(left, right jellyfin.Item) int {
	if left.ParentIndexNumber != right.ParentIndexNumber {
		return left.ParentIndexNumber - right.ParentIndexNumber
	}
	return left.IndexNumber - right.IndexNumber
}

func comparePlaybackOrder(left, right PlaybackItem) int {
	if left.SeasonNumber != right.SeasonNumber {
		return left.SeasonNumber - right.SeasonNumber
	}
	return left.EpisodeNumber - right.EpisodeNumber
}

func isTextSubtitle(stream jellyfin.MediaStream) bool {
	if stream.IsTextSubtitleStream {
		return true
	}
	switch strings.ToLower(stream.Codec) {
	case "srt", "subrip", "vtt", "webvtt":
		return true
	default:
		return false
	}
}

func playbackItem(item jellyfin.Item) PlaybackItem {
	return PlaybackItem{
		ID: item.ID, Type: strings.ToLower(item.Type), Title: item.Name,
		SeriesTitle: item.SeriesName, SeriesID: item.SeriesID, SeasonID: item.SeasonID,
		SeasonNumber: item.ParentIndexNumber, EpisodeNumber: item.IndexNumber,
	}
}

func playbackTrack(stream jellyfin.MediaStream, externalURL string) PlaybackTrack {
	label := strings.TrimSpace(stream.DisplayTitle)
	if label == "" {
		label = strings.TrimSpace(strings.Join([]string{stream.Language, strings.ToUpper(stream.Codec)}, " · "))
		label = strings.Trim(label, " ·")
	}
	return PlaybackTrack{
		Index: stream.Index, Type: strings.ToLower(stream.Type), Label: label,
		Language: stream.Language, Codec: stream.Codec, Channels: stream.Channels,
		ChannelLayout: stream.ChannelLayout, Default: stream.IsDefault, Forced: stream.IsForced,
		HearingImpaired: stream.IsHearingImpaired, External: externalURL != "", ExternalURL: externalURL,
	}
}

func firstContainer(container string) string {
	if at := strings.IndexByte(container, ','); at >= 0 {
		return container[:at]
	}
	return container
}

func subtitleExtension(codec string) string {
	switch strings.ToLower(codec) {
	case "subrip":
		return "srt"
	case "webvtt":
		return "vtt"
	case "ssa":
		return "ass"
	default:
		return strings.ToLower(codec)
	}
}

func playbackMIME(source jellyfin.MediaSource, resource string) string {
	if strings.Contains(strings.ToLower(resource), ".m3u8") {
		return "application/x-mpegURL"
	}
	switch firstContainer(strings.ToLower(source.Container)) {
	case "mp4", "mov", "m4v":
		return "video/mp4"
	case "mkv", "matroska":
		return "video/x-matroska"
	case "webm":
		return "video/webm"
	case "ts", "mpegts":
		return "video/mp2t"
	default:
		return "video/*"
	}
}

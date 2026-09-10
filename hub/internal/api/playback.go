package api

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"regexp"
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

	LastPositionMillis int64
	LastSequence       int64
	Started            bool
	Stopped            bool
	Timer              *time.Timer
}

func (s *Server) requirePlay(w http.ResponseWriter, r *http.Request) bool {
	if TokenFrom(r.Context()).HasScope("play") {
		return true
	}
	writeError(w, r, http.StatusForbidden, Error{
		Code: CodeForbiddenScope, Message: "this token cannot play media",
	})
	return false
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
	if positionMillis < 30_000 || durationMillis-positionMillis <= 30_000 ||
		(item.UserData != nil && item.UserData.Played) {
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
	}
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

func randomPlaybackID() (string, error) {
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return hex.EncodeToString(raw), nil
}

func (s *Server) addPlaybackSession(session *playbackSession) {
	s.playbackMu.Lock()
	s.playbackSessions[session.ID] = session
	s.playbackMu.Unlock()
	session.Timer = time.AfterFunc(s.playbackTTL, func() {
		s.playbackMu.Lock()
		current := s.playbackSessions[session.ID]
		if current == session {
			delete(s.playbackSessions, session.ID)
		}
		s.playbackMu.Unlock()
		if current == session {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			s.finishPlaybackSession(ctx, session)
		}
	})
}

func (s *Server) playbackSessionForRequest(
	w http.ResponseWriter, r *http.Request,
) (*playbackSession, bool) {
	id := r.PathValue("sessionId")
	if !isHex32(id) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad playback session id"})
		return nil, false
	}
	s.playbackMu.Lock()
	session := s.playbackSessions[id]
	s.playbackMu.Unlock()
	owner := TokenFrom(r.Context()).Label
	requestedUser := strings.TrimSpace(r.Header.Get(jellyfinUserHeader))
	if requestedUser == "" && s.jellyfin != nil {
		requestedUser = s.jellyfin.UserID()
	}
	if session == nil || session.Owner != owner || requestedUser != session.UserID {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such playback session"})
		return nil, false
	}
	if session.Timer != nil {
		session.Timer.Reset(s.playbackTTL)
	}
	return session, true
}

func (s *Server) handlePlaybackSelect(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	var body PlaybackSelectBody
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, playbackBodyLimit)).Decode(&body); err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid playback selection"})
		return
	}
	if body.PositionMillis < 0 || (body.MaxBitrate != nil && (*body.MaxBitrate < 0 || *body.MaxBitrate > maxPlaybackBitrate)) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid playback selection"})
		return
	}
	session.mu.Lock()
	defer session.mu.Unlock()
	previousPlaySession := ""
	if session.Info != nil {
		previousPlaySession = session.Info.PlaySessionID
	}
	session.Prepare.PositionMillis = body.PositionMillis
	session.Prepare.StartMode = "resume"
	if body.MediaSourceID != nil {
		session.Prepare.MediaSourceID = *body.MediaSourceID
	}
	if body.AudioStreamIndex != nil {
		session.Prepare.AudioStreamIndex = body.AudioStreamIndex
	}
	if body.SubtitleStreamIndex != nil {
		session.Prepare.SubtitleStreamIndex = body.SubtitleStreamIndex
	}
	if body.MaxBitrate != nil {
		session.Prepare.MaxBitrate = *body.MaxBitrate
	}
	if body.ForceTranscode != nil {
		session.Prepare.ForceTranscode = *body.ForceTranscode
	}
	ctx, cancel := timeoutFor(r, 30*time.Second)
	defer cancel()
	plan, err := s.negotiatePlayback(ctx, session)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	if previousPlaySession != "" && previousPlaySession != session.Info.PlaySessionID {
		go func() {
			cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 8*time.Second)
			defer cleanupCancel()
			_ = session.Client.CloseTranscode(cleanupCtx, session.DeviceID, previousPlaySession)
		}()
	}
	writeJSON(w, http.StatusOK, plan)
}

func (s *Server) handlePlaybackEvent(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	var body PlaybackEventBody
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<10)).Decode(&body); err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid playback event"})
		return
	}
	allowed := map[string]bool{"started": true, "progress": true, "paused": true, "unpaused": true, "seek": true, "stopped": true}
	if !allowed[body.Type] || body.Sequence < 1 || body.PositionMillis < 0 || body.Volume < 0 || body.Volume > 100 {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid playback event"})
		return
	}
	session.mu.Lock()
	defer session.mu.Unlock()
	if body.Sequence <= session.LastSequence {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "ignored": true})
		return
	}
	maxPosition := session.Plan.DurationMillis + 300_000
	if maxPosition > 300_000 && body.PositionMillis > maxPosition {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "playback position is outside the item"})
		return
	}
	event := jellyfin.PlaybackEvent{
		ItemID: session.Item.ID, MediaSourceID: session.Source.ID,
		PlaySessionID: session.Info.PlaySessionID, PositionTicks: body.PositionMillis * 10_000,
		IsPaused: body.Paused || body.Type == "paused", IsMuted: body.Muted,
		VolumeLevel: body.Volume, AudioStreamIndex: session.Plan.SelectedAudioIndex,
		SubtitleStreamIndex: session.Plan.SelectedSubtitleIndex, PlayMethod: session.Plan.PlayMethod,
		CanSeek: true, EventName: playbackEventName(body.Type),
	}
	endpoint := "/Sessions/Playing/Progress"
	if body.Type == "started" {
		endpoint = "/Sessions/Playing"
	} else if body.Type == "stopped" {
		endpoint = "/Sessions/Playing/Stopped"
	}
	ctx, cancel := timeoutFor(r, 12*time.Second)
	defer cancel()
	if err := session.Client.SendPlaybackEvent(ctx, endpoint, event); err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	session.LastSequence = body.Sequence
	session.LastPositionMillis = body.PositionMillis
	if body.Type == "started" {
		session.Started = true
	}
	if body.Type == "stopped" {
		session.Stopped = true
		s.invalidatePlaybackCaches(session)
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func playbackEventName(kind string) string {
	switch kind {
	case "paused":
		return "Pause"
	case "unpaused":
		return "Unpause"
	case "seek":
		return "TimeUpdate"
	default:
		return "TimeUpdate"
	}
}

func (s *Server) handlePlaybackDelete(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	s.playbackMu.Lock()
	if current := s.playbackSessions[session.ID]; current == session {
		delete(s.playbackSessions, session.ID)
	}
	if session.Timer != nil {
		session.Timer.Stop()
	}
	s.playbackMu.Unlock()
	ctx, cancel := timeoutFor(r, 12*time.Second)
	defer cancel()
	s.finishPlaybackSession(ctx, session)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) finishPlaybackSession(ctx context.Context, session *playbackSession) {
	session.mu.Lock()
	defer session.mu.Unlock()
	if session.Started && !session.Stopped && session.Info != nil {
		event := jellyfin.PlaybackEvent{
			ItemID: session.Item.ID, MediaSourceID: session.Source.ID,
			PlaySessionID:       session.Info.PlaySessionID,
			PositionTicks:       session.LastPositionMillis * 10_000,
			AudioStreamIndex:    session.Plan.SelectedAudioIndex,
			SubtitleStreamIndex: session.Plan.SelectedSubtitleIndex,
			PlayMethod:          session.Plan.PlayMethod, CanSeek: true, VolumeLevel: 100,
		}
		if err := session.Client.SendPlaybackEvent(ctx, "/Sessions/Playing/Stopped", event); err != nil {
			slog.Warn("playback stop report failed", "error", err)
		}
		session.Stopped = true
		s.invalidatePlaybackCaches(session)
	}
	if session.Info != nil {
		if err := session.Client.CloseTranscode(ctx, session.DeviceID, session.Info.PlaySessionID); err != nil {
			slog.Debug("transcode cleanup did not complete", "error", err)
		}
	}
}

func (s *Server) invalidatePlaybackCaches(session *playbackSession) {
	s.cache.InvalidatePrefix("home:" + session.UserID + ":")
	s.cache.Invalidate("library:item:" + session.UserID + ":" + session.Item.ID)
	if session.Item.SeriesID != "" {
		s.cache.Invalidate("library:item:" + session.UserID + ":" + session.Item.SeriesID)
		s.cache.InvalidatePrefix("library:seasons:" + session.UserID + ":" + session.Item.SeriesID)
		s.cache.InvalidatePrefix("library:episodes:" + session.UserID + ":" + session.Item.SeriesID + ":")
	}
	if session.Item.SeasonID != "" {
		s.cache.Invalidate("library:item:" + session.UserID + ":" + session.Item.SeasonID)
	}
}

func (s *Server) handlePlaybackStream(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	session.mu.Lock()
	resource := session.Source.DirectStreamURL
	if resource == "" {
		query := url.Values{
			"static": {"true"}, "mediaSourceId": {session.Source.ID},
			"playSessionId": {session.Info.PlaySessionID}, "deviceId": {session.DeviceID},
		}
		resource = "/Videos/" + session.Item.ID + "/stream?" + query.Encode()
	}
	client := session.Client
	itemID := session.Item.ID
	session.mu.Unlock()
	if !validPlaybackResource(resource, itemID) {
		writeError(w, r, http.StatusBadGateway, Error{Code: CodeUpstreamDown, Message: "Jellyfin returned an invalid stream"})
		return
	}
	s.proxyPlaybackResource(w, r, client, resource, false, itemID, session.ID)
}

func (s *Server) handlePlaybackSubtitle(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	trackID := r.PathValue("trackId")
	session.mu.Lock()
	resource := session.Subtitles[trackID]
	client, itemID := session.Client, session.Item.ID
	session.mu.Unlock()
	if resource == "" || !validPlaybackResource(resource, itemID) {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such subtitle track"})
		return
	}
	offsetMillis, err := strconv.ParseInt(r.URL.Query().Get("offsetMillis"), 10, 64)
	if r.URL.Query().Get("offsetMillis") == "" {
		offsetMillis = 0
		err = nil
	}
	if err != nil || offsetMillis < -maxSubtitleOffset || offsetMillis > maxSubtitleOffset {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "subtitle offset is invalid"})
		return
	}
	if offsetMillis != 0 {
		s.proxyShiftedSubtitle(w, r, client, resource, offsetMillis)
		return
	}
	s.proxyPlaybackResource(w, r, client, resource, false, itemID, session.ID)
}

func (s *Server) proxyShiftedSubtitle(
	w http.ResponseWriter, r *http.Request, client *jellyfin.Client, resource string, offsetMillis int64,
) {
	response, err := client.OpenResource(r.Context(), http.MethodGet, resource, nil)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	defer response.Body.Close()
	if response.StatusCode >= 400 {
		copyPlaybackResponseHeaders(w.Header(), response.Header)
		w.WriteHeader(response.StatusCode)
		_, _ = io.Copy(w, io.LimitReader(response.Body, 8<<10))
		return
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maxSubtitleBytes+1))
	if err != nil || len(body) > maxSubtitleBytes {
		writeError(w, r, http.StatusBadGateway, Error{Code: CodeUpstreamDown, Message: "Jellyfin returned an invalid subtitle"})
		return
	}
	shifted := shiftSubtitleTimings(body, offsetMillis)
	w.Header().Set("Content-Type", response.Header.Get("Content-Type"))
	w.Header().Set("Content-Length", strconv.Itoa(len(shifted)))
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(shifted)
}

var subtitleTimestamp = regexp.MustCompile(`(?:(\d{1,3}):)?(\d{2}):(\d{2})([,.])(\d{3})`)

func shiftSubtitleTimings(body []byte, offsetMillis int64) []byte {
	return subtitleTimestamp.ReplaceAllFunc(body, func(value []byte) []byte {
		parts := subtitleTimestamp.FindSubmatch(value)
		if len(parts) != 6 {
			return value
		}
		hours, minutes, seconds, millis := int64(0), int64(0), int64(0), int64(0)
		if len(parts[1]) > 0 {
			hours, _ = strconv.ParseInt(string(parts[1]), 10, 64)
		}
		minutes, _ = strconv.ParseInt(string(parts[2]), 10, 64)
		seconds, _ = strconv.ParseInt(string(parts[3]), 10, 64)
		millis, _ = strconv.ParseInt(string(parts[5]), 10, 64)
		total := ((hours*60+minutes)*60+seconds)*1000 + millis + offsetMillis
		if total < 0 {
			total = 0
		}
		h := total / 3_600_000
		m := (total % 3_600_000) / 60_000
		s := (total % 60_000) / 1000
		ms := total % 1000
		separator := string(parts[4])
		if len(parts[1]) > 0 || h > 0 {
			return []byte(fmt.Sprintf("%02d:%02d:%02d%s%03d", h, m, s, separator, ms))
		}
		return []byte(fmt.Sprintf("%02d:%02d%s%03d", m, s, separator, ms))
	})
}

func (s *Server) handlePlaybackTrickplay(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	index, err := strconv.Atoi(r.PathValue("index"))
	session.mu.Lock()
	info := session.Plan.Trickplay
	client, itemID, sourceID := session.Client, session.Item.ID, session.Source.ID
	session.mu.Unlock()
	if err != nil || info == nil || index < 0 {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such trickplay tile"})
		return
	}
	perTile := info.TileWidth * info.TileHeight
	tileCount := (info.ThumbnailCount + perTile - 1) / perTile
	if index >= tileCount {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such trickplay tile"})
		return
	}
	query := url.Values{"MediaSourceId": {sourceID}}
	resource := fmt.Sprintf("/Videos/%s/Trickplay/%d/%d.jpg?%s", itemID, info.Width, index, query.Encode())
	response, err := client.OpenResource(r.Context(), http.MethodGet, resource, nil)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	defer response.Body.Close()
	copyPlaybackResponseHeaders(w.Header(), response.Header)
	w.Header().Set("Content-Disposition", "inline")
	w.Header().Set("Cache-Control", "private, max-age=14400")
	w.WriteHeader(response.StatusCode)
	_, _ = io.Copy(w, response.Body)
}

// handlePlaybackPreview extracts one small frame when Jellyfin has not generated
// trickplay sheets for the item. The media path remains inside the session and
// never enters the API response or an ffmpeg shell command.
func (s *Server) handlePlaybackPreview(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	position, err := strconv.ParseInt(r.URL.Query().Get("positionMillis"), 10, 64)
	session.mu.Lock()
	duration, sourcePath := session.Plan.DurationMillis, session.Source.Path
	session.mu.Unlock()
	if err != nil || position < 0 || duration <= 0 || position > duration {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "positionMillis is outside this item",
		})
		return
	}
	if sourcePath == "" {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "preview", Message: "preview source is unavailable",
		})
		return
	}
	// Five-second buckets make a drag reuse nearby frames and cap process churn.
	position = (position / 5_000) * 5_000
	ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
	defer cancel()
	frame, err := s.previewFrame(ctx, sourcePath, position)
	if err != nil {
		slog.Debug("preview frame extraction failed")
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "preview", Message: "preview frame is unavailable",
		})
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Content-Disposition", "inline")
	w.Header().Set("Cache-Control", "private, max-age=86400")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(frame)
}

func extractPreviewFrame(ctx context.Context, sourcePath string, positionMillis int64) ([]byte, error) {
	ffmpeg, err := findFFmpeg()
	if err != nil {
		return nil, err
	}
	var output, stderr bytes.Buffer
	command := exec.CommandContext(
		ctx, ffmpeg,
		"-nostdin", "-hide_banner", "-loglevel", "error",
		"-ss", fmt.Sprintf("%.3f", float64(positionMillis)/1000),
		"-i", sourcePath,
		"-frames:v", "1", "-vf", "scale=240:-2",
		"-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1",
	)
	command.Stdout = &output
	command.Stderr = &stderr
	if err := command.Run(); err != nil {
		return nil, fmt.Errorf("ffmpeg preview: %w: %s", err, strings.TrimSpace(stderr.String()))
	}
	if output.Len() == 0 || output.Len() > 2<<20 {
		return nil, fmt.Errorf("ffmpeg preview returned %d bytes", output.Len())
	}
	return output.Bytes(), nil
}

func findFFmpeg() (string, error) {
	if binary, err := exec.LookPath("ffmpeg"); err == nil {
		return binary, nil
	}
	programFiles := os.Getenv("ProgramFiles")
	if programFiles != "" {
		for _, relative := range []string{
			filepath.Join("Jellyfin", "Server", "ffmpeg.exe"),
			filepath.Join("Jellyfin", "Server", "jellyfin-ffmpeg", "ffmpeg.exe"),
		} {
			candidate := filepath.Join(programFiles, relative)
			if info, err := os.Stat(candidate); err == nil && !info.IsDir() {
				return candidate, nil
			}
		}
	}
	return "", fmt.Errorf("ffmpeg executable was not found")
}

func (s *Server) handlePlaybackHLS(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	decoded, err := base64.RawURLEncoding.DecodeString(r.PathValue("resource"))
	if err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad HLS resource"})
		return
	}
	resource := string(decoded)
	session.mu.Lock()
	client, itemID := session.Client, session.Item.ID
	session.mu.Unlock()
	if !validPlaybackResource(resource, itemID) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad HLS resource"})
		return
	}
	s.proxyPlaybackResource(w, r, client, resource, true, itemID, session.ID)
}

func encodePlaybackResource(resource string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(resource))
}

func validPlaybackResource(resource, itemID string) bool {
	parsed, err := url.Parse(resource)
	if err != nil || parsed.IsAbs() || parsed.Host != "" || parsed.Path == "" {
		return false
	}
	cleaned := path.Clean(parsed.Path)
	parts := strings.Split(strings.TrimPrefix(cleaned, "/"), "/")
	return len(parts) >= 2 && strings.EqualFold(parts[0], "Videos") &&
		playbackIDsEqual(parts[1], itemID)
}

func playbackIDsEqual(left, right string) bool {
	canonical := func(value string) string {
		value = strings.ToLower(strings.ReplaceAll(value, "-", ""))
		if len(value) != 32 {
			return ""
		}
		for _, char := range value {
			if (char < '0' || char > '9') && (char < 'a' || char > 'f') {
				return ""
			}
		}
		return value
	}
	a, b := canonical(left), canonical(right)
	return a != "" && a == b
}

func sanitizePlaybackResource(resource string) (string, error) {
	parsed, err := url.Parse(resource)
	if err != nil || parsed.IsAbs() || parsed.Host != "" || parsed.Path == "" {
		return "", fmt.Errorf("invalid playback resource")
	}
	query := parsed.Query()
	for name := range query {
		switch strings.ToLower(name) {
		case "apikey", "api_key", "token", "access_token", "x-emby-token":
			query.Del(name)
		}
	}
	parsed.RawQuery = query.Encode()
	parsed.ForceQuery = false
	return parsed.RequestURI(), nil
}

func playbackResourceQueryValue(resource, wanted string) string {
	parsed, err := url.Parse(resource)
	if err != nil {
		return ""
	}
	for name, values := range parsed.Query() {
		if strings.EqualFold(name, wanted) && len(values) > 0 {
			return values[0]
		}
	}
	return ""
}

func (s *Server) proxyPlaybackResource(
	w http.ResponseWriter, r *http.Request, client *jellyfin.Client, resource string,
	rewriteHLS bool, itemID, sessionID string,
) {
	headers := make(http.Header)
	for _, name := range []string{"Range", "If-Range", "If-None-Match", "If-Modified-Since"} {
		if value := r.Header.Get(name); value != "" {
			headers.Set(name, value)
		}
	}
	response, err := client.OpenResource(r.Context(), http.MethodGet, resource, headers)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	defer response.Body.Close()
	if response.StatusCode >= 400 {
		copyPlaybackResponseHeaders(w.Header(), response.Header)
		w.WriteHeader(response.StatusCode)
		_, _ = io.Copy(w, io.LimitReader(response.Body, 8<<10))
		return
	}
	contentType := response.Header.Get("Content-Type")
	isManifest := rewriteHLS && (strings.Contains(strings.ToLower(contentType), "mpegurl") ||
		strings.HasSuffix(strings.ToLower(strings.Split(resource, "?")[0]), ".m3u8"))
	if !isManifest {
		copyPlaybackResponseHeaders(w.Header(), response.Header)
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(response.StatusCode)
		_, _ = io.Copy(w, response.Body)
		return
	}
	rewritten, err := rewriteHLSManifest(response.Body, resource, itemID, sessionID)
	if err != nil {
		writeError(w, r, http.StatusBadGateway, Error{Code: CodeUpstreamDown, Message: "Jellyfin returned an invalid HLS manifest"})
		return
	}
	w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	w.Header().Set("Content-Length", strconv.Itoa(len(rewritten)))
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(rewritten)
}

func copyPlaybackResponseHeaders(dst, src http.Header) {
	for _, name := range []string{
		"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag",
		"Last-Modified", "Cache-Control",
	} {
		if value := src.Get(name); value != "" {
			dst.Set(name, value)
		}
	}
}

var hlsURIAttribute = regexp.MustCompile(`URI="([^"]+)"`)

func rewriteHLSManifest(
	reader io.Reader, baseResource, itemID, sessionID string,
) ([]byte, error) {
	baseURL, err := url.Parse(baseResource)
	if err != nil {
		return nil, err
	}
	baseURL.Scheme, baseURL.Host = "", ""
	rewrite := func(raw string) (string, error) {
		ref, err := url.Parse(raw)
		if err != nil {
			return "", err
		}
		ref.Scheme, ref.Host = "", ""
		resolved := baseURL.ResolveReference(ref)
		resource, err := sanitizePlaybackResource(resolved.RequestURI())
		if err != nil {
			return "", err
		}
		if !validPlaybackResource(resource, itemID) {
			return "", fmt.Errorf("HLS resource escaped item")
		}
		return "/v1/playback/sessions/" + sessionID + "/hls/" + encodePlaybackResource(resource), nil
	}
	var out strings.Builder
	scanner := bufio.NewScanner(reader)
	buffer := make([]byte, 64<<10)
	scanner.Buffer(buffer, 1<<20)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "#") {
			var replaceErr error
			line = hlsURIAttribute.ReplaceAllStringFunc(line, func(match string) string {
				parts := hlsURIAttribute.FindStringSubmatch(match)
				if len(parts) != 2 {
					return match
				}
				value, err := rewrite(parts[1])
				if err != nil {
					replaceErr = err
					return match
				}
				return `URI="` + value + `"`
			})
			if replaceErr != nil {
				return nil, replaceErr
			}
		} else if strings.TrimSpace(line) != "" {
			line, err = rewrite(strings.TrimSpace(line))
			if err != nil {
				return nil, err
			}
		}
		out.WriteString(line)
		out.WriteByte('\n')
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	return []byte(out.String()), nil
}

package api

// Offline downloads that play on Apple (#5). AVPlayer cannot play an MKV, so for
// an Apple device the hub repackages the source into an MP4: it copies every
// stream AVPlayer already understands, converts the few it does not, and serves
// the finished file by byte range under the same grant as an original download.
// What the MP4 will hold is decided once, by repackage.PlanApple, and told to the
// app in the manifest before any work starts.

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"ayaneohub/internal/adapters/jellyfin"
	"ayaneohub/internal/httpx"
	"ayaneohub/internal/repackage"
)

const (
	offlineFormatOriginal = "original"
	offlineFormatApple    = "apple"

	codeOfflinePreparing  = "offline_preparing"
	codeOfflineFailed     = "offline_failed"
	codeAppleUnavailable  = "offline_apple_unavailable"
	appleRetryAfter       = 5
	appleStatusURLPattern = "/v1/offline/grants/%s/status"
)

// OfflineApple is what an Apple download's MP4 will hold.
type OfflineApple struct {
	Container          string                 `json:"container"`
	MIMEType           string                 `json:"mimeType"`
	EstimatedSizeBytes int64                  `json:"estimatedSizeBytes"`
	StatusURL          string                 `json:"statusUrl"`
	Video              OfflineAppleVideo      `json:"video"`
	Audio              []OfflineAppleAudio    `json:"audio"`
	Subtitles          []OfflineAppleSubtitle `json:"subtitles"`
}

type OfflineAppleVideo struct {
	SourceIndex int    `json:"sourceIndex"`
	Codec       string `json:"codec"`
	OutputCodec string `json:"outputCodec"`
	Tag         string `json:"tag"`
	Converted   bool   `json:"converted"`
	Reason      string `json:"reason,omitempty"`
	Width       int    `json:"width,omitempty"`
	Height      int    `json:"height,omitempty"`
}

type OfflineAppleAudio struct {
	SourceIndex    int    `json:"sourceIndex"`
	Language       string `json:"language"`
	Label          string `json:"label"`
	Codec          string `json:"codec"`
	OutputCodec    string `json:"outputCodec"`
	Channels       int    `json:"channels"`
	OutputChannels int    `json:"outputChannels"`
	Converted      bool   `json:"converted"`
	Reason         string `json:"reason,omitempty"`
	Default        bool   `json:"default"`
}

type OfflineAppleSubtitle struct {
	SourceIndex     int    `json:"sourceIndex"`
	Language        string `json:"language"`
	Label           string `json:"label"`
	Codec           string `json:"codec"`
	OutputCodec     string `json:"outputCodec,omitempty"`
	External        bool   `json:"external"`
	Forced          bool   `json:"forced"`
	HearingImpaired bool   `json:"hearingImpaired"`
	Available       bool   `json:"available"`
	Reason          string `json:"reason,omitempty"`
}

// OfflineAppleSummary is what the picker is told of an episode before anything is
// prepared: how big the MP4 will be and what will not come across.
type OfflineAppleSummary struct {
	EstimatedSizeBytes int64 `json:"estimatedSizeBytes"`
	VideoConverted     bool  `json:"videoConverted"`
	AudioConverted     int   `json:"audioConverted"`
	OmittedSubtitles   int   `json:"omittedSubtitles"`
}

// OfflineStatus is where one grant's file stands.
type OfflineStatus struct {
	GrantID            string              `json:"grantId"`
	Format             string              `json:"format"`
	State              string              `json:"state"`
	Percent            int                 `json:"percent"`
	QueuePosition      int                 `json:"queuePosition"`
	EstimatedSizeBytes int64               `json:"estimatedSizeBytes"`
	SizeBytes          int64               `json:"sizeBytes,omitempty"`
	ETag               string              `json:"etag,omitempty"`
	Error              *OfflineStatusError `json:"error,omitempty"`
}

type OfflineStatusError struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	Retryable bool   `json:"retryable"`
}

// appleGrantError is an item that cannot become an MP4, for a reason an app can
// show: no_video, or no_source_file.
type appleGrantError struct{ reason, message string }

func (e *appleGrantError) Error() string { return e.message }

// parseOfflineFormat reads the format of a prepare: "" is the original.
func parseOfflineFormat(text string) (string, bool) {
	switch strings.ToLower(strings.TrimSpace(text)) {
	case "", offlineFormatOriginal:
		return "", true
	case offlineFormatApple:
		return offlineFormatApple, true
	}
	return "", false
}

// planSourceFrom is a Jellyfin media source as the plan reads it.
func planSourceFrom(source jellyfin.MediaSource, item jellyfin.Item) repackage.Source {
	ticks := source.RunTimeTicks
	if ticks <= 0 {
		ticks = item.RunTimeTicks
	}
	out := repackage.Source{
		Container: source.Container, SizeBytes: source.Size, BitRate: source.Bitrate,
		DurationSeconds: float64(ticks) / jellyfin.TicksPerSecond, DefaultAudio: -1,
	}
	if source.DefaultAudioStreamIndex != nil {
		out.DefaultAudio = *source.DefaultAudioStreamIndex
	}
	for _, stream := range source.MediaStreams {
		kind := strings.ToLower(stream.Type)
		if kind != "video" && kind != "audio" && kind != "subtitle" {
			continue // cover pictures and the like
		}
		out.Streams = append(out.Streams, repackage.Stream{
			Index: stream.Index, Type: kind, Codec: stream.Codec, Profile: stream.Profile,
			Language: stream.Language, Label: playbackTrack(stream, "").Label, Channels: stream.Channels,
			BitRate: stream.Bitrate, BitDepth: stream.BitDepth, PixelFormat: stream.PixelFormat,
			Width: stream.Width, Height: stream.Height, Default: stream.IsDefault, Forced: stream.IsForced,
			HearingImpaired: stream.IsHearingImpaired, External: stream.IsExternal,
		})
	}
	return out
}

func mediaSourceOf(item jellyfin.Item, id string) (jellyfin.MediaSource, bool) {
	for _, source := range item.MediaSources {
		if source.ID == id {
			return source, true
		}
	}
	return jellyfin.MediaSource{}, false
}

// appleManifestOf is the plan as the manifest tells it.
func appleManifestOf(grantID string, plan repackage.Plan) *OfflineApple {
	out := &OfflineApple{
		Container: "mp4", MIMEType: "video/mp4", EstimatedSizeBytes: plan.EstimatedBytes,
		StatusURL: fmt.Sprintf(appleStatusURLPattern, grantID),
		Video: OfflineAppleVideo{
			SourceIndex: plan.Video.SourceIndex, Codec: plan.Video.Codec, OutputCodec: plan.Video.OutputCodec,
			Tag: plan.Video.Tag, Converted: plan.Video.Converted, Reason: plan.Video.Reason,
			Width: plan.Video.Width, Height: plan.Video.Height,
		},
		Audio:     []OfflineAppleAudio{},
		Subtitles: []OfflineAppleSubtitle{},
	}
	for _, track := range plan.Audio {
		out.Audio = append(out.Audio, OfflineAppleAudio{
			SourceIndex: track.SourceIndex, Language: track.Language, Label: track.Label, Codec: track.Codec,
			OutputCodec: track.OutputCodec, Channels: track.Channels, OutputChannels: track.OutputChannels,
			Converted: track.Converted, Reason: track.Reason, Default: track.Default,
		})
	}
	for _, track := range plan.Subtitles {
		out.Subtitles = append(out.Subtitles, OfflineAppleSubtitle{
			SourceIndex: track.SourceIndex, Language: track.Language, Label: track.Label, Codec: track.Codec,
			OutputCodec: track.OutputCodec, External: track.External, Forced: track.Forced,
			HearingImpaired: track.HearingImpaired, Available: track.Available, Reason: track.Reason,
		})
	}
	return out
}

// appleSameMedia says whether a renewed grant would make the same MP4 as the one
// the app downloaded, apart from the sidecar subtitles. It compares what the plan
// decides about the file: the container (the size is compared by the caller), the
// video, every audio track and the subtitles that live inside the file. The
// sidecars beside it are left out, since Bazarr adds, replaces and removes them for
// as long as the download is kept, which does not make it another file (#45).
// Labels, sizes and the picture's width are not what the plan signs either.
func appleSameMedia(previous, renewed offlineGrant) bool {
	if previous.Format != offlineFormatApple || renewed.Format != offlineFormatApple ||
		previous.Manifest.Apple == nil || renewed.Manifest.Apple == nil ||
		previous.Manifest.Source.Container != renewed.Manifest.Source.Container {
		return false
	}
	before, after := previous.Manifest.Apple, renewed.Manifest.Apple
	if before.Container != after.Container ||
		before.Video.SourceIndex != after.Video.SourceIndex || before.Video.OutputCodec != after.Video.OutputCodec ||
		before.Video.Tag != after.Video.Tag || before.Video.Converted != after.Video.Converted ||
		len(before.Audio) != len(after.Audio) {
		return false
	}
	for at, track := range before.Audio {
		other := after.Audio[at]
		if track.SourceIndex != other.SourceIndex || track.Language != other.Language || track.OutputCodec != other.OutputCodec ||
			track.OutputChannels != other.OutputChannels || track.Converted != other.Converted || track.Default != other.Default {
			return false
		}
	}
	inside := func(tracks []OfflineAppleSubtitle) []OfflineAppleSubtitle {
		var kept []OfflineAppleSubtitle
		for _, track := range tracks {
			if !track.External {
				kept = append(kept, track)
			}
		}
		return kept
	}
	left, right := inside(before.Subtitles), inside(after.Subtitles)
	if len(left) != len(right) {
		return false
	}
	for at, track := range left {
		other := right[at]
		if track.SourceIndex != other.SourceIndex || track.Language != other.Language || track.Available != other.Available ||
			track.Forced != other.Forced || track.HearingImpaired != other.HearingImpaired {
			return false
		}
	}
	return true
}

// keepPlannedMedia is a renewal of a grant whose sidecars changed since its MP4 was
// made: the grant stays as it was planned (its plan signature, its manifest and its
// source description, so that the track indexes in the manifest still mean what they
// meant and a file is only ever built or served for the plan it was promised), with
// the new expiry and the item as it is now.
func keepPlannedMedia(previous, renewed offlineGrant) offlineGrant {
	kept := previous
	kept.ExpiresAt = renewed.ExpiresAt
	kept.Manifest.ExpiresAt = renewed.ExpiresAt
	library := previous.Manifest.Item.Library
	kept.Manifest.Item = renewed.Manifest.Item
	kept.Manifest.Item.Library = library
	// A copy, so that the renewal's edits to the manifest never touch the stored one.
	apple := *previous.Manifest.Apple
	kept.Manifest.Apple = &apple
	return kept
}

func appleSummaryOf(plan repackage.Plan) *OfflineAppleSummary {
	summary := &OfflineAppleSummary{EstimatedSizeBytes: plan.EstimatedBytes, VideoConverted: plan.Video.Converted}
	for _, track := range plan.Audio {
		if track.Converted {
			summary.AudioConverted++
		}
	}
	for _, track := range plan.Subtitles {
		if !track.Available {
			summary.OmittedSubtitles++
		}
	}
	return summary
}

// planOfflineStreams plans an item's source for an Apple device, from its streams
// alone: it fails, with a reason an app can show, for one with no picture.
func planOfflineStreams(source jellyfin.MediaSource, item jellyfin.Item) (repackage.Plan, error) {
	plan, err := repackage.PlanApple(planSourceFrom(source, item))
	if errors.Is(err, repackage.ErrNoVideo) {
		return repackage.Plan{}, &appleGrantError{"no_video", "This item has no picture to put in an MP4."}
	}
	return plan, err
}

// planOffline is that plan for a download that is about to be made, which also
// needs a file on this PC to read.
func planOffline(source jellyfin.MediaSource, item jellyfin.Item) (repackage.Plan, error) {
	if path := strings.TrimSpace(source.Path); path == "" || !filepath.IsAbs(path) {
		return repackage.Plan{}, &appleGrantError{"no_source_file", "This item's file is not one the media PC can read."}
	}
	return planOfflineStreams(source, item)
}

// withSourcePaths fills in the path of each media source that came without one.
// Jellyfin may keep the paths from a profile that is not an administrator's, and
// a repackage reads the file, so the missing ones are asked of the server itself.
// A failure leaves them missing, which the plan then refuses.
func (s *Server) withSourcePaths(ctx context.Context, item *jellyfin.Item) {
	missing := false
	for _, source := range item.MediaSources {
		missing = missing || strings.TrimSpace(source.Path) == ""
	}
	if !missing || s.jellyfin == nil {
		return
	}
	server, err := s.jellyfin.ItemAsServer(ctx, item.ID)
	if err != nil {
		return
	}
	for at := range item.MediaSources {
		if strings.TrimSpace(item.MediaSources[at].Path) != "" {
			continue
		}
		if source, found := mediaSourceOf(*server, item.MediaSources[at].ID); found {
			item.MediaSources[at].Path = source.Path
		}
	}
}

// makeGrant is makeOfflineGrant for either format. An Apple grant is the same
// capability (owner, user, item, source, expiry) with a manifest that says what
// the MP4 will hold; it keeps no subtitle resources, since the subtitles are
// fetched from Jellyfin when the file is built, and it keeps no path.
func makeGrant(format, owner, userID, batchKey, clientItemKey string, item jellyfin.Item, wantedSource string) (offlineGrant, error) {
	grant, err := makeOfflineGrant(owner, userID, batchKey, clientItemKey, item, wantedSource)
	if err != nil || format != offlineFormatApple {
		return grant, err
	}
	source, found := mediaSourceOf(item, grant.MediaSourceID)
	if !found {
		return offlineGrant{}, fmt.Errorf("the selected original media source is unavailable")
	}
	plan, err := planOffline(source, item)
	if err != nil {
		return offlineGrant{}, err
	}
	if info, statErr := os.Stat(source.Path); statErr != nil || !info.Mode().IsRegular() {
		return offlineGrant{}, &appleGrantError{"no_source_file", "This item's file is not on the media PC."}
	}
	grant.Format, grant.PlanSignature = offlineFormatApple, plan.Signature()
	grant.Resource, grant.Subtitles = "", nil
	grant.Manifest.Format = offlineFormatApple
	grant.Manifest.Subtitles = []OfflineSubtitle{}
	grant.Manifest.Apple = appleManifestOf(grant.ID, plan)
	return grant, nil
}

// repackager is where an Apple download's MP4s are made, created on first use (or
// at start, so that finished files are found and aged even before anyone asks).
func (s *Server) repackager() *repackage.Manager {
	s.appleOnce.Do(func() {
		dir := s.cfg.Server.OfflineCache
		if dir == "" {
			return
		}
		manager, err := repackage.NewManager(repackage.Options{
			Dir: dir, MaxBytes: s.cfg.Server.OfflineCacheMaxBytes.Bytes(), MaxAge: s.cfg.Server.OfflineCacheMaxAge.Std(),
		})
		if err != nil {
			slog.Error("the offline repackage cache could not be opened", "error", err)
			return
		}
		s.appleManager = manager
	})
	return s.appleManager
}

// closeRepackager stops the worker and removes what it had half written.
func (s *Server) closeRepackager() {
	s.appleOnce.Do(func() {})
	if s.appleManager != nil {
		s.appleManager.Close()
	}
}

// appleUnavailable says why the media PC cannot prepare an Apple download, or
// nothing if it can.
func (s *Server) appleUnavailable(needFFmpeg bool) *Error {
	if s.repackager() == nil {
		return &Error{Code: codeAppleUnavailable, Reason: "no_cache", Message: "The media PC has no folder set up to prepare Apple downloads."}
	}
	if needFFmpeg {
		if _, err := s.ffmpegPath(); err != nil {
			return &Error{Code: codeAppleUnavailable, Reason: "no_ffmpeg", Message: "The media PC has no ffmpeg to prepare Apple downloads."}
		}
	}
	return nil
}

// appleSpec is how to make a grant's file, for the queue.
func (s *Server) appleSpec(grant offlineGrant) repackage.Spec {
	var estimate int64
	if grant.Manifest.Apple != nil {
		estimate = grant.Manifest.Apple.EstimatedSizeBytes
	}
	return repackage.Spec{Signature: grant.PlanSignature, EstimateBytes: estimate, Build: s.appleBuild(grant)}
}

func appleFailure(code, message string, retryable bool) error {
	return &repackage.JobError{Code: code, Message: message, Retryable: retryable}
}

// appleBuild makes one grant's MP4. It asks Jellyfin where the file is (the path is
// never stored: not in the grant, not in the cache record), checks that it is the
// file the grant was made for, fetches the text subtitles that live beside it, and
// runs ffmpeg. The source is only ever read.
func (s *Server) appleBuild(grant offlineGrant) repackage.BuildFunc {
	return func(ctx context.Context, job repackage.Job) error {
		ffmpeg, err := s.ffmpegPath()
		if err != nil {
			return appleFailure("no_ffmpeg", "The media PC has no ffmpeg to prepare this download.", false)
		}
		if s.jellyfin == nil {
			return appleFailure("internal", "The media PC cannot reach Jellyfin.", true)
		}
		client := s.jellyfin.ForUser(grant.UserID)
		item, err := client.Item(ctx, grant.ItemID)
		if err != nil {
			var upstream *httpx.Error
			if errors.As(err, &upstream) && upstream.Kind == httpx.KindNotFound {
				return appleFailure("source_missing", "The file for this item is not on the media PC.", false)
			}
			return err
		}
		s.withSourcePaths(ctx, item)
		source, found := mediaSourceOf(*item, grant.MediaSourceID)
		if !found {
			return appleFailure("source_changed", "The file changed since this download was prepared. Prepare it again.", false)
		}
		path := strings.TrimSpace(source.Path)
		info, statErr := os.Stat(path)
		if path == "" || statErr != nil || !info.Mode().IsRegular() {
			return appleFailure("source_missing", "The file for this item is not on the media PC.", false)
		}
		plan, planErr := repackage.PlanApple(planSourceFrom(source, *item))
		if info.Size() != grant.Manifest.Source.SizeBytes || planErr != nil || plan.Signature() != grant.PlanSignature {
			return appleFailure("source_changed", "The file changed since this download was prepared. Prepare it again.", false)
		}

		sidecars := map[int]string{}
		for _, track := range plan.Subtitles {
			if !track.Available || !track.External {
				continue
			}
			file, fetchErr := s.fetchAppleSubtitle(ctx, client, grant, track.SourceIndex, job.Work)
			if fetchErr != nil {
				if ctx.Err() != nil {
					return ctx.Err()
				}
				slog.Warn("a subtitle for an Apple download could not be fetched", "grant", grant.ID, "error", fetchErr)
				return appleFailure("subtitle_unavailable", "A subtitle could not be read from Jellyfin.", true)
			}
			sidecars[track.SourceIndex] = file
		}

		var encoder repackage.Encoder
		if plan.Video.Converted {
			encoder = s.appleEncoder(ffmpeg)
		}
		planned := planSourceFrom(source, *item)
		buildErr := s.buildMP4(ctx, repackage.BuildSpec{
			FFmpeg: ffmpeg, Plan: plan, Out: job.Out, Encoder: encoder,
			Duration: time.Duration(planned.DurationSeconds * float64(time.Second)),
			Inputs:   repackage.Inputs{SourcePath: path, Subtitles: sidecars, FileIndex: repackage.FileIndexes(planned.Streams)},
		}, job.Progress)
		return s.appleBuildFailure(buildErr, path, job.Out)
	}
}

// appleBuildFailure turns what a run of ffmpeg said into a failure an app can act
// on. What ffmpeg printed names files, so it goes to the hub's log with the source
// and cache paths blanked, and not to the app.
func (s *Server) appleBuildFailure(err error, sourcePath, out string) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return err
	}
	detail := err.Error()
	var run *repackage.FFmpegError
	if errors.As(err, &run) {
		detail += ": " + run.Stderr
	}
	for _, path := range []string{sourcePath, out, filepath.Dir(out)} {
		if path != "" {
			detail = strings.ReplaceAll(detail, path, "<path>")
		}
	}
	slog.Warn("ffmpeg could not repackage a download for Apple", "detail", detail)
	return appleFailure("ffmpeg_failed", "The media PC could not convert this file.", true)
}

// fetchAppleSubtitle brings one text subtitle that lives beside the video across
// as an SRT file. Jellyfin hands it over in UTF-8 whatever the file's own encoding
// was (a Hebrew SRT is commonly Windows-1255), which is why it is asked for rather
// than read from disk.
func (s *Server) fetchAppleSubtitle(ctx context.Context, client *jellyfin.Client, grant offlineGrant, index int, dir string) (string, error) {
	body, err := readSubtitleText(ctx, client, grant.ItemID, grant.MediaSourceID, index, "srt")
	if err != nil {
		return "", err
	}
	if strings.TrimSpace(string(body)) == "" {
		// A subtitle with nothing in it still has to be a track, as the manifest
		// promised: a single empty cue keeps ffmpeg from refusing it.
		body = []byte("1\n00:00:00,000 --> 00:00:00,001\n \n")
	}
	file := filepath.Join(dir, "sub-"+strconv.Itoa(index)+".srt")
	return file, os.WriteFile(file, body, 0o600)
}

// appleEncoder is the H.264 encoder a conversion uses, found once.
func (s *Server) appleEncoder(ffmpeg string) repackage.Encoder {
	s.appleEncoderOnce.Do(func() {
		// Not the job's context: one that ends mid-look must not make the fallback
		// the choice for the life of the hub.
		s.appleEncoderChoice = s.pickEncoder(context.Background(), ffmpeg)
		slog.Info("Apple downloads convert video with", "encoder", s.appleEncoderChoice.Name)
	})
	return s.appleEncoderChoice
}

// ensureApple queues a grant's file unless it is already there.
func (s *Server) ensureApple(grant offlineGrant) repackage.Status {
	return s.repackager().Ensure(grant.ID, s.appleSpec(grant))
}

func offlineStatusOf(grant offlineGrant, status repackage.Status) OfflineStatus {
	out := OfflineStatus{
		GrantID: grant.ID, Format: offlineFormatApple, State: string(status.State), Percent: status.Percent,
		QueuePosition: status.QueuePosition, SizeBytes: status.SizeBytes, ETag: status.ETag,
	}
	if grant.Manifest.Apple != nil {
		out.EstimatedSizeBytes = grant.Manifest.Apple.EstimatedSizeBytes
	}
	if status.Err != nil {
		out.Error = &OfflineStatusError{Code: status.Err.Code, Message: status.Err.Message, Retryable: status.Err.Retryable}
	}
	return out
}

// handleOfflineStatus serves GET /v1/offline/grants/{grantId}/status. Asking
// about an Apple grant whose file is not there (never started, released, aged out
// or lost in a restart) queues it, so an app only ever has to ask.
func (s *Server) handleOfflineStatus(w http.ResponseWriter, r *http.Request) {
	if !s.requireDownload(w, r) {
		return
	}
	grant, _, ok := s.offlineGrantForRequest(w, r, false)
	if !ok {
		return
	}
	if grant.Format != offlineFormatApple {
		size := grant.Manifest.Source.SizeBytes
		writeJSON(w, http.StatusOK, OfflineStatus{
			GrantID: grant.ID, Format: offlineFormatOriginal, State: string(repackage.StateReady), Percent: 100,
			EstimatedSizeBytes: size, SizeBytes: size,
		})
		return
	}
	if unavailable := s.appleUnavailable(false); unavailable != nil {
		writeError(w, r, http.StatusServiceUnavailable, *unavailable)
		return
	}
	writeJSON(w, http.StatusOK, offlineStatusOf(grant, s.ensureApple(grant)))
}

// handleOfflineRetry serves POST /v1/offline/grants/{grantId}/retry: a failed
// Apple item is queued again, anything else is left as it is.
func (s *Server) handleOfflineRetry(w http.ResponseWriter, r *http.Request) {
	if !s.requireDownload(w, r) {
		return
	}
	grant, _, ok := s.offlineGrantForRequest(w, r, false)
	if !ok {
		return
	}
	if grant.Format != offlineFormatApple {
		s.handleOfflineStatus(w, r)
		return
	}
	if unavailable := s.appleUnavailable(true); unavailable != nil {
		writeError(w, r, http.StatusServiceUnavailable, *unavailable)
		return
	}
	writeJSON(w, http.StatusOK, offlineStatusOf(grant, s.repackager().Retry(grant.ID, s.appleSpec(grant))))
}

// handleOfflineRelease serves DELETE /v1/offline/grants/{grantId}/media: the app
// has stored its copy, so the hub's can go. An expired grant may still release.
func (s *Server) handleOfflineRelease(w http.ResponseWriter, r *http.Request) {
	if !s.requireDownload(w, r) {
		return
	}
	grant, _, ok := s.offlineGrantForRequest(w, r, true)
	if !ok {
		return
	}
	if grant.Format == offlineFormatApple {
		if manager := s.repackager(); manager != nil {
			manager.Release(grant.ID)
		}
	}
	w.WriteHeader(http.StatusNoContent)
}

// serveAppleMedia answers the media route of an Apple grant: a conflict while the
// file is being made, then the MP4 by range.
func (s *Server) serveAppleMedia(w http.ResponseWriter, r *http.Request, grant offlineGrant) {
	if unavailable := s.appleUnavailable(false); unavailable != nil {
		writeError(w, r, http.StatusServiceUnavailable, *unavailable)
		return
	}
	status := s.ensureApple(grant)
	switch status.State {
	case repackage.StateFailed:
		code, message, retryable := "internal", "The file could not be prepared on the media PC.", true
		if status.Err != nil {
			code, message, retryable = status.Err.Code, status.Err.Message, status.Err.Retryable
		}
		writeError(w, r, http.StatusConflict, Error{Code: codeOfflineFailed, Reason: code, Message: message, Retryable: retryable})
		return
	case repackage.StateReady:
	default:
		writeError(w, r, http.StatusConflict, Error{
			Code: codeOfflinePreparing, Reason: string(status.State), Retryable: true, RetryAfterSeconds: appleRetryAfter,
			Message: "This download is still being prepared on the media PC.",
		})
		return
	}
	byteRange, _, ok := requireSingleByteRange(w, r)
	if !ok {
		return
	}
	if byteRange != "" {
		r.Header.Set("Range", byteRange)
	}
	artifact, found := s.repackager().Open(grant.ID)
	if !found {
		// Lost between the status and the open (released, or aged out): it is
		// queued again, and the app asks again.
		writeError(w, r, http.StatusConflict, Error{
			Code: codeOfflinePreparing, Reason: string(repackage.StateQueued), Retryable: true, RetryAfterSeconds: appleRetryAfter,
			Message: "This download is still being prepared on the media PC.",
		})
		return
	}
	defer artifact.Close()
	out, err := streamUntilStalled(w, defaultStallPolicy)
	if err != nil {
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "the download could not be started", Retryable: true})
		return
	}
	header := w.Header()
	header.Set("Content-Type", "video/mp4")
	header.Set("ETag", artifact.ETag)
	header.Set("Accept-Ranges", "bytes")
	header.Set("Cache-Control", "private, no-store")
	header.Set("X-Content-Type-Options", "nosniff")
	// No name and no time: a name would come out in a header, and the file's own
	// stays on the hub.
	http.ServeContent(out, r, "", time.Time{}, contextReadSeeker{ReadSeeker: artifact, ctx: r.Context()})
}

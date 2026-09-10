package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"ayaneohub/internal/adapters/jellyfin"
)

const (
	offlineBodyLimit = 128 << 10
	offlineGrantTTL  = 30 * 24 * time.Hour
	maxOfflineItems  = 300
)

var offlineClientKey = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,120}$`)

type OfflineSource struct {
	ID        string          `json:"id"`
	Name      string          `json:"name"`
	Container string          `json:"container"`
	MIMEType  string          `json:"mimeType"`
	SizeBytes int64           `json:"sizeBytes"`
	Bitrate   int             `json:"bitrate,omitempty"`
	Tracks    []PlaybackTrack `json:"tracks"`
}

type OfflineSelectionItem struct {
	Item               LibraryItem     `json:"item"`
	Sources            []OfflineSource `json:"sources"`
	EstimatedSizeBytes int64           `json:"estimatedSizeBytes"`
	Available          bool            `json:"available"`
}

type OfflineSelectionSeason struct {
	Season   LibraryItem            `json:"season"`
	Episodes []OfflineSelectionItem `json:"episodes"`
}

type OfflineSelectionResponse struct {
	Series             LibraryItem              `json:"series"`
	Seasons            []OfflineSelectionSeason `json:"seasons"`
	EpisodeCount       int                      `json:"episodeCount"`
	EstimatedSizeBytes int64                    `json:"estimatedSizeBytes"`
	PlayTargetID       string                   `json:"playTargetId,omitempty"`
}

type OfflinePrepareBody struct {
	BatchKey string               `json:"batchKey"`
	SeriesID string               `json:"seriesId,omitempty"`
	Items    []OfflinePrepareItem `json:"items"`
}

type OfflinePrepareItem struct {
	ClientItemKey string `json:"clientItemKey"`
	ItemID        string `json:"itemId"`
	MediaSourceID string `json:"mediaSourceId,omitempty"`
}

type OfflineSubtitle struct {
	Track PlaybackTrack `json:"track"`
	URL   string        `json:"url"`
}

type OfflineManifest struct {
	GrantID       string            `json:"grantId"`
	BatchKey      string            `json:"batchKey"`
	ClientItemKey string            `json:"clientItemKey"`
	ExpiresAt     int64             `json:"expiresAt"`
	Item          LibraryItem       `json:"item"`
	Source        OfflineSource     `json:"source"`
	MediaURL      string            `json:"mediaUrl"`
	Subtitles     []OfflineSubtitle `json:"subtitles"`
}

type OfflinePrepareResponse struct {
	BatchKey string            `json:"batchKey"`
	Items    []OfflineManifest `json:"items"`
}

type OfflineProgressSyncBody struct {
	Events []OfflineProgressEvent `json:"events"`
}

type OfflineProgressEvent struct {
	ClientEventKey string `json:"clientEventKey"`
	ItemID         string `json:"itemId"`
	PositionMillis int64  `json:"positionMillis"`
	DurationMillis int64  `json:"durationMillis"`
	Completed      bool   `json:"completed,omitempty"`
	OccurredAt     int64  `json:"occurredAt"`
}

type OfflineProgressResult struct {
	ClientEventKey       string `json:"clientEventKey"`
	ItemID               string `json:"itemId"`
	Status               string `json:"status"` // applied | server_newer | duplicate
	ServerPositionMillis int64  `json:"serverPositionMillis,omitempty"`
}

type OfflineProgressSyncResponse struct {
	Results []OfflineProgressResult `json:"results"`
}

func (s *Server) requireDownload(w http.ResponseWriter, r *http.Request) bool {
	if TokenFrom(r.Context()).HasScope("download") {
		return true
	}
	writeError(w, r, http.StatusForbidden, Error{
		Code: CodeForbiddenScope, Message: "this token cannot download media for offline use",
	})
	return false
}

func (s *Server) handleOfflineSelection(w http.ResponseWriter, r *http.Request) {
	if !s.requireDownload(w, r) {
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
	ctx, cancel := timeoutFor(r, 30*time.Second)
	defer cancel()
	series, err := client.Item(ctx, seriesID)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	if !strings.EqualFold(series.Type, "Series") {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "item is not a series"})
		return
	}
	seasons, err := client.Seasons(ctx, seriesID)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	episodes, err := client.EpisodesFrom(ctx, seriesID, "", maxOfflineItems)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	bySeason := make(map[string][]OfflineSelectionItem)
	response := OfflineSelectionResponse{Series: libraryItemFrom(*series), Seasons: []OfflineSelectionSeason{}}
	if target, targetErr := client.NextUpForSeries(ctx, seriesID, true); targetErr == nil && len(target.Items) > 0 {
		response.PlayTargetID = target.Items[0].ID
	}
	for _, episode := range episodes.Items {
		selection := offlineSelectionItem(episode)
		bySeason[episode.SeasonID] = append(bySeason[episode.SeasonID], selection)
		if selection.Available {
			response.EpisodeCount++
			response.EstimatedSizeBytes += selection.EstimatedSizeBytes
		}
	}
	for _, season := range seasons.Items {
		items := bySeason[season.ID]
		sort.SliceStable(items, func(i, j int) bool {
			return items[i].Item.IndexNumber < items[j].Item.IndexNumber
		})
		response.Seasons = append(response.Seasons, OfflineSelectionSeason{
			Season: libraryItemFrom(season), Episodes: items,
		})
		delete(bySeason, season.ID)
	}
	// Retain playable episodes whose season object was deleted or unavailable.
	for _, items := range bySeason {
		if len(items) == 0 {
			continue
		}
		response.Seasons = append(response.Seasons, OfflineSelectionSeason{
			Season:   LibraryItem{ID: items[0].Item.SeasonID, Type: "season", Title: "Other"},
			Episodes: items,
		})
	}
	writeJSON(w, http.StatusOK, response)
}

func offlineSelectionItem(item jellyfin.Item) OfflineSelectionItem {
	sources := offlineSources(item)
	size := int64(0)
	if len(sources) > 0 {
		size = sources[0].SizeBytes
	}
	return OfflineSelectionItem{
		Item: libraryItemFrom(item), Sources: sources,
		EstimatedSizeBytes: size, Available: len(sources) > 0 && size > 0,
	}
}

func offlineSources(item jellyfin.Item) []OfflineSource {
	out := make([]OfflineSource, 0, len(item.MediaSources))
	for _, source := range item.MediaSources {
		if source.ID == "" || source.Size <= 0 {
			continue
		}
		tracks := make([]PlaybackTrack, 0, len(source.MediaStreams))
		for _, stream := range source.MediaStreams {
			track := playbackTrack(stream, "")
			track.External = stream.IsExternal
			tracks = append(tracks, track)
		}
		out = append(out, OfflineSource{
			ID: source.ID, Name: source.Name, Container: firstContainer(source.Container),
			MIMEType: playbackMIME(source, ""), SizeBytes: source.Size,
			Bitrate: source.Bitrate, Tracks: tracks,
		})
	}
	return out
}

func (s *Server) handleOfflinePrepare(w http.ResponseWriter, r *http.Request) {
	if !s.requireDownload(w, r) {
		return
	}
	client, ok := s.jellyfinForRequest(w, r)
	if !ok {
		return
	}
	var body OfflinePrepareBody
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, offlineBodyLimit)).Decode(&body); err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid offline batch"})
		return
	}
	if !offlineClientKey.MatchString(body.BatchKey) || len(body.Items) == 0 || len(body.Items) > maxOfflineItems {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "offline batch is empty or invalid"})
		return
	}
	if body.SeriesID != "" && !isHex32(body.SeriesID) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad series id"})
		return
	}
	seen := make(map[string]bool)
	owner := TokenFrom(r.Context()).Label
	ctx, cancel := timeoutFor(r, 45*time.Second)
	defer cancel()
	response := OfflinePrepareResponse{BatchKey: body.BatchKey, Items: []OfflineManifest{}}
	created := []offlineGrant{}
	for _, request := range body.Items {
		if !isHex32(request.ItemID) || !offlineClientKey.MatchString(request.ClientItemKey) || seen[request.ItemID] {
			writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "offline item id or key is invalid"})
			return
		}
		seen[request.ItemID] = true
		if existing, found := s.offline.find(owner, client.UserID(), request.ClientItemKey); found &&
			existing.ItemID == request.ItemID &&
			(request.MediaSourceID == "" || existing.MediaSourceID == request.MediaSourceID) {
			response.Items = append(response.Items, existing.Manifest)
			continue
		}
		item, err := client.Item(ctx, request.ItemID)
		if err != nil {
			writeUpstreamError(w, r, "jellyfin", err)
			return
		}
		if !strings.EqualFold(item.Type, "Movie") && !strings.EqualFold(item.Type, "Episode") {
			writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "offline items must be movies or episodes"})
			return
		}
		if body.SeriesID != "" && item.SeriesID != body.SeriesID {
			writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "episode is outside the selected series"})
			return
		}
		grant, err := makeOfflineGrant(owner, client.UserID(), body.BatchKey, request.ClientItemKey, *item, request.MediaSourceID)
		if err != nil {
			writeError(w, r, http.StatusConflict, Error{Code: CodeInvalidRequest, Message: err.Error()})
			return
		}
		created = append(created, grant)
		response.Items = append(response.Items, grant.Manifest)
	}
	if err := s.offline.putMany(created); err != nil {
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "could not persist offline grants"})
		return
	}
	writeJSON(w, http.StatusOK, response)
}

func makeOfflineGrant(owner, userID, batchKey, clientItemKey string, item jellyfin.Item, wantedSource string) (offlineGrant, error) {
	var selected *jellyfin.MediaSource
	for i := range item.MediaSources {
		source := &item.MediaSources[i]
		if source.Size <= 0 || source.ID == "" {
			continue
		}
		if wantedSource == "" || source.ID == wantedSource {
			selected = source
			break
		}
	}
	if selected == nil {
		return offlineGrant{}, fmt.Errorf("the selected original media source is unavailable")
	}
	id, err := randomPlaybackID()
	if err != nil {
		return offlineGrant{}, err
	}
	expires := time.Now().Add(offlineGrantTTL).UnixMilli()
	resource := "/Videos/" + item.ID + "/stream?" + url.Values{
		"static": {"true"}, "mediaSourceId": {selected.ID},
	}.Encode()
	grant := offlineGrant{
		ID: id, Owner: owner, UserID: userID, BatchKey: batchKey,
		ClientItemKey: clientItemKey, ItemID: item.ID, SeriesID: item.SeriesID,
		MediaSourceID: selected.ID, Resource: resource, Subtitles: make(map[string]string),
		CreatedAt: time.Now().UnixMilli(), ExpiresAt: expires,
	}
	source := offlineSourceFrom(*selected)
	subtitles := []OfflineSubtitle{}
	for _, stream := range selected.MediaStreams {
		if !stream.IsExternal || !strings.EqualFold(stream.Type, "Subtitle") {
			continue
		}
		upstream := stream.DeliveryURL
		if upstream == "" || !validPlaybackResource(upstream, item.ID) {
			upstream = fmt.Sprintf("/Videos/%s/%s/Subtitles/%d/0/Stream.%s",
				item.ID, selected.ID, stream.Index, subtitleExtension(stream.Codec))
		}
		if sanitized, sanitizeErr := sanitizePlaybackResource(upstream); sanitizeErr == nil {
			upstream = sanitized
		}
		if !validPlaybackResource(upstream, item.ID) {
			continue
		}
		key := strconv.Itoa(stream.Index)
		grant.Subtitles[key] = upstream
		track := playbackTrack(stream, "")
		track.External = true
		subtitles = append(subtitles, OfflineSubtitle{
			Track: track,
			URL:   "/v1/offline/grants/" + id + "/subtitles/" + key,
		})
	}
	grant.Manifest = OfflineManifest{
		GrantID: id, BatchKey: batchKey, ClientItemKey: clientItemKey, ExpiresAt: expires,
		Item: libraryItemFrom(item), Source: source,
		MediaURL: "/v1/offline/grants/" + id + "/media", Subtitles: subtitles,
	}
	return grant, nil
}

func offlineSourceFrom(source jellyfin.MediaSource) OfflineSource {
	tracks := make([]PlaybackTrack, 0, len(source.MediaStreams))
	for _, stream := range source.MediaStreams {
		track := playbackTrack(stream, "")
		track.External = stream.IsExternal
		tracks = append(tracks, track)
	}
	return OfflineSource{
		ID: source.ID, Name: source.Name, Container: firstContainer(source.Container),
		MIMEType: playbackMIME(source, ""), SizeBytes: source.Size,
		Bitrate: source.Bitrate, Tracks: tracks,
	}
}

func (s *Server) offlineGrantForRequest(w http.ResponseWriter, r *http.Request, allowExpired bool) (offlineGrant, *jellyfin.Client, bool) {
	grant, found := s.offline.get(r.PathValue("grantId"))
	if !found || grant.Owner != TokenFrom(r.Context()).Label {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such offline grant"})
		return offlineGrant{}, nil, false
	}
	client, ok := s.jellyfinForRequest(w, r)
	if !ok {
		return offlineGrant{}, nil, false
	}
	if client.UserID() != grant.UserID {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such offline grant"})
		return offlineGrant{}, nil, false
	}
	if !allowExpired && time.Now().UnixMilli() >= grant.ExpiresAt {
		writeError(w, r, http.StatusGone, Error{Code: "grant_expired", Message: "offline grant expired; renew it and resume"})
		return offlineGrant{}, nil, false
	}
	return grant, client, true
}

func (s *Server) handleOfflineMedia(w http.ResponseWriter, r *http.Request) {
	if !s.requireDownload(w, r) {
		return
	}
	grant, client, ok := s.offlineGrantForRequest(w, r, false)
	if !ok {
		return
	}
	if !validPlaybackResource(grant.Resource, grant.ItemID) {
		writeError(w, r, http.StatusBadGateway, Error{Code: CodeUpstreamDown, Message: "stored offline resource is invalid"})
		return
	}
	s.proxyPlaybackResource(w, r, client, grant.Resource, false, grant.ItemID, grant.ID)
}

func (s *Server) handleOfflineSubtitle(w http.ResponseWriter, r *http.Request) {
	if !s.requireDownload(w, r) {
		return
	}
	grant, client, ok := s.offlineGrantForRequest(w, r, false)
	if !ok {
		return
	}
	resource := grant.Subtitles[r.PathValue("trackId")]
	if resource == "" || !validPlaybackResource(resource, grant.ItemID) {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such offline subtitle"})
		return
	}
	s.proxyPlaybackResource(w, r, client, resource, false, grant.ItemID, grant.ID)
}

func (s *Server) handleOfflineRenew(w http.ResponseWriter, r *http.Request) {
	if !s.requireDownload(w, r) {
		return
	}
	previous, client, ok := s.offlineGrantForRequest(w, r, true)
	if !ok {
		return
	}
	ctx, cancel := timeoutFor(r, 30*time.Second)
	defer cancel()
	item, err := client.Item(ctx, previous.ItemID)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	renewed, err := makeOfflineGrant(previous.Owner, previous.UserID, previous.BatchKey,
		previous.ClientItemKey, *item, previous.MediaSourceID)
	if err != nil || renewed.Manifest.Source.SizeBytes != previous.Manifest.Source.SizeBytes {
		writeError(w, r, http.StatusConflict, Error{Code: "source_changed", Message: "the Jellyfin media source changed; restart this item"})
		return
	}
	// Keep the stable grant URL so an in-flight queue row needs only an expiry update.
	renewed.ID = previous.ID
	renewed.CreatedAt = previous.CreatedAt
	renewed.Manifest.GrantID = previous.ID
	renewed.Manifest.MediaURL = "/v1/offline/grants/" + previous.ID + "/media"
	for index := range renewed.Manifest.Subtitles {
		trackID := renewed.Manifest.Subtitles[index].Track.Index
		renewed.Manifest.Subtitles[index].URL = fmt.Sprintf("/v1/offline/grants/%s/subtitles/%d", previous.ID, trackID)
	}
	if err := s.offline.put(renewed); err != nil {
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "could not persist renewed grant"})
		return
	}
	writeJSON(w, http.StatusOK, renewed.Manifest)
}

func (s *Server) handleOfflineProgressSync(w http.ResponseWriter, r *http.Request) {
	if !s.requireDownload(w, r) {
		return
	}
	client, ok := s.jellyfinForRequest(w, r)
	if !ok {
		return
	}
	var body OfflineProgressSyncBody
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, offlineBodyLimit)).Decode(&body); err != nil ||
		len(body.Events) == 0 || len(body.Events) > 100 {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid offline progress events"})
		return
	}
	ctx, cancel := timeoutFor(r, 45*time.Second)
	defer cancel()
	response := OfflineProgressSyncResponse{Results: []OfflineProgressResult{}}
	owner := TokenFrom(r.Context()).Label
	for _, event := range body.Events {
		if !offlineClientKey.MatchString(event.ClientEventKey) || !isHex32(event.ItemID) ||
			event.PositionMillis < 0 || event.DurationMillis <= 0 || event.OccurredAt <= 0 {
			writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid offline progress event"})
			return
		}
		receipt := owner + ":" + client.UserID() + ":" + event.ClientEventKey
		result := OfflineProgressResult{ClientEventKey: event.ClientEventKey, ItemID: event.ItemID}
		if s.offline.wasSynced(receipt) {
			result.Status = "duplicate"
			response.Results = append(response.Results, result)
			continue
		}
		item, err := client.Item(ctx, event.ItemID)
		if err != nil {
			writeUpstreamError(w, r, "jellyfin", err)
			return
		}
		serverTime := int64(0)
		if item.UserData != nil && item.UserData.LastPlayedDate != "" {
			if parsed, parseErr := time.Parse(time.RFC3339Nano, item.UserData.LastPlayedDate); parseErr == nil {
				serverTime = parsed.UnixMilli()
			}
		}
		result.ServerPositionMillis = int64(item.PositionSeconds()) * 1000
		if serverTime > event.OccurredAt {
			result.Status = "server_newer"
			if err := s.offline.markSynced(receipt, time.Now().UnixMilli()); err != nil {
				writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "could not persist sync receipt"})
				return
			}
			response.Results = append(response.Results, result)
			continue
		}
		position := event.PositionMillis
		if event.Completed {
			position = event.DurationMillis
		}
		playSession, _ := randomPlaybackID()
		playbackClient := client.WithPlaybackDevice("pocketds-offline", "AYANEO Pocket DS Offline", Version)
		if err := playbackClient.SendPlaybackEvent(ctx, "/Sessions/Playing/Stopped", jellyfin.PlaybackEvent{
			ItemID: event.ItemID, PlaySessionID: playSession,
			PositionTicks: position * 10_000, PlayMethod: "DirectPlay", CanSeek: true,
		}); err != nil {
			writeUpstreamError(w, r, "jellyfin", err)
			return
		}
		if err := s.offline.markSynced(receipt, time.Now().UnixMilli()); err != nil {
			writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "could not persist sync receipt"})
			return
		}
		result.Status = "applied"
		response.Results = append(response.Results, result)
	}
	writeJSON(w, http.StatusOK, response)
}

package api

import (
	"context"
	"fmt"
	"hash/fnv"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"

	"ayaneohub/internal/adapters/arr"
	"ayaneohub/internal/adapters/bazarr"
	"ayaneohub/internal/cache"
)

const maxNotificationHistoryLimit = 100

type notificationLimits struct {
	Sonarr int
	Radarr int
	Bazarr int
}

type NotificationItem struct {
	ID         string `json:"id"`
	Service    string `json:"service"`
	Kind       string `json:"kind"`
	Severity   string `json:"severity"` // info | success | warning | error
	Title      string `json:"title"`
	Detail     string `json:"detail,omitempty"`
	OccurredAt string `json:"occurredAt,omitempty"`
	TimeLabel  string `json:"timeLabel,omitempty"`
	Active     bool   `json:"active,omitempty"`
}

type NotificationSection struct {
	Service string             `json:"service"`
	State   string             `json:"state"` // up | degraded | unavailable | disabled
	Items   []NotificationItem `json:"items"`
}

type notificationsSnapshot struct {
	GeneratedAt    time.Time             `json:"generatedAt"`
	AttentionCount int                   `json:"attentionCount"`
	Sections       []NotificationSection `json:"sections"`
	Partial        []Partial             `json:"partial"`
}

type NotificationsResponse struct {
	notificationsSnapshot
	Cache CacheInfo `json:"cache"`
}

func (s *Server) handleNotifications(w http.ResponseWriter, r *http.Request) {
	limits, err := parseNotificationLimits(r)
	if err != nil {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: err.Error(),
		})
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()

	cacheKey := fmt.Sprintf("notifications:%d:%d:%d", limits.Sonarr, limits.Radarr, limits.Bazarr)
	snapshot, meta, err := cache.Fetch(ctx, s.cache, cacheKey, cache.Notifications,
		func(ctx context.Context) (notificationsSnapshot, error) {
			return s.gatherNotifications(ctx, limits), nil
		})
	if err != nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Message: "could not read service notifications", Retryable: true,
		})
		return
	}
	writeJSON(w, http.StatusOK, NotificationsResponse{
		notificationsSnapshot: snapshot,
		Cache:                 cacheInfoFrom(meta),
	})
}

func parseNotificationLimits(r *http.Request) (notificationLimits, error) {
	limits := notificationLimits{Sonarr: 60, Radarr: 20, Bazarr: 40}
	values := []struct {
		name   string
		target *int
	}{
		{"sonarrLimit", &limits.Sonarr},
		{"radarrLimit", &limits.Radarr},
		{"bazarrLimit", &limits.Bazarr},
	}
	for _, value := range values {
		raw := strings.TrimSpace(r.URL.Query().Get(value.name))
		if raw == "" {
			continue
		}
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed < 1 || parsed > maxNotificationHistoryLimit {
			return notificationLimits{}, fmt.Errorf("%s must be between 1 and %d", value.name, maxNotificationHistoryLimit)
		}
		*value.target = parsed
	}
	return limits, nil
}

func (s *Server) gatherNotifications(ctx context.Context, limits notificationLimits) notificationsSnapshot {
	sections := make([]NotificationSection, 3)
	partials := make([][]Partial, 3)
	services := []string{"sonarr", "radarr", "bazarr"}
	var wait sync.WaitGroup
	for index, service := range services {
		wait.Add(1)
		go func(index int, service string) {
			defer wait.Done()
			limit := map[string]int{"sonarr": limits.Sonarr, "radarr": limits.Radarr, "bazarr": limits.Bazarr}[service]
			sections[index], partials[index] = s.notificationSection(ctx, service, limit)
		}(index, service)
	}
	wait.Wait()

	out := notificationsSnapshot{
		GeneratedAt: time.Now().UTC(),
		Sections:    sections,
		Partial:     []Partial{},
	}
	for index := range sections {
		out.Partial = append(out.Partial, partials[index]...)
		if sections[index].State == "unavailable" {
			out.AttentionCount++
		}
		for _, item := range sections[index].Items {
			if item.Active && (item.Severity == "warning" || item.Severity == "error") {
				out.AttentionCount++
			}
		}
	}
	return out
}

func (s *Server) notificationSection(ctx context.Context, service string, limit int) (NotificationSection, []Partial) {
	section := NotificationSection{Service: service, State: "disabled", Items: []NotificationItem{}}
	configured, exists := s.cfg.Services[service]
	if !exists || !configured.Enabled {
		return section, nil
	}

	if service == "bazarr" {
		if s.bazarr == nil {
			section.State = "unavailable"
			return section, []Partial{notificationPartial(service, "adapter unavailable")}
		}
		return loadBazarrNotifications(ctx, s.bazarr, limit)
	}
	client := s.arrs[service]
	if client == nil {
		section.State = "unavailable"
		return section, []Partial{notificationPartial(service, "adapter unavailable")}
	}
	return loadArrNotifications(ctx, service, client, limit)
}

func loadArrNotifications(ctx context.Context, service string, client *arr.Client, limit int) (NotificationSection, []Partial) {
	section := NotificationSection{Service: service, State: "up", Items: []NotificationItem{}}
	var history []arr.HistoryRecord
	var health []arr.HealthCheck
	var historyErr, healthErr error
	var wait sync.WaitGroup
	wait.Add(2)
	go func() {
		defer wait.Done()
		history, historyErr = client.History(ctx, limit)
	}()
	go func() {
		defer wait.Done()
		health, healthErr = client.Health(ctx)
	}()
	wait.Wait()

	partial := []Partial{}
	if historyErr != nil {
		partial = append(partial, notificationPartial(service, "history unavailable"))
	}
	if healthErr != nil {
		partial = append(partial, notificationPartial(service, "health unavailable"))
	}
	section.State = notificationState(historyErr, healthErr)
	for _, issue := range health {
		section.Items = append(section.Items, arrHealthNotification(service, issue))
	}
	for _, record := range history {
		section.Items = append(section.Items, arrHistoryNotification(service, record))
	}
	return section, partial
}

func loadBazarrNotifications(ctx context.Context, client *bazarr.Client, limit int) (NotificationSection, []Partial) {
	section := NotificationSection{Service: "bazarr", State: "up", Items: []NotificationItem{}}
	var history bazarr.History
	var health []bazarr.HealthIssue
	var historyErr, healthErr error
	var wait sync.WaitGroup
	wait.Add(2)
	go func() {
		defer wait.Done()
		history, historyErr = client.History(ctx, limit)
	}()
	go func() {
		defer wait.Done()
		health, healthErr = client.Health(ctx)
	}()
	wait.Wait()

	partial := []Partial{}
	if historyErr != nil {
		partial = append(partial, notificationPartial("bazarr", "history unavailable"))
	}
	if healthErr != nil {
		partial = append(partial, notificationPartial("bazarr", "health unavailable"))
	}
	section.State = notificationState(historyErr, healthErr)
	for _, issue := range health {
		section.Items = append(section.Items, NotificationItem{
			ID: stableNoticeID("bazarr:health", issue.Object, issue.Issue), Service: "bazarr", Kind: "health",
			Severity: "warning", Title: textOr(issue.Object, "Bazarr needs attention"),
			Detail: issue.Issue, Active: true,
		})
	}

	historyItems := make([]NotificationItem, 0, len(history.Episodes)+len(history.Movies))
	for _, record := range history.Episodes {
		historyItems = append(historyItems, bazarrEpisodeNotification(record))
	}
	for _, record := range history.Movies {
		historyItems = append(historyItems, bazarrMovieNotification(record))
	}
	sort.SliceStable(historyItems, func(left, right int) bool {
		return parseNoticeTime(historyItems[left].OccurredAt).After(
			parseNoticeTime(historyItems[right].OccurredAt),
		)
	})
	if len(historyItems) > limit {
		historyItems = historyItems[:limit]
	}
	section.Items = append(section.Items, historyItems...)
	return section, partial
}

func notificationState(historyErr, healthErr error) string {
	if historyErr != nil && healthErr != nil {
		return "unavailable"
	}
	if historyErr != nil || healthErr != nil {
		return "degraded"
	}
	return "up"
}

func notificationPartial(service, message string) Partial {
	return Partial{
		Service: service, Reason: "unreachable", Affects: []string{"notifications"},
		Message: message,
	}
}

func arrHealthNotification(service string, check arr.HealthCheck) NotificationItem {
	severity := strings.ToLower(check.Type)
	if severity != "error" && severity != "warning" {
		severity = "warning"
	}
	return NotificationItem{
		ID: stableNoticeID(service+":health", check.Source, check.Type, check.Message), Service: service, Kind: "health",
		Severity: severity, Title: textOr(check.Source, titleCase(service)+" needs attention"),
		Detail: check.Message, Active: true,
	}
}

func arrHistoryNotification(service string, record arr.HistoryRecord) NotificationItem {
	label, severity := arrEventPresentation(record.EventType)
	title := arrDisplayTitle(service, record)
	if title == "" {
		title = textOr(record.SourceTitle, titleCase(service)+" activity")
	}
	detail := firstNonEmpty(record.Data["message"], record.Data["reason"])
	if detail == "" {
		detail = joinDistinct(" · ", record.Quality.Quality.Name, record.Data["indexer"], record.SourceTitle)
	}
	return NotificationItem{
		ID: service + ":history:" + itoa(record.ID), Service: service,
		Kind: record.EventType, Severity: severity, Title: title + " · " + label,
		Detail: detail, OccurredAt: record.Date,
	}
}

func arrDisplayTitle(service string, record arr.HistoryRecord) string {
	if service == "radarr" && record.Movie != nil {
		return record.Movie.Title
	}
	if record.Series == nil {
		return ""
	}
	title := record.Series.Title
	if record.Episode != nil {
		title += " " + formatEpisode(record.Episode.SeasonNumber, record.Episode.EpisodeNumber)
		if record.Episode.Title != "" {
			title += " · " + record.Episode.Title
		}
	}
	return title
}

func arrEventPresentation(event string) (string, string) {
	switch strings.ToLower(event) {
	case "grabbed":
		return "Grabbed", "info"
	case "downloadfolderimported":
		return "Imported", "success"
	case "downloadfailed":
		return "Download failed", "error"
	case "downloadignored":
		return "Download ignored", "warning"
	case "episodefiledeleted", "moviefiledeleted":
		return "File deleted", "warning"
	case "episodefilerenamed", "moviefilerenamed":
		return "Renamed", "info"
	}
	return humanizeIdentifier(event), "info"
}

func bazarrEpisodeNotification(record bazarr.EpisodeHistory) NotificationItem {
	label, severity := bazarrActionPresentation(record.Action)
	base := strings.TrimSpace(joinDistinct(" ", record.SeriesTitle, record.EpisodeNumber))
	if record.EpisodeTitle != "" {
		base = joinDistinct(" · ", base, record.EpisodeTitle)
	}
	return NotificationItem{
		ID: stableNoticeID("bazarr:episode", strconv.Itoa(record.ID), strconv.Itoa(record.Action),
			record.ParsedTimestamp, record.SeriesTitle, record.EpisodeNumber, record.EpisodeTitle,
			record.Language.Code2, record.Provider, record.Description),
		Service: "bazarr", Kind: "subtitle", Severity: severity,
		Title:      textOr(base, "Episode subtitles") + " · " + label,
		Detail:     bazarrDetail(record.Language, record.Provider, record.Score, record.Description),
		OccurredAt: bazarrTime(record.ParsedTimestamp), TimeLabel: record.Timestamp,
	}
}

func bazarrMovieNotification(record bazarr.MovieHistory) NotificationItem {
	label, severity := bazarrActionPresentation(record.Action)
	return NotificationItem{
		ID: stableNoticeID("bazarr:movie", strconv.Itoa(record.ID), strconv.Itoa(record.Action),
			record.ParsedTimestamp, record.Title, record.Language.Code2, record.Provider, record.Description),
		Service: "bazarr", Kind: "subtitle", Severity: severity,
		Title:      textOr(record.Title, "Movie subtitles") + " · " + label,
		Detail:     bazarrDetail(record.Language, record.Provider, record.Score, record.Description),
		OccurredAt: bazarrTime(record.ParsedTimestamp), TimeLabel: record.Timestamp,
	}
}

func stableNoticeID(prefix string, parts ...string) string {
	hash := fnv.New64a()
	for _, part := range parts {
		_, _ = hash.Write([]byte(part))
		_, _ = hash.Write([]byte{0})
	}
	return prefix + ":" + strconv.FormatUint(hash.Sum64(), 16)
}

func bazarrActionPresentation(action int) (string, string) {
	switch action {
	case 0:
		return "Subtitle deleted", "warning"
	case 1:
		return "Subtitle downloaded", "success"
	case 2:
		return "Subtitle downloaded manually", "success"
	case 3:
		return "Subtitle upgraded", "success"
	case 4:
		return "Subtitle uploaded", "success"
	case 5:
		return "Subtitle synced", "info"
	case 6:
		return "Subtitle translated", "success"
	default:
		return "Subtitle activity", "info"
	}
}

func bazarrDetail(language bazarr.Language, provider, score, description string) string {
	languageName := firstNonEmpty(language.Name, language.Code2, language.Code3)
	return joinDistinct(" · ", languageName, provider, score, description)
}

func bazarrTime(value string) string {
	if parsed := parseBazarrLocalTime(value, time.Now()); !parsed.IsZero() {
		return parsed.UTC().Format(time.RFC3339)
	}
	return ""
}

func parseBazarrLocalTime(value string, now time.Time) time.Time {
	value = strings.TrimSpace(value)
	if value == "" {
		return time.Time{}
	}
	layouts := []string{
		"01/02/06 15:04:05", "1/2/06 15:04:05", "02/01/06 15:04:05", "2/1/06 15:04:05",
		"01/02/2006 15:04:05", "1/2/2006 15:04:05", "02/01/2006 15:04:05", "2/1/2006 15:04:05",
		"01/02/06 3:04:05 PM", "1/2/06 3:04:05 PM", "2006-01-02 15:04:05",
	}
	best := time.Time{}
	bestDistance := time.Duration(1<<63 - 1)
	for _, layout := range layouts {
		parsed, err := time.ParseInLocation(layout, value, now.Location())
		if err != nil || parsed.After(now.Add(24*time.Hour)) {
			continue
		}
		distance := now.Sub(parsed)
		if distance < bestDistance {
			best, bestDistance = parsed, distance
		}
	}
	return best
}

func parseNoticeTime(value string) time.Time {
	parsed, _ := time.Parse(time.RFC3339, value)
	return parsed
}

func formatEpisode(season, episode int) string {
	return fmt.Sprintf("S%02dE%02d", season, episode)
}

func humanizeIdentifier(value string) string {
	if value == "" {
		return "Activity"
	}
	var out []rune
	for index, char := range value {
		if index > 0 && unicode.IsUpper(char) {
			out = append(out, ' ')
		}
		out = append(out, char)
	}
	return titleCase(string(out))
}

func titleCase(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return ""
	}
	runes := []rune(value)
	runes[0] = unicode.ToUpper(runes[0])
	return string(runes)
}

func textOr(value, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return strings.TrimSpace(value)
}

func joinDistinct(separator string, values ...string) string {
	seen := map[string]bool{}
	joined := []string{}
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" || seen[value] {
			continue
		}
		seen[value] = true
		joined = append(joined, value)
	}
	return strings.Join(joined, separator)
}

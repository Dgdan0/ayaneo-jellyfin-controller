package api

import (
	"context"
	"fmt"
	"math"
	"sort"
	"strings"
	"sync"

	"ayaneohub/internal/adapters/bookkeeprr"
	"ayaneohub/internal/adapters/kavita"
	"ayaneohub/internal/adapters/storyteller"
)

func loadBookKeeprrNotifications(ctx context.Context, client *bookkeeprr.Client, limit int) (NotificationSection, []Partial) {
	section := NotificationSection{Service: "bookkeeprr", State: "up", Items: []NotificationItem{}}
	var downloads *bookkeeprr.DownloadsResponse
	var series *bookkeeprr.SeriesList
	var downloadErr, seriesErr error
	var wait sync.WaitGroup
	wait.Add(2)
	go func() { defer wait.Done(); downloads, downloadErr = client.Downloads(ctx) }()
	go func() { defer wait.Done(); series, seriesErr = client.Series(ctx, 1, 100) }()
	wait.Wait()
	section.State = notificationState(downloadErr, seriesErr)
	partials := []Partial{}
	if downloadErr != nil {
		partials = append(partials, notificationPartial("bookkeeprr", "downloads unavailable"))
	}
	if seriesErr != nil {
		partials = append(partials, notificationPartial("bookkeeprr", "wanted titles unavailable"))
	}
	covered := map[int]bool{}
	if downloads != nil {
		for _, download := range downloads.Downloads {
			section.Items = append(section.Items, bookDownloadNotification(download))
			if download.Series != nil {
				covered[download.Series.ID] = true
			}
		}
	}
	if series != nil {
		for _, record := range series.Rows {
			if notice, ok := bookWantedNotification(record, covered[record.ID]); ok {
				section.Items = append(section.Items, notice)
			}
		}
	}
	sort.SliceStable(section.Items, func(i, j int) bool {
		if section.Items[i].Active != section.Items[j].Active {
			return section.Items[i].Active
		}
		return section.Items[i].OccurredAt > section.Items[j].OccurredAt
	})
	if len(section.Items) > limit {
		section.Items = section.Items[:limit]
	}
	return section, partials
}

func bookDownloadNotification(download bookkeeprr.Download) NotificationItem {
	status := strings.ToLower(strings.TrimSpace(download.Status))
	label, severity, active := "Queued", "info", true
	switch status {
	case "downloading":
		label = "Downloading"
	case "completed", "import_pending":
		label = "Downloaded · importing"
	case "importing":
		label = "Adding to reading library"
	case "imported":
		label, severity, active = "Imported by BookKeeprr", "success", false
	case "superseded":
		label, active = "Replaced by another release", false
	case "failed":
		label, severity = "Download failed", "error"
	case "retry_pending":
		label, severity = "Retry pending", "warning"
	case "cancelled", "canceled":
		label, active = "Cancelled", false
	}
	title := "Reading download"
	if download.Series != nil && download.Series.Title != "" {
		title = download.Series.Title
	}
	detail := ""
	if download.Release != nil {
		detail = download.Release.Title
	}
	if download.Progress != nil && active && status == "downloading" {
		detail = fmt.Sprintf("%d%% · %s", int(math.Round(math.Max(0, math.Min(1, *download.Progress))*100)), detail)
	}
	if download.Error != nil && *download.Error != "" {
		detail = *download.Error
	}
	when := download.AddedAt
	if status == "imported" && download.ImportedAt != nil {
		when = *download.ImportedAt
	}
	return NotificationItem{ID: fmt.Sprintf("bookkeeprr:download:%d:%s", download.ID, status), Service: "bookkeeprr",
		Kind: "download", Severity: severity, Title: title + " · " + label, Detail: detail,
		OccurredAt: when, Active: active}
}

func bookWantedNotification(record bookkeeprr.SeriesRecord, hasDownload bool) (NotificationItem, bool) {
	if record.Downloaded > 0 || hasDownload || record.ID <= 0 || (!record.Monitored && record.Monitoring != "none") {
		return NotificationItem{}, false
	}
	title := record.Title
	if title == "" {
		title = record.TitleEnglish
	}
	if title == "" {
		return NotificationItem{}, false
	}
	if record.Monitoring == "none" {
		return NotificationItem{ID: fmt.Sprintf("bookkeeprr:choice:%d", record.ID), Service: "bookkeeprr",
			Kind: "choice", Severity: "warning", Title: title + " · Choose a release",
			Detail: "Search available torrents and select one to download.", OccurredAt: record.AddedAt, Active: true}, true
	}
	return NotificationItem{ID: fmt.Sprintf("bookkeeprr:wanted:%d", record.ID), Service: "bookkeeprr",
		Kind: "wanted", Severity: "warning", Title: title + " · Searching for a release",
		Detail: "BookKeeprr is monitoring this title; no download has been grabbed.", OccurredAt: record.AddedAt, Active: true}, true
}

func loadStorytellerNotifications(ctx context.Context, client *storyteller.Client, limit int) (NotificationSection, []Partial) {
	section := NotificationSection{Service: "storyteller", State: "up", Items: []NotificationItem{}}
	books, err := client.Books(ctx)
	if err != nil {
		section.State = "unavailable"
		return section, []Partial{notificationPartial("storyteller", "alignment status unavailable")}
	}
	for _, book := range books {
		if notice, ok := storytellerLibraryNotification(book); ok {
			section.Items = append(section.Items, notice)
		}
		if notice, ok := readaloudNotification(book); ok {
			section.Items = append(section.Items, notice)
		}
	}
	sort.SliceStable(section.Items, func(i, j int) bool {
		if section.Items[i].Active != section.Items[j].Active {
			return section.Items[i].Active
		}
		return section.Items[i].OccurredAt > section.Items[j].OccurredAt
	})
	if len(section.Items) > limit {
		section.Items = section.Items[:limit]
	}
	return section, nil
}

func storytellerLibraryNotification(book storyteller.Book) (NotificationItem, bool) {
	if book.ID <= 0 || book.Title == "" || book.CreatedAt == "" || (book.Ebook == nil && book.Audiobook == nil) {
		return NotificationItem{}, false
	}
	format := "Ebook"
	if book.Ebook != nil && book.Audiobook != nil {
		format = "Ebook and audiobook"
	} else if book.Audiobook != nil {
		format = "Audiobook"
	}
	return NotificationItem{ID: fmt.Sprintf("storyteller:library:%d", book.ID), Service: "storyteller", Kind: "library",
		Severity: "success", Title: book.Title + " · Ready in Library", Detail: format + " available in Storyteller.",
		OccurredAt: book.CreatedAt, Active: false}, true
}

func loadKavitaNotifications(ctx context.Context, client *kavita.Client, limit int) (NotificationSection, []Partial) {
	section := NotificationSection{Service: "kavita", State: "up", Items: []NotificationItem{}}
	libraries, err := client.Libraries(ctx)
	if err != nil {
		section.State = "unavailable"
		return section, []Partial{notificationPartial("kavita", "reading library unavailable")}
	}
	partials := []Partial{}
	for _, library := range libraries {
		page, err := client.Series(ctx, library.ID, 1, 20, kavita.SortAdded, kavita.Descending)
		if err != nil {
			partials = append(partials, notificationPartial("kavita", "recent "+library.Name+" items unavailable"))
			continue
		}
		for _, series := range page.Items {
			if series.ID <= 0 || series.Name == "" || series.Created == "" {
				continue
			}
			section.Items = append(section.Items, NotificationItem{
				ID: fmt.Sprintf("kavita:library:%d", series.ID), Service: "kavita", Kind: "library",
				Severity: "success", Title: series.Name + " · Ready in Library",
				Detail: "Available in " + library.Name + ".", OccurredAt: series.Created,
			})
		}
	}
	if len(partials) > 0 {
		section.State = "degraded"
	}
	sort.SliceStable(section.Items, func(i, j int) bool { return section.Items[i].OccurredAt > section.Items[j].OccurredAt })
	if len(section.Items) > limit {
		section.Items = section.Items[:limit]
	}
	return section, partials
}

func readaloudNotification(book storyteller.Book) (NotificationItem, bool) {
	if book.Readaloud == nil || book.ID <= 0 || book.Readaloud.Missing {
		return NotificationItem{}, false
	}
	status := strings.ToUpper(strings.TrimSpace(book.Readaloud.Status))
	label, severity, active := "Read along ready", "success", false
	detail := "Ebook and audiobook are synchronized."
	switch status {
	case "", "ALIGNED":
	case "ERROR", "STOPPED":
		label, severity, active = "Alignment needs attention", "error", true
		detail = "Open Storyteller to inspect or retry this alignment."
	default:
		label, severity, active = "Aligning audiobook", "info", true
		detail = strings.ReplaceAll(strings.ToLower(book.Readaloud.CurrentStage), "_", " ")
		if detail == "" {
			detail = "Storyteller is processing this book."
		} else if book.Readaloud.StageProgress > 0 && book.Readaloud.StageProgress < 1 {
			detail += fmt.Sprintf(" · %d%% of this stage", int(math.Round(book.Readaloud.StageProgress*100)))
		}
		if book.Readaloud.QueuePosition > 0 {
			detail = fmt.Sprintf("Queue position %d · %s", book.Readaloud.QueuePosition, detail)
		}
	}
	return NotificationItem{ID: fmt.Sprintf("storyteller:alignment:%d:%s", book.ID, status), Service: "storyteller",
		Kind: "alignment", Severity: severity, Title: book.Title + " · " + label, Detail: detail,
		OccurredAt: book.UpdatedAt, Active: active}, true
}

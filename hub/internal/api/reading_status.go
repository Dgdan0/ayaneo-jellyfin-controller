package api

// A book's reading status (#63): one word for where a book stands for a person, which
// the apps show on the book's page, on its cover (the tick) and in its menu, and on which
// Continue reading and Home decide whether a book is theirs to show.
//
// It is built on what the hub already keeps of "you" (reading_you.go): the month finished,
// the times read, the Goodreads import's shelf, and now what the person chose from an
// app. A book has one status at a time, and the person's own choice outranks every
// guess:
//
//	want         on the person's Want to read list
//	reading      being read: Continue reading and Home show it
//	finished     read to the end, marked finished, or imported as read: the cover's tick
//	not-reading  put down. The book's place is kept; it just is not shown as being read
//
// Where the person has chosen nothing, the status is worked out, in this order: finished
// (a month finished, an import that says read, or a place at the end), then the import's
// to-read shelf, then reading (a place begun, or an import that says currently reading).
// A book with none of these has no status, and the work says nothing of it.
//
// Additive: a work served before this had no "status", and an app that does not know it
// ignores it.

import (
	"net/http"
)

const (
	statusWant       = "want"
	statusReading    = "reading"
	statusFinished   = "finished"
	statusNotReading = "not-reading"
)

// validReadingStatus is whether a word is one a person can choose.
func validReadingStatus(value string) bool {
	switch value {
	case statusWant, statusReading, statusFinished, statusNotReading:
		return true
	}
	return false
}

// effectiveReadingStatus is the one status of a book for a person: [you] is what the hub
// holds of them for it (nil when nothing), [progress] where they are in it (nil when
// unknown). Empty when there is nothing to say.
func effectiveReadingStatus(you *ReadingYou, progress *ReadingProgress) string {
	if you != nil && validReadingStatus(you.Chosen) {
		return you.Chosen
	}
	if you != nil && (you.Finished != "" || you.Status == "read") {
		return statusFinished
	}
	if progress != nil && progress.Completed {
		return statusFinished
	}
	if you != nil && you.Status == "to-read" {
		return statusWant
	}
	if progress != nil && progress.Percentage > 0 {
		return statusReading
	}
	if you != nil && you.Status == "currently-reading" {
		return statusReading
	}
	return ""
}

// stampReadingStatus gives each of works, and the books inside a series among them, their
// status for the profile the request is for. Nothing is added where the profile cannot be
// told, or there is nothing to say.
func (s *Server) stampReadingStatus(r *http.Request, works []ReadingWork) {
	profile, ok := s.readingProfile(r)
	if !ok || s.readingYou == nil {
		return
	}
	statusOf := func(workID string, progress *ReadingProgress) string {
		if workID == "" {
			return ""
		}
		record, edit := s.readingYou.view(profile, workID)
		return effectiveReadingStatus(mergeYou(record, edit), progress)
	}
	for i := range works {
		work := &works[i]
		// A series is not a book: it has no status of its own, its books do.
		if work.EntityType != "collection" {
			work.Status = statusOf(work.ID, work.Progress)
		}
		for j := range work.Sections {
			items := work.Sections[j].Items
			for k := range items {
				items[k].Status = statusOf(items[k].WorkID, items[k].Progress)
			}
		}
	}
}

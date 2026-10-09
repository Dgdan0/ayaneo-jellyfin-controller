package api

// Our read-along (#66). A book whose Storyteller uuid has a wordsync pack under /derived/readalong
// (readingdomain's readalong_pack.go) is read along by its corrected sentences and, for an app that
// asks, word by word:
//
//   - the audio manifest's narration is the pack's sentence set, so a place in the text and a moment
//     of the audiobook convert by the corrected times, and the manifest says `wordLevel: true` when
//     the word set can be served;
//   - `…/file?format=readaloud` (with or without `audio=omit`) is the edition with the sentence set's
//     SMIL in place of Storyteller's, or with `granularity=word` the word set's SMIL and text.
//
// A set is used only when it holds for the edition as it is (FindPack: passes, re-timed, built from
// this edition's size and time, not being written) and it narrates the same audio pieces as the
// edition, each ending at the same moment (overlayUsable): that is what the manifest maps onto the
// tracks, so a set that agrees with it plays from the same places. Anything else is today's edition,
// exactly, and deleting the pack folder is too.

import (
	"context"
	"log/slog"
	"strings"

	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/cache"
	readingdomain "ayaneohub/internal/reading"
)

// readalongPack is the book's pack for its read-along edition as it is (file), or nil. A pack that
// is there and does not hold is logged with why (never a path).
func (s *Server) readalongPack(book storyteller.Book, file readingdomain.MediaFile) *readingdomain.Pack {
	if strings.TrimSpace(book.UUID) == "" {
		return nil
	}
	pack, reason := readingdomain.FindPack(s.cfg.Server.MediaRemovalRoots, book.UUID, file, s.now())
	if pack == nil && reason != readingdomain.PackNone && reason != readingdomain.PackBadIdentity {
		slog.Info("read-along pack not used", "book", book.ID, "reason", reason)
	}
	if pack != nil {
		for granularity, why := range pack.Skipped {
			slog.Info("read-along pack set not used", "book", book.ID, "granularity", granularity, "reason", why)
		}
	}
	return pack
}

// narrationOf is what the edition narrates, as overlay rewrites it when there is one: read once per
// edition (path, size, time) and set (granularity, fingerprint), and kept.
func (s *Server) narrationOf(ctx context.Context, file readingdomain.MediaFile, overlay *readingdomain.Overlay) (*readingdomain.Alignment, error) {
	key := alignmentKey(file)
	if overlay != nil {
		key += "\x00" + overlay.Granularity + "\x00" + overlay.Fingerprint
	}
	narration, _, err := cache.Fetch(ctx, s.cache, key, cache.ReadingAlignment,
		func(fetchCtx context.Context) (*readingdomain.Alignment, error) {
			narration, err := s.readAlignment(contextReaderAt{ReaderAt: file, ctx: fetchCtx}, file.Size, overlay)
			if err == nil && fetchCtx.Err() != nil {
				// The edition's contents are read last and an edition without them is still
				// an edition, so a read cut short there would succeed without them and be
				// kept, for hours, as the edition's.
				return nil, fetchCtx.Err()
			}
			return narration, err
		})
	return narration, err
}

// packCheck is whether a set of a pack can stand in for the edition's narration, and why not.
type packCheck struct {
	usable bool
	reason string
}

// overlayUsable says whether a set of a pack reads and narrates the edition's audio pieces: the sentence
// set each ending at the same moment (SamePieces), the word set none past it (NarratesWithin). It is worked out
// once per edition and set and kept; a word set is read for it and not kept, since only the
// answer is needed (the app reads the words from its copy). Only the end of ctx is an error.
func (s *Server) overlayUsable(ctx context.Context, book int64, file readingdomain.MediaFile, overlay *readingdomain.Overlay) (bool, error) {
	if overlay == nil {
		return true, nil
	}
	key := "readalong-check:" + alignmentKey(file) + "\x00" + overlay.Granularity + "\x00" + overlay.Fingerprint
	check, _, err := cache.Fetch(ctx, s.cache, key, cache.ReadingAlignment,
		func(fetchCtx context.Context) (*packCheck, error) {
			refused := func(reason, detail string) (*packCheck, error) {
				slog.Info("read-along pack set not used", "book", book, "granularity", overlay.Granularity, "reason", reason, "detail", detail)
				return &packCheck{reason: reason}, nil
			}
			plain, err := s.narrationOf(fetchCtx, file, nil)
			if err != nil {
				if fetchCtx.Err() != nil {
					return nil, fetchCtx.Err()
				}
				return refused("edition_unreadable", "")
			}
			var set *readingdomain.Alignment
			if overlay.Granularity == readingdomain.GranularityWord {
				set, err = s.readAlignment(contextReaderAt{ReaderAt: file, ctx: fetchCtx}, file.Size, overlay)
			} else {
				set, err = s.narrationOf(fetchCtx, file, overlay)
			}
			if err != nil {
				if fetchCtx.Err() != nil {
					return nil, fetchCtx.Err()
				}
				return refused("unreadable", err.Error())
			}
			// The sentence set is what the manifest maps onto the tracks, so its pieces are the edition's exactly; the
			// word set plays from that mapping, so none of its pieces may run past where the edition's ends.
			fits := set.SamePieces(plain)
			if overlay.Granularity == readingdomain.GranularityWord {
				fits = set.NarratesWithin(plain)
			}
			if !fits {
				return refused("pieces_differ", "")
			}
			return &packCheck{usable: true}, nil
		})
	if err != nil {
		return false, err
	}
	return check.usable, nil
}

// copyOverlay is the set of the book's pack a read-along copy is made with for an app that asked
// for granularity ("" is the sentence set), or nil for the edition as it is. A word set that cannot
// be used falls back to the sentence set, and that to the edition. Only the end of ctx is an error.
func (s *Server) copyOverlay(ctx context.Context, book storyteller.Book, file readingdomain.MediaFile, granularity string) (*readingdomain.Overlay, error) {
	pack := s.readalongPack(book, file)
	if pack == nil {
		return nil, nil
	}
	var sets []*readingdomain.Overlay
	if granularity == readingdomain.GranularityWord {
		sets = append(sets, pack.Overlay(readingdomain.GranularityWord))
	}
	sets = append(sets, pack.Overlay(readingdomain.GranularitySentence))
	for _, set := range sets {
		if set == nil {
			continue
		}
		usable, err := s.overlayUsable(ctx, book.ID, file, set)
		if err != nil {
			return nil, err
		}
		if usable {
			return set, nil
		}
	}
	return nil, nil
}

// readingGranularity reads `granularity`: empty or "sentence" (today's apps, and the default), or
// "word". ok is false for anything else.
func readingGranularity(value string) (string, bool) {
	switch value {
	case "", readingdomain.GranularitySentence:
		return readingdomain.GranularitySentence, true
	case readingdomain.GranularityWord:
		return readingdomain.GranularityWord, true
	}
	return "", false
}

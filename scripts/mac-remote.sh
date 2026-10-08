#!/usr/bin/env bash
# Drives scripts/mac.sh on the MacBook from the Windows PC, over SSH (Git Bash).
# The Mac is a build machine here, the way the Pocket DS is an adb target for
# dev.sh: the code is edited in this checkout, and the Mac only builds and runs it.
#
#   scripts/mac-remote.sh sync              copy apple/ and scripts/mac.sh to the Mac
#   scripts/mac-remote.sh <mac.sh command>  sync, run scripts/mac.sh there, then copy
#                                           shots/apple/ back into this checkout
#     e.g.  test | build | sims [-demo] | shot [-demo] | mac [-demo] | logs | testflight
#
# testflight and testflight-notes <build> take the build's notes for the TestFlight
# app, NOTES="..." or NOTES_FILE=<a file here>; testflight starts nothing without them.
#
# The Mac side is a build copy, ~/Builds/ayaneo-jellyfin-controller: a plain
# directory, not a git checkout, so nothing there is ever committed or discarded,
# and the Mac's own checkout is left alone. Files go over as git sees them here,
# uncommitted edits included. A file deleted here is deleted there only if an
# earlier sync from this checkout put it there.
#
# The hub address and token stay in the Mac checkout's apple/dev.env. HUB_DEV_ENV
# points the build copy at that file, so the token never crosses the network.
#
# Needs Host "mac" in ~/.ssh/config with key login. On Windows the System32
# OpenSSH client is used: it is the one that reaches the 1Password SSH agent.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${MAC_HOST:-mac}"
REMOTE_DIR="${MAC_BUILD_DIR:-Builds/ayaneo-jellyfin-controller}"          # under the Mac home
DEV_ENV="${MAC_DEV_ENV:-Projects/ayaneo-jellyfin-controller/apple/dev.env}" # under the Mac home
MANIFEST="$ROOT/.tmp/mac-sync-manifest"
SSH=ssh
[[ -x /c/Windows/System32/OpenSSH/ssh.exe ]] && SSH=/c/Windows/System32/OpenSSH/ssh.exe

remote() { "$SSH" -o BatchMode=yes -o ServerAliveInterval=30 "$HOST" "$@"; }

sync() {
  mkdir -p "$(dirname "$MANIFEST")"
  local current="$MANIFEST.new"
  # Index entries deleted in the working tree are still listed, so keep only
  # files that exist.
  (cd "$ROOT" && git ls-files -co --exclude-standard -- apple scripts/mac.sh |
    while IFS= read -r f; do [[ -f "$f" ]] && printf '%s\n' "$f"; done | sort -u) > "$current"

  (cd "$ROOT" && tar -cf - -T "$current") |
    remote "mkdir -p ~/$REMOTE_DIR && tar -xf - -C ~/$REMOTE_DIR"

  if [[ -f "$MANIFEST" ]]; then
    local gone
    gone="$(comm -23 "$MANIFEST" "$current")"
    if [[ -n "$gone" ]]; then
      printf '%s\n' "$gone" | remote "cd ~/$REMOTE_DIR && while IFS= read -r f; do rm -f -- \"\$f\"; done"
      echo "removed $(printf '%s\n' "$gone" | wc -l | tr -d ' ') files deleted here"
    fi
  fi
  mv "$current" "$MANIFEST"
  echo "synced $(wc -l < "$MANIFEST" | tr -d ' ') files to $HOST:~/$REMOTE_DIR"
}

# Only the pictures this run made (newer than the marker `run` leaves): the
# folder keeps every earlier one too, and copying all of them back each time
# had grown to a minute a run.
fetch_shots() {
  if remote "test -d ~/$REMOTE_DIR/shots/apple"; then
    mkdir -p "$ROOT/shots"
    # Without these, macOS tar adds Finder metadata as ._ files beside each PNG.
    remote "cd ~/$REMOTE_DIR && find shots/apple -type f -newer .run-start > .run-files &&
      if [ -s .run-files ]; then COPYFILE_DISABLE=1 tar --no-mac-metadata --no-xattrs -cf - -T .run-files; fi" |
      (cd "$ROOT" && tar -xf - 2>/dev/null || true)
    echo "screenshots in $ROOT/shots/apple"
  fi
}

# testflight's notes, NOTES or NOTES_FILE: the text goes to the build copy
# through ssh's own input, so no quoting on the way can change a word of it,
# and mac.sh reads it there. False when there are none, and notes an earlier
# run left there are removed.
send_notes() {
  local text=""
  if [[ -n "${NOTES_FILE:-}" ]]; then
    [[ -f "$NOTES_FILE" ]] || { echo "no notes file $NOTES_FILE" >&2; exit 2; }
    text="$(tr -d '\r' < "$NOTES_FILE")"
  else
    text="${NOTES:-}"
  fi
  if [[ -z "${text//[[:space:]]/}" ]]; then
    remote "rm -f ~/$REMOTE_DIR/.testflight-notes"
    return 1
  fi
  printf '%s\n' "$text" | remote "cat > ~/$REMOTE_DIR/.testflight-notes"
}

run() {
  sync
  remote "touch ~/$REMOTE_DIR/.run-start"
  local notes=""
  case "${1:-}" in
    testflight|testflight-notes) if send_notes; then notes="\$HOME/$REMOTE_DIR/.testflight-notes"; fi ;;
  esac
  # A non-interactive SSH shell on the Mac does not read the login profile, so
  # Homebrew's tools (xcodegen) are not on its PATH.
  # HUB_SECTION (home, library, services, …) opens that section in Debug
  # builds, for screenshots of a screen other than the last one open, and
  # HUB_OPEN (continue, latest, …) opens that Home row's first title.
  # HUB_SIDE=books shows the Books side, and HUB_SHEET=profiles opens the
  # avatar's sheet. SHOT_WAIT gives a screen with artwork longer to load.
  # HUB_PLAY opens the player on an item (HUB_PLAY_EXIT leaves it after that
  # many seconds, HUB_PLAY_CHROME=pinned keeps its controls up, HUB_PLAY_TOUR=1
  # opens its panels in turn, HUB_PLAY_SUBTITLE=eng turns those subtitles on,
  # HUB_PLAY_SCRUB=100 holds a drag across the picture 100 seconds on).
  # HUB_READ=rw_demo_ff/rw_demo_ff-51 opens the comic reader, with -demo only
  # (HUB_READ_CHROME=pinned, HUB_READ_PAGE=<n>, HUB_READ_SHEET=display|keys|pages|end), and
  # HUB_BOOK=rw_demo_rr6/rr6 the ebook reader, with -demo only (HUB_BOOK_CHROME=pinned,
  # HUB_BOOK_AT=<percent>, HUB_BOOK_SHEET=menu|contents|bookmarks|appearance|keys, HUB_BOOK_SCROLL=1|0,
  # HUB_BOOK_READALONG=1 to read along).
  # HUB_TITLE=<item id>[|subtitles|removal] opens a library title and on to its subtitles or deletion
  # page, HUB_SUBTITLES=search searches and opens the first result, HUB_REMOVAL=confirm asks the alert.
  # SHOT_SIMS names the simulators to use, comma-separated (all three by
  # default), SHOT_STATE names the screenshots and SHOT_TIMES takes several,
  # that many seconds after launch. `turn landscape` turns the simulators, and
  # HUB_WIDTH lays the app out as narrow as an iPad's Split View. testflight
  # takes BUILD_NUMBER (the minute in UTC otherwise), TESTFLIGHT_PLATFORMS
  # ("iOS macOS"), TESTFLIGHT_WAIT_MINUTES and its notes (send_notes).
  remote "export PATH=/opt/homebrew/bin:\$PATH; cd ~/$REMOTE_DIR && \
    HUB_SECTION=$(printf '%q' "${HUB_SECTION:-}") HUB_OPEN=$(printf '%q' "${HUB_OPEN:-}") \
    HUB_SIDE=$(printf '%q' "${HUB_SIDE:-}") HUB_SHEET=$(printf '%q' "${HUB_SHEET:-}") \
    HUB_PLAY=$(printf '%q' "${HUB_PLAY:-}") HUB_PLAY_EXIT=$(printf '%q' "${HUB_PLAY_EXIT:-}") \
    HUB_PLAY_CHROME=$(printf '%q' "${HUB_PLAY_CHROME:-}") HUB_PLAY_FROM_END=$(printf '%q' "${HUB_PLAY_FROM_END:-}") \
    HUB_PLAY_TOUR=$(printf '%q' "${HUB_PLAY_TOUR:-}") HUB_PLAY_SUBTITLE=$(printf '%q' "${HUB_PLAY_SUBTITLE:-}") \
    HUB_PLAY_SCRUB=$(printf '%q' "${HUB_PLAY_SCRUB:-}") HUB_READ=$(printf '%q' "${HUB_READ:-}") \
    HUB_READ_CHROME=$(printf '%q' "${HUB_READ_CHROME:-}") HUB_READ_PAGE=$(printf '%q' "${HUB_READ_PAGE:-}") \
    HUB_READ_SHEET=$(printf '%q' "${HUB_READ_SHEET:-}") HUB_BOOK=$(printf '%q' "${HUB_BOOK:-}") \
    HUB_BOOK_CHROME=$(printf '%q' "${HUB_BOOK_CHROME:-}") HUB_BOOK_AT=$(printf '%q' "${HUB_BOOK_AT:-}") \
    HUB_BOOK_SHEET=$(printf '%q' "${HUB_BOOK_SHEET:-}") HUB_BOOK_SCROLL=$(printf '%q' "${HUB_BOOK_SCROLL:-}") \
    HUB_BOOK_READALONG=$(printf '%q' "${HUB_BOOK_READALONG:-}") \
    HUB_WIDTH=$(printf '%q' "${HUB_WIDTH:-}") \
    HUB_SEEN_DWELL_MS=$(printf '%q' "${HUB_SEEN_DWELL_MS:-}") \
    HUB_HERO=$(printf '%q' "${HUB_HERO:-}") \
    HUB_TITLE=$(printf '%q' "${HUB_TITLE:-}") HUB_SUBTITLES=$(printf '%q' "${HUB_SUBTITLES:-}") \
    HUB_REMOVAL=$(printf '%q' "${HUB_REMOVAL:-}") HUB_DOWNLOAD=$(printf '%q' "${HUB_DOWNLOAD:-}") \
    HUB_CAST=$(printf '%q' "${HUB_CAST:-}") HUB_OFFLINE_TITLE=$(printf '%q' "${HUB_OFFLINE_TITLE:-}") HUB_OFFLINE_WATCH=$(printf '%q' "${HUB_OFFLINE_WATCH:-}") \
    HUB_BOOK_THEME=$(printf '%q' "${HUB_BOOK_THEME:-}") HUB_BOOK_COLUMNS=$(printf '%q' "${HUB_BOOK_COLUMNS:-}") \
    HUB_BOOK_FONT=$(printf '%q' "${HUB_BOOK_FONT:-}") HUB_BOOK_SIZE=$(printf '%q' "${HUB_BOOK_SIZE:-}") \
    HUB_BOOK_TABLET=$(printf '%q' "${HUB_BOOK_TABLET:-}") \
    SHOT_SIMS=$(printf '%q' "${SHOT_SIMS:-}") SHOT_WAIT=$(printf '%q' "${SHOT_WAIT:-3}") \
    SHOT_STATE=$(printf '%q' "${SHOT_STATE:-}") SHOT_TIMES=$(printf '%q' "${SHOT_TIMES:-}") \
    ${BUILD_NUMBER:+BUILD_NUMBER=$(printf '%q' "$BUILD_NUMBER")} \
    ${TESTFLIGHT_WAIT_MINUTES:+TESTFLIGHT_WAIT_MINUTES=$(printf '%q' "$TESTFLIGHT_WAIT_MINUTES")} \
    ${TESTFLIGHT_PLATFORMS:+TESTFLIGHT_PLATFORMS=$(printf '%q' "$TESTFLIGHT_PLATFORMS")} \
    ${notes:+NOTES_FILE=$notes} \
    ${UITEST_ONLY:+UITEST_ONLY=$(printf '%q' "$UITEST_ONLY")} \
    ${UITEST_SIM:+UITEST_SIM=$(printf '%q' "$UITEST_SIM")} \
    HUB_DEV_ENV=\$HOME/$DEV_ENV bash scripts/mac.sh $(printf '%q ' "$@")"
  fetch_shots
}

case "${1:-}" in
  ""|-h|--help) sed -n '2,24p' "$0" ;;
  sync) sync ;;
  *) run "$@" ;;
esac

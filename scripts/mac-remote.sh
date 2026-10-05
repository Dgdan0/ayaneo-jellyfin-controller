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

run() {
  sync
  remote "touch ~/$REMOTE_DIR/.run-start"
  # A non-interactive SSH shell on the Mac does not read the login profile, so
  # Homebrew's tools (xcodegen) are not on its PATH.
  # HUB_SECTION (home, library, services, …) opens that section in Debug
  # builds, for screenshots of a screen other than the last one open, and
  # HUB_OPEN (continue, latest, …) opens that Home row's first title.
  # HUB_SIDE=books shows the Books side, and HUB_SHEET=profiles opens the
  # avatar's sheet. SHOT_WAIT gives a screen with artwork longer to load.
  # HUB_PLAY opens the player on an item (HUB_PLAY_EXIT leaves it after that
  # many seconds, HUB_PLAY_CHROME=pinned keeps its controls up, HUB_PLAY_TOUR=1
  # opens its panels in turn, HUB_PLAY_SUBTITLE=eng turns those subtitles on).
  # SHOT_SIMS names the simulators to use, comma-separated (all three by
  # default), SHOT_STATE names the screenshots and SHOT_TIMES takes several,
  # that many seconds after launch. `turn landscape` turns the simulators, and
  # HUB_WIDTH lays the app out as narrow as an iPad's Split View. testflight
  # takes BUILD_NUMBER (the minute in UTC otherwise), TESTFLIGHT_PLATFORMS
  # ("iOS macOS") and TESTFLIGHT_WAIT_MINUTES.
  remote "export PATH=/opt/homebrew/bin:\$PATH; cd ~/$REMOTE_DIR && \
    HUB_SECTION=$(printf '%q' "${HUB_SECTION:-}") HUB_OPEN=$(printf '%q' "${HUB_OPEN:-}") \
    HUB_SIDE=$(printf '%q' "${HUB_SIDE:-}") HUB_SHEET=$(printf '%q' "${HUB_SHEET:-}") \
    HUB_PLAY=$(printf '%q' "${HUB_PLAY:-}") HUB_PLAY_EXIT=$(printf '%q' "${HUB_PLAY_EXIT:-}") \
    HUB_PLAY_CHROME=$(printf '%q' "${HUB_PLAY_CHROME:-}") HUB_PLAY_FROM_END=$(printf '%q' "${HUB_PLAY_FROM_END:-}") \
    HUB_PLAY_TOUR=$(printf '%q' "${HUB_PLAY_TOUR:-}") HUB_PLAY_SUBTITLE=$(printf '%q' "${HUB_PLAY_SUBTITLE:-}") \
    HUB_WIDTH=$(printf '%q' "${HUB_WIDTH:-}") \
    SHOT_SIMS=$(printf '%q' "${SHOT_SIMS:-}") SHOT_WAIT=$(printf '%q' "${SHOT_WAIT:-3}") \
    SHOT_STATE=$(printf '%q' "${SHOT_STATE:-}") SHOT_TIMES=$(printf '%q' "${SHOT_TIMES:-}") \
    ${BUILD_NUMBER:+BUILD_NUMBER=$(printf '%q' "$BUILD_NUMBER")} \
    ${TESTFLIGHT_WAIT_MINUTES:+TESTFLIGHT_WAIT_MINUTES=$(printf '%q' "$TESTFLIGHT_WAIT_MINUTES")} \
    ${TESTFLIGHT_PLATFORMS:+TESTFLIGHT_PLATFORMS=$(printf '%q' "$TESTFLIGHT_PLATFORMS")} \
    ${UITEST_ONLY:+UITEST_ONLY=$(printf '%q' "$UITEST_ONLY")} \
    HUB_DEV_ENV=\$HOME/$DEV_ENV bash scripts/mac.sh $(printf '%q ' "$@")"
  fetch_shots
}

case "${1:-}" in
  ""|-h|--help) sed -n '2,22p' "$0" ;;
  sync) sync ;;
  *) run "$@" ;;
esac

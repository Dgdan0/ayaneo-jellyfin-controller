#!/usr/bin/env bash
# The Apple client's dev.sh, run on the MacBook.
#
#   scripts/mac.sh project          generate apple/Hub.xcodeproj from apple/project.yml
#   scripts/mac.sh test [filter]    HubKit tests (swift test), the JVM-test equivalent; a filter runs those alone
#   scripts/mac.sh build            build for the iOS Simulator and for the Mac
#   scripts/mac.sh sims [-demo]     boot iPad Pro 12.9", iPad mini and iPhone; install,
#                                   launch and screenshot each into shots/apple/
#   scripts/mac.sh shot [-demo]     relaunch on the booted simulators and screenshot again
#   scripts/mac.sh capture          screenshot the booted simulators as they are, no relaunch
#   scripts/mac.sh transparency reduce|normal
#                                   turn the simulators' Reduce transparency on or off
#   scripts/mac.sh build-tests      compile the UI tests for the iOS Simulator, running none
#   scripts/mac.sh uitest           the UI tests on the iPhone simulator, against -demo
#                                   (UITEST_ONLY=<Class>[/<test>] runs one alone,
#                                   UITEST_SIM=<simulator> runs them on another)
#   scripts/mac.sh quit             end JellyHub on the simulators (SHOT_SIMS, or all three)
#                                   and the Mac's Debug build, and nothing else
#   scripts/mac.sh shots-prune [minutes]
#                                   delete the build copy's screenshots older than that
#                                   (120); each run has already copied its own back
#   scripts/mac.sh turn landscape|portrait
#                                   turn the simulators themselves (SHOT_SIMS, or all
#                                   three); shot then names its pictures -landscape
#   scripts/mac.sh mac [-demo]      build and run the Mac app
#   scripts/mac.sh mac-shot <WxH> [-demo]
#                                   the built Mac app with its window that size: it draws
#                                   the window into shots/apple/[<state>-]mac-<WxH>.png
#                                   after SHOT_WAIT seconds and quits itself
#   scripts/mac.sh logs             stream the app's log from the booted simulators
#   scripts/mac.sh testflight       archive JellyHub for iOS (iPhone and iPad) and macOS,
#                                   upload both to App Store Connect and wait until they
#                                   are VALID, give them their notes and see them in the
#                                   TestFlight group. The notes are the build's "What to
#                                   Test" in the TestFlight app, a few plain lines for the
#                                   owner: NOTES="..." or NOTES_FILE=<path>, and a build
#                                   is not started without them
#   scripts/mac.sh testflight-notes <build>
#                                   give a build that is already up those notes (NOTES or
#                                   NOTES_FILE), or show what it says without them
#
# A hub address and token in apple/dev.env (gitignored) are passed to Debug
# builds on launch, the way dev.sh seed does on the Pocket DS:
#   HUB_URL=https://ayaneo-media-pc.tail737e96.ts.net
#   HUB_TOKEN=...
# -demo runs against built-in fixtures instead, for layout work without a hub.
#
# Debug launches also take HUB_PLAY=<item id> (the player opens on it; "demo-e5"
# with -demo), HUB_PLAY_EXIT=<seconds> (it leaves through Back's own path, so a
# run against the real hub never leaves a session open), HUB_PLAY_CHROME=pinned,
# HUB_PLAY_TOUR=1 (its panels open in turn), HUB_PLAY_SUBTITLE=<language>,
# HUB_PLAY_SCRUB=<seconds> (a drag across the picture held that far on),
# HUB_READ=<work id>/<issue id> (the comic reader, with -demo only: rw_demo_ff/rw_demo_ff-51),
# HUB_READ_CHROME=pinned, HUB_READ_PAGE=<n>, HUB_READ_SHEET=display|keys|pages|end,
# HUB_BOOK=<work id>/<edition id> (the ebook reader, with -demo only: rw_demo_rr6/rr6),
# HUB_BOOK_CHROME=pinned, HUB_BOOK_AT=<percent>, HUB_BOOK_SHEET=menu|contents|bookmarks|appearance|keys,
# HUB_BOOK_SCROLL=1|0 (continuous scrolling on or off, kept as Appearance keeps it) and,
# with -demo, HUB_PLAY_FROM_END=<seconds>. SHOT_SIMS="iPad Pro (12.9-inch) (4th generation),iPhone 17 Pro Max"
# limits sims and shot to those simulators, and HUB_WIDTH=375 lays the app out
# in a window that wide, as an iPad's Split View would; SHOT_STATE names the screenshots
# <state>-<device>[-landscape].png, and SHOT_TIMES="5 9 13" takes them that many
# seconds after launch instead of once after SHOT_WAIT (adding -t<seconds>).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APPLE="$ROOT/apple"
DERIVED="$APPLE/build"
BUNDLE_ID="com.dgdan.jellyhub"
# The Mac's Debug build (apple/project.yml): the TestFlight copy owns the plain id.
MAC_DEBUG_ID="$BUNDLE_ID.debug"
SHOTS="$ROOT/shots/apple"
# The devices the user owns (APPLE_PLAN.md): an iPad Pro 12.9" (4th
# generation, 2020, A12Z; 1024 x 1366 pt), an iPad mini (A17 Pro) and an
# iPhone 17 Pro Max (440 x 956 pt).
SIMS=("iPad Pro (12.9-inch) (4th generation)" "iPad mini (A17 Pro)" "iPhone 17 Pro Max")
RUNTIME="com.apple.CoreSimulator.SimRuntime.iOS-26-3"

# scripts/mac-remote.sh runs this from a build copy that has no dev.env of its
# own and points HUB_DEV_ENV at the Mac checkout's, so the token is never copied.
DEV_ENV="${HUB_DEV_ENV:-$APPLE/dev.env}"
if [[ -f "$DEV_ENV" ]]; then
  # shellcheck disable=SC1090
  source "$DEV_ENV"
fi

project() {
  (cd "$APPLE" && xcodegen generate --spec project.yml --quiet)
}

# The simulators to use: SHOT_SIMS, comma-separated, or all three.
selected_sims() {
  if [[ -z "${SHOT_SIMS:-}" ]]; then
    printf '%s\n' "${SIMS[@]}"
  else
    tr ',' '\n' <<< "$SHOT_SIMS"
  fi
}

find_udid() {
  xcrun simctl list devices available -j | python3 -c '
import json, sys
name = sys.argv[1]
for runtime, devices in json.load(sys.stdin)["devices"].items():
    for d in devices:
        if d["name"] == name and "iOS" in runtime:
            print(d["udid"]); sys.exit(0)
sys.exit(1)' "$1"
}

# The device type a simulator in SIMS is made from, where Xcode makes none
# by default.
sim_type() {
  case "$1" in
    "iPad Pro (12.9-inch) (4th generation)") echo "com.apple.CoreSimulator.SimDeviceType.iPad-Pro--12-9-inch---4th-generation-" ;;
    "iPad mini (A17 Pro)") echo "com.apple.CoreSimulator.SimDeviceType.iPad-mini-A17-Pro" ;;
    "iPhone 17 Pro Max") echo "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro-Max" ;;
    *) return 1 ;;
  esac
}

# A simulator's id; one this Mac does not have yet is made.
udid_of() {
  local udid type
  if udid="$(find_udid "$1")"; then
    echo "$udid"
  elif type="$(sim_type "$1")"; then
    xcrun simctl create "$1" "$type" "$RUNTIME"
  else
    echo "no simulator named $1" >&2
    exit 1
  fi
}

build_ios() {
  project
  xcodebuild -project "$APPLE/Hub.xcodeproj" -scheme Hub -configuration Debug \
    -destination 'generic/platform=iOS Simulator' -derivedDataPath "$DERIVED" -quiet build
}

# Until the Apple Developer Program enrolment gives a team id, the Mac app is
# signed ad hoc so its sandbox entitlements apply. Set APPLE_TEAM_ID in
# apple/dev.env once there is one.
build_mac() {
  project
  local signing=(CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM=)
  if [[ -n "${APPLE_TEAM_ID:-}" ]]; then
    signing=(DEVELOPMENT_TEAM="$APPLE_TEAM_ID" -allowProvisioningUpdates)
  fi
  xcodebuild -project "$APPLE/Hub.xcodeproj" -scheme Hub -configuration Debug \
    -destination 'platform=macOS' -derivedDataPath "$DERIVED" -quiet "${signing[@]}" build
}

app_ios() { echo "$DERIVED/Build/Products/Debug-iphonesimulator/JellyHub.app"; }
app_mac() { echo "$DERIVED/Build/Products/Debug/JellyHub.app"; }

launch_args() {
  local args=()
  for arg in "$@"; do
    [[ "$arg" == "-demo" ]] && args+=("-demo")
  done
  echo "${args[@]:-}"
}

launch_sim() {
  local udid="$1"; shift
  # Reading writes the place to the hub: the reader opens only against the demo.
  if [[ -n "${HUB_READ:-}" && " $* " != *" -demo "* ]]; then
    echo "HUB_READ opens the reader only with -demo"
    exit 2
  fi
  if [[ -n "${HUB_BOOK:-}" && " $* " != *" -demo "* ]]; then
    echo "HUB_BOOK opens the reader only with -demo"
    exit 2
  fi
  xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
  # SIMCTL_CHILD_ variables reach the app's environment without the token
  # ever being written into the simulator.
  SIMCTL_CHILD_HUB_URL="${HUB_URL:-}" SIMCTL_CHILD_HUB_TOKEN="${HUB_TOKEN:-}" \
    SIMCTL_CHILD_HUB_SECTION="${HUB_SECTION:-}" SIMCTL_CHILD_HUB_OPEN="${HUB_OPEN:-}" \
    SIMCTL_CHILD_HUB_SIDE="${HUB_SIDE:-}" SIMCTL_CHILD_HUB_SHEET="${HUB_SHEET:-}" \
    SIMCTL_CHILD_HUB_PLAY="${HUB_PLAY:-}" SIMCTL_CHILD_HUB_PLAY_EXIT="${HUB_PLAY_EXIT:-}" \
    SIMCTL_CHILD_HUB_PLAY_CHROME="${HUB_PLAY_CHROME:-}" SIMCTL_CHILD_HUB_PLAY_FROM_END="${HUB_PLAY_FROM_END:-}" \
    SIMCTL_CHILD_HUB_PLAY_TOUR="${HUB_PLAY_TOUR:-}" SIMCTL_CHILD_HUB_PLAY_SUBTITLE="${HUB_PLAY_SUBTITLE:-}" \
    SIMCTL_CHILD_HUB_PLAY_SCRUB="${HUB_PLAY_SCRUB:-}" SIMCTL_CHILD_HUB_READ="${HUB_READ:-}" \
    SIMCTL_CHILD_HUB_READ_CHROME="${HUB_READ_CHROME:-}" SIMCTL_CHILD_HUB_READ_PAGE="${HUB_READ_PAGE:-}" \
    SIMCTL_CHILD_HUB_READ_SHEET="${HUB_READ_SHEET:-}" SIMCTL_CHILD_HUB_BOOK="${HUB_BOOK:-}" \
    SIMCTL_CHILD_HUB_BOOK_CHROME="${HUB_BOOK_CHROME:-}" SIMCTL_CHILD_HUB_BOOK_AT="${HUB_BOOK_AT:-}" \
    SIMCTL_CHILD_HUB_BOOK_SHEET="${HUB_BOOK_SHEET:-}" SIMCTL_CHILD_HUB_BOOK_SCROLL="${HUB_BOOK_SCROLL:-}" \
    SIMCTL_CHILD_HUB_SEEN_DWELL_MS="${HUB_SEEN_DWELL_MS:-}" \
    SIMCTL_CHILD_HUB_HERO="${HUB_HERO:-}" \
    SIMCTL_CHILD_HUB_WIDTH="${HUB_WIDTH:-}" SIMCTL_CHILD_HUB_ORIENT="$(cat "$SHOTS/.turned-$udid" 2>/dev/null)" \
    xcrun simctl launch "$udid" "$BUNDLE_ID" $(launch_args "$@") >/dev/null
}

# Where a screenshot goes: <state>-<device>[-landscape][-t<seconds>].png, or
# <device>.png with no SHOT_STATE.
turned() { [[ "$(cat "$SHOTS/.turned-$1" 2>/dev/null)" == "landscape" ]]; }

shot_file() {
  local name="$1" udid="$2" at="${3:-}" file
  file="${SHOT_STATE:+$SHOT_STATE-}$(echo "$name" | tr -cd '[:alnum:]-')"
  turned "$udid" && file+="-landscape"
  [[ -n "$at" ]] && file+="-t$at"
  echo "$SHOTS/$file.png"
}

shoot_all() {
  while IFS= read -r name; do
    local udid file
    udid="$(udid_of "$name")"
    file="$(shot_file "$name" "$udid" "${1:-}")"
    xcrun simctl io "$udid" screenshot "$file" >/dev/null 2>&1 || continue
    # Xcode 26's simctl captured a turned simulator as its screen stands,
    # upright with the picture on its side; Xcode 27's captures it the way it
    # is turned. A turned one that comes back upright is turned to stand.
    if turned "$udid"; then
      local width height
      width="$(sips -g pixelWidth "$file" | awk '/pixelWidth/ {print $2}')"
      height="$(sips -g pixelHeight "$file" | awk '/pixelHeight/ {print $2}')"
      if (( height > width )); then sips -r 270 "$file" >/dev/null 2>&1; fi
    fi
    echo "$file"
  done < <(selected_sims)
}

shoot() {
  mkdir -p "$SHOTS"
  if [[ -n "${SHOT_TIMES:-}" ]]; then
    local elapsed=0 at
    for at in $SHOT_TIMES; do
      sleep "$((at - elapsed))"
      elapsed="$at"
      shoot_all "$at"
    done
  else
    sleep "${SHOT_WAIT:-3}"
    shoot_all
  fi
}

sims() {
  build_ios
  while IFS= read -r name; do
    local udid
    udid="$(udid_of "$name")"
    xcrun simctl boot "$udid" >/dev/null 2>&1 || true
    xcrun simctl bootstatus "$udid" -b >/dev/null
    xcrun simctl install "$udid" "$(app_ios)"
    launch_sim "$udid" "$@"
  done < <(selected_sims)
  shoot
}

shot() {
  while IFS= read -r name; do
    launch_sim "$(udid_of "$name")" "$@"
  done < <(selected_sims)
  shoot
}

# The UI tests (apple/HubUITests) on the iPhone simulator, or the one
# UITEST_SIM names. They launch the app with -demo, so they never touch the
# real hub.
uitest() {
  project
  local udid
  udid="$(udid_of "${UITEST_SIM:-iPhone 17 Pro Max}")"
  xcrun simctl boot "$udid" >/dev/null 2>&1 || true
  # UITEST_ONLY=LibraryArrangeTests runs one class (or Class/testMethod) alone.
  local only=()
  [[ -n "${UITEST_ONLY:-}" ]] && only=(-only-testing:"HubUITests/$UITEST_ONLY")
  xcodebuild -project "$APPLE/Hub.xcodeproj" -scheme Hub -configuration Debug \
    -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$DERIVED" ${only[@]+"${only[@]}"} test 2>&1 |
    grep -E "Test Case|Test Suite|error:|failed|passed|TEST (SUCCEEDED|FAILED)|\*\*" || true
}

# Turns the simulators themselves, through a UI test (HubUITests/DeviceTurn):
# an iPad app that shares the screen with others cannot turn itself, and an
# iPad simulator stays turned afterwards. The way each is turned is kept in
# shots/apple/.turned-<udid>, which shot reads to name its pictures. With
# Xcode 27 the iPhone simulator comes back upright when the test ends, so
# launch_sim also hands that file's word to the app as HUB_ORIENT, and a
# Debug build on a phone turns its own window (HubApp's DebugOrientation).
# An iPad once turned sideways stays sideways through a later test that turns
# it upright, so upright is a restart instead: a simulator boots upright.
turn() {
  local orientation="${1:-}"
  case "$orientation" in
    landscape|portrait) ;;
    *) echo "scripts/mac.sh turn landscape|portrait"; exit 2 ;;
  esac
  project
  mkdir -p "$SHOTS"
  while IFS= read -r name; do
    local udid
    udid="$(udid_of "$name")"
    if [[ "$orientation" == "portrait" ]]; then
      xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true
      xcrun simctl boot "$udid" >/dev/null 2>&1 || true
      xcrun simctl bootstatus "$udid" -b >/dev/null
    else
      xcrun simctl boot "$udid" >/dev/null 2>&1 || true
      xcrun simctl bootstatus "$udid" -b >/dev/null
      TEST_RUNNER_HUB_TURN="$orientation" xcodebuild -project "$APPLE/Hub.xcodeproj" -scheme Hub -configuration Debug \
        -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$DERIVED" \
        -only-testing:HubUITests/DeviceTurn/testTurn test 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" || true
    fi
    echo "$orientation" > "$SHOTS/.turned-$udid"
    echo "$name: $orientation"
  done < <(selected_sims)
}

# Nothing of ours is left running when a session ends: `sims` and `shot`
# leave the app open on each simulator. Only JellyHub's own id is ended;
# the simulators stay booted and anything else on them is left alone.
quit_app() {
  while IFS= read -r name; do
    local udid
    udid="$(find_udid "$name")" || continue
    xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
    echo "$name: JellyHub ended"
  done < <(selected_sims)
  pkill -f "$(app_mac)/Contents/MacOS/JellyHub" >/dev/null 2>&1 || true
}

# Screenshots in the build copy older than MINUTES (120 by default) are
# deleted: every run copies its own pictures back to the PC, and the Mac's
# disk is small. The .turned files stay.
shots_prune() {
  local minutes="${1:-120}"
  [[ -d "$SHOTS" ]] || return 0
  local before after
  before="$(du -sh "$SHOTS" | cut -f1)"
  find "$SHOTS" -type f -name '*.png' -mmin +"$minutes" -delete
  after="$(du -sh "$SHOTS" | cut -f1)"
  echo "shots/apple: $before before, $after after"
}

# Reduce transparency on the three simulators: an accessibility setting inside
# each simulator, not the Mac's. Relaunch with `shot` to see it.
transparency() {
  local value
  case "${1:-}" in
    reduce) value=YES ;;
    normal) value=NO ;;
    *) echo "scripts/mac.sh transparency reduce|normal"; exit 2 ;;
  esac
  for name in "${SIMS[@]}"; do
    local udid
    udid="$(udid_of "$name")"
    xcrun simctl spawn "$udid" defaults write com.apple.Accessibility EnhancedBackgroundContrastEnabled -bool "$value"
    xcrun simctl spawn "$udid" notifyutil -p com.apple.accessibility.cache.enhance.background.contrast >/dev/null 2>&1 || true
    echo "$name: reduce transparency $1"
  done
}

mac() {
  build_mac
  # Only this build of the Mac app: the simulators run apps named Hub too.
  pkill -f "$(app_mac)/Contents/MacOS/JellyHub" >/dev/null 2>&1 || true
  # Detached, with its output in a file: an SSH session that starts it
  # (scripts/mac-remote.sh) otherwise stays open as long as the app runs.
  HUB_URL="${HUB_URL:-}" HUB_TOKEN="${HUB_TOKEN:-}" nohup "$(app_mac)/Contents/MacOS/JellyHub" $(launch_args "$@") \
    > "$DERIVED/mac-app.log" 2>&1 < /dev/null &
  echo "started $(app_mac); its output goes to $DERIVED/mac-app.log"
}

# The Mac app with its window at one size, for the orientation and size pass:
# it draws its own window into its container (a screenshot over SSH needs
# Screen Recording, which stays off) and quits itself through its own path.
mac_shot() {
  local size="${1:-1280x820}" app container file pid
  shift || true
  app="$(app_mac)/Contents/MacOS/JellyHub"
  container="$HOME/Library/Containers/$MAC_DEBUG_ID/Data"
  pkill -f "$app" >/dev/null 2>&1 || true
  rm -f "$container/hub-window.png"
  # The window's last frame is kept in the app's own defaults and wins over
  # the size asked for, so it is forgotten first. A window resized after it
  # opened drew its scrolled pages where they had been.
  local prefs="$container/Library/Preferences/$MAC_DEBUG_ID"
  # A first run has no defaults yet, which pipefail would count as failing.
  { defaults read "$prefs" 2>/dev/null || true; } | sed -n 's/^ *"\(NSWindow Frame [^"]*\)" = .*/\1/p' |
    while IFS= read -r key; do defaults delete "$prefs" "$key"; done
  # -demo goes last: before the pair, AppKit read it as a key and
  # -ApplePersistenceIgnoreState as its value, opened the "YES" left over as a
  # document, and its alert held the app until the script gave up.
  # The player only against the demo hub: the app quits itself once it has
  # drawn the window, and a real session must leave through the player's path.
  if [[ -n "${HUB_PLAY:-}" && " $* " != *" -demo "* ]]; then
    echo "mac-shot opens the player only with -demo"
    exit 2
  fi
  if [[ -n "${HUB_READ:-}${HUB_BOOK:-}" && " $* " != *" -demo "* ]]; then
    echo "mac-shot opens the reader only with -demo"
    exit 2
  fi
  HUB_URL="${HUB_URL:-}" HUB_TOKEN="${HUB_TOKEN:-}" HUB_SECTION="${HUB_SECTION:-}" HUB_SIDE="${HUB_SIDE:-}" \
    HUB_OPEN="${HUB_OPEN:-}" HUB_SHEET="${HUB_SHEET:-}" HUB_WINDOW="$size" HUB_SNAPSHOT="${SHOT_WAIT:-8}" \
    HUB_PLAY="${HUB_PLAY:-}" HUB_PLAY_CHROME="${HUB_PLAY_CHROME:-}" HUB_PLAY_SCRUB="${HUB_PLAY_SCRUB:-}" \
    HUB_READ="${HUB_READ:-}" HUB_READ_CHROME="${HUB_READ_CHROME:-}" HUB_READ_PAGE="${HUB_READ_PAGE:-}" \
    HUB_READ_SHEET="${HUB_READ_SHEET:-}" HUB_BOOK="${HUB_BOOK:-}" HUB_BOOK_CHROME="${HUB_BOOK_CHROME:-}" \
    HUB_BOOK_AT="${HUB_BOOK_AT:-}" HUB_BOOK_SHEET="${HUB_BOOK_SHEET:-}" HUB_BOOK_SCROLL="${HUB_BOOK_SCROLL:-}" \
    HUB_SEEN_DWELL_MS="${HUB_SEEN_DWELL_MS:-}" \
    HUB_HERO="${HUB_HERO:-}" \
    nohup "$app" -ApplePersistenceIgnoreState YES $(launch_args "$@") > "$DERIVED/mac-app.log" 2>&1 < /dev/null &
  pid=$!
  for _ in $(seq 1 90); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  # Never left running.
  kill "$pid" 2>/dev/null || true
  mkdir -p "$SHOTS"
  file="$SHOTS/${SHOT_STATE:+$SHOT_STATE-}mac-$size.png"
  if cp "$container/hub-window.png" "$file" 2>/dev/null; then echo "$file"; else echo "no picture from the Mac app"; fi
}

# TestFlight: JellyHub archived for iOS (iPhone and iPad) and macOS, signed
# for the App Store, uploaded with the App Store Connect API key, then
# followed until App Store Connect has processed both and handed them to the
# TestFlight group. The key and its ids stay on this Mac, in ~/.appstoreconnect
# (ASC_ENV): ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH, APPLE_TEAM_ID, ASC_APP_ID
# and TESTFLIGHT_GROUP.
#
# The key's role (App Manager) may not use Apple's cloud-managed signing, so
# the two distribution identities live in a keychain of their own beside the
# key, with its own random password in a 600 file; the login keychain is never
# opened (an SSH session could not anyway). The first run makes a key pair
# here and asks App Store Connect for an Apple Distribution and a Mac
# Installer Distribution certificate, and for App Store profiles; later runs
# reuse them. The keychain is on the search list for the run only.
TF_DIR="$HOME/.appstoreconnect"
TF_KEYCHAIN="$TF_DIR/jellyhub-signing.keychain-db"
TF_PASSWORD_FILE="$TF_DIR/jellyhub-signing.pass"
XCODE_CERTS="/Applications/Xcode.app/Contents/SharedFrameworks/DVTFoundation.framework/Versions/A/Resources"
# macOS's own LibreSSL: its PKCS#12 files are ones `security import` reads.
OPENSSL=/usr/bin/openssl
# The Mac Installer Distribution certificate's name, as issued and as it may become.
TF_INSTALLER=("3rd Party Mac Developer Installer" "Mac Installer Distribution")

tf_keychain() {
  # The search list as it was, without this keychain (create-keychain adds it).
  TF_SEARCH_LIST="$(security list-keychains -d user | tr -d '"' | { grep -v "$TF_KEYCHAIN" || true; } | xargs)"
  # shellcheck disable=SC2064
  trap "security list-keychains -d user -s $TF_SEARCH_LIST; security lock-keychain '$TF_KEYCHAIN' 2>/dev/null || true" EXIT
  [[ -f "$TF_PASSWORD_FILE" ]] || (umask 077; "$OPENSSL" rand -hex 24 > "$TF_PASSWORD_FILE")
  TF_PASSWORD="$(cat "$TF_PASSWORD_FILE")"
  [[ -f "$TF_KEYCHAIN" ]] || security create-keychain -p "$TF_PASSWORD" "$TF_KEYCHAIN"
  security unlock-keychain -p "$TF_PASSWORD" "$TF_KEYCHAIN"
  # Locked again two hours on, and whenever the Mac sleeps.
  security set-keychain-settings -lut 7200 "$TF_KEYCHAIN"
  # Apple's intermediates as Xcode carries them (WWDR G3 and G6): the Mac's
  # System keychain has only the one that expired in 2023.
  local cer
  for cer in AppleWWDRCA-2030.cer AppleWWDRCAG6.cer; do
    security import "$XCODE_CERTS/$cer" -k "$TF_KEYCHAIN" >/dev/null 2>&1 || true
  done
  # shellcheck disable=SC2086
  security list-keychains -d user -s "$TF_KEYCHAIN" $TF_SEARCH_LIST
}

# Whether the keychain has a certificate whose name starts with one of these.
tf_has() {
  local name
  for name in "$@"; do
    security find-certificate -c "$name" "$TF_KEYCHAIN" >/dev/null 2>&1 && return 0
  done
  return 1
}

# An identity of `type` (an App Store Connect certificate type), made here
# once: a key pair, its request, and the certificate App Store Connect issues.
tf_identity() {
  local type="$1" work
  shift
  tf_has "$@" && return
  work="$(umask 077; mktemp -d)"
  "$OPENSSL" req -new -newkey rsa:2048 -nodes -keyout "$work/key.pem" -out "$work/csr.pem" \
    -subj "/CN=JellyHub $type/C=US" >/dev/null 2>&1
  swift "$APPLE/Tools/asc.swift" cert "$type" "$work/csr.pem" "$work/cert.cer"
  "$OPENSSL" x509 -inform DER -in "$work/cert.cer" -out "$work/cert.pem"
  "$OPENSSL" pkcs12 -export -inkey "$work/key.pem" -in "$work/cert.pem" -out "$work/identity.p12" \
    -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -passout "pass:$TF_PASSWORD"
  security import "$work/identity.p12" -k "$TF_KEYCHAIN" -P "$TF_PASSWORD" \
    -T /usr/bin/codesign -T /usr/bin/productbuild -T /usr/bin/xcodebuild >/dev/null
  rm -rf "$work"
  # Never ask for another: a certificate named otherwise would be made again on every run.
  tf_has "$@" || { echo "the new $type certificate is not named $*; it is in $TF_KEYCHAIN"; exit 1; }
}

# The SHA-1 of the first certificate whose name starts with one of these.
tf_sha1() {
  local name
  for name in "$@"; do
    security find-certificate -c "$name" -Z "$TF_KEYCHAIN" 2>/dev/null |
      awk '/^SHA-1 hash:/ && !seen {print $3; seen = 1}' | grep . && return 0
  done
  return 1
}

# An App Store profile for the Apple Distribution certificate, installed where
# Xcode looks for profiles. Never in a $(...), where set -e does not hold.
tf_profile() {
  local type="$1" name="$2" extension="$3" work serial uuid
  local installed="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
  work="$(mktemp -d)"
  serial="$(security find-certificate -c "Apple Distribution" -p "$TF_KEYCHAIN" |
    "$OPENSSL" x509 -noout -serial | cut -d= -f2)"
  swift "$APPLE/Tools/asc.swift" profile "$type" "$BUNDLE_ID" "$serial" "$name" "$work/profile" >&2
  security cms -D -i "$work/profile" > "$work/profile.plist"
  uuid="$(plutil -extract UUID raw "$work/profile.plist")"
  mkdir -p "$installed"
  cp "$work/profile" "$installed/$uuid.$extension"
  rm -rf "$work"
}

# The export's options; a Mac package also names its installer certificate.
tf_export_options() {
  local file="$1" profile="$2" installer="${3:-}"
  {
    cat <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>upload</string>
  <key>signingStyle</key><string>manual</string>
  <key>teamID</key><string>$APPLE_TEAM_ID</string>
  <key>signingCertificate</key><string>Apple Distribution</string>
  <key>provisioningProfiles</key><dict><key>$BUNDLE_ID</key><string>$profile</string></dict>
  <key>uploadSymbols</key><true/>
  <key>manageAppVersionAndBuildNumber</key><false/>
PLIST
    # By its SHA-1: Xcode 27 matched no certificate to the selector "Mac Installer Distribution".
    if [[ -n "$installer" ]]; then
      echo "  <key>installerSigningCertificate</key><string>$installer</string>"
    fi
    echo "</dict>"
    echo "</plist>"
  } > "$file"
}

tf_env() {
  local env="${ASC_ENV:-$TF_DIR/jellyhub.env}"
  if [[ ! -f "$env" ]]; then
    echo "no $env: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH, APPLE_TEAM_ID, ASC_APP_ID, TESTFLIGHT_GROUP"
    exit 2
  fi
  set -a
  # shellcheck disable=SC1090
  source "$env"
  set +a
}

# The build's notes, "What to Test" in the TestFlight app, written to $1 from
# NOTES_FILE or NOTES (mac-remote.sh puts either from the PC in a file here).
# Checked before anything is archived: App Store Connect takes 4000 characters.
tf_notes() {
  local file="$1" text
  if [[ -n "${NOTES_FILE:-}" ]]; then
    [[ -f "$NOTES_FILE" ]] || { echo "no notes file $NOTES_FILE"; exit 2; }
    text="$(tr -d '\r' < "$NOTES_FILE")"
  else
    text="$(printf '%s' "${NOTES:-}" | tr -d '\r')"
  fi
  if [[ -z "${text//[[:space:]]/}" ]]; then
    echo "no notes for the TestFlight app: NOTES=\"...\" or NOTES_FILE=<path>, a few plain lines for the owner"
    exit 2
  fi
  # Bytes, which are never fewer than the characters App Store Connect counts.
  if (( $(printf '%s' "$text" | wc -c) > 4000 )); then
    echo "the notes are $(printf '%s' "$text" | wc -c | tr -d ' ') bytes; What to Test takes 4000 characters"
    exit 2
  fi
  mkdir -p "$(dirname "$file")"
  printf '%s\n' "$text" > "$file"
}

testflight() {
  tf_env
  local build version out auth platform installer
  # Always increasing: the minute of the upload in UTC, yyMMddHHmm.
  build="${BUILD_NUMBER:-$(date -u +%y%m%d%H%M)}"
  version="$(awk -F'"' '/MARKETING_VERSION:/ {print $2; exit}' "$APPLE/project.yml")"
  out="$APPLE/build/testflight/$build"
  tf_notes "$out/notes.txt"
  project
  auth=(-allowProvisioningUpdates -authenticationKeyPath "$ASC_KEY_PATH"
        -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID")

  echo "signing identities and profiles"
  tf_keychain
  tf_identity DISTRIBUTION "Apple Distribution"
  tf_identity MAC_INSTALLER_DISTRIBUTION "${TF_INSTALLER[@]}"
  # codesign and productbuild may use the keys without asking anyone.
  security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$TF_PASSWORD" "$TF_KEYCHAIN" >/dev/null
  security find-identity -v "$TF_KEYCHAIN" | sed -n 's/.*"\(.*\)".*/  \1/p'
  tf_profile IOS_APP_STORE "JellyHub iOS App Store" mobileprovision
  tf_profile MAC_APP_STORE "JellyHub Mac App Store" provisionprofile
  installer="$(tf_sha1 "${TF_INSTALLER[@]}")"
  tf_export_options "$out/export-iOS.plist" "JellyHub iOS App Store"
  tf_export_options "$out/export-macOS.plist" "JellyHub Mac App Store" "$installer"

  echo "JellyHub $version ($build)"
  # TESTFLIGHT_PLATFORMS=macOS with the BUILD_NUMBER of an iOS upload sends the
  # Mac build that failed after it; the wait below wants both under one number.
  for platform in ${TESTFLIGHT_PLATFORMS:-iOS macOS}; do
    local signing=(CODE_SIGNING_ALLOWED=NO)
    # The Mac app keeps its sandbox only if the archive carries it: an ad hoc
    # signature holds the entitlements for the export to sign again.
    [[ "$platform" == "macOS" ]] && signing=(CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=-)
    echo "archiving for $platform"
    xcodebuild -project "$APPLE/Hub.xcodeproj" -scheme Hub -configuration Release \
      -destination "generic/platform=$platform" -archivePath "$out/JellyHub-$platform.xcarchive" \
      -derivedDataPath "$DERIVED" CURRENT_PROJECT_VERSION="$build" DEVELOPMENT_TEAM="$APPLE_TEAM_ID" \
      "${signing[@]}" -quiet archive
    echo "uploading $platform"
    xcodebuild -exportArchive -archivePath "$out/JellyHub-$platform.xcarchive" \
      -exportOptionsPlist "$out/export-$platform.plist" -exportPath "$out/$platform" "${auth[@]}"
  done
  echo "waiting for App Store Connect to process $build"
  swift "$APPLE/Tools/asc.swift" wait "$build" "${TESTFLIGHT_WAIT_MINUTES:-60}"
  # As soon as it is VALID: the group gets the build then, and its testers the notes.
  swift "$APPLE/Tools/asc.swift" notes "$build" "$out/notes.txt"
  swift "$APPLE/Tools/asc.swift" group "${TESTFLIGHT_GROUP:-me}" "$build"
}

# Notes for a build already up, from NOTES or NOTES_FILE, or what it says now.
testflight_notes() {
  local build="${1:-}"
  [[ -n "$build" ]] || { echo "testflight-notes <build>"; exit 2; }
  tf_env
  if [[ -z "${NOTES_FILE:-}" && -z "${NOTES:-}" ]]; then
    swift "$APPLE/Tools/asc.swift" notes "$build"
    return
  fi
  local file="$APPLE/build/testflight/$build/notes.txt"
  tf_notes "$file"
  swift "$APPLE/Tools/asc.swift" notes "$build" "$file"
}

case "${1:-build}" in
  project) project ;;
  test) shift; (cd "$APPLE/HubKit" && swift test ${1:+--filter "$1"}) ;;
  build) build_ios && build_mac ;;
  sims) shift; sims "$@" ;;
  shot) shift; shot "$@" ;;
  capture) SHOT_WAIT=0 shoot ;;
  transparency) shift; transparency "$@" ;;
  build-tests) project && xcodebuild -project "$APPLE/Hub.xcodeproj" -scheme Hub -configuration Debug \
      -destination 'generic/platform=iOS Simulator' -derivedDataPath "$DERIVED" -quiet build-for-testing ;;
  uitest) uitest ;;
  turn) shift; turn "$@" ;;
  quit) quit_app ;;
  shots-prune) shift; shots_prune "$@" ;;
  mac) shift; mac "$@" ;;
  mac-shot) shift; mac_shot "$@" ;;
  testflight) testflight ;;
  testflight-notes) shift; testflight_notes "$@" ;;
  logs) xcrun simctl spawn booted log stream --level debug --predicate "subsystem == '$BUNDLE_ID' OR process == 'Hub'" ;;
  *) sed -n '2,62p' "$0"; exit 2 ;;
esac

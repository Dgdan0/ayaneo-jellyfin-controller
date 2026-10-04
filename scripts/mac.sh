#!/usr/bin/env bash
# The Apple client's dev.sh, run on the MacBook.
#
#   scripts/mac.sh project          generate apple/Hub.xcodeproj from apple/project.yml
#   scripts/mac.sh test             HubKit tests (swift test), the JVM-test equivalent
#   scripts/mac.sh build            build for the iOS Simulator and for the Mac
#   scripts/mac.sh sims [-demo]     boot iPad Pro 12.9", iPad mini and iPhone; install,
#                                   launch and screenshot each into shots/apple/
#   scripts/mac.sh shot [-demo]     relaunch on the booted simulators and screenshot again
#   scripts/mac.sh capture          screenshot the booted simulators as they are, no relaunch
#   scripts/mac.sh transparency reduce|normal
#                                   turn the simulators' Reduce transparency on or off
#   scripts/mac.sh uitest           the UI tests on the iPhone simulator, against -demo
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
#                                   are VALID and in the TestFlight group
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
# HUB_PLAY_TOUR=1 (its panels open in turn), HUB_PLAY_SUBTITLE=<language> and,
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
  xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
  # SIMCTL_CHILD_ variables reach the app's environment without the token
  # ever being written into the simulator.
  SIMCTL_CHILD_HUB_URL="${HUB_URL:-}" SIMCTL_CHILD_HUB_TOKEN="${HUB_TOKEN:-}" \
    SIMCTL_CHILD_HUB_SECTION="${HUB_SECTION:-}" SIMCTL_CHILD_HUB_OPEN="${HUB_OPEN:-}" \
    SIMCTL_CHILD_HUB_SIDE="${HUB_SIDE:-}" SIMCTL_CHILD_HUB_SHEET="${HUB_SHEET:-}" \
    SIMCTL_CHILD_HUB_PLAY="${HUB_PLAY:-}" SIMCTL_CHILD_HUB_PLAY_EXIT="${HUB_PLAY_EXIT:-}" \
    SIMCTL_CHILD_HUB_PLAY_CHROME="${HUB_PLAY_CHROME:-}" SIMCTL_CHILD_HUB_PLAY_FROM_END="${HUB_PLAY_FROM_END:-}" \
    SIMCTL_CHILD_HUB_PLAY_TOUR="${HUB_PLAY_TOUR:-}" SIMCTL_CHILD_HUB_PLAY_SUBTITLE="${HUB_PLAY_SUBTITLE:-}" \
    SIMCTL_CHILD_HUB_WIDTH="${HUB_WIDTH:-}" \
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

# The UI tests (apple/HubUITests) on the iPhone simulator. They launch the app
# with -demo, so they never touch the real hub.
uitest() {
  project
  local udid
  udid="$(udid_of "iPhone 17 Pro Max")"
  xcrun simctl boot "$udid" >/dev/null 2>&1 || true
  xcodebuild -project "$APPLE/Hub.xcodeproj" -scheme Hub -configuration Debug \
    -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$DERIVED" test 2>&1 |
    grep -E "Test Case|Test Suite|error:|failed|passed|TEST (SUCCEEDED|FAILED)|\*\*" || true
}

# Turns the simulators themselves, through a UI test (HubUITests/DeviceTurn):
# an iPad app that shares the screen with others cannot turn itself, and the
# simulator stays turned afterwards. The way each is turned is kept in
# shots/apple/.turned-<udid>, which shot reads to name its pictures.
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
    xcrun simctl boot "$udid" >/dev/null 2>&1 || true
    xcrun simctl bootstatus "$udid" -b >/dev/null
    TEST_RUNNER_HUB_TURN="$orientation" xcodebuild -project "$APPLE/Hub.xcodeproj" -scheme Hub -configuration Debug \
      -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$DERIVED" \
      -only-testing:HubUITests/DeviceTurn/testTurn test 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" || true
    echo "$orientation" > "$SHOTS/.turned-$udid"
    echo "$name: $orientation"
  done < <(selected_sims)
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
  container="$HOME/Library/Containers/$BUNDLE_ID/Data"
  pkill -f "$app" >/dev/null 2>&1 || true
  rm -f "$container/hub-window.png"
  # The window's last frame is kept in the app's own defaults and wins over
  # the size asked for, so it is forgotten first. A window resized after it
  # opened drew its scrolled pages where they had been.
  local prefs="$container/Library/Preferences/$BUNDLE_ID"
  defaults read "$prefs" 2>/dev/null | sed -n 's/^ *"\(NSWindow Frame [^"]*\)" = .*/\1/p' |
    while IFS= read -r key; do defaults delete "$prefs" "$key"; done
  HUB_URL="${HUB_URL:-}" HUB_TOKEN="${HUB_TOKEN:-}" HUB_SECTION="${HUB_SECTION:-}" HUB_SIDE="${HUB_SIDE:-}" \
    HUB_OPEN="${HUB_OPEN:-}" HUB_SHEET="${HUB_SHEET:-}" HUB_WINDOW="$size" HUB_SNAPSHOT="${SHOT_WAIT:-8}" \
    nohup "$app" $(launch_args "$@") -ApplePersistenceIgnoreState YES > "$DERIVED/mac-app.log" 2>&1 < /dev/null &
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

# TestFlight: JellyHub archived for iOS (iPhone and iPad) and macOS, signed by
# Xcode's cloud-managed distribution certificate through the App Store
# Connect API key, uploaded, then followed until App Store Connect has
# processed both and given them to the TestFlight group. The key and its ids
# stay on this Mac, in ~/.appstoreconnect (ASC_ENV): ASC_KEY_ID, ASC_ISSUER_ID,
# ASC_KEY_PATH, APPLE_TEAM_ID, ASC_APP_ID and TESTFLIGHT_GROUP. Nothing here
# touches the login keychain: the iOS archive is unsigned and the Mac one
# signed ad hoc (to carry its sandbox), and the export signs both.
testflight() {
  local env="${ASC_ENV:-$HOME/.appstoreconnect/jellyhub.env}"
  if [[ ! -f "$env" ]]; then
    echo "no $env: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH, APPLE_TEAM_ID, ASC_APP_ID, TESTFLIGHT_GROUP"
    exit 2
  fi
  set -a
  # shellcheck disable=SC1090
  source "$env"
  set +a
  project
  local build version out auth platform
  # Always increasing: the upload's minute in UTC, which fits Apple's 32-bit build numbers.
  build="${BUILD_NUMBER:-$(date -u +%y%m%d%H%M)}"
  version="$(awk -F'"' '/MARKETING_VERSION:/ {print $2; exit}' "$APPLE/project.yml")"
  out="$APPLE/build/testflight/$build"
  mkdir -p "$out"
  auth=(-allowProvisioningUpdates -authenticationKeyPath "$ASC_KEY_PATH"
        -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID")
  cat > "$out/export.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>upload</string>
  <key>signingStyle</key><string>automatic</string>
  <key>teamID</key><string>$APPLE_TEAM_ID</string>
  <key>uploadSymbols</key><true/>
  <key>manageAppVersionAndBuildNumber</key><false/>
</dict>
</plist>
PLIST
  echo "JellyHub $version ($build)"
  for platform in iOS macOS; do
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
      -exportOptionsPlist "$out/export.plist" -exportPath "$out/$platform" "${auth[@]}"
  done
  echo "waiting for App Store Connect to process $build"
  swift "$APPLE/Tools/asc.swift" wait "$build" "${TESTFLIGHT_WAIT_MINUTES:-60}"
  swift "$APPLE/Tools/asc.swift" group "${TESTFLIGHT_GROUP:-me}"
}

case "${1:-build}" in
  project) project ;;
  test) (cd "$APPLE/HubKit" && swift test) ;;
  build) build_ios && build_mac ;;
  sims) shift; sims "$@" ;;
  shot) shift; shot "$@" ;;
  capture) SHOT_WAIT=0 shoot ;;
  transparency) shift; transparency "$@" ;;
  uitest) uitest ;;
  turn) shift; turn "$@" ;;
  mac) shift; mac "$@" ;;
  mac-shot) shift; mac_shot "$@" ;;
  testflight) testflight ;;
  logs) xcrun simctl spawn booted log stream --level debug --predicate "subsystem == '$BUNDLE_ID' OR process == 'Hub'" ;;
  *) sed -n '2,42p' "$0"; exit 2 ;;
esac

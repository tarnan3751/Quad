#!/usr/bin/env bash
# Drive the iOS app on a booted simulator by hand (taps, typing, swipes) through the QuadDriver UI test.
#
#   scripts/ios-drive.sh start              # build + start the driver (background); waits until it is ready
#   scripts/ios-drive.sh tree               # accessibility tree of the app
#   scripts/ios-drive.sh tap "Sign in"      # any QuadDriver command (see iosApp/iosAppUITests/QuadDriver.swift)
#   scripts/ios-drive.sh stop
#
# Env: QUAD_DRIVER_DIR (default /tmp/quad-driver), SIMULATOR (default "iPhone 17"), DERIVED_DATA (default build/ios-dd).
set -euo pipefail

cd "$(dirname "$0")/.."
DIR="${QUAD_DRIVER_DIR:-/tmp/quad-driver}"
SIM="${SIMULATOR:-iPhone 17}"
DD="${DERIVED_DATA:-build/ios-dd}"

case "${1:-}" in
  start)
    rm -rf "$DIR" && mkdir -p "$DIR"
    TEST_RUNNER_QUAD_DRIVER_DIR="$DIR" xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
      -destination "platform=iOS Simulator,name=$SIM" -derivedDataPath "$DD" \
      -only-testing:iosAppUITests/QuadDriver >"$DIR/xcodebuild.log" 2>&1 &
    for _ in $(seq 1 900); do
      [ -f "$DIR/ready" ] && { echo "driver ready ($DIR)"; exit 0; }
      sleep 1
    done
    echo "driver did not start; see $DIR/xcodebuild.log" >&2; exit 1
    ;;
  "")
    sed -n '2,9p' "$0"; exit 2
    ;;
esac

[ -f "$DIR/ready" ] || { echo "driver not running: scripts/ios-drive.sh start" >&2; exit 1; }
n=$(( $(cat "$DIR/seq" 2>/dev/null || echo 0) + 1 ))
echo "$n" >"$DIR/seq"
cmd="$*"
[ "$1" = "stop" ] && cmd="quit"
printf '%s' "$cmd" >"$DIR/cmd.$n.tmp" && mv "$DIR/cmd.$n.tmp" "$DIR/cmd.$n"
for _ in $(seq 1 1200); do
  if [ -f "$DIR/out.$n" ]; then
    cat "$DIR/out.$n"; echo
    [ "$cmd" = "quit" ] && rm -f "$DIR/ready"
    grep -q '^ERR' "$DIR/out.$n" && exit 1
    exit 0
  fi
  sleep 0.1
done
echo "no answer to: $cmd" >&2; exit 1

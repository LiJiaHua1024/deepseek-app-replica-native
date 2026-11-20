#!/usr/bin/env bash
# tools/press.sh <x> <y> [press|long] [out.png]
# Injects ACTION_DOWN, holds (press: 150ms, long: 700ms), screenshots mid-hold,
# then injects ACTION_UP. Output: /tmp/shots/press/<out.png> (default press.png).
set -euo pipefail
X="${1:?x}"; Y="${2:?y}"; MODE="${3:-press}"; NAME="${4:-press}"
SERIAL="${SERIAL:-emulator-5554}"
ADB="adb -s $SERIAL"
HOLD=150; [ "$MODE" = long ] && HOLD=700
OUT="/tmp/shots/press"; mkdir -p "$OUT"
TEST_PKG=io.github.lijiahua1024.dsnative.test
RUNNER=$TEST_PKG/androidx.test.runner.AndroidJUnitRunner
CLS=io.github.lijiahua1024.dsnative.MotionServer
DIR=/sdcard/Android/data/io.github.lijiahua1024.dsnative/files

$ADB shell mkdir -p $DIR >/dev/null 2>&1 || true
$ADB shell rm -f $DIR/inject.cmd $DIR/inject.log >/dev/null 2>&1 || true
# hold pointer down for HOLD+1200ms so the screenshot window sits inside it;
# the trailing move+up off-target cancels the tap so rows that launch intents
# (相机/相册/文件, voice mode) don't fire on release.
$ADB shell "printf 'down %s %s\nsleep %s\nmove %s %s\nup %s %s\n' $X $Y $((HOLD+1200)) $X $((Y-80)) $X $((Y-80)) > $DIR/inject.cmd"
$ADB shell am instrument -w -e class $CLS#serve $RUNNER >/dev/null 2>&1 &
INSTR_PID=$!
# wait for the live "down" marker before capturing the pressed frame
for i in $(seq 1 60); do
  if $ADB shell "grep -q '^down ' $DIR/inject.log 2>/dev/null"; then break; fi
  sleep 0.05
done
sleep "$(awk "BEGIN{print $HOLD/1000}")"
if ! $ADB exec-out screencap -p > "$OUT/$NAME.png" 2>/dev/null || [ ! -s "$OUT/$NAME.png" ]; then
  $ADB shell screencap -p /sdcard/_press.png >/dev/null 2>&1 && $ADB pull /sdcard/_press.png "$OUT/$NAME.png" >/dev/null 2>&1
fi
wait $INSTR_PID || true
echo "$OUT/$NAME.png"

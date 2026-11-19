#!/usr/bin/env bash
# tools/shot.sh <app> <scene> — reproducible screenshot of one app in one scene.
# app: ref | native. Output: /tmp/shots/<app>/<scene>.png
# State reset: pm clear only the two app packages (never the whole device).
# Scenes are driven by adb input for native, by CDP for ref (tools/refdrive.mjs),
# plus the shared mock backend on host :8321.
set -euo pipefail

APP="${1:?app: ref|native}"
SCENE="${2:?scene name}"
SERIAL="${SERIAL:-emulator-5554}"
ADB="adb -s $SERIAL"
OUT="/tmp/shots/$APP"
mkdir -p "$OUT"
PKG_REF="io.github.lijiahua1024.dsreplica"
PKG_NATIVE="io.github.lijiahua1024.dsnative"

case "$APP" in
  ref)    PKG=$PKG_REF ;;
  native) PKG=$PKG_NATIVE ;;
  *) echo "unknown app $APP" >&2; exit 2 ;;
esac

repo="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="$HOME/android-sdk/platform-tools:$HOME/tools/node22/bin:$PATH"

$ADB shell settings put secure back_gesture_inset_scale_left 0.0 >/dev/null 2>&1 || true
$ADB shell settings put secure back_gesture_inset_scale_right 0.0 >/dev/null 2>&1 || true

# reset state: only these two packages may be pm-cleared
$ADB shell am force-stop $PKG_REF   >/dev/null 2>&1 || true
$ADB shell am force-stop $PKG_NATIVE >/dev/null 2>&1 || true
$ADB shell pm clear $PKG >/dev/null 2>&1 || true
# per-app locale is wiped by pm clear: re-apply afterwards
$ADB shell cmd locale set-app-locales $PKG --locales zh-CN >/dev/null 2>&1 || true
# dismiss keyboard / go to a neutral state
$ADB shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
$ADB shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
sleep 0.3

# theme variants: <scene>.dark / .light via system night mode
UIMODE=""
SCENE_OUT="$SCENE"
case "$SCENE" in
  *.dark)  UIMODE=yes; SCENE="${SCENE%.dark}" ;;
  *.light) UIMODE=no;  SCENE="${SCENE%.light}" ;;
esac
SCENE="${SCENE%.default}"
if [ -n "$UIMODE" ]; then
  $ADB shell cmd uimode night $UIMODE >/dev/null 2>&1 || true
fi
trap '[ -n "$UIMODE" ] && $ADB shell cmd uimode night no >/dev/null 2>&1 || true' EXIT

if [ "$APP" = ref ]; then
  # node driver connects via webview_devtools CDP, installs the fetch proxy to
  # the mock backend, drives the scene, and captures at the right moment.
  node "$repo/tools/refdrive.mjs" "$SERIAL" "$PKG_REF" "$SCENE" "$OUT/$SCENE_OUT.png"
else
  bash "$repo/tools/scenes-native.sh" "$ADB" "$PKG" "$SCENE" "$OUT/$SCENE_OUT.png"
fi

[ -s "$OUT/$SCENE_OUT.png" ] && echo "shot: $OUT/$SCENE_OUT.png"

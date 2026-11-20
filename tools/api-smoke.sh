#!/usr/bin/env bash
# tools/api-smoke.sh — smoke the debug APK on API 23/29/34/36.
# Per level: create AVD if missing, boot, install, launch, snapshot a
# screenshot + crash-scan logcat. Log + shots under /tmp/api-smoke/.
set -u
export PATH="$HOME/android-sdk/platform-tools:$HOME/android-sdk/emulator:$HOME/android-sdk/cmdline-tools/latest/bin:$PATH"
APK="${APK:-$PWD/app/build/outputs/apk/debug/app-debug.apk}"
PKG=io.github.lijiahua1024.dsnative
OUT=/tmp/api-smoke
mkdir -p "$OUT"
: >$OUT/log.txt

for API in 23 29 34 36; do
  IMG_ABI="system-images;android-$API;google_apis;$([ $API = 23 ] && echo x86 || echo x86_64)"
  AVD="smoke$API"
  echo "=== API $API ===" >>$OUT/log.txt
  if [ ! -f "$HOME/.android/avd/$AVD.ini" ]; then
    echo "no" | avdmanager create avd -n "$AVD" -k "$IMG_ABI" -d "pixel_4" >>$OUT/log.txt 2>&1
  fi
  emulator -avd "$AVD" -no-window -gpu swiftshader_indirect -no-snapshot-save >/tmp/api-smoke/emu$API.log 2>&1 &
  EMUPID=$!
  SERIAL=""
  for i in $(seq 1 60); do
    sleep 5
    SERIAL=$(adb devices | awk '/^emulator-/{print $1}' | grep -v emulator-5554 | head -1)
    [ -n "$SERIAL" ] && break
  done
  if [ -z "$SERIAL" ]; then echo "API $API: no emulator" >>$OUT/log.txt; kill $EMUPID; continue; fi
  adb -s "$SERIAL" wait-for-device
  for i in $(seq 1 60); do
    BOOT=$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [ "$BOOT" = "1" ] && break; sleep 5
  done
  echo "API $API: booted ($BOOT)" >>$OUT/log.txt
  adb -s "$SERIAL" install -r "$APK" >>$OUT/log.txt 2>&1
  adb -s "$SERIAL" shell am start -n $PKG/.MainActivity >>$OUT/log.txt 2>&1
  sleep 8
  adb -s "$SERIAL" logcat -d | grep -iE "FATAL EXCEPTION|AndroidRuntime.*$PKG|Process: $PKG" | head -5 >>$OUT/log.txt
  adb -s "$SERIAL" exec-out screencap -p >"$OUT/api$API.png" 2>/dev/null
  RUNNING=$(adb -s "$SERIAL" shell pidof $PKG 2>/dev/null | tr -d '\r')
  echo "API $API: pid=$RUNNING shot=$OUT/api$API.png" >>$OUT/log.txt
  kill $EMUPID 2>/dev/null
  sleep 3
done
echo SMOKE_DONE >>$OUT/log.txt

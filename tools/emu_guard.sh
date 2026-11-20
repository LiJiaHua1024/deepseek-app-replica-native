#!/usr/bin/env bash
# tools/emu_guard.sh — watchdog: keep emulator-5554 alive + ready.
# Relaunches on device loss, waits for boot, re-grants perms.
export PATH="$HOME/android-sdk/platform-tools:$HOME/android-sdk/emulator:$PATH"
PKG=io.github.lijiahua1024.dsnative
RPKG=io.github.lijiahua1024.dsreplica
while true; do
  if ! adb -s emulator-5554 get-state 2>/dev/null | grep -q device; then
    pgrep -f "emulator.*-avd test" >/dev/null || {
      setsid nohup emulator -avd test -no-window -no-audio -gpu swiftshader_indirect \
        >>/tmp/emulator_guard.log 2>&1 </dev/null &
    }
    adb -s emulator-5554 wait-for-device 2>/dev/null
    for i in $(seq 1 60); do
      [ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
      sleep 5
    done
    for p in READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_VISUAL_USER_SELECTED READ_EXTERNAL_STORAGE; do
      adb -s emulator-5554 shell pm grant $PKG android.permission.$p 2>/dev/null
    done
    for p in RECORD_AUDIO READ_MEDIA_IMAGES READ_MEDIA_VISUAL_USER_SELECTED; do
      adb -s emulator-5554 shell pm grant $RPKG android.permission.$p 2>/dev/null
    done
    adb -s emulator-5554 shell settings put secure back_gesture_inset_scale_left 1.0 2>/dev/null
    adb -s emulator-5554 shell settings put secure back_gesture_inset_scale_right 1.0 2>/dev/null
  fi
  sleep 10
done

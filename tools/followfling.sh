#!/usr/bin/env bash
# Follow-ratio + fling measurement for the native drawer.
# Emits inject.cmd with: one slow drag (trace dump) then 4 fling swipes at
# {500,1500,3000,6000} device-px/s release velocity, pv + trace per fling.
set -euo pipefail
ADB=${ADB:-$HOME/android-sdk/platform-tools/adb}
SER=${SER:-emulator-5554}
D=/sdcard/Android/data/io.github.lijiahua1024.dsnative/files
OUT=${OUT:-/tmp/followfling_native.log}

{
  echo "launch"; echo "sleep 3500"
  # ensure closed
  echo "seq 950 1200 250 1200 20 200"; echo "sleep 700"
  # follow-ratio drag: 80->730 over 2000ms (slow, no flick)
  echo "seq 80 1200 730 1200 40 2000"
  echo "dumptrace"; echo "pv"; echo "snap f_drag"
  echo "sleep 300"
  echo "seq 950 1200 250 1200 20 200"; echo "sleep 700"
  # flings: dx=400 at v px/s -> dur = 400*1000/v ms
  for v in 500 1500 3000 6000; do
    dur=$((400000 / v))
    echo "flingseq 80 1200 480 1200 20 $dur"
    echo "pv"; echo "dumptrace"
    echo "sleep 800"; echo "snap fl_$v"
    echo "seq 950 1200 250 1200 20 200"; echo "sleep 700"
  done
} > /tmp/followfling.cmd

$ADB -s "$SER" push /tmp/followfling.cmd "$D/inject.cmd" >/dev/null
$ADB -s "$SER" shell "rm -f $D/inject.log"
$ADB -s "$SER" shell am instrument -w -e class io.github.lijiahua1024.dsnative.MotionServer \
  io.github.lijiahua1024.dsnative.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -3
$ADB -s "$SER" exec-out cat "$D/inject.log" > "$OUT"
grep -c "seq-dropped" "$OUT" || true
echo "log -> $OUT"

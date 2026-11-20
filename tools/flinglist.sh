#!/usr/bin/env bash
# tools/flinglist.sh <list:chat|sider> — native inertia-scroll fling probe.
# For each dir x velocity cell: clamp the list to the far bound with three
# heavy opposite flings, read the probe, fire the test fling, read again.
# travel = |p1 - p0| in device px. App must already be in the right state
# (shot.sh chat.long / sider.sessions just ran).
set -euo pipefail
LIST="${1:?chat|sider}"
ADB=${ADB:-$HOME/android-sdk/platform-tools/adb}
SER=${SER:-emulator-5554}
D=/sdcard/Android/data/io.github.lijiahua1024.dsnative/files
OUT=${OUT:-/tmp/flinglist_$LIST.log}

X=540; [ "$LIST" = "sider" ] && X=400
Y_HI=1800; Y_LO=1200; DIST=400

{
  echo "launch"; echo "sleep 3000"
  if [ "$LIST" = "chat" ]; then
    # launch restores an empty session (Welcome branch has no list): open the
    # drawer and tap the top session row (most recent = the long chat just
    # driven by shot.sh) so the message list exists before flinging
    echo "seq 80 1200 400 1200 20 200"; echo "sleep 900"
    echo "tap 300 500"; echo "sleep 1500"
  else
    # open the drawer via edge drag
    echo "seq 80 1200 400 1200 20 200"; echo "sleep 900"
  fi
  echo "scrollreset"
  for dir in up down; do
    for v in 500 1500 3000 6000; do
      dur=$((400000 / v))
      # clamp to far bound: for dir=up (finger up-swipe, scrolls down toward
      # the bottom) start at the top; for dir=down start at the bottom
      for i in 1 2 3; do
        if [ "$dir" = up ]; then
          echo "flingseq $X $Y_LO $X $((Y_LO+DIST)) 20 66"
        else
          echo "flingseq $X $Y_HI $X $((Y_HI-DIST)) 20 66"
        fi
        echo "sleep 1200"
      done
      echo "scrollreset"
      echo "scroll $LIST"
      if [ "$dir" = up ]; then
        echo "flingseq $X $Y_HI $X $((Y_HI-DIST)) 20 $dur"
      else
        echo "flingseq $X $Y_LO $X $((Y_LO+DIST)) 20 $dur"
      fi
      echo "sleep 2500"
      echo "scroll $LIST"
    done
  done
} > /tmp/flinglist_$LIST.cmd

# pm clear wipes the external files dir; recreate it before push (adb push
# cannot mkdir under /sdcard/Android/data on this API level)
$ADB -s "$SER" shell run-as io.github.lijiahua1024.dsnative mkdir -p "$D" 2>/dev/null || true
$ADB -s "$SER" push /tmp/flinglist_$LIST.cmd "$D/inject.cmd" >/dev/null
$ADB -s "$SER" shell "rm -f $D/inject.log"
$ADB -s "$SER" shell am instrument -w -e class io.github.lijiahua1024.dsnative.MotionServer \
  io.github.lijiahua1024.dsnative.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -3
$ADB -s "$SER" exec-out cat "$D/inject.log" > "$OUT"
echo "log -> $OUT"

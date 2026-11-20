#!/usr/bin/env bash
# tools/nativegrid.sh — drawer gesture grid for the native app.
# ONE instrument run: MotionServer launches MainActivity inside the
# instrumented process, injects precise-eventTime seq swipes, and saves
# UiAutomation screenshots between combos -> /tmp/ngrid_snap_*.png.
# Output: /tmp/gesture_grid_native.json
set -uo pipefail
export PATH="$HOME/android-sdk/platform-tools:$PATH"
SERIAL="${SERIAL:-emulator-5554}"
ADB="adb -s $SERIAL"
PKG=io.github.lijiahua1024.dsnative
TEST_PKG=io.github.lijiahua1024.dsnative.test
RUNNER=$TEST_PKG/androidx.test.runner.AndroidJUnitRunner
CLS=io.github.lijiahua1024.dsnative.MotionServer
DIR=/sdcard/Android/data/$PKG/files

$ADB shell settings put secure back_gesture_inset_scale_left 0.0 >/dev/null 2>&1 || true
$ADB shell settings put secure back_gesture_inset_scale_right 0.0 >/dev/null 2>&1 || true
$ADB shell rm -f $DIR/inject.cmd $DIR/inject.log $DIR/inject.done "$DIR/snap_*.png" >/dev/null 2>&1

# build one command batch for the whole 5x5x3 grid
{
  echo "launch"; echo "sleep 3500"
  for dx in 100 250 450 650 850; do
    for dur in 80 200 400 700 1200; do
      for dy in 0 150 400; do
        k="${dx}_${dur}_${dy}"
        # ensure closed: fast left swipe + settle, snapshot pre-state
        echo "seq 950 1200 250 1200 20 200"; echo "sleep 700"
        echo "snap pre_$k"
        echo "seq 80 1200 $((80+dx)) $((1200+dy)) 20 $dur"; echo "sleep 800"
        echo "snap open_$k"
        echo "seq 850 1200 $((850-dx)) $((1200+dy)) 20 $dur"; echo "sleep 800"
        echo "snap close_$k"
      done
    done
  done
} > /tmp/ngrid.cmd
$ADB push /tmp/ngrid.cmd $DIR/inject.cmd >/dev/null
$ADB shell am instrument -w -e class $CLS#serve $RUNNER 2>&1 | tail -3

mkdir -p /tmp/ngrid
$ADB shell "ls $DIR/snap_*.png" | tr -d '\r' | while read -r f; do
  $ADB pull "$f" "/tmp/ngrid/$(basename "$f")" >/dev/null 2>&1
done

python3 - <<'PYEOF'
import json, re
from PIL import Image
import numpy as np
def state(p):
    try:
        g = np.array(Image.open(p).convert('RGB'), dtype=int).mean(2)
        return 'open' if int((g[220:300, 60:800] < 200).sum()) > 100 else 'closed'
    except Exception:
        return 'closed'
out = []
for dx in (100,250,450,650,850):
    for dur in (80,200,400,700,1200):
        for dy in (0,150,400):
            k = f"{dx}_{dur}_{dy}"
            pre = state(f"/tmp/ngrid/snap_pre_{k}.png")
            op = state(f"/tmp/ngrid/snap_open_{k}.png")
            cl = state(f"/tmp/ngrid/snap_close_{k}.png") if op == 'open' else 'n-a'
            out.append({'dx':dx,'dur':dur,'dy':dy,'open':op,'close':cl,'pre':pre})
json.dump({'combos':out}, open('/tmp/gesture_grid_native.json','w'))
bad = [c for c in out if c['pre'] == 'open']
print('75 combos; pre-state-open (invalid):', len(bad), [ (c['dx'],c['dur'],c['dy']) for c in bad ][:10])
PYEOF

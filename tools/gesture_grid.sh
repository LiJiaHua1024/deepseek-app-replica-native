#!/usr/bin/env bash
# tools/gesture_grid.sh <app> — swipe grid over the drawer gesture.
# Grid: 5 distances x 5 durations x 3 angles, right-swipe (open) then left-swipe
# (close) from the opened state. Writes /tmp/gesture_grid_<app>.json:
#   {combos:[{dx,dur,dy,open_state,close_state}], match: n/total}
set -euo pipefail
export PATH="$HOME/android-sdk/platform-tools:$HOME/tools/node22/bin:$PATH"
APP="${1:?ref|native}"
SERIAL="${SERIAL:-emulator-5554}"
ADB="adb -s $SERIAL"
case "$APP" in
  ref)    PKG=io.github.lijiahua1024.dsreplica ;;
  native) PKG=io.github.lijiahua1024.dsnative ;;
esac

$ADB shell settings put secure back_gesture_inset_scale_left 0.0 >/dev/null 2>&1 || true
$ADB shell settings put secure back_gesture_inset_scale_right 0.0 >/dev/null 2>&1 || true
$ADB shell am force-stop $PKG >/dev/null
$ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || \
  $ADB shell am start -n $PKG/.MainActivity >/dev/null 2>&1 || true
sleep 5

# enter API key if the app asks for one (first run after pm clear happens in shot.sh;
# here we reuse existing state — if apikey screen shows, fill it).
for i in 1 2 3; do
  $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || sleep 1
  $ADB pull /sdcard/ui.xml /tmp/gg_ui.xml >/dev/null 2>&1
  if grep -q "输入 API Key\|Enter.*API.*Key\|输入.*Key" /tmp/gg_ui.xml; then
    python3 - "$PKG" <<'PYEOF'
import re, subprocess, sys
x = open('/tmp/gg_ui.xml').read()
# tap first editable field
m = re.search(r'<node[^>]*class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
if m:
    cx, cy = (int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2
    subprocess.run(['adb','-s','emulator-5554','shell','input','tap',str(cx),str(cy)])
PYEOF
    sleep 0.5
    $ADB shell input text "sk-mock-test-key"; sleep 0.4
    python3 - <<'PYEOF'
import re, subprocess
x = open('/tmp/gg_ui.xml').read()
for m in re.finditer(r'<node[^>]*text="(确定|Confirm|OK)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
    cx, cy = (int(m.group(2))+int(m.group(4)))//2, (int(m.group(3))+int(m.group(5)))//2
    subprocess.run(['adb','-s','emulator-5554','shell','input','tap',str(cx),str(cy)])
PYEOF
    sleep 2
  else
    break
  fi
done
sleep 1

state() {
  $ADB exec-out screencap -p > /tmp/gg_cap.png
  python3 - <<'PYEOF'
from PIL import Image
import numpy as np
im = np.array(Image.open('/tmp/gg_cap.png').convert('RGB'), dtype=int)
g = im.mean(2)
# drawer-open signature: the sider search bar + top icons put dark pixels in
# the strip y150-300; closed main pages keep it uniform.
strip = g[220:300, 60:800]
print('open' if int((strip < 200).sum()) > 100 else 'closed')
PYEOF
}

echo '{"combos":[' > /tmp/gesture_grid_$APP.json
first=1
for dx in 100 250 450 650 850; do
  for dur in 80 200 400 700 1200; do
    for dy in 0 150 400; do
      # ensure our app is foreground, else relaunch
      focus=$($ADB shell dumpsys window 2>/dev/null | grep -o 'mCurrentFocus[^}]*' | head -1 || true)
      if [[ "$focus" != *"$PKG"* ]]; then
        $ADB shell am force-stop $PKG >/dev/null
        $ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
        sleep 4
      fi
      # ensure closed: tap the scrim edge to dismiss an open drawer
      st=$(state)
      if [ "$st" = "open" ]; then
        $ADB shell input tap 1000 1200; sleep 0.7; st=$(state)
        [ "$st" = "open" ] && { $ADB shell input tap 980 600; sleep 0.7; }
      fi
      $ADB shell input swipe 80 1200 $((80+dx)) $((1200+dy)) $dur
      sleep 0.8
      open_state=$(state)
      close_state="n-a"
      if [ "$open_state" = "open" ]; then
        $ADB shell input swipe 1000 1200 $((1000-dx)) $((1200+dy)) $dur
        sleep 0.8
        close_state=$(state)
      fi
      [ $first -eq 0 ] && echo ',' >> /tmp/gesture_grid_$APP.json; first=0
      echo "{\"dx\":$dx,\"dur\":$dur,\"dy\":$dy,\"open\":\"$open_state\",\"close\":\"$close_state\"}" >> /tmp/gesture_grid_$APP.json
    done
  done
done
echo ']}' >> /tmp/gesture_grid_$APP.json
python3 - "$APP" <<'PYEOF'
import json, sys
d = json.load(open(f'/tmp/gesture_grid_{sys.argv[1]}.json'))
print(len(d['combos']), 'combos recorded')
PYEOF

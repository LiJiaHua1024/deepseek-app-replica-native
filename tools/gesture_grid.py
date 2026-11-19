#!/usr/bin/env python3
# tools/gesture_grid.py <app> — drawer-gesture swipe grid with majority voting.
# Each cell runs 3x; the outcome is the majority. Ref WebView delivery is
# stochastic in the 80-400ms band (Chromium holds move delivery ~290ms), so a
# single sample is not representative. Writes /tmp/gesture_grid_<app>.json with
# per-cell {dx,dur,dy,open,close,runs}.
import subprocess, time, json, sys
from PIL import Image
import numpy as np

ADB = ['adb', '-s', 'emulator-5554']
APP = sys.argv[1]
PKG = {'ref': 'io.github.lijiahua1024.dsreplica',
       'native': 'io.github.lijiahua1024.dsnative'}[APP]
RUNS = 3

def state():
    subprocess.run(ADB + ['exec-out', 'screencap', '-p'], stdout=open('/tmp/gg.png', 'wb'))
    g = np.array(Image.open('/tmp/gg.png').convert('L'))
    return 'open' if int((g[220:300, 60:800] < 200).sum()) > 100 else 'closed'

def ensure_fg():
    f = subprocess.run(ADB + ['shell', 'dumpsys', 'window'], capture_output=True, text=True).stdout
    cur = f.split('mCurrentFocus')[1] if 'mCurrentFocus' in f else ''
    if PKG not in cur:
        subprocess.run(ADB + ['shell', 'am', 'force-stop', PKG], capture_output=True)
        subprocess.run(ADB + ['shell', 'monkey', '-p', PKG, '-c',
                              'android.intent.category.LAUNCHER', '1'], capture_output=True)
        time.sleep(4)

subprocess.run(ADB + ['shell', 'settings', 'put', 'secure', 'back_gesture_inset_scale_left', '0.0'],
               capture_output=True)
subprocess.run(ADB + ['shell', 'am', 'force-stop', PKG], capture_output=True)
subprocess.run(ADB + ['shell', 'monkey', '-p', PKG, '-c',
                      'android.intent.category.LAUNCHER', '1'], capture_output=True)
time.sleep(5)

combos = {}
for run in range(RUNS):
    for dx in [100, 250, 450, 650, 850]:
        for dur in [80, 200, 400, 700, 1200]:
            for dy in [0, 150, 400]:
                key = f'{dx},{dur},{dy}'
                cell = combos.setdefault(key, {'open': [], 'close': []})
                ensure_fg()
                if state() == 'open':
                    subprocess.run(ADB + ['shell', 'input', 'tap', '1000', '1200'])
                    time.sleep(0.6)
                subprocess.run(ADB + ['shell', 'input', 'swipe', '80', '1200',
                                      str(80 + dx), str(1200 + dy), str(dur)])
                time.sleep(0.7)
                o = state()
                cell['open'].append(o)
                if o == 'open':
                    subprocess.run(ADB + ['shell', 'input', 'swipe', '1000', '1200',
                                          str(1000 - dx), str(1200 + dy), str(dur)])
                    time.sleep(0.7)
                    cell['close'].append(state())

def majority(v):
    return 'open' if v.count('open') > v.count('closed') else 'closed'

out = {'combos': []}
for key, cell in combos.items():
    dx, dur, dy = map(int, key.split(','))
    out['combos'].append({'dx': dx, 'dur': dur, 'dy': dy,
                          'open': majority(cell['open']) if cell['open'] else 'n-a',
                          'close': majority(cell['close']) if cell['close'] else 'n-a',
                          'runs': cell})
json.dump(out, open(f'/tmp/gesture_grid_{APP}.json', 'w'), indent=1)
print(len(out['combos']), 'combos recorded')

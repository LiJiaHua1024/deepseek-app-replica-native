#!/usr/bin/env python3
import json, sys

n = json.load(open('/tmp/gesture_grid_native.json'))['combos']
r = json.load(open('/tmp/gesture_grid_ref.json'))['combos']
key = lambda c: (c['dx'], c['dur'], c['dy'])
nm = {key(c): c for c in n}
rm = {key(c): c for c in r}
mismatch = []
total = 0
for k in rm:
    if k not in nm:
        continue
    total += 1
    a, b = nm[k], rm[k]
    if a['open'] != b['open'] or (b['open'] == 'open' and a['close'] != b['close']):
        mismatch.append((k, f"n:{a['open']}/{a['close']}", f"r:{b['open']}/{b['close']}"))
print(f"match {total - len(mismatch)}/{total} = {(total - len(mismatch)) / total * 100:.1f}%")
for m in mismatch:
    print(m)

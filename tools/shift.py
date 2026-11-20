#!/usr/bin/env python3
# tools/shift.py <imgA> <imgB> — vertical content shift in px between two
# screenshots via 1-D FFT cross-correlation of a mid-screen row profile.
# Positive = content moved down (scrollTop decreased).
import sys
import numpy as np
from PIL import Image

a = np.asarray(Image.open(sys.argv[1]).convert('L'), dtype=np.float64)
b = np.asarray(Image.open(sys.argv[2]).convert('L'), dtype=np.float64)
# band avoiding topbar/composer: y 400-1700, x 60-1020
ya, yb = a[400:1700, 60:1020], b[400:1700, 60:1020]
# row profile = per-row std across x (distinctive for text bands)
pa = ya.std(axis=1) - ya.std(axis=1).mean()
pb = yb.std(axis=1) - yb.std(axis=1).mean()
n = 1 << (len(pa) * 2 - 1).bit_length()
fa, fb = np.fft.rfft(pa, n), np.fft.rfft(pb, n)
cc = np.fft.irfft(fa * np.conj(fb), n)
cc = np.concatenate([cc[-len(pa) + 1:], cc[:len(pa)]])
lag = int(np.argmax(cc)) - len(pa) + 1
print(-lag)

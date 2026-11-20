#!/usr/bin/env python3
"""transcheck.py <ref_dir> <nat_dir> [x0 y0 x1 y1] [edge]

Compare two transition recordings frame-by-frame.
- duration: frames between first and last 'changed' frame (mad vs final > eps);
  PASS when |dur_ref - dur_nat| <= 2.
- mean: blurred ssim between ref and native frames at matched progress points
  (5 samples inside the active window); PASS when >= 0.97.
- edge mode: also tracks the transition front x-position per frame (column of
  max per-column MAD between adjacent frames inside the window); PASS when the
  max position delta at matched progress <= 3px.
Prints one line: dur_ref dur_nat mean_ssim [max_pos_px].
"""
import sys, os, glob
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare import compare, load  # noqa: E402

EPS = 1.5  # mean-abs-diff (0..255) above which a frame counts as 'changed'


def frames(d, box):
    out = []
    for p in sorted(glob.glob(os.path.join(d, '*.png'))):
        a = load(p)
        if box:
            x0, y0, x1, y1 = box
            a = a[y0:y1, x0:x1]
        out.append(a)
    return out


def mad(a, b):
    h = min(a.shape[0], b.shape[0]); w = min(a.shape[1], b.shape[1])
    return float(np.abs(a[:h, :w] - b[:h, :w]).mean())


def window(fs):
    end = fs[-1]
    d = [mad(f, end) for f in fs]
    idx = [i for i, v in enumerate(d) if v > EPS]
    if not idx:
        return 0, len(fs) - 1, d
    return idx[0], idx[-1], d


def edge_pos(fs):
    """transition-front x per frame (max per-column MAD vs previous frame)."""
    pos = []
    prev = fs[0]
    for f in fs:
        h = min(f.shape[0], prev.shape[0])
        col = np.abs(f[:h] - prev[:h]).mean(axis=(0, 2))
        pos.append(float(np.argmax(col)) if col.size else 0.0)
        prev = f
    return np.array(pos)


def pick(fs, i0, i1, n=5):
    if i1 <= i0:
        return [fs[i0]]
    idx = np.linspace(i0, i1, n).round().astype(int)
    return [fs[i] for i in idx]


def main():
    ref_d, nat_d = sys.argv[1], sys.argv[2]
    box = None
    mode = 'fade'
    rest = sys.argv[3:]
    if rest and rest[-1] == 'edge':
        mode = 'edge'; rest = rest[:-1]
    if rest:
        box = tuple(int(v) for v in rest[:4])
    rf, nf = frames(ref_d, box), frames(nat_d, box)
    r0, r1, _ = window(rf)
    n0, n1, _ = window(nf)
    dur_ref, dur_nat = r1 - r0 + 1, n1 - n0 + 1
    # matched-progress ssim over the overlapping active window
    rr, nn = pick(rf, r0, r1), pick(nf, n0, n1)
    k = min(len(rr), len(nn))
    ss = [compare_img(rr[i], nn[i]) for i in range(k)]
    mean = float(np.mean(ss)) if ss else 0.0
    line = f"dur_ref={dur_ref} dur_nat={dur_nat} ssim={mean:.4f}"
    if mode == 'edge':
        rp, np_ = edge_pos(rf), edge_pos(nf)
        t = np.linspace(0, 1, 11)
        ri = np.interp(t, np.linspace(0, 1, len(rp)), rp)
        ni = np.interp(t, np.linspace(0, 1, len(np_)), np_)
        line += f" max_pos_px={float(np.abs(ri - ni).max()):.1f}"
    print(line)


def compare_img(a, b):
    h = min(a.shape[0], b.shape[0]); w = min(a.shape[1], b.shape[1])
    from compare import metrics
    mask = np.zeros((h, w), bool)
    ssim, _, _ = metrics(a[:h, :w], b[:h, :w], mask)
    return ssim


if __name__ == '__main__':
    main()

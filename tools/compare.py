#!/usr/bin/env python3
"""compare.py — perceptual comparison of two same-size screenshots.

usage:
  compare.py A.png B.png [--mask mask.json] [--noise .agent/noise.json]
      -> JSON {blurred_ssim, color_mad, worst: [{x,y,w,h,blurred_ssim} x3],
               pass, thresholds}
  compare.py --all [--shots /tmp/shots] [--noise .agent/noise.json]
      -> writes .agent/scores.json for every scene shot in both apps.

Metrics (masked pixels excluded):
  blurred_ssim: SSIM on grayscale after gaussian blur sigma=3px (11x11 window).
  color_mad:    mean over R,G,B of mean |a-b| after the same blur, 0-255.
Masks: status bar, nav bar, and regions declared in .agent/mask.json only.
Noise floor: .agent/noise.json (ref-vs-ref repeatability) is subtracted from the
raw metrics before thresholding: pass = (ssim - (1-noise_ssim)) >= 0.98 and
(mad - noise_mad) <= 4.
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter, uniform_filter

REPO = Path(__file__).resolve().parent.parent
STATUS_BAR_PX = 88   # top mask (system status bar @440dpi)
NAV_BAR_PX = 64      # bottom mask (gesture pill area)
TILE = 293           # worst-rect tile size (~grid 4x8 on 1080x2340)

THRESH_SSIM = 0.98
THRESH_MAD = 4.0


def load(p):
    return np.asarray(Image.open(p).convert('RGB'), dtype=np.float64)


def default_mask(shape):
    h, w = shape
    m = np.zeros((h, w), dtype=bool)
    m[:STATUS_BAR_PX, :] = True
    m[h - NAV_BAR_PX:, :] = True
    return m


def load_extra_masks(path, shape):
    m = np.zeros(shape, dtype=bool)
    if path and Path(path).exists():
        for r in json.loads(Path(path).read_text()).get('regions', []):
            m[r['y']:r['y'] + r['h'], r['x']:r['x'] + r['w']] = True
    return m


def ssim_map(a, b):
    """windowed SSIM (uniform 11x11) on float grayscale arrays."""
    C1, C2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    win = 11
    mu_a = uniform_filter(a, win)
    mu_b = uniform_filter(b, win)
    sa = uniform_filter(a * a, win) - mu_a ** 2
    sb = uniform_filter(b * b, win) - mu_b ** 2
    sab = uniform_filter(a * b, win) - mu_a * mu_b
    return ((2 * mu_a * mu_b + C1) * (2 * sab + C2)) / ((mu_a ** 2 + mu_b ** 2 + C1) * (sa + sb + C2))


def metrics(a, b, mask):
    """a,b RGB float64; mask True = ignored."""
    keep = ~mask
    ga = np.zeros_like(a[:, :, 0])
    gb = np.zeros_like(a[:, :, 0])
    ba = np.zeros_like(a)
    bb = np.zeros_like(b)
    for ch in range(3):
        ba[:, :, ch] = gaussian_filter(a[:, :, ch], 3)
        bb[:, :, ch] = gaussian_filter(b[:, :, ch], 3)
    ga = ba.mean(axis=2)
    gb = bb.mean(axis=2)
    smap = ssim_map(ga, gb)
    ssim = float(smap[keep].mean())
    diffs = np.abs(ba - bb).mean(axis=2)
    mad = float(diffs[keep].mean())
    return ssim, mad, smap


def worst_rects(smap, mask, n=3, tile=TILE):
    h, w = smap.shape
    out = []
    used = np.zeros_like(mask)
    cand = []
    for y in range(0, h, tile):
        for x in range(0, w, tile):
            y2, x2 = min(y + tile, h), min(x + tile, w)
            region = np.zeros(h * w, bool).reshape(h, w)
            region[y:y2, x:x2] = True
            k = region & ~mask
            if k.sum() < 500:
                continue
            cand.append((float(smap[k].mean()), x, y, x2 - x, y2 - y))
    cand.sort()
    for s, x, y, tw, th in cand:
        if len(out) >= n:
            break
        if used[y:y + th, x:x + tw].mean() > 0.5:
            continue
        out.append({'x': x, 'y': y, 'w': tw, 'h': th, 'blurred_ssim': round(s, 4)})
        used[y:y + th, x:x + tw] = True
    return out


def noise_floor(noise_path, scene=None):
    if not noise_path or not Path(noise_path).exists():
        return 0.0, 0.0
    n = json.loads(Path(noise_path).read_text())
    ssim_loss, mad_n = n.get('global', {}).get('ssim_loss', 0.0), n.get('global', {}).get('mad', 0.0)
    if scene and scene in n.get('scenes', {}):
        s = n['scenes'][scene]
        ssim_loss = max(ssim_loss, s.get('ssim_loss', 0.0))
        mad_n = max(mad_n, s.get('mad', 0.0))
    return ssim_loss, mad_n


def compare(a_path, b_path, mask_extra=None, mask_path=None, noise_path=None, scene=None):
    a, b = load(a_path), load(b_path)
    if a.shape != b.shape:
        raise SystemExit(f'size mismatch {a.shape} vs {b.shape}')
    mask = default_mask(a.shape[:2])
    if mask_extra is not None:
        mask = mask | mask_extra
    if mask_path:
        mask = mask | load_extra_masks(mask_path, a.shape[:2])
    ssim, mad, smap = metrics(a, b, mask)
    ssim_loss, mad_noise = noise_floor(noise_path, scene)
    adj_ssim = ssim - ssim_loss
    adj_mad = mad - mad_noise
    return {
        'blurred_ssim': round(ssim, 4),
        'color_mad': round(mad, 3),
        'noise_ssim_loss': round(ssim_loss, 4),
        'noise_mad': round(mad_noise, 3),
        'adj_ssim': round(adj_ssim, 4),
        'adj_mad': round(adj_mad, 3),
        'worst': worst_rects(smap, mask),
        'pass': bool(adj_ssim >= THRESH_SSIM and adj_mad <= THRESH_MAD),
        'thresholds': {'blurred_ssim': THRESH_SSIM, 'color_mad': THRESH_MAD},
    }


def main():
    args = sys.argv[1:]
    if '--all' in args:
        shots = Path('/tmp/shots')
        results = {}
        for ref_png in sorted((shots / 'ref').glob('*.png')):
            scene = ref_png.stem
            nat = shots / 'native' / ref_png.name
            if not nat.exists():
                results[scene] = {'error': 'missing native shot'}
                continue
            results[scene] = compare(str(ref_png), str(nat),
                                     mask_path=str(REPO / '.agent' / 'mask.json'),
                                     noise_path=str(REPO / '.agent' / 'noise.json'),
                                     scene=scene)
        scores = {
            'thresholds': {'blurred_ssim': THRESH_SSIM, 'color_mad': THRESH_MAD},
            'noise_adjusted': True,
            'scenes': results,
        }
        ok = [v for v in results.values() if 'adj_ssim' in v]
        if ok:
            scores['mean_blurred_ssim'] = round(float(np.mean([v['adj_ssim'] for v in ok])), 4)
            scores['mean_color_mad'] = round(float(np.mean([v['adj_mad'] for v in ok])), 3)
            scores['all_pass'] = all(v['pass'] for v in ok)
        out = REPO / '.agent' / 'scores.json'
        out.write_text(json.dumps(scores, indent=2, ensure_ascii=False))
        print(json.dumps(scores, indent=2, ensure_ascii=False))
        return
    a, b = args[0], args[1]
    mask_extra = None
    if '--mask' in args:
        mp = args[args.index('--mask') + 1]
        shape = load(a).shape[:2]
        mask_extra = load_extra_masks(mp, shape)
    noise = None
    if '--noise' in args:
        noise = args[args.index('--noise') + 1]
    scene = Path(a).stem
    print(json.dumps(compare(a, b, mask_extra=mask_extra, mask_path=str(REPO / '.agent' / 'mask.json') if (REPO / '.agent' / 'mask.json').exists() else None, noise_path=noise, scene=scene), indent=2, ensure_ascii=False))


if __name__ == '__main__':
    main()

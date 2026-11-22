#!/usr/bin/env python3
"""Measure Laplacian variance attenuation behind modal/popup overlays."""
import argparse
import json
from pathlib import Path

import cv2


def lap_var(path: Path, roi) -> float:
    img = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
    if roi:
        x0, y0, x1, y1 = roi
        img = img[y0:y1, x0:x1]
    return float(cv2.Laplacian(img, cv2.CV_64F).var())


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--off-before", required=True, type=Path)
    p.add_argument("--off-after", required=True, type=Path)
    p.add_argument("--nat-before", required=True, type=Path)
    p.add_argument("--nat-after", required=True, type=Path)
    p.add_argument("--roi", nargs=4, type=int, required=True)
    args = p.parse_args()
    ob, oa = lap_var(args.off_before, args.roi), lap_var(args.off_after, args.roi)
    nb, na = lap_var(args.nat_before, args.roi), lap_var(args.nat_after, args.roi)
    off_ratio = oa / ob if ob else 1.0
    nat_ratio = na / nb if nb else 1.0
    print(
        json.dumps(
            {
                "official": {"before": round(ob, 2), "after": round(oa, 2), "attenuation": round(1 - off_ratio, 4)},
                "native": {"before": round(nb, 2), "after": round(na, 2), "attenuation": round(1 - nat_ratio, 4)},
                "attenuation_diff": round(abs((1 - nat_ratio) - (1 - off_ratio)), 4),
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()

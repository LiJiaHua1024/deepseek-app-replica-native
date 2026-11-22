#!/usr/bin/env python3
"""Compare official and native screenshots (blurred SSIM, MAD, connected diff blocks, XML bounds)."""
import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path

import cv2
import numpy as np


def ssim_map_gray(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    c1 = (0.01 * 255) ** 2
    c2 = (0.03 * 255) ** 2
    mu_a = cv2.boxFilter(a, -1, (11, 11), borderType=cv2.BORDER_REFLECT)
    mu_b = cv2.boxFilter(b, -1, (11, 11), borderType=cv2.BORDER_REFLECT)
    sa = cv2.boxFilter(a * a, -1, (11, 11), borderType=cv2.BORDER_REFLECT) - mu_a * mu_a
    sb = cv2.boxFilter(b * b, -1, (11, 11), borderType=cv2.BORDER_REFLECT) - mu_b * mu_b
    sab = cv2.boxFilter(a * b, -1, (11, 11), borderType=cv2.BORDER_REFLECT) - mu_a * mu_b
    num = (2.0 * mu_a * mu_b + c1) * (2.0 * sab + c2)
    den = (mu_a * mu_a + mu_b * mu_b + c1) * (sa + sb + c2)
    return num / den


def compute_diff_report(
    off_path: Path,
    nat_path: Path,
    status_mask: int = 80,
    nav_mask: int = 46,
    pixel_thresh: int = 8,
    top_n: int = 8,
) -> dict:
    off = cv2.cvtColor(cv2.imread(str(off_path)), cv2.COLOR_BGR2RGB).astype(np.float64)
    nat = cv2.cvtColor(cv2.imread(str(nat_path)), cv2.COLOR_BGR2RGB).astype(np.float64)
    if off.shape != nat.shape:
        raise ValueError(f"shape mismatch: {off.shape} vs {nat.shape}")
    h, w, _ = off.shape
    mask = np.zeros((h, w), dtype=bool)
    if status_mask > 0:
        mask[:status_mask, :] = True
    if nav_mask > 0:
        mask[h - nav_mask :, :] = True
    keep = ~mask

    off_blur = cv2.GaussianBlur(off, (0, 0), sigmaX=3.0, sigmaY=3.0, borderType=cv2.BORDER_REFLECT)
    nat_blur = cv2.GaussianBlur(nat, (0, 0), sigmaX=3.0, sigmaY=3.0, borderType=cv2.BORDER_REFLECT)

    smap = ssim_map_gray(off_blur.mean(axis=2), nat_blur.mean(axis=2))
    blurred_ssim = float(smap[keep].mean())
    color_mad = float(np.abs(off_blur - nat_blur).mean(axis=2)[keep].mean())

    raw_diff = np.abs(off - nat).mean(axis=2)
    raw_diff[mask] = 0.0
    binary = (raw_diff > pixel_thresh).astype(np.uint8)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    dilated = cv2.dilate(binary, kernel, iterations=1)
    num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(dilated, connectivity=8)

    regions = []
    for lbl in range(1, num_labels):
        x, y, bw, bh, _ = stats[lbl]
        comp_mask = (labels == lbl) & (binary > 0)
        area_px = int(comp_mask.sum())
        if area_px < 16:
            continue
        mean_diff = float(raw_diff[comp_mask].mean())
        off_dom = [int(round(v)) for v in np.median(off[comp_mask], axis=0)]
        nat_dom = [int(round(v)) for v in np.median(nat[comp_mask], axis=0)]
        regions.append(
            {
                "x": int(x),
                "y": int(y),
                "w": int(bw),
                "h": int(bh),
                "area_px": area_px,
                "mean_rgb_diff": round(mean_diff, 2),
                "official_dominant_rgb": off_dom,
                "native_dominant_rgb": nat_dom,
            }
        )
    regions.sort(key=lambda r: r["area_px"], reverse=True)
    return {
        "official": str(off_path),
        "native": str(nat_path),
        "size": [w, h],
        "blurred_ssim": round(blurred_ssim, 4),
        "color_mad": round(color_mad, 3),
        "top_diff_regions": regions[:top_n],
    }


def parse_xml_nodes(xml_path: Path):
    import re

    out = []
    for n in ET.parse(xml_path).iter("node"):
        text = n.get("text") or ""
        desc = n.get("content-desc") or ""
        if not text and not desc:
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if not m:
            continue
        b = [int(v) for v in m.groups()]
        out.append(
            {
                "label": text or desc,
                "kind": "text" if text else "desc",
                "bounds": b,
                "selected": n.get("selected") == "true",
                "checked": n.get("checked") == "true",
            }
        )
    return out


def compare_xml(off_xml: Path, nat_xml: Path):
    off_nodes = parse_xml_nodes(off_xml)
    nat_nodes = parse_xml_nodes(nat_xml)
    nat_by_label = {}
    for n in nat_nodes:
        nat_by_label.setdefault(n["label"], []).append(n)
    diffs = []
    for o in off_nodes:
        cands = nat_by_label.get(o["label"], [])
        if not cands:
            diffs.append({"label": o["label"], "status": "missing_in_native", "official": o["bounds"]})
            continue
        best = min(
            cands,
            key=lambda c: sum(abs(a - b) for a, b in zip(o["bounds"], c["bounds"])),
        )
        delta = [b - a for a, b in zip(o["bounds"], best["bounds"])]
        if any(abs(d) > 2 for d in delta) or o["checked"] != best["checked"]:
            diffs.append(
                {
                    "label": o["label"],
                    "official": o["bounds"],
                    "native": best["bounds"],
                    "delta_ltrb": delta,
                    "checked_match": o["checked"] == best["checked"],
                }
            )
    return diffs


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--official", required=True, type=Path)
    p.add_argument("--native", required=True, type=Path)
    p.add_argument("--official-xml", type=Path)
    p.add_argument("--native-xml", type=Path)
    p.add_argument("--status-mask", type=int, default=80)
    p.add_argument("--nav-mask", type=int, default=46)
    args = p.parse_args()
    rep = compute_diff_report(args.official, args.native, args.status_mask, args.nav_mask)
    if args.official_xml and args.native_xml:
        rep["xml_diffs"] = compare_xml(args.official_xml, args.native_xml)
    print(json.dumps(rep, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

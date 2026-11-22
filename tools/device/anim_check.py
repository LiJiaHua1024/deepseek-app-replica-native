#!/usr/bin/env python3
"""Measure animation duration, PTS timing, edge/front displacement, and progress SSIM from two MP4 recordings."""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np

from diff_report import ssim_map_gray


def extract_frames_with_pts(mp4_path: Path, roi=None):
    cap = cv2.VideoCapture(str(mp4_path))
    if not cap.isOpened():
        raise RuntimeError(f"cannot open video: {mp4_path}")
    frames = []
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        pts = float(cap.get(cv2.CAP_PROP_POS_MSEC))
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB).astype(np.float64)
        if roi:
            x0, y0, x1, y1 = roi
            rgb = rgb[y0:y1, x0:x1]
        frames.append((pts, rgb))
    cap.release()
    return frames


def active_window(frames, eps: float = 0.8):
    first = frames[0][1]
    last = frames[-1][1]
    from_start = [float(np.abs(f - first).mean()) for _, f in frames]
    to_end = [float(np.abs(f - last).mean()) for _, f in frames]
    start_idx = next((i for i, d in enumerate(from_start) if d > eps), 0)
    # Include the pre-motion frame so start->first movement delta is captured
    start_idx = max(0, start_idx - 1)
    end_idx = max([i for i, d in enumerate(to_end) if d > eps] + [start_idx]) + 1
    end_idx = min(end_idx, len(frames) - 1)
    return start_idx, end_idx, from_start, to_end


def edge_trajectory(frames, i0: int, i1: int, axis: str = "x"):
    """Track moving edge position per frame using vertical/horizontal profile."""
    pts0 = frames[i0][0]
    base = frames[i0][1]
    traj = []
    for i in range(i0, i1 + 1):
        pts, f = frames[i]
        diff = np.abs(f - base).mean(axis=2)
        prof = diff.mean(axis=0 if axis == "x" else 1)
        active = np.where(prof > 2.5)[0]
        front = float(active[-1]) if len(active) else 0.0
        back = float(active[0]) if len(active) else 0.0
        if i > i0:
            step = np.abs(f - frames[i - 1][1]).mean(axis=2).mean(axis=0 if axis == "x" else 1)
            peak = float(np.argmax(step)) if step.max() > 1.0 else None
        else:
            peak = None
        traj.append(
            {
                "t_ms": round(pts - pts0, 2),
                "mad_from_start": round(float(diff.mean()), 2),
                "span_start_px": back,
                "span_end_px": front,
                "step_peak_px": peak,
            }
        )
    return traj


def compare_videos(off_mp4: Path, nat_mp4: Path, roi=None, axis: str = "x"):
    of = extract_frames_with_pts(off_mp4, roi)
    nf = extract_frames_with_pts(nat_mp4, roi)
    o0, o1, _, _ = active_window(of)
    n0, n1, _, _ = active_window(nf)
    o_dur_ms = of[o1][0] - of[o0][0]
    n_dur_ms = nf[n1][0] - nf[n0][0]
    o_traj = edge_trajectory(of, o0, o1, axis)
    n_traj = edge_trajectory(nf, n0, n1, axis)

    ssims = []
    for p in (0.0, 0.25, 0.5, 0.75, 1.0):
        oi = int(round(o0 + p * (o1 - o0)))
        ni = int(round(n0 + p * (n1 - n0)))
        oa = cv2.GaussianBlur(of[oi][1], (0, 0), 3.0).mean(axis=2)
        na = cv2.GaussianBlur(nf[ni][1], (0, 0), 3.0).mean(axis=2)
        ssims.append(round(float(ssim_map_gray(oa, na).mean()), 4))

    return {
        "official": {"active_frames": o1 - o0 + 1, "duration_ms": round(o_dur_ms, 2), "trajectory": o_traj},
        "native": {"active_frames": n1 - n0 + 1, "duration_ms": round(n_dur_ms, 2), "trajectory": n_traj},
        "duration_diff_ms": round(n_dur_ms - o_dur_ms, 2),
        "frame_diff": (n1 - n0) - (o1 - o0),
        "progress_ssim": ssims,
        "mean_progress_ssim": round(float(np.mean(ssims)), 4),
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--official", required=True, type=Path)
    p.add_argument("--native", required=True, type=Path)
    p.add_argument("--roi", nargs=4, type=int, help="x0 y0 x1 y1")
    p.add_argument("--axis", default="x", choices=("x", "y"))
    args = p.parse_args()
    print(json.dumps(compare_videos(args.official, args.native, args.roi, args.axis), indent=2))


if __name__ == "__main__":
    main()

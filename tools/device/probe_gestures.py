#!/usr/bin/env python3
"""Cross-app gesture & follow-finger probe using MotionServer on the attached device."""
import argparse
import json
import subprocess
import time
from pathlib import Path

import cv2
import numpy as np

SERIAL = "13a7a291"
FILES_DIR = "/sdcard/Android/data/io.github.lijiahua1024.dsnative/files"


def adb(*args, check=True):
    return subprocess.run(
        ["adb", "-s", SERIAL, *args],
        check=check,
        capture_output=True,
        text=True,
    )


def analyze_drawer_frame(img_rgb: np.ndarray, closed_rgb: np.ndarray):
    """Return card_left_x (0 if closed, ~890 if open), card_top_y, scrim_brightness, open_state."""
    h, w, _ = img_rgb.shape
    drawer_diff = float(np.abs(img_rgb[120:1700, 60:720].astype(float) - closed_rgb[120:1700, 60:720].astype(float)).mean())
    if drawer_diff < 1.0:
        return {"open_ratio": 0.0, "card_left_x": 0, "card_top_y": 0, "state": "CLOSED"}

    # Scan y=[525:565] (clean gap between drawer items & empty white on Welcome) from right to left.
    # The sliding Welcome card [card_left_x .. w] is pure white (>=253.5), while scrim/shadow to its left is <=253.0.
    band = img_rgb[525:565].mean(axis=(0, 2))
    x = w - 3
    while x > 10 and band[x] >= 253.5:
        x -= 1
    card_left_x = 0 if x <= 10 else x + 1

    # Find card top-left/top-edge by checking brightness at x = min(w - 30, card_left_x + 120)
    if 10 < card_left_x < w - 60:
        cx = min(w - 30, card_left_x + 120)
        col = img_rgb[65:220, cx].mean(axis=1)
        wh_y = np.where(col >= 253.0)[0]
        card_top_y = int(65 + wh_y[0]) if len(wh_y) else 65
        scrim_probe_x = max(20, card_left_x - 30)
        scrim_rgb = float(img_rgb[545, scrim_probe_x].mean())
    else:
        card_top_y = 0
        scrim_rgb = 255.0

    if card_left_x <= 12:
        state = "CLOSED"
    elif card_left_x >= 840:
        state = "OPEN"
    else:
        state = "PARTIAL"
    return {
        "state": state,
        "card_left_x": int(card_left_x),
        "card_top_y": int(card_top_y),
        "scrim_545": round(scrim_rgb, 1),
        "drawer_diff": round(drawer_diff, 2),
    }


def run_batch(commands, out_dir: Path, prefix: str):
    out_dir.mkdir(parents=True, exist_ok=True)
    adb("shell", f"rm -f {FILES_DIR}/snap_*.png {FILES_DIR}/inject.log {FILES_DIR}/inject.done")
    cmd_path = out_dir / f"{prefix}_inject.cmd"
    cmd_path.write_text("\n".join(commands) + "\n")
    adb("push", str(cmd_path), f"{FILES_DIR}/inject.cmd")
    t0 = time.time()
    res = adb(
        "shell", "am", "instrument", "-w", "-e", "class",
        "io.github.lijiahua1024.dsnative.MotionServer#serve",
        "io.github.lijiahua1024.dsnative.test/androidx.test.runner.AndroidJUnitRunner",
    )
    elapsed = time.time() - t0
    if "OK (1 test)" not in res.stdout:
        raise RuntimeError(f"MotionServer failed:\n{res.stdout}\n{res.stderr}")
    # Pull all snaps and log
    adb("pull", f"{FILES_DIR}/inject.done", str(out_dir / f"{prefix}_inject.done"))
    ls = adb("shell", f"ls {FILES_DIR}/snap_*.png").stdout.split()
    for remote in ls:
        name = Path(remote).name
        adb("pull", remote, str(out_dir / name))
    print(f"Batch {prefix} finished in {elapsed:.1f}s, pulled {len(ls)} snaps")


def build_drawer_probe_commands(use_raw=True):
    cmds = [
        "tap 972 1080",
        "sleep 400",
        "snap base_closed",
    ]
    drag_fn = "rawdrag" if use_raw else "rawdrag"
    fling_fn = "rawflingseq" if use_raw else "flingseq"
    seq_fn = "rawseq" if use_raw else "seq"

    # 1. Slop + Follow-finger + Distance threshold (pause 150ms before up -> ~0 velocity)
    for dx in [15, 25, 35, 50, 80, 150, 260, 340, 400, 460, 540, 700]:
        tag = f"drag_dx{dx}"
        cmds += [
            f"{drag_fn} 160 1000 {160 + dx} 1000 15 300",
            "sleep 120",
            f"snap mid_{tag}",
            f"up {160 + dx} 1000",
            "sleep 420",
            f"snap end_{tag}",
            "tap 972 1080",
            "sleep 380",
        ]

    # 2. Fast flick sweep
    for dur, dxs in [(90, [30, 50, 70, 90, 120, 160]), (150, [75, 110, 150, 200])]:
        for dx in dxs:
            tag = f"flick_dx{dx}_d{dur}"
            cmds += [
                f"{fling_fn} 80 1000 {80 + dx} 1000 6 {dur}",
                "sleep 420",
                f"snap end_{tag}",
                "tap 972 1080",
                "sleep 380",
            ]

    # 3. Start-X sweep (safe from 0..60px system back-edge zone)
    for sx in [80, 200, 540, 850]:
        ex = min(1070, sx + 210)
        tag = f"startx_{sx}_flick"
        cmds += [
            f"{fling_fn} {sx} 1000 {ex} 1000 6 120",
            "sleep 420",
            f"snap end_{tag}",
            "tap 972 1080",
            "sleep 380",
        ]

    # 4. Angle lock sweep (dx=320, various dy)
    for dy in [40, 100, 160, 220, 300, 420, 600]:
        tag = f"ang_dy{dy}"
        cmds += [
            f"{drag_fn} 160 1000 480 {1000 + dy} 12 240",
            "sleep 100",
            f"snap mid_{tag}",
            f"up 480 {1000 + dy}",
            "sleep 420",
            f"snap end_{tag}",
            "tap 972 1080",
            "sleep 380",
        ]

    # 5. Close tests (open drawer first by tap 78 157)
    for dx in [-100, -220, -320, -420, -540]:
        tag = f"close_dx{abs(dx)}"
        cmds += [
            "tap 78 157",
            "sleep 450",
            f"{drag_fn} 760 1000 {760 + dx} 1000 15 300",
            "sleep 120",
            f"snap mid_{tag}",
            f"up {760 + dx} 1000",
            "sleep 420",
            f"snap end_{tag}",
            "tap 972 1080",
            "sleep 350",
        ]

    for dx in [-40, -70, -100, -140]:
        tag = f"close_flick{abs(dx)}"
        cmds += [
            "tap 78 157",
            "sleep 450",
            f"{fling_fn} 760 1000 {760 + dx} 1000 6 90",
            "sleep 420",
            f"snap end_{tag}",
            "tap 972 1080",
            "sleep 350",
        ]

    # 6. Drawer vertical scroll + slight left drift conflict check
    cmds += [
        "tap 78 157",
        "sleep 450",
        f"{seq_fn} 350 1300 280 950 12 220",
        "sleep 420",
        "snap end_drawer_vert_drift",
        "tap 972 1080",
        "sleep 350",
    ]
    return cmds


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--mode", choices=("official", "native", "analyze"), required=True)
    p.add_argument("--out", type=Path, default=Path("captures/audit_baseline/gesture_probe"))
    args = p.parse_args()

    out_dir = args.out / args.mode if args.mode != "analyze" else args.out
    if args.mode in ("official", "native"):
        pkg = "com.deepseek.chat/.MainActivity" if args.mode == "official" else "io.github.lijiahua1024.dsnative/.MainActivity"
        adb("shell", "am", "start", "-n", pkg)
        time.sleep(1.0)
        # Ensure we start on clean empty New Chat Welcome screen (never in an old session)
        adb("shell", "input", "tap", "972", "1080")
        time.sleep(0.5)
        adb("shell", "input", "tap", "1008", "157")
        time.sleep(0.6)
        cmds = build_drawer_probe_commands(use_raw=True)
        run_batch(cmds, out_dir, args.mode)

    # Analyze
    base_p = out_dir / "snap_base_closed.png"
    if not base_p.exists():
        print("Missing base_closed in", out_dir)
        return
    base = cv2.cvtColor(cv2.imread(str(base_p)), cv2.COLOR_BGR2RGB)
    results = {}
    for sp in sorted(out_dir.glob("snap_*.png")):
        key = sp.stem.replace("snap_", "")
        img = cv2.cvtColor(cv2.imread(str(sp)), cv2.COLOR_BGR2RGB)
        results[key] = analyze_drawer_frame(img, base)
    (out_dir / "results.json").write_text(json.dumps(results, indent=2))
    print(f"=== {out_dir} summary ===")
    for k in sorted(results.keys()):
        r = results[k]
        print(f"  {k:28}: {r}")


if __name__ == "__main__":
    main()

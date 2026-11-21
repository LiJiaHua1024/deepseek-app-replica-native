#!/usr/bin/env python3
import argparse
import json
import math
import statistics
from pathlib import Path

from PIL import Image


def parse_args():
    parser = argparse.ArgumentParser(
        description="Measure edge-to-edge system-bar backgrounds from real screenshots."
    )
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--official", required=True, type=Path)
    parser.add_argument("--native", required=True, type=Path)
    parser.add_argument("--theme", required=True, choices=("light", "dark"))
    parser.add_argument("--mode", required=True, choices=("status", "navigation"))
    parser.add_argument("--tolerance", type=int, default=3)
    return parser.parse_args()


def is_icon_pixel(pixel):
    return max(pixel[0], pixel[1], pixel[2]) - min(pixel[0], pixel[1], pixel[2]) > 12


def sample_band(image, y_start, y_end):
    pixels = [
        image.getpixel((x, y))
        for y in range(y_start, y_end)
        for x in range(0, image.width, 2)
        if not is_icon_pixel(image.getpixel((x, y)))
    ]
    if len(pixels) < image.width // 2:
        raise ValueError("insufficient background pixels after icon exclusion")
    return tuple(round(statistics.median(pixel[channel] for pixel in pixels)) for channel in range(3)), len(pixels)


def sample_navigation_band(image):
    background, count = sample_band(image, max(0, image.height - 34), image.height - 6)
    return background, count


def sample_status_band(image):
    return sample_band(image, 4, max(5, min(20, math.ceil(image.height / 120))))


def diff(left, right):
    return [abs(left[index] - right[index]) for index in range(3)]


def main():
    args = parse_args()
    with Image.open(args.official) as official_image:
        official_image = official_image.convert("RGB")
        if args.mode == "status":
            official_bar, official_count = sample_status_band(official_image)
            official_content, _ = sample_band(official_image, 18, 40)
        else:
            official_bar, official_count = sample_navigation_band(official_image)
            official_content, _ = sample_band(
                official_image, max(0, official_image.height - 70), official_image.height - 40
            )
    with Image.open(args.native) as native_image:
        native_image = native_image.convert("RGB")
        if args.mode == "status":
            native_bar, native_count = sample_status_band(native_image)
            native_content, _ = sample_band(native_image, 18, 40)
        else:
            native_bar, native_count = sample_navigation_band(native_image)
            native_content, _ = sample_band(
                native_image, max(0, native_image.height - 70), native_image.height - 40
            )

    official_bar_content_diff = diff(official_bar, official_content)
    native_bar_content_diff = diff(native_bar, native_content)
    official_native_bar_diff = diff(official_bar, native_bar)
    pass_native_content = max(native_bar_content_diff) <= args.tolerance
    pass_official_native = max(official_native_bar_diff) <= args.tolerance
    passed = pass_native_content and pass_official_native
    result = {
        "serial": args.serial,
        "package": args.package,
        "theme": args.theme,
        "mode": args.mode,
        "tolerance": args.tolerance,
        "official": {"bar_rgb": official_bar, "content_rgb": official_content, "samples": official_count},
        "native": {"bar_rgb": native_bar, "content_rgb": native_content, "samples": native_count},
        "official_bar_content_rgb_diff": official_bar_content_diff,
        "native_bar_content_rgb_diff": native_bar_content_diff,
        "official_native_bar_rgb_diff": official_native_bar_diff,
        "pass_native_content": pass_native_content,
        "pass_official_native": pass_official_native,
        "pass": passed,
    }
    print(json.dumps(result, sort_keys=True))
    raise SystemExit(0 if passed else 1)


if __name__ == "__main__":
    main()

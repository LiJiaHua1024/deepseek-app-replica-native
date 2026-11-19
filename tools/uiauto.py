#!/usr/bin/env python3
"""uiauto.py — uiautomator dump helpers.
usage:
  uiauto.py find <dump.xml> <regex>     -> "x y w h" center+bounds of first node whose
                                         text/content-desc/resource-id matches
  uiauto.py list <dump.xml>             -> "text|desc|cls|bounds" one per node
"""
import re
import sys
import xml.etree.ElementTree as ET


def nodes(path):
    tree = ET.parse(path)
    for el in tree.iter('node'):
        yield el


def attrs(el):
    return (
        el.get('text', ''),
        el.get('content-desc', ''),
        el.get('resource-id', ''),
        el.get('class', ''),
        el.get('bounds', ''),
    )


def bounds_xywh(b):
    m = re.match(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', b)
    if not m:
        return None
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    return x1, y1, x2 - x1, y2 - y1


def main():
    cmd = sys.argv[1]
    if cmd == 'list':
        for el in nodes(sys.argv[2]):
            t, d, r, c, b = attrs(el)
            if t or d:
                print(f"{t}|{d}|{c}|{b}")
        return
    if cmd == 'find':
        path, pat = sys.argv[2], sys.argv[3]
        rx = re.compile(pat)
        for el in nodes(path):
            t, d, r, c, b = attrs(el)
            if rx.search(t) or rx.search(d):
                xy = bounds_xywh(b)
                if xy:
                    x, y, w, h = xy
                    print(x + w // 2, y + h // 2, w, h)
                    return
        sys.exit(1)


if __name__ == '__main__':
    main()

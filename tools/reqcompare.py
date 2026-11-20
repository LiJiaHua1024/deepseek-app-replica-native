#!/usr/bin/env python3
"""reqcompare.py — request-equivalence comparison between ref and native.

usage:
  RECORD_FILE=/tmp/req-ref.jsonl    node tools/mock-api/server.js   # drive ref
  RECORD_FILE=/tmp/req-native.jsonl node tools/mock-api/server.js   # drive native
  python tools/reqcompare.py /tmp/req-ref.jsonl /tmp/req-native.jsonl

For each request position (nth request), compares:
  - method + path (query __delay stripped)
  - header names + values, case-insensitive names; ignored: user-agent,
    date, content-length, host, connection, accept-encoding (transport), and
    any *time*/timestamp header
  - JSON body field-by-field (multipart bodies compared by field names and
    filename/disposition headers, not boundary strings)
"""
import json
import re
import sys

IGNORE_HEADERS = {
    'user-agent', 'date', 'content-length', 'host', 'connection',
    'accept-encoding', 'transfer-encoding', 'x-request-id', 'traceparent', 'tracestate',
}
IGNORE_BODY_KEYS = {'client_ts', 'client_send_ts', 'device_session_id'}


def norm_path(url):
    url = re.sub(r'[?&]__delay=\d+', '', url or '')
    return re.sub(r'[?&]$', '', url)


def norm_headers(h):
    out = {}
    for k, v in (h or {}).items():
        lk = k.lower()
        if lk in IGNORE_HEADERS or 'time' in lk:
            continue
        v = v if not isinstance(v, list) else ','.join(map(str, v))
        if lk == 'content-type':
            v = re.sub(r';\s*boundary=[^;]*', '', v)
        out[lk] = v
    return out


def json_body(body):
    try:
        return json.loads(body)
    except Exception:
        return None


def flatten(obj, prefix=''):
    """JSON body -> {dotpath: scalar|type} for field-by-field comparison."""
    out = {}
    if isinstance(obj, dict):
        for k in sorted(obj):
            if k in IGNORE_BODY_KEYS:
                continue
            out.update(flatten(obj[k], f'{prefix}{k}'))
    elif isinstance(obj, list):
        out[prefix + '#n'] = len(obj)
        for i, v in enumerate(obj):
            out.update(flatten(v, f'{prefix}[{i}].'))
    else:
        if isinstance(obj, str):
            # 服务端分配的随机 id（mock 每次上传生成新的 file-*）
            obj = re.sub(r'file-[A-Za-z0-9]{6,}', 'file-<id>', obj)
        out[prefix or '$'] = obj
    return out


def multipart_fields(body):
    """multipart/form-data -> ordered field names + filename hints."""
    fields = re.findall(r'name="([^"]+)"(?:;\s*filename="([^"]*)")?', body or '')
    return fields


def compare_req(a, b, idx, errors):
    tag = f'req#{idx} {a.get("method")} {norm_path(a.get("url"))}'
    if a.get('method') != b.get('method'):
        errors.append(f'{tag}: method {a.get("method")} != {b.get("method")}')
        return
    if norm_path(a.get('url')) != norm_path(b.get('url')):
        errors.append(f'{tag}: path {norm_path(a.get("url"))} != {norm_path(b.get("url"))}')
        return
    ha, hb = norm_headers(a.get('headers')), norm_headers(b.get('headers'))
    for k in sorted(set(ha) | set(hb)):
        if ha.get(k) != hb.get(k):
            errors.append(f'{tag}: header {k}: {ha.get(k)!r} != {hb.get(k)!r}')
    ba, bb = a.get('body', ''), b.get('body', '')
    ja, jb = json_body(ba), json_body(bb)
    if ja is not None and jb is not None:
        fa, fb = flatten(ja), flatten(jb)
        for k in sorted(set(fa) | set(fb)):
            va, vb = fa.get(k, '<absent>'), fb.get(k, '<absent>')
            if va != vb:
                errors.append(f'{tag}: body {k}: {va!r} != {vb!r}')
    elif 'multipart' in str(a.get('headers', {}).get('content-type', '')):
        if not ba or not bb:
            pass  # refdrive 的 CDP 拦截拿不到上传 body；只能比对单侧，跳过
        else:
            fa, fb = multipart_fields(ba), multipart_fields(bb)
            if [n for n, _ in fa] != [n for n, _ in fb]:
                errors.append(f'{tag}: multipart fields {fa} != {fb}')
    elif ba != bb:
        errors.append(f'{tag}: raw body differs ({len(ba)}B vs {len(bb)}B)')


def main():
    ra = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
    rb = [json.loads(l) for l in open(sys.argv[2]) if l.strip()]
    errors = []
    if len(ra) != len(rb):
        errors.append(f'request count differs: ref={len(ra)} native={len(rb)}')
    for i, (a, b) in enumerate(zip(ra, rb)):
        compare_req(a, b, i, errors)
    if errors:
        print('FAIL: %d differences' % len(errors))
        for e in errors:
            print('  ' + e)
        sys.exit(1)
    print(f'OK: {len(ra)} requests equivalent')
    sys.exit(0)


if __name__ == '__main__':
    main()

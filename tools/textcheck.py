#!/usr/bin/env python3
# tools/textcheck.py — parity check: ref src/i18n/zh.ts keys vs Strings.kt get().
# Prints JSON {missing:[], mismatched:[{key,ref,native}], matched:n, total:n}
import re, json, sys

REF = '/tmp/webview-reference/src/i18n/zh.ts'
NAT = 'app/src/main/java/io/github/lijiahua1024/dsnative/theme/Strings.kt'

def unescape(s):
    return s.replace("\\'", "'").replace('\\"', '"').replace('\\n', '\n')

ref = {}
src = open(REF).read()
body = src[src.index('export const zh = {'):]
for m in re.finditer(r"^\s*(\w+)\s*:\s*('((?:[^'\\]|\\.)*)'|\"((?:[^\"\\]|\\.)*)\")", body, re.M):
    ref[m.group(1)] = unescape(m.group(3) if m.group(3) is not None else m.group(4))

nat = {}
ksrc = open(NAT).read()
for m in re.finditer(r'"(\w+)"\s*->\s*pick\(\s*"((?:[^"\\]|\\.)*)"', ksrc):
    nat[m.group(1)] = unescape(m.group(2))
# non-pick entries: "key" -> "value"
for m in re.finditer(r'"(\w+)"\s*->\s*"((?:[^"\\]|\\.)*)"', ksrc):
    nat.setdefault(m.group(1), unescape(m.group(2)))

missing, mismatched, matched = [], [], 0
for k, v in ref.items():
    if k not in nat:
        missing.append(k)
    elif nat[k] != v:
        mismatched.append({'key': k, 'ref': v, 'native': nat[k]})
    else:
        matched += 1
print(json.dumps({'total': len(ref), 'matched': matched,
                  'missing': missing, 'mismatched': mismatched}, ensure_ascii=False, indent=1))

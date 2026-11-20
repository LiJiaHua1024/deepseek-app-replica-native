#!/usr/bin/env bash
# sse_parity.sh — decode every tools/sse-samples/*.sse with BOTH the ref
# parser (sse.ts via node type-stripping) and the native SseReader (unit test
# writing /tmp/native-parse.json), then diff the event sequences per sample.
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/tools/node22/bin:$PATH"

mkdir -p /tmp/sse-parity/ref
for f in tools/sse-samples/*.sse; do
  n=$(basename "$f" .sse)
  node --experimental-strip-types tools/parse_ref.mjs "$f" > "/tmp/sse-parity/ref/$n.json" 2>/dev/null
done

SSE_OUT=/tmp/native-parse.json ./gradlew :app:testDebugUnitTest \
  --tests 'io.github.lijiahua1024.dsnative.SseParityTest' -q >/dev/null 2>&1

python3 - <<'PY'
import json, sys, glob, os
native = {e['sample'][:-4]: e['events'] for e in json.load(open('/tmp/native-parse.json'))}
fails = []
for rf in sorted(glob.glob('/tmp/sse-parity/ref/*.json')):
    n = os.path.basename(rf)[:-5]
    ref = json.load(open(rf))
    got = native.get(n)
    if got is None:
        fails.append(f'{n}: native missing')
        continue
    if ref != got:
        fails.append(f'{n}: ref {len(ref)} events vs native {len(got)}')
        for i, (a, b) in enumerate(zip(ref, got)):
            if a != b:
                fails.append(f'  ev[{i}] ref={a} native={b}')
                break
missing = set(native) - {os.path.basename(f)[:-5] for f in glob.glob('/tmp/sse-parity/ref/*.json')}
fails += [f'{m}: ref missing' for m in missing]
if fails:
    print('FAIL:'); [print('  ' + f) for f in fails]; sys.exit(1)
print(f'OK: {len(native)} samples, event sequences identical')
PY

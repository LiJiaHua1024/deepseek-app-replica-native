#!/usr/bin/env bash
# tools/netcheck.sh — request-equivalence check (net.* inventory entries).
# Drives the ref app then the native app through the same request flows while
# the shared mock records every request (RECORD_FILE), then diffs the two
# recordings position-by-position with tools/reqcompare.py.
#
# Flows recorded (refdrive 'net.record' / scenes-native 'net.record'):
#   GET /models (real key-entry verify)
#   POST /messages (plain)
#   POST /messages with 智能搜索 on (deepseek_web_search tool)
#   POST /files + POST /messages with file block
#   POST /messages 'toolong' -> 400 + stripped retry
set -uo pipefail
SERIAL="${SERIAL:-emulator-5554}"
ADB="adb -s $SERIAL"
RPKG=io.github.lijiahua1024.dsreplica
NPKG=io.github.lijiahua1024.dsnative
REPO="$(cd "$(dirname "$0")/.." && pwd)"
PATH="$HOME/android-sdk/platform-tools:$HOME/tools/node22/bin:$PATH"
export PATH

kill_mock() { pkill -f "mock-api/server.js" 2>/dev/null || true; sleep 0.4; }
start_mock() { # record_file
  kill_mock
  RECORD_FILE="$1" setsid nohup ~/tools/node22/bin/node "$REPO/tools/mock-api/server.js" >/tmp/mock-net.log 2>&1 &
  for i in $(seq 1 20); do (exec 3<>/dev/tcp/localhost/8321) 2>/dev/null && return 0; sleep 0.3; done
  echo "mock failed to start" >&2; return 1
}

rm -f /tmp/req-ref.jsonl /tmp/req-native.jsonl

echo "== ref recording"
start_mock /tmp/req-ref.jsonl || exit 1
$ADB shell am force-stop "$RPKG" >/dev/null 2>&1
$ADB shell pm clear "$RPKG" >/dev/null 2>&1
node "$REPO/tools/refdrive.mjs" "$SERIAL" "$RPKG" net.record /tmp/net-record-ref.png \
  || { echo "ref net.record failed"; tail -5 /tmp/refdrive.log 2>/dev/null; }

echo "== native recording"
start_mock /tmp/req-native.jsonl || exit 1
$ADB shell am force-stop "$NPKG" >/dev/null 2>&1
$ADB shell pm clear "$NPKG" >/dev/null 2>&1
$ADB shell am start -n "$NPKG/.MainActivity" >/dev/null 2>&1; sleep 1.5
$ADB shell am broadcast -a "$NPKG.DEBUG_API_ORIGIN" --es origin "http://10.0.2.2:8321" >/dev/null 2>&1
bash "$REPO/tools/scenes-native.sh" "$ADB" "$NPKG" net.record /tmp/net-record-native.png \
  || echo "native net.record failed"

echo "== compare"
wc -l /tmp/req-ref.jsonl /tmp/req-native.jsonl 2>/dev/null
python3 "$REPO/tools/reqcompare.py" /tmp/req-ref.jsonl /tmp/req-native.jsonl

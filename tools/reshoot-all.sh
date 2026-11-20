#!/usr/bin/env bash
# tools/reshoot-all.sh <app> — re-shoot every scene listed in /tmp/scenes.txt
# (or $SCENES). One retry per scene (the DEBUG_API_ORIGIN broadcast races the
# runtime-registered receiver occasionally). Log: /tmp/reshoot-<app>.log
set -u
APP="${1:?ref|native}"
SCENES="${SCENES:-/tmp/scenes.txt}"
cd "$(dirname "$0")/.."
while read -r s; do
  [ -z "$s" ] && continue
  if ! bash tools/shot.sh "$APP" "$s" </dev/null >>/tmp/reshoot-$APP.log 2>&1; then
    echo "RETRY $s" >>/tmp/reshoot-$APP.log
    bash tools/shot.sh "$APP" "$s" </dev/null >>/tmp/reshoot-$APP.log 2>&1 \
      || echo "FAIL $s" >>/tmp/reshoot-$APP.log
  fi
  echo "DONE $s" >>/tmp/reshoot-$APP.log
done < "$SCENES"
echo ALL_DONE >>/tmp/reshoot-$APP.log

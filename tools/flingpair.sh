#!/usr/bin/env bash
# tools/flingpair.sh <ref|native> <chat|sider> — inertia fling travel table.
# App must already show the target list. For each dir x v: 3 opposite swipes
# clamp to far bound, read pos, 800px test swipe, read pos. Prints RAW lines
# "POS <tag> <value>" (native=probe units, ref=css scrollTop) plus RANGE.
set -euo pipefail
APP=$1; LIST=$2
S=emulator-5554
D=/sdcard/Android/data/io.github.lijiahua1024.dsnative/files/motion2
PKG=io.github.lijiahua1024.dsnative

if [ "$APP" = native ]; then
  adb -s $S push /dev/null /data/local/tmp/n >/dev/null 2>&1 || true
  # start a long polling MotionServer reading internal files dir
  { echo launch; echo sleep 3000
    if [ "$LIST" = chat ]; then
      # open the most recent session via drawer + top row tap
      echo 'seq 80 1200 400 1200 20 200'; echo sleep 900; echo 'tap 300 500'; echo sleep 1500
    else
      # sider: leave the drawer open so swipes land on the sider list
      echo 'seq 80 1200 400 1200 20 200'; echo sleep 900
    fi
    echo scrollreset
    for i in $(seq 1 400); do echo 'sleep 200'; echo "scroll $LIST"; done; } > /tmp/poll2.cmd
  adb -s $S push /tmp/poll2.cmd /data/local/tmp/poll2.cmd >/dev/null
  adb -s $S shell run-as $PKG mkdir files/motion 2>/dev/null || true
  adb -s $S shell run-as $PKG cp /data/local/tmp/poll2.cmd files/motion/inject.cmd
  adb -s $S shell run-as $PKG rm files/motion/inject.log 2>/dev/null || true
  adb -s $S shell am instrument -w -e class io.github.lijiahua1024.dsnative.MotionServer \
    $PKG.test/androidx.test.runner.AndroidJUnitRunner >/tmp/instr2.out 2>&1 &
  sleep 6
  pos() { adb -s $S shell run-as $PKG cat files/motion/inject.log 2>/dev/null | grep -oE "scroll $LIST [-0-9.]+" | tail -1 | awk '{print $3}'; }
else
  pos() {
    node - <<'EOF'
const {execFileSync}=require('node:child_process')
const adb=(...a)=>execFileSync('adb',['-s','emulator-5554',...a],{encoding:'utf8'}).trim()
const pid=adb('shell','pidof io.github.lijiahua1024.dsreplica || true')
const port=adb('forward','tcp:0',`localabstract:webview_devtools_remote_${pid}`)
fetch(`http://127.0.0.1:${port}/json/list`).then(r=>r.json()).then(async pages=>{
  const page=pages.find(p=>/index\.html/.test(p.url))
  const ws=new WebSocket(page.webSocketDebuggerUrl);let id=0;const pend=new Map()
  ws.addEventListener('message',({data})=>{const m=JSON.parse(data);if(m.id)pend.get(m.id)?.(m)})
  await new Promise(r=>ws.addEventListener('open',r,{once:true}))
  const ev=e=>new Promise(res=>{const i=++id;pend.set(i,m=>res(m.result?.result?.value));ws.send(JSON.stringify({id:i,method:'Runtime.evaluate',params:{expression:e,returnByValue:true}}))})
  const sel = process.env.SEL
  console.log(await ev(`(document.querySelector('${sel}')||{scrollTop:-1}).scrollTop`))
  process.exit(0)
})
EOF
  }
  if [ "$LIST" = sider ]; then export SEL='[class*=listWrap] > [class*=list]'; else export SEL='[class*=scroll]'; fi
fi

for dir in up down; do
  for v in 500 1500 3000 6000; do
    # clamp to far bound with three 800px swipes opposite the test dir
    for i in 1 2 3; do
      if [ "$dir" = up ]; then adb -s $S shell input swipe 540 500 540 1300 133
      else adb -s $S shell input swipe 540 1900 540 1100 133; fi
      sleep 1.0
    done
    P0=$(pos); dur=$((800000/v))
    if [ "$dir" = up ]; then adb -s $S shell input swipe 540 1900 540 1100 $dur
    else adb -s $S shell input swipe 540 500 540 1300 $dur; fi
    sleep 3.2
    P1=$(pos)
    echo "POS ${dir}_${v} $P0 $P1"
  done
done
# range probes: bottom and top
for i in 1 2 3; do adb -s $S shell input swipe 540 1900 540 1100 133; sleep 1.0; done
echo "POS bottom $(pos)"
for i in 1 2 3; do adb -s $S shell input swipe 540 500 540 1300 133; sleep 1.0; done
echo "POS top $(pos)"

#!/usr/bin/env bash
# tools/transcheck.sh [entry...] — record transitions on ref + native,
# compare frame-by-frame via transcheck.py.
#
# ref:    refdrive scene -> adb trigger inside screenrecord -> frames
# native: shell state builder -> same adb trigger -> frames
# Criteria per entry: |dur_ref-dur_nat| <= 2 frames, mean ssim >= 0.97,
# edge mode also requires max position delta <= 3px.
set -uo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL=${SERIAL:-emulator-5554}
RPKG=io.github.lijiahua1024.dsreplica
NPKG=io.github.lijiahua1024.dsnative
KEY=sk-mock-test-key
ADB="adb -s $SERIAL"
DUMP=/sdcard/dsn.xml
OUT=/tmp/trans; mkdir -p "$OUT"

waitdev() { for i in $(seq 1 60); do $ADB get-state 2>/dev/null | grep -q device && return 0; sleep 3; done; return 1; }
mock() { curl -s -o /dev/null http://localhost:8321/models || setsid nohup ~/tools/node22/bin/node "$REPO/tools/mock-api/server.js" >/tmp/mock.log 2>&1 & sleep 0.6; }
dump() { $ADB shell uiautomator dump $DUMP >/dev/null 2>&1; $ADB shell cat $DUMP > /tmp/dsn.xml 2>/dev/null; }
find_node() { for i in 1 2 3; do dump; local c; c=$(python3 "$REPO/tools/uiauto.py" find /tmp/dsn.xml "$1" 2>/dev/null) && { echo "$c"; return 0; }; sleep 0.5; done; return 1; }
find_row() { # regex [ymin] [ymax]
  local re="$1" ymin="${2:-0}" ymax="${3:-2400}"
  for i in 1 2 3; do
    dump
    python3 - "$re" "$ymin" "$ymax" <<'EOF' && return 0
import re,sys,xml.etree.ElementTree as ET
re_s,ymin,ymax=sys.argv[1],int(sys.argv[2]),int(sys.argv[3])
best=None
for e in ET.parse('/tmp/dsn.xml').iter('node'):
    t=e.get('text') or e.get('content-desc') or ''
    m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',e.get('bounds') or '')
    if not (m and re.search(re_s,t)): continue
    x1,y1,x2,y2=map(int,m.groups())
    if ymin<=y1<=ymax and x2-x1>200: best=(x1+x2)//2,(y1+y2)//2
if best: print(*best)
else: sys.exit(1)
EOF
    [ $? -eq 0 ] && return 0
    sleep 0.5
  done
}
wait_node() { local deadline=$((SECONDS + ${2:-15})); while [ $SECONDS -lt $deadline ]; do find_node "$1" >/dev/null 2>&1 && return 0; sleep 0.4; done; return 1; }
tap_node() { local c; c=$(find_node "$1") || return 1; $ADB shell input tap $c; }
kill_foreground_blockers() {
  for p in $($ADB shell pm list packages 2>/dev/null | tr -d '\r' | sed 's/^package://' | grep -i 'camera\|documentsui\|photopicker\|gallery'); do
    $ADB shell am force-stop "$p" >/dev/null 2>&1
  done
}
nlaunch() { for i in 1 2 3; do $ADB shell am start -n "$NPKG/.MainActivity" >/dev/null 2>&1 && return 0; sleep 3; done; return 1; }
nkey() {
  kill_foreground_blockers
  $ADB shell am force-stop $NPKG >/dev/null 2>&1; sleep 0.8
  nlaunch; sleep 2
  if wait_node "输入 API Key|Enter API Key" 8; then
    dump; local ec; ec=$(python3 - <<'EOF'
import re,xml.etree.ElementTree as ET
els=[e for e in ET.parse('/tmp/dsn.xml').iter('node') if 'EditText' in (e.get('class') or '')]
m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',els[0].get('bounds'));x1,y1,x2,y2=map(int,m.groups());print((x1+x2)//2,(y1+y2)//2)
EOF
); [ -n "$ec" ] && $ADB shell input tap $ec; sleep 0.5
    $ADB shell input text "$KEY"; sleep 0.3
    tap_node "^确定$|^Confirm$" >/dev/null
    wait_node "发消息|按住说话|hold to talk|Hold to talk|Message|New chat" 25
  fi
}
nsend() { $ADB shell am broadcast -a "$NPKG.DEBUG_INPUT" --es text "$1" --ez send true >/dev/null; sleep 0.4; }
ndrawer() { local m; m=$(find_node "打开边栏|Open sidebar" || true); [ -n "$m" ] && $ADB shell input tap $m || $ADB shell input tap 73 201; sleep 0.8; }
nsettings() { ndrawer; tap_node "API Key|设置|Settings" >/dev/null; wait_node "外观|Appearance|主题" 10; }

s_home()    { nkey; }
s_sett()    { nkey; nsettings; }
s_session() { nkey; nsend "你好"; wait_node "假后端|复制|Copy" 40; ndrawer; wait_node "搜索对话|Search chats" 8; sleep 0.4; }
s_chat()    { nkey; nsend "你好"; wait_node "假后端|复制|Copy" 40; }
s_think()   { nkey; nsend "thinking"; sleep 0.6; }
s_file()    { nkey; nsend "file"; wait_node "TXT|复制|Copy" 40; sleep 0.6; }

# record <dir> <trigger...> : screenrecord 3s, run trigger ~400ms in, extract frames
record() {
  local dir="$1"; shift
  local secs="${RECLEN:-3}"
  rm -rf "$dir"; mkdir -p "$dir"
  $ADB shell rm -f /sdcard/_tr.mp4
  $ADB shell "screenrecord --time-limit $secs /sdcard/_tr.mp4 >/dev/null 2>&1" &
  local pid=$!
  sleep 0.4
  "$@"
  wait $pid 2>/dev/null || true
  $ADB pull /sdcard/_tr.mp4 "$dir/tr.mp4" >/dev/null 2>&1 || { echo "record: pull failed"; return 1; }
  ffmpeg -y -loglevel error -i "$dir/tr.mp4" -vsync 0 "$dir/f%03d.png" || { echo "record: ffmpeg failed"; return 1; }
  [ -f "$dir/f001.png" ] || { echo "record: no frames"; return 1; }
}

refgo() { # scene -> drive ref to state (leaves app in that state)
  $ADB shell am force-stop $RPKG >/dev/null 2>&1
  for i in $(seq 1 25); do [ -z "$($ADB shell pidof $RPKG 2>/dev/null | tr -d '\r')" ] && break; sleep 0.3; done
  PRESS_SEL= node "$REPO/tools/refdrive.mjs" "$SERIAL" "$RPKG" "$1" "$OUT/_r.png" >/tmp/refdrive_tr.log 2>&1
}

# triggers — find_node based so both apps (identical layout) get real coords
t_drawer() { local c; c=$(find_node "打开边栏|Open sidebar") || c="73 201"; $ADB shell input tap $c; }
t_settings() { local c; c=$(find_node "设置$|Settings$" || find_node "API Key|设置|Settings") || c="150 2260"; $ADB shell input tap $c; }
reval() { REF_EXPR="$1" PRESS_SEL= node "$REPO/tools/refdrive.mjs" "$SERIAL" "$RPKG" evalonly "$OUT/_e.png" >>/tmp/refdrive_tr.log 2>&1; }
t_attach() { local c; c=$(find_node "uploadFile|上传文件|Upload|上传") || c="1008 2196"; $ADB shell input tap $c; }
# ref 端用 CDP 点按真正的按钮元素（uiautomator 坐标在 WebView 上容易脱靶）
tr_attach() { reval '(()=>{const row=document.querySelector("[class*=functionRow]");const e=row?[...row.querySelectorAll("[aria-label]")].find(x=>/上传文件|Upload files|close|关闭/.test(x.getAttribute("aria-label")||"")):null;if(!e)return "none";e.click();return "ok"})()'; }
tr_voice() {
  reval '(()=>{const e=[...document.querySelectorAll("[aria-label]")].find(x=>/语音输入|Voice input/.test(x.getAttribute("aria-label")||""));if(!e)return "none";e.click();return "ok"})()'
  sleep 0.5
  local c; c=$(find_node "按住说话|Hold to talk") || c="540 2196"; $ADB shell input swipe $c $c 1200
}
t_voice() { local c; c=$(find_node "oice|语音") || c="980 2196"; $ADB shell input tap $c; sleep 0.4; c=$(find_node "按住说话|Hold to talk") || c="540 2196"; $ADB shell input swipe $c $c 1200; }
t_search() { local c; c=$(find_node "搜索对话|Search chats|搜索") || c="540 250"; $ADB shell input tap $c; }
t_subpage() { local c; c=$(find_node "数据管理|Data management|账号管理|Account") || c="400 700"; $ADB shell input tap $c; }
t_langsel() { local c; c=$(find_node "语言|Language") || c="400 1050"; $ADB shell input tap $c; }
t_send() { local c; c=$(find_node "发送|Send") || c="990 2196"; $ADB shell input tap $c; }
t_holdmsg() { local c; c=$(find_row "你好|假后端" 300 1800 || find_node "你好|假后端") || c="540 700"; $ADB shell input swipe $c $c 500; }
t_holdcard() { local c; c=$(find_node "TXT|文件|File") || c="540 900"; $ADB shell input swipe $c $c 500; }
t_holdsess() { local c; c=$(find_row "你好|问候" 300 1600 || find_node "你好|问候") || c="400 800"; $ADB shell input swipe $c $c 500; }
t_img() { local c; c=$(find_node "查看图片|image|图片") || c="540 1400"; $ADB shell input tap $c; }
t_none() { :; }

run() { # id refscene reftrig natprep nattrig "region" mode
  local id="$1" scene="$2" rtrig="$3" nprep="$4" ntrig="$5" region="$6" mode="${7:-fade}"
  waitdev || { echo "$id: device lost"; return 1; }
  refgo "$scene" || { echo "$id: ref scene failed"; tail -3 /tmp/refdrive_tr.log; return 1; }
  # CDP-triggered ref entries pay ~3s attach cost before the click lands
  case "$rtrig" in tr_*) RECLEN=9 ;; *) RECLEN=3 ;; esac
  record "$OUT/ref_$id" $rtrig || return 1
  $nprep || { echo "$id: native prep failed"; return 1; }
  RECLEN=3
  record "$OUT/nat_$id" $ntrig || return 1
  echo "== $id"; python3 "$REPO/tools/transcheck.py" "$OUT/ref_$id" "$OUT/nat_$id" $region $mode
}

mock
ENTRIES="${@:-drawer settings_open settings_sub settings_sel attach_sheet voice_overlay search_panel send_fade thinking toast filecard msgitem sitem iconfade btnfade readbars searchrow}"
for e in $ENTRIES; do
  case "$e" in
    drawer)        run drawer        welcome.default  t_drawer       s_home      t_drawer     "0 200 1080 2100" edge ;;
    settings_open) run settings_open sider.sessions   t_settings     s_session   t_settings   "0 200 1080 2200" edge ;;
    settings_sub)  run settings_sub  settings.root    t_subpage      s_sett      t_subpage    "0 200 1080 2200" edge ;;
    settings_sel)  run settings_sel  settings.root    t_langsel      s_sett      t_langsel    "0 200 1080 2200" fade ;;
    attach_sheet)  run attach_sheet  welcome.default  tr_attach      s_home      t_attach     "0 1400 1080 2340" edge ;;
    voice_overlay) run voice_overlay welcome.default  tr_voice       s_home      t_voice      "0 800 1080 2340" fade ;;
    search_panel)  run search_panel  sider.sessions   t_search       s_session   t_search     "0 200 1080 2000" edge ;;
    searchrow)     run searchrow     searchpanel.default t_holdmsg  s_search    t_holdmsg    "0 400 1080 1400" fade ;;
    send_fade)     run send_fade     welcome.default  tr_send_short  s_home      tr_nsend     "0 200 1080 1500" fade ;;
    thinking)      run thinking      welcome.default  tr_send_think  s_home      tr_think     "0 900 1080 2100" fade ;;
    toast)         run toast         chat.default     t_copy         s_chat      t_copy       "300 200 800 500" fade ;;
    filecard)      run filecard      chat.files       t_holdcard     s_file      t_holdcard   "0 700 1080 1200" fade ;;
    msgitem)       run msgitem       chat.default     t_holdmsg      s_chat      t_holdmsg    "0 500 1080 1000" fade ;;
    sitem)         run sitem         sider.sessions   t_holdsess     s_session   t_holdsess   "0 500 1080 1200" fade ;;
    iconfade)      run iconfade      welcome.default  t_holdicon     s_home      t_holdicon   "0 80 1080 300" fade ;;
    btnfade)       run btnfade       apikey.default   t_holdlogin    s_keyscreen t_holdlogin  "0 1200 1080 1700" fade ;;
    readbars)      run readbars      chat.default     t_speaker      s_chat      t_speaker    "700 1500 1080 2000" fade ;;
    *)             echo "unknown entry: $e" ;;
  esac
done
echo "transcheck done"

#!/usr/bin/env bash
# tools/presscheck.sh [entry ...]
# Captures a mid-hold pressed frame on both apps and compares them.
#   ref:    refdrive scene -> real `input swipe` hold -> screencap at HOLD ms
#   native: scene setup    -> tools/press.sh (MotionServer) -> screencap at HOLD ms
# Entries: iconbtn attachments chatinput voicebtn codeblock searchpanel
#          settings sessionitem chatinput-lp usermsg-lp sessionitem-lp
set -uo pipefail
SERIAL="${SERIAL:-emulator-5554}"
ADB="adb -s $SERIAL"
RPKG=io.github.lijiahua1024.dsreplica
NPKG=io.github.lijiahua1024.dsnative
KEY=sk-mock-test-key
OUT=/tmp/press; mkdir -p "$OUT"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
DUMP=/sdcard/d.xml
PATH="$HOME/android-sdk/platform-tools:$HOME/tools/node22/bin:$PATH"
export PATH

dump() { $ADB shell uiautomator dump $DUMP >/dev/null 2>&1; $ADB shell cat $DUMP > /tmp/dsn.xml 2>/dev/null; }
find_node() { for i in 1 2 3; do dump; local c; c=$(python3 "$REPO/tools/uiauto.py" find /tmp/dsn.xml "$1" 2>/dev/null) && { echo "$c"; return 0; }; sleep 0.5; done; return 1; }
wait_node() { local deadline=$((SECONDS + ${2:-15})); while [ $SECONDS -lt $deadline ]; do find_node "$1" >/dev/null 2>&1 && return 0; sleep 0.4; done; return 1; }
tap_node() { local c; c=$(find_node "$1") || return 1; $ADB shell input tap $c; }
shot() { # out.png — validate PNG signature, retry on truncated/empty captures
  local i
  for i in 1 2 3 4 5 6; do
    if ! $ADB exec-out screencap -p > "$1" 2>/dev/null || [ ! -s "$1" ]; then
      $ADB shell screencap -p /sdcard/_s.png >/dev/null 2>&1 && $ADB pull /sdcard/_s.png "$1" >/dev/null 2>&1
    fi
    [ -s "$1" ] && head -c4 "$1" | grep -q 'PNG' && return 0
    sleep 2
  done
  # screencap 死窗兜底：screenrecord 走 MediaCodec 不经 SurfaceFlinger 快照
  $ADB shell rm -f /sdcard/_fb.mp4
  $ADB shell "screenrecord --time-limit 2 /sdcard/_fb.mp4 >/dev/null 2>&1"
  $ADB pull /sdcard/_fb.mp4 /tmp/_fb.mp4 >/dev/null 2>&1 && \
    ffmpeg -y -loglevel error -sseof -0.2 -i /tmp/_fb.mp4 -update 1 -frames:v 1 "$1" 2>/dev/null && \
    [ -s "$1" ] && head -c4 "$1" | grep -q 'PNG' && return 0
  echo "shot failed: $1" >&2; return 1
}

mock() { curl -s -o /dev/null http://localhost:8321/models || setsid nohup ~/tools/node22/bin/node "$REPO/tools/mock-api/server.js" >/tmp/mock.log 2>&1 & sleep 0.6; }

# --- native helpers -------------------------------------------------------
# a press that lands on 拍照/相册/文件 launches an external activity which then
# owns the top task — kill camera-ish packages so the app actually comes forward
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
  if wait_node "输入 API Key|Enter API Key" 8; then :; else return 0; fi
  dump; local ec; ec=$(python3 - "$REPO" <<'EOF'
import re,sys,xml.etree.ElementTree as ET
els=[e for e in ET.parse('/tmp/dsn.xml').iter('node') if 'EditText' in (e.get('class') or '')]
m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',els[0].get('bounds'));x1,y1,x2,y2=map(int,m.groups());print((x1+x2)//2,(y1+y2)//2)
EOF
); [ -n "$ec" ] && $ADB shell input tap $ec; sleep 0.5
  $ADB shell input text "$KEY"; sleep 0.3
  tap_node "^确定$|^Confirm$" >/dev/null
  wait_node "发消息|按住说话|hold to talk|Hold to talk|Message|New chat" 25
}
nsend() { $ADB shell am broadcast -a "$NPKG.DEBUG_INPUT" --es text "$1" --ez send true >/dev/null; sleep 0.4; }
ndrawer() {
  local m; m=$(find_node "打开边栏|Open sidebar" || true)
  [ -n "$m" ] && $ADB shell input tap $m || $ADB shell input tap 73 201; sleep 0.8
}
nsettings() { ndrawer; tap_node "API Key|设置|Settings" >/dev/null; wait_node "外观|Appearance|主题" 10; }

# find coords of a clickable row whose text matches, preferring the widest
# match inside y range — used for search results / session items
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
  return 1
}

# hold-press element matching regex on CURRENT screen (native app: uiautomator)
hold() { # regex holdms out.png
  local re="$1" ms="$2" out="$3" c
  c=$(find_node "$re" || find_row "$re") || { echo "NO NODE /$re/" >&2; return 1; }
  hold_at $(echo $c | awk '{print $1, $2}') "$ms" "$out"
}
# hold-press at device coords on CURRENT screen
hold_at() { # cx cy holdms out.png
  [ $# -ge 4 ] || { echo "hold_at: bad args '$*'" >&2; return 1; }
  local c="$1 $2" ms="$3" out="$4"
  # 长按阈值为 ms；菜单弹出/动画另留 600ms 余量再取景（此前在 700ms 处
  # 截图会抢在菜单出现前）。按住时长 = ms+900 保证按下仍在进行。
  $ADB shell "input swipe $c $c $((ms + 900)) >/dev/null 2>&1 &"
  sleep "$(awk "BEGIN{print ($ms + 600)/1000}")"
  shot "$out"
  sleep 1.0
}

press_native() { # regex holdms out.png — MotionServer hold; UP lands off-target
  # so press rows that launch intents (camera/gallery/docs) don't fire on release.
  local re="$1" ms="$2" out="$3" c
  c=$(find_node "$re" || find_row "$re") || { echo "NO NODE /$re/" >&2; return 1; }
  local mode=press; [ "$ms" -ge 700 ] && mode=long
  local p; p=$(bash "$REPO/tools/press.sh" $c "$mode" "_pc.png") || return 1
  cp "$p" "$out"
}

ref_scene() { # scene sel — drives ref app into state, prints element coords
  # 冷启动消除上一场景残留；必须等旧进程真死掉，
  # 否则 refdrive 会 attach 到垂死 WebView 读到残留 DOM
  $ADB shell am force-stop $RPKG >/dev/null 2>&1
  for i in $(seq 1 25); do [ -z "$($ADB shell pidof $RPKG 2>/dev/null | tr -d '\r')" ] && break; sleep 0.3; done
  PRESS_SEL="$2" node "$REPO/tools/refdrive.mjs" "$SERIAL" "$RPKG" "$1" "$OUT/_ref_base.png" >/tmp/refdrive.log 2>&1 \
    || { echo "refdrive $1 failed"; tail -3 /tmp/refdrive.log; return 1; }
  grep '^PRESSXY' /tmp/refdrive.log | tail -1
}

cmp_pair() { # id -> compare ref_<id>.png vs native_<id>.png
  python3 - "$OUT/ref_$1.png" "$OUT/native_$1.png" <<'EOF'
import sys
sys.path.insert(0,'tools')
from compare import compare
r = compare(sys.argv[1], sys.argv[2])
print(f"  ssim={r['blurred_ssim']:.4f} mad={r['color_mad']:.2f}")
EOF
}

waitdev() { for i in $(seq 1 90); do $ADB get-state 2>/dev/null | grep -q device && return 0; sleep 5; done; return 1; }
run() { # id refscene refsel natregex mode native_setup
  local id="$1" refscene="$2" refsel="$3" re="$4" mode="$5"; shift 5
  waitdev || { echo "$id: device lost"; return 1; }
  local holdms=150; [ "$mode" = long ] && holdms=700
  echo "== $id"
  local xy; xy=$(ref_scene "$refscene" "$refsel" | grep '^PRESSXY' | awk '{print $2, $3}')
  if [ "$mode" = menu ]; then
    # 场景自身已呈菜单态（WebView 长按只产生文本选择）——直接用场景截图
    cp "$OUT/_ref_base.png" "$OUT/ref_$id.png"
  else
    local xyt="${xy//[[:space:]]/}"
    [ -z "$xyt" -o "$xyt" = "none" ] && { echo "$id: ref element missing"; tail -3 /tmp/refdrive.log; return 1; }
    hold_at $xy "$holdms" "$OUT/ref_$id.png" || return 1
  fi
  waitdev || { echo "$id: device lost"; return 1; }
  "$@" || { echo "$id: native setup failed"; return 1; }
  press_native "$re" "$holdms" "$OUT/native_$id.png" || { echo "$id: native node missing"; return 1; }
  cmp_pair "$id"
}

# native state builders ----------------------------------------------------
s_home()    { nkey; }
s_attach()  { nkey; tap_node "uploadFile|上传文件|Upload|上传" >/dev/null
  # first run may raise the runtime media-permission dialog before the sheet
  local g; g=$(find_node "允许|ALLOW|Allow|while using" || true)
  [ -n "$g" ] && { $ADB shell input tap $g; sleep 1.2; }
  wait_node "相册|Gallery|Photos|Camera|拍照" 8; sleep 0.8; }
s_code()    { nkey; nsend "code"; wait_node "println|复制|Copy" 40; sleep 0.6; }
s_search()  { nkey; nsend "search"; wait_node "个网页|pages" 40; sleep 0.6; tap_node "个网页|pages$" >/dev/null; wait_node "Technical Report|Search results|搜索结果" 10; sleep 0.6; }
s_sett()    { nkey; nsettings; }
s_session() { nkey; nsend "你好"; wait_node "假后端|复制|Copy" 40; ndrawer; wait_node "搜索对话|Search chats" 8; sleep 0.4; }
s_usermsg() { nkey; $ADB shell am broadcast -a "$NPKG.DEBUG_INPUT" --es text "refused" --ez send true --ez newchat true >/dev/null; sleep 0.4; wait_node "换个话题|无法回答|复制|Copy" 40
  local i; for i in 1 2 3 4 5 6; do
    find_node "^refused$" >/dev/null 2>&1 && return 0
    $ADB shell input swipe 540 900 540 1500 300 >/dev/null; sleep 0.4
  done; }

ENTRIES="${@:-iconbtn attachments chatinput voicebtn codeblock searchpanel settings sessionitem chatinput-lp usermsg-lp sessionitem-lp}"
mock
for e in $ENTRIES; do
  case "$e" in
    iconbtn)       run iconbtn      welcome.default      "document.querySelector('button[aria-label=\"打开边栏\"],button[aria-label=\"Open sidebar\"]')" "打开边栏|Open sidebar" press s_home ;;
    attachments)   run attachments  attachments.default  "[...document.querySelectorAll('button')].find(b=>/拍照|Camera/.test(b.textContent))" "拍照|Camera" press s_attach ;;
    chatinput)     run chatinput    welcome.default      "document.querySelector('textarea')" "按住说话|[Hh]old to talk|Message or" press s_home ;;
    voicebtn)      run voicebtn     welcome.default      "document.querySelector('button[aria-label*=\"oice\"],button[aria-label*=\"语音\"]')" "语音输入|Voice input|oice" press s_home ;;
    codeblock)     run codeblock    chat.code            "[...document.querySelectorAll('button')].find(b=>/复制|Copy/.test(b.textContent))" "复制|Copy" press s_code ;;
    searchpanel)   run searchpanel  searchpanel.default  "[...document.querySelectorAll('a,li,div')].find(e=>/Technical Report|example-c/.test(e.textContent)&&e.getBoundingClientRect().height<220)" "Technical Report|example-c.org|DeepSeek 发布" press s_search ;;
    settings)      run settings     settings.root        "[...document.querySelectorAll('button,div,li,a')].filter(e=>/^(外观|Appearance)/.test(e.textContent.trim())&&e.getBoundingClientRect().height<90).pop()" "外观|Appearance" press s_sett ;;
    sessionitem)   run sessionitem  sider.sessions       "[...document.querySelectorAll('a,li,div')].filter(e=>/^(问候|你好)$/.test(e.textContent.trim())).pop()" "你好|问候" press s_session ;;
    chatinput-lp)  run chatinput-lp welcome.default      "document.querySelector('textarea')" "按住说话|[Hh]old to talk|Message or" long  s_home ;;
    usermsg-lp)    run usermsg-lp   chat.menu            "document.body" "^refused$" menu  s_usermsg ;;
    # ref 与 native 都走真实长按：WebView contextmenu 在真实按压下同样触发，
    # 且 :active 高亮同步出现（此前合成的 contextmenu 事件没有按压态）
    sessionitem-lp) run sessionitem-lp sider.sessions   "[...document.querySelectorAll('a,li,div')].filter(e=>/^(问候|你好)$/.test(e.textContent.trim())&&e.getBoundingClientRect().height<70)[0]" "你好|问候" press s_session ;;
  esac
done
echo "presscheck done"

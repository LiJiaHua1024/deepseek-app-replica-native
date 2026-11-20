#!/usr/bin/env bash
# tools/backcheck.sh — 22 back.* inventory rows on the emulator.
# For each state: enter it, then verify `input keyevent BACK` and a left-edge
# swipe produce the identical expected landing state (ref App.tsx onBack order:
# dialogs->Escape, searchPanel->close, mobileSider->close, else exit;
# AndroidSettings: popup->close, root->close, export->data, else->root;
# attachments->close; voice overlay has no handler -> exits).
# Output: /tmp/backcheck.log  (one "PASS/FAIL <id> <method> -> <signature>" per row)
set -uo pipefail
ADB="${ADB:-adb -s emulator-5554}"
PKG="io.github.lijiahua1024.dsnative"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
KEY="sk-mock-test-key"
LOG=/tmp/backcheck.log
: > "$LOG"

dump() { $ADB shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; $ADB shell cat /sdcard/u.xml > /tmp/dsn.xml 2>/dev/null; }
find_node() { dump; python3 "$REPO/tools/uiauto.py" find /tmp/dsn.xml "$1"; }
tap_node() { local c; c=$(find_node "$1") || { echo "no node /$1/" >&2; return 1; }; $ADB shell input tap $(echo "$c" | awk '{print $1+20, $2+20}'); }
wait_node() { local deadline=$((SECONDS + ${2:-15})); while [ $SECONDS -lt $deadline ]; do if find_node "$1" >/dev/null 2>&1; then return 0; fi; sleep 0.4; done; return 1; }
wait_text() { wait_node "$1" "${2:-15}"; }
send_msg() { $ADB shell am broadcast -a "$PKG.DEBUG_INPUT" --es text "'$1'" --ez send true >/dev/null; sleep 0.4; }
sig() { # current screen signature (short)
  dump
  if ! $ADB shell dumpsys window | grep -q "mCurrentFocus=.*$PKG"; then echo "EXITED"; return; fi
  grep -o 'text="[^"]*"' /tmp/dsn.xml | sort -u | tr '\n' '|' | cut -c1-160
}
launch_home() { $ADB shell am start -n "$PKG/.MainActivity" >/dev/null; }
apiorigin() { $ADB shell am broadcast -a "$PKG.DEBUG_API_ORIGIN" --es origin "http://10.0.2.2:8321" >/dev/null; sleep 0.3; }
open_drawer() { $ADB shell input swipe 4 1200 520 1200 350 >/dev/null; sleep 1.1; }
back() { # method: key|swipe
  if [ "$1" = key ]; then $ADB shell input keyevent KEYCODE_BACK; else $ADB shell input swipe 0 800 620 800 300 >/dev/null; fi
  sleep 1
}
fresh() {
  $ADB shell am force-stop $PKG >/dev/null
  $ADB shell am start -n "$PKG/.MainActivity" >/dev/null
  apiorigin
  wait_text "发消息|按住说话|开启新对话|Message|Hold to talk|New chat|输入 API Key" 20
}
enter_key() { # only if key screen shows
  if find_node "输入 API Key|Enter API Key" >/dev/null 2>&1; then
    dump
    local ec; ec=$(python3 - <<'EOF'
import re, xml.etree.ElementTree as ET
els=[e for e in ET.parse('/tmp/dsn.xml').iter('node') if 'EditText' in (e.get('class') or '')]
if els:
    x1,y1,x2,y2=map(int,re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',els[0].get('bounds')).groups())
    print((x1+x2)//2,(y1+y2)//2)
EOF
)
    [ -n "$ec" ] && $ADB shell input tap $ec; sleep 0.5
    $ADB shell input text "$KEY"; sleep 0.3
    tap_node "^确定$|^Confirm$"
    wait_text "发消息|按住说话|开启新对话|Message|Hold to talk|New chat" 20
  fi
}
check() { # id method expect_regex
  local s; s=$(sig)
  if [[ "$1" == EXITED ]]; then :; fi
  local got="$s"
  if echo "$got" | grep -qE "$3"; then echo "PASS $2 $1 -> ${got:0:90}" >>"$LOG"
  else echo "FAIL $2 $1 expected=/$3/ got=${got:0:140}" >>"$LOG"; fi
}

HOME_RE="发消息|按住说话|你好"
SETTINGS_RE="外观|个性化|数据管理|设置"
DRAWER_RE="搜索对话内容|新对话|API Key"
EXIT_RE="EXITED"
$ADB shell settings put secure back_gesture_inset_scale_left 1.0 >/dev/null 2>&1
$ADB shell settings put secure back_gesture_inset_scale_right 1.0 >/dev/null 2>&1
$ADB shell pm clear $PKG >/dev/null
launch_home; apiorigin
wait_text "输入 API Key|Enter API Key" 15
# --- back.apikey: apikey screen -> exits
for m in key swipe; do
  if [ "$m" = swipe ]; then $ADB shell am force-stop $PKG >/dev/null; launch_home; wait_text "输入 API Key|Enter API Key" 15; fi
  back $m; check back.apikey $m "$EXIT_RE"
done
launch_home; sleep 1.5; enter_key
# --- back.chat: home -> exits
for m in key swipe; do
  if [ "$m" = swipe ]; then launch_home; wait_text "$HOME_RE" 15; fi
  back $m; check back.chat $m "$EXIT_RE"
done
launch_home; wait_text "$HOME_RE" 15
# --- back.sider: drawer open -> home
for m in key swipe; do
  open_drawer; find_node "$DRAWER_RE" >/dev/null
  back $m; check back.sider $m "$HOME_RE"
done
# --- back.attachments: panel open -> composer visible, panel gone
for m in key swipe; do
  tap_node "uploadFile|上传文件" >/dev/null 2>&1
  wait_text "相册|Gallery|文件|Files" 8
  back $m
  s=$(sig); if echo "$s" | grep -qE "发消息|按住说话" && ! echo "$s" | grep -qE "相册|Gallery"; then
    echo "PASS back.attachments $m -> ${s:0:90}" >>"$LOG"; else echo "FAIL back.attachments $m got=${s:0:140}" >>"$LOG"; fi
done
# --- back.keyboard: IME open -> composer stays, IME gone (hard to see in dump; check app still up + field editable)
tap_edittext() { dump; python3 - <<'EOF' >/tmp/edittap.txt
import re, xml.etree.ElementTree as ET
els=[e for e in ET.parse('/tmp/dsn.xml').iter('node') if 'EditText' in (e.get('class') or '')]
if els:
    x1,y1,x2,y2=map(int,re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',els[0].get('bounds')).groups())
    print((x1+x2)//2,(y1+y2)//2)
EOF
[ -s /tmp/edittap.txt ] && $ADB shell input tap $(cat /tmp/edittap.txt); }
for m in key swipe; do
  tap_edittext; sleep 0.8
  back $m; check back.keyboard $m "$HOME_RE"
done
# --- dialogs + sider-menu + multiselect (need a session in drawer)
send_msg "hi"; wait_text "hello mock|换个话题" 30 || true
for m in key swipe; do
  open_drawer
  c=$(find_node "^问候$|^hi$|hello mock" || true)
  [ -z "$c" ] && c=$(find_node "conversations|会话|问候" || true)
  # long-press first session row center
  dump; c=$(python3 - <<'EOF'
import re, xml.etree.ElementTree as ET
t=ET.parse('/tmp/dsn.xml'); best=None
for e in t.iter('node'):
    if e.get('clickable')=='true' and e.get('class','').endswith('View'):
        b=e.get('bounds'); x1,y1,x2,y2=map(int,re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b).groups())
        if 60<y1<700 and x2-x1>500: best=((x1+x2)//2,(y1+y2)//2); break
if best: print(*best)
EOF
)
  [ -n "$c" ] && $ADB shell input swipe $c $c 1200; sleep 0.8
  back $m; check back.sider-menu $m "$DRAWER_RE"
  $ADB shell input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 0.5 # close drawer if still open
done
# --- rename/delete dialogs via sider long-press menu
for d in rename delete; do
  for m in key swipe; do
    open_drawer
    dump; c=$(python3 - <<'EOF'
import re, xml.etree.ElementTree as ET
t=ET.parse('/tmp/dsn.xml'); best=None
for e in t.iter('node'):
    if e.get('clickable')=='true' and e.get('class','').endswith('View'):
        b=e.get('bounds'); x1,y1,x2,y2=map(int,re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b).groups())
        if 60<y1<700 and x2-x1>500: best=((x1+x2)//2,(y1+y2)//2); break
if best: print(*best)
EOF
)
    [ -n "$c" ] && $ADB shell input swipe $c $c 1200; sleep 0.7
    if [ "$d" = rename ]; then tap_node "重命名|Rename" >/dev/null 2>&1; else tap_node "删除|Delete" >/dev/null 2>&1; fi
    sleep 0.8
    back $m; check back.dialog.$d $m "$DRAWER_RE"
    $ADB shell input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 0.5
  done
done
# --- back.multiselect: enter selection mode -> back closes drawer (ref: no sel handler)
for m in key swipe; do
  open_drawer
  tap_node "选择对话|选择|Select" >/dev/null 2>&1; sleep 0.8
  back $m; check back.multiselect $m "$HOME_RE"
done
# --- settings pages
open_settings() { open_drawer; tap_node "^设置$|^Settings$|^API Key$" >/dev/null 2>&1; wait_text "$SETTINGS_RE" 12; }
for m in key swipe; do
  open_settings
  back $m; check back.settings.root $m "$HOME_RE"
done
# sub-pages -> root ; export -> data
for pg in "数据管理|数据:data" "个性化:personalization" "字体:font" "协议|条款|服务协议:agreements" "账号管理|账号|API Key:account"; do
  pat="${pg%%:*}"; name="${pg##*:}"
  for m in key swipe; do
    open_settings
    tap_node "$pat" >/dev/null 2>&1; sleep 1
    back $m; check back.settings.$name $m "$SETTINGS_RE"
  done
done
# export: data page -> 导出 row -> export page -> back lands on data page
for m in key swipe; do
  open_settings
  tap_node "数据管理|数据" >/dev/null 2>&1; sleep 1
  tap_node "导出所有历史对话|导出" >/dev/null 2>&1; sleep 1
  back $m; check back.settings.export $m "数据管理|导入历史对话|删除所有历史对话"
done
# popup: open 外观 dialog -> back closes popup only
for m in key swipe; do
  open_settings
  tap_node "外观" >/dev/null 2>&1; sleep 0.8
  back $m; check back.settings.popup $m "$SETTINGS_RE"
done
# --- voice overlay: back exits (ref has no handler)
for m in key swipe; do
  launch_home; wait_text "$HOME_RE" 15
  $ADB shell pm grant $PKG android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
  tap_node "语音输入|Voice input" >/dev/null 2>&1; sleep 0.7
  wait_text "按住说话|Hold to talk" 8
  back $m; check back.voice $m "$EXIT_RE"
done
# --- back.dialog.logout: account page 退出/清除 dialog -> dismiss
for m in key swipe; do
  open_settings
  tap_node "账号管理|账号|API Key" >/dev/null 2>&1; sleep 1
  tap_node "退出|清除|删除 API|Log ?out|Remove" >/dev/null 2>&1; sleep 0.8
  back $m; check back.dialog.logout $m "$SETTINGS_RE|账号|API Key"
done
# --- imageviewer: attach photo -> tap thumb -> viewer -> back closes viewer
for m in key swipe; do
  launch_home; wait_text "$HOME_RE" 15
  $ADB shell pm grant $PKG android.permission.READ_MEDIA_IMAGES >/dev/null 2>&1 || true
  tap_node "uploadFile|上传文件" >/dev/null 2>&1
  wait_text "相册|Gallery" 8; sleep 0.8
  a=$(find_node "相册|Gallery" || true); ay=$(echo "$a" | awk '{print $2}')
  py=$((ay - 314)); $ADB shell input tap 165 $py; sleep 0.8
  # viewer opens on tapping a draft thumb
  dump; c=$(python3 - <<'EOF'
import re, xml.etree.ElementTree as ET
t=ET.parse('/tmp/dsn.xml')
for e in t.iter('node'):
    b=e.get('bounds') or ''
    m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b)
    if not m: continue
    x1,y1,x2,y2=map(int,m.groups())
    if 'ImageView' in (e.get('class') or '') and 80<x2-x1<300 and y1>1200:
        print((x1+x2)//2,(y1+y2)//2); break
EOF
)
  [ -n "$c" ] && $ADB shell input tap $c; sleep 1
  back $m; check back.dialog.imagepreview $m "$HOME_RE|上传文件|相册"
done
# --- attpreview: draft attachment preview dialog -> back closes
for m in key swipe; do
  launch_home; wait_text "$HOME_RE" 15
  $ADB shell pm grant $PKG android.permission.READ_MEDIA_IMAGES >/dev/null 2>&1 || true
  tap_node "uploadFile|上传文件" >/dev/null 2>&1
  wait_text "相册|Gallery" 8; sleep 0.8
  a=$(find_node "相册|Gallery" || true); ay=$(echo "$a" | awk '{print $2}')
  py=$((ay - 314)); $ADB shell input tap 165 $py; sleep 0.8
  # long-press/tap the draft chip -> preview dialog
  dump; c=$(python3 - <<'EOF'
import re, xml.etree.ElementTree as ET
t=ET.parse('/tmp/dsn.xml')
for e in t.iter('node'):
    b=e.get('bounds') or ''
    m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b)
    if not m: continue
    x1,y1,x2,y2=map(int,m.groups())
    if e.get('clickable')=='true' and 40<x2-x1<260 and 600<y1<1400:
        print((x1+x2)//2,(y1+y2)//2); break
EOF
)
  [ -n "$c" ] && $ADB shell input tap $c; sleep 1
  back $m; check back.dialog.attpreview $m "$HOME_RE|发消息|按住说话"
done
# --- searchpanel: sources dialog -> back closes
for m in key swipe; do
  launch_home; wait_text "$HOME_RE" 15; send_msg "search"
  wait_text "个网页|pages" 40 || true
  tap_node "个网页|pages$" >/dev/null 2>&1; sleep 1
  back $m; check back.searchpanel $m "$HOME_RE|个网页|pages"
done
echo "BACKCHECK_DONE" >>"$LOG"
cat "$LOG"

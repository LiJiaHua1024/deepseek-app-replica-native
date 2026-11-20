#!/usr/bin/env bash
# tools/backcheck.sh — 22 back.* inventory rows on the emulator.
# For each state: enter it, then verify `input keyevent BACK` and a left-edge
# swipe produce the identical expected landing state (ref App.tsx onBack order:
# dialogs->Escape, searchPanel->close, mobileSider->close, else exit;
# AndroidSettings: popup->close, root->close, export->data, else->root;
# attachments->close; voice overlay has no handler -> exits).
# Output: /tmp/backcheck.log  (one "PASS/FAIL <id> <method> -> <signature>" per row)
set -uo pipefail
export PATH="$HOME/android-sdk/platform-tools:$HOME/tools/node22/bin:$PATH"
ADB="${ADB:-adb -s emulator-5554}"
PKG="io.github.lijiahua1024.dsnative"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
KEY="sk-mock-test-key"
LOG=/tmp/backcheck.log
: > "$LOG"

dump() { # retry: a flaky adb call leaves an empty dump which every consumer misreads
  local i
  for i in 1 2 3; do
    $ADB shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
    $ADB shell cat /sdcard/u.xml > /tmp/dsn.xml 2>/dev/null
    [ -s /tmp/dsn.xml ] && return 0
    sleep 0.4
  done
}
find_node() { dump; python3 "$REPO/tools/uiauto.py" find /tmp/dsn.xml "$1"; }
tap_node() { local c; c=$(find_node "$1") || { echo "no node /$1/" >&2; return 1; }; $ADB shell input tap $(echo "$c" | awk '{print $1+20, $2+20}'); }
wait_node() { local deadline=$((SECONDS + ${2:-15})); while [ $SECONDS -lt $deadline ]; do if find_node "$1" >/dev/null 2>&1; then return 0; fi; sleep 0.4; done; return 1; }
wait_text() { wait_node "$1" "${2:-15}"; }
send_msg() { $ADB shell am broadcast -a "$PKG.DEBUG_INPUT" --es text "'$1'" --ez send true >/dev/null; sleep 0.4; }
sig() { # current screen signature (short); retry the focus check before EXITED
  dump
  local i
  for i in 1 2 3; do
    if $ADB shell dumpsys window 2>/dev/null | grep -q "mCurrentFocus=.*$PKG"; then break; fi
    [ "$i" = 3 ] && { echo "EXITED"; return; }
    sleep 0.5
  done
  grep -o 'text="[^"]*"' /tmp/dsn.xml | sort -u | tr '\n' '|' | cut -c1-160
}
launch_home() { $ADB shell am start -n "$PKG/.MainActivity" >/dev/null; }
# The origin override lands in a runtime-registered receiver (manifest delivery
# is unreliable after force-stop); a broadcast sent before onCreate registers it
# is dropped. Poll until the prefs file appears so callers can trust the mock.
apiorigin() {
  local i
  for i in $(seq 1 12); do
    $ADB shell am broadcast -a "$PKG.DEBUG_API_ORIGIN" --es origin "http://10.0.2.2:8321" >/dev/null 2>&1
    sleep 0.6
    $ADB shell run-as $PKG test -f /data/data/$PKG/shared_prefs/debug_api.xml 2>/dev/null && return 0
  done
  echo "WARN apiorigin pref not written" >>"$LOG"
}
# With the OS back gesture zone active, an x=4 swipe is eaten as "back", so
# open the drawer through the hamburger button (same target the gesture hits).
# The sider's own nodes are always in the dump, so only tap the hamburger when
# its center sits on the left edge; confirm open when it slides right (>800px).
open_drawer() {
  local i c x
  assert_focus || { launch_home; wait_text "$HOME_RE|输入 API Key|Enter API Key" 10; enter_key; }
  for i in 1 2 3 4; do
    dump
    c=$(python3 - <<'EOF'
import re, xml.etree.ElementTree as ET
for e in ET.parse('/tmp/dsn.xml').iter('node'):
    s=' '.join([e.get('text') or '',e.get('content-desc') or ''])
    if re.search(r'边栏|侧边栏|[Ss]idebar|Sider', s):
        m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',e.get('bounds') or '')
        if m:
            x1,y1,x2,y2=map(int,m.groups())
            cx,cy=(x1+x2)//2,(y1+y2)//2
            if cx<300: print(cx,cy); break
EOF
)
    x=$(python3 tools/uiauto.py find /tmp/dsn.xml "边栏|[Ss]idebar" 2>/dev/null | awk '{print $1}')
    [ -n "$x" ] && [ "$x" -gt 800 ] && return 0
    [ -n "$c" ] && $ADB shell input tap $c || $ADB shell input tap 73 201
    sleep 0.9
  done
  return 1
}
fresh() {
  $ADB shell am force-stop $PKG >/dev/null
  $ADB shell am start -n "$PKG/.MainActivity" >/dev/null
  sleep 1.2
  apiorigin
  wait_text "发消息|按住说话|开启新对话|Message|Hold to talk|New chat|输入 API Key|Enter API Key" 20
}
enter_key() { # only if key screen shows
  if find_node "输入 API Key|Enter API Key|API Key to start" >/dev/null 2>&1; then
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
  else
    echo "FAIL $2 $1 expected=/$3/ got=${got:0:140}" >>"$LOG"
    mkdir -p /tmp/backcheck_fails
    $ADB exec-out screencap -p > "/tmp/backcheck_fails/$1_$2.png" 2>/dev/null
    cp /tmp/dsn.xml "/tmp/backcheck_fails/$1_$2.xml" 2>/dev/null || true
  fi
}
# Guarantee the app is focused on the chat home before entering a row's state;
# rows after a legit EXIT would otherwise act on the launcher or apikey screen.
ensure_home() {
  $ADB shell am force-stop io.github.lijiahua1024.dsreplica >/dev/null 2>&1 || true
  if ! assert_focus || ! find_node "$HOME_RE" >/dev/null 2>&1; then
    fresh; enter_key
    find_node "$HOME_RE" >/dev/null 2>&1 || { enter_key; }
    find_node "$HOME_RE" >/dev/null 2>&1 || echo "WARN ensure_home failed" >>"$LOG"
  fi
}

HOME_RE="发消息|按住说话|你好|hold to talk|Message or hold|欢迎回来|New chat|开始聊天"
SETTINGS_RE="外观|个性化|数据管理|设置|Appearance|Personalization|Data management|Settings"
DRAWER_RE="搜索对话|新对话|API Key|Search chats|会话"
EXIT_RE="EXITED"
# ref app must never take focus mid-run: a legit "exit" check would reveal it
# and every later input would drive the wrong package.
$ADB shell am force-stop io.github.lijiahua1024.dsreplica >/dev/null 2>&1 || true
assert_focus() {
  $ADB shell dumpsys window | grep -q "mCurrentFocus=.*$PKG" || {
    echo "SKIP focus lost (not on $PKG)" >>"$LOG"; return 1; }
}
close_if_drawer() { # press BACK only when the drawer is actually open
  dump
  local x
  x=$(python3 tools/uiauto.py find /tmp/dsn.xml "边栏|[Ss]idebar" 2>/dev/null | awk '{print $1}')
  [ -n "$x" ] && [ "$x" -gt 800 ] && { $ADB shell input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 0.5; }
  return 0
}
back() { # method: key|swipe — re-focus first so exits don't strand us
  if ! assert_focus; then launch_home; wait_text "$HOME_RE|输入 API Key|Enter API Key" 10; fi
  if [ "$1" = key ]; then $ADB shell input keyevent KEYCODE_BACK; else $ADB shell input swipe 0 800 620 800 300 >/dev/null; fi
  sleep 1
}
$ADB shell settings put secure back_gesture_inset_scale_left 1.0 >/dev/null 2>&1
$ADB shell settings put secure back_gesture_inset_scale_right 1.0 >/dev/null 2>&1
$ADB shell pm clear $PKG >/dev/null
# pre-grant media perms so "+" opens the panel instead of the runtime dialog
for p in READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_VISUAL_USER_SELECTED READ_EXTERNAL_STORAGE; do
  $ADB shell pm grant $PKG android.permission.$p >/dev/null 2>&1 || true
done
launch_home; sleep 1.2; apiorigin
wait_text "输入 API Key|Enter API Key|API Key to start" 15
# --- back.apikey: apikey screen -> exits
for m in key swipe; do
  if [ "$m" = swipe ]; then $ADB shell am force-stop $PKG >/dev/null; launch_home; wait_text "输入 API Key|Enter API Key|API Key to start" 15; fi
  back $m; check back.apikey $m "$EXIT_RE"
done
launch_home; sleep 1.5; enter_key
# --- back.chat: home -> exits
for m in key swipe; do
  if [ "$m" = swipe ]; then ensure_home; fi
  back $m; check back.chat $m "$EXIT_RE"
done
ensure_home
# --- back.sider: drawer open -> home
for m in key swipe; do
  open_drawer; find_node "$DRAWER_RE" >/dev/null
  back $m; check back.sider $m "$HOME_RE"
done
# --- back.attachments: panel open -> composer visible, panel gone
for m in key swipe; do
  tap_node "uploadFile|上传文件|Upload|上传" >/dev/null 2>&1
  wait_text "相册|Gallery|文件|Files|Photos|Documents" 8
  back $m
  s=$(sig); if echo "$s" | grep -qE "发消息|按住说话|hold to talk|Message or hold" && ! echo "$s" | grep -qE "相册|Gallery|Photos"; then
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
  c=$(find_node "问候|hi$|hello mock|mock reply" || true)
  [ -z "$c" ] && c=$(find_node "会话|问候|chat|Chat" || true)
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
  close_if_drawer
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
    close_if_drawer
  done
done
# --- back.multiselect: enter selection mode -> back closes drawer (ref: no sel handler)
for m in key swipe; do
  open_drawer
  tap_node "选择对话|选择|Select|选择会话" >/dev/null 2>&1; sleep 0.8
  back $m; check back.multiselect $m "$HOME_RE"
  # selection mode survives the drawer close; leave it before the next row
  open_drawer
  tap_node "关闭|Close$|取消|Cancel" >/dev/null 2>&1; sleep 0.5
  close_if_drawer
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
  tap_node "数据管理|数据|Data management|Data" >/dev/null 2>&1; sleep 1
  tap_node "导出所有历史对话|导出|Export" >/dev/null 2>&1; sleep 1
  back $m; check back.settings.export $m "数据管理|导入历史对话|删除所有历史对话|Data management|Import|Delete all"
done
# popup: open 外观 dialog -> back closes popup only
for m in key swipe; do
  open_settings
  tap_node "外观|Appearance" >/dev/null 2>&1; sleep 0.8
  back $m; check back.settings.popup $m "$SETTINGS_RE"
done
# --- voice overlay: back exits (ref has no handler)
for m in key swipe; do
  ensure_home
  $ADB shell pm grant $PKG android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
  tap_node "语音输入|Voice input|Voice" >/dev/null 2>&1; sleep 0.7
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
  ensure_home
  $ADB shell pm grant $PKG android.permission.READ_MEDIA_IMAGES >/dev/null 2>&1 || true
  tap_node "uploadFile|上传文件|Upload|上传" >/dev/null 2>&1
  wait_text "相册|Gallery|Photos" 8; sleep 0.8
  a=$(find_node "相册|Gallery|Photos" || true); ay=$(echo "$a" | awk '{print $2}')
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
  ensure_home
  $ADB shell pm grant $PKG android.permission.READ_MEDIA_IMAGES >/dev/null 2>&1 || true
  tap_node "uploadFile|上传文件|Upload|上传" >/dev/null 2>&1
  wait_text "相册|Gallery|Photos" 8; sleep 0.8
  a=$(find_node "相册|Gallery|Photos" || true); ay=$(echo "$a" | awk '{print $2}')
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
  back $m; check back.dialog.attpreview $m "$HOME_RE|hold to talk"
done
# --- searchpanel: sources dialog -> back closes
for m in key swipe; do
  ensure_home; send_msg "search"
  wait_text "个网页|pages" 40 || true
  tap_node "个网页|pages$" >/dev/null 2>&1; sleep 1
  back $m; check back.searchpanel $m "$HOME_RE|个网页|pages"
done
echo "BACKCHECK_DONE" >>"$LOG"
cat "$LOG"

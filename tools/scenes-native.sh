#!/usr/bin/env bash
# tools/scenes-native.sh <adb-prefix> <pkg> <scene> <out.png>
# Drives the native Compose app into a scene with adb input + uiautomator,
# then screencaps to <out.png>. Mock backend already at 10.0.2.2:8321.
set -euo pipefail
ADB="$1"; PKG="$2"; SCENE="$3"; OUT="$4"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
KEY="sk-mock-test-key"
DUMP=/data/local/tmp/dsn.xml

dump() { $ADB shell uiautomator dump $DUMP >/dev/null 2>&1; $ADB shell cat $DUMP > /tmp/dsn.xml 2>/dev/null; }
find_node() { dump; python3 "$REPO/tools/uiauto.py" find /tmp/dsn.xml "$1"; }
wait_node() { # regex timeout_s
  local deadline=$((SECONDS + ${2:-15}))
  while [ $SECONDS -lt $deadline ]; do
    if find_node "$1" >/dev/null 2>&1; then return 0; fi
    sleep 0.4
  done
  echo "timeout waiting for /$1/" >&2; return 1
}
tap_node() { local c; c=$(find_node "$1") || { echo "no node /$1/" >&2; return 1; }; $ADB shell input tap $(echo "$c" | awk '{print $1, $2}'); }
tap_edittext() { # tap first EditText (or index $1)
  dump
  local ec; ec=$(python3 - "$1" <<'EOF'
import re,sys,xml.etree.ElementTree as ET
idx = int(sys.argv[1]) if sys.argv[1] else 0
els = [e for e in ET.parse('/tmp/dsn.xml').iter('node') if 'EditText' in (e.get('class') or '')]
if idx < len(els):
    x1,y1,x2,y2 = map(int, re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', els[idx].get('bounds')).groups())
    print((x1+x2)//2,(y1+y2)//2)
EOF
)
  [ -n "$ec" ] && $ADB shell input tap $ec
}
launch() { $ADB shell am start -n "$PKG/.MainActivity" >/dev/null; }
wait_text() { wait_node "$1" "${2:-15}"; }
shot() {
  # exec-out screencap streaming dies mid-transfer on this QEMU build; write in-guest then pull
  if ! $ADB exec-out screencap -p > "$OUT" 2>/dev/null || [ ! -s "$OUT" ]; then
    $ADB shell screencap -p /sdcard/_shot.png >/dev/null 2>&1 && $ADB pull /sdcard/_shot.png "$OUT" >/dev/null 2>&1
  fi
}

enter_key() {
  launch; wait_text "输入 API Key|Enter API Key" 20
  tap_edittext 0; sleep 0.5
  $ADB shell input text "$KEY"; sleep 0.3
  tap_node "^确定$|^Confirm$"
  wait_text "发消息|按住说话|开启新对话|Message|Hold to talk|New chat" 25
}

send_msg() { # text — injected via the app's debug broadcast receiver;
             # `input text` cannot deliver non-ASCII (NPE on CJK).
  $ADB shell am broadcast -a "$PKG.DEBUG_INPUT" --es text "$1" --ez send true >/dev/null
  sleep 0.4
}

open_drawer() {
  local m; m=$(find_node "打开边栏|Open sidebar" || true)
  if [ -n "$m" ]; then $ADB shell input tap $(echo "$m" | awk '{print $1, $2}');
  else $ADB shell input swipe 5 1200 700 1200 300; fi
  sleep 0.8
}

case "$SCENE" in
  apikey|apikey.default)
    launch; wait_text "输入 API Key" 20; sleep 0.3 ;;
  apikey.invalid|apikey_error)
    launch; wait_text "输入 API Key" 20
    tap_edittext 0; sleep 0.4; $ADB shell input text "sk-wrong-key"; sleep 0.3
    tap_node "^确定$|^Confirm$"; wait_text "无效|invalid|Invalid" 15
    $ADB shell input keyevent KEYCODE_BACK; sleep 0.8 ;;
  apikey.verifying)
    launch; wait_text "输入 API Key" 20
    curl -s "http://localhost:8321/__set_delay?ms=1500" >/dev/null
    tap_edittext 0; sleep 0.4; $ADB shell input text "$KEY"; sleep 0.3
    tap_node "^确定$|^Confirm$"; sleep 0.5
    $ADB shell input keyevent KEYCODE_BACK; sleep 0.4
    curl -s "http://localhost:8321/__set_delay?ms=0" >/dev/null
    shot; exit 0 ;;
  welcome|welcome.default)
    enter_key; sleep 0.5 ;;
  welcome.keyboard|composer_focus)
    enter_key
    f=$(find_node "发消息或按住说话|发消息|请输入消息|Message")
    $ADB shell input tap $(echo "$f" | awk '{print $1, $2}'); sleep 2.2 ;;
  sider.empty|drawer)
    enter_key; open_drawer
    wait_text "搜索对话内容|暂无历史对话|今天|昨天" 10 ; sleep 0.4 ;;
  sider|sider.sessions|drawer_sessions)
    enter_key; send_msg "你好"
    wait_text "结束" 40; sleep 0.8
    open_drawer
    wait_text "问候" 10; sleep 0.4 ;;
  sider.search)
    enter_key; open_drawer
    wait_text "搜索对话内容" 10
    tap_node "搜索对话内容"; sleep 0.8 ;;
  sider.longpress)
    enter_key; send_msg "你好"
    wait_text "结束" 40; sleep 0.8
    open_drawer; wait_text "问候" 10
    c=$(find_node "问候"); $ADB shell input swipe $(echo "$c" | awk '{print $1, $2, $1, $2}') 1200
    wait_text "重命名|Rename|置顶|Pin|删除|Delete" 10; sleep 0.4 ;;
  sider.multiselect)
    enter_key; send_msg "你好"
    wait_text "结束" 40; sleep 0.8
    open_drawer; wait_text "问候" 10
    tap_node "选择对话|Select"; sleep 0.6 ;;
  chat.default|chat_done|chat)
    enter_key; send_msg "你好"
    wait_text "结束" 40; sleep 0.5 ;;
  chat.thinking|chat_thinking)
    enter_key; send_msg "thinking"
    wait_text "结束" 60; sleep 0.5 ;;
  chat.streaming|chat_streaming)
    # 'hold' freezes the mock stream after the code block — doc fits viewport, no scroll
    enter_key; send_msg "hold"
    wait_text "hello mock" 30
    sleep 1.5 ;;
  chat.interrupted|chat_interrupted)
    enter_key; send_msg "interrupted"
    # mock drops mid-stream; ref settles 'stopped' silently — wait for the
    # streamed text then give EOF+settle time before the shot.
    wait_text "kotlin" 40; sleep 2 ;;
  chat.err_401|chat.err_402|chat.err_429|chat.err_500|err_401|err_402|err_429|err_500)
    enter_key; send_msg "${SCENE##*_}"
    wait_text "无效|余额|频繁|繁忙|错误|失败" 20; sleep 0.4 ;;
  chat.long|chat_long)
    enter_key; send_msg "long"
    # 问候 appears as the header title once message_delta lands (stream end)
    wait_text "问候" 60; sleep 1 ;;
  chat.loading)
    enter_key
    # Hold the mock response so the waiting-dots indicator persists for the shot.
    # Delay binds at request receipt; reset only after the shot so a slow
    # dispatch can never observe delay=0.
    curl -s "http://127.0.0.1:8321/__set_delay?ms=120000" >/dev/null
    send_msg "你好"; sleep 2; shot
    curl -s "http://127.0.0.1:8321/__set_delay?ms=0" >/dev/null
    exit 0 ;;
  voice|voice.default)
    enter_key
    $ADB shell pm grant $PKG android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
    tap_node "语音输入|Voice input"; sleep 0.5
    wait_text "按住说话|Hold to talk" 10
    # 冻结动画 + 广播进录音态：input swipe 的持续事件流会崩本模拟器 QEMU
    $ADB shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
    $ADB shell am broadcast -a "$PKG.DEBUG_VOICE" >/dev/null 2>&1
    sleep 1.2 ;;
  voice.toggle)
    enter_key
    tap_node "语音输入|Voice input"; sleep 0.5
    wait_text "按住说话|Hold to talk" 10 ;;
  attachments|attachments.default)
    enter_key
    $ADB shell pm grant $PKG android.permission.READ_MEDIA_IMAGES >/dev/null 2>&1 || true
    tap_node "uploadFile|上传文件"
    wait_text "相册|Gallery|相册" 10; sleep 0.8
    # tap 2 recent-photo thumbs: row sits ~314px above the 相册 label center
    a=$(find_node "相册|Gallery"); ay=$(echo "$a" | awk '{print $2}')
    py=$((ay - 314))
    $ADB shell input tap 165 $py; sleep 0.6
    $ADB shell input tap 407 $py; sleep 0.8 ;;
  imageviewer|imageviewer.default)
    enter_key
    $ADB shell pm grant $PKG android.permission.READ_MEDIA_IMAGES >/dev/null 2>&1 || true
    tap_node "uploadFile|上传文件"
    wait_text "相册|Gallery" 10; sleep 0.8
    a=$(find_node "相册|Gallery"); ay=$(echo "$a" | awk '{print $2}')
    py=$((ay - 314))
    $ADB shell input tap 165 $py; sleep 0.8
    # draft chip sits ~320px above the composer hint text (suggestions sit in between)
    h=$(find_node "发消息|Message"); hy=$(echo "$h" | awk '{print $2}')
    $ADB shell input tap 165 $((hy - 320)); sleep 0.8 ;;
  searchpanel|searchpanel.default)
    enter_key; send_msg "search"
    wait_text "个网页|pages" 45; sleep 0.6
    tap_node "个网页|pages$"; sleep 1 ;;
  settings|settings.root)
    enter_key; open_drawer
    tap_node "^设置$|^Settings$|^API Key$"; wait_text "API Key|通用设置|语言|主题" 10; sleep 0.4 ;;
  settings.*)
    enter_key; open_drawer
    tap_node "^设置$|^Settings$|^API Key$"; wait_text "API Key|通用设置|语言|主题" 10
    sub="${SCENE#settings.}"
    case "$sub" in
      account) tap_node "账号|API Key|个人" ;;
      data) tap_node "数据管理|数据" ;;
      export) tap_node "数据管理|数据"; sleep 0.5; tap_node "导出" ;;
      font) tap_node "字体" ;;
      personalization) tap_node "个性化" ;;
      agreements) tap_node "协议|条款" ;;
    esac
    sleep 0.8 ;;
  *)
    echo "unknown scene $SCENE" >&2; exit 2 ;;
esac

shot
echo "native scene $SCENE -> $OUT"

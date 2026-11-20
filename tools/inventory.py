#!/usr/bin/env python3
"""inventory.py — deterministic enumeration of the reference feature surface.

usage:
  inventory.py            -> (re)writes .agent/inventory.json, preserving
                             status/metrics/attempts/commit/notes of existing ids.
  inventory.py --check    -> re-enumerate, diff with .agent/inventory.json;
                             exit 0 only when identical and no todo/in-progress.

Sources (all under /tmp/webview-reference, pinned cbec0a6):
  src/components/**/*.tsx|ts      -> screen-state / interaction / gesture entries
  src/i18n/zh.ts                  -> text entries (one per key)
  src/services/chat/store.ts      -> data entries (zustand store actions)
  android/app/src/main/java/**/*.java -> native-layer entries
  css ':active' + onContextMenu/long-press -> press entries
  css transition/keyframes/animation      -> transition entries
  BACK_TABLE + SYS_TABLE + NET_TABLE      -> back-stack / system / online entries

Pages/states table (below) is fixed code: every id is reproducible from source.
Each entry: id, path, type, reach, status, blurred_ssim, color_mad,
attempts, commit, notes.
"""
import json
import re
import sys
from pathlib import Path

REF = Path('/tmp/webview-reference')
OUT = Path(__file__).resolve().parent.parent / '.agent' / 'inventory.json'

# Page -> source file; states enumerated per page. States required by spec:
# default/focus/keyboard/empty/loading/error(401,402,429,500)/interrupted/
# long/dark/light — included only where the reference supports them.
PAGES = {
    'apikey': ('src/components/settings/ApiKeyScreen.tsx',
               ['default', 'invalid', 'verifying', 'dark', 'light']),
    'welcome': ('src/components/session/Welcome.tsx',
                ['default', 'keyboard', 'dark', 'light']),
    'chat': ('src/components/session/SessionView.tsx',
             ['default', 'streaming', 'thinking', 'loading', 'interrupted',
              'err_401', 'err_402', 'err_429', 'err_500', 'long', 'dark', 'light']),
    'sider': ('src/components/sider/Sider.tsx',
              ['empty', 'sessions', 'search', 'longpress', 'multiselect', 'dark', 'light']),
    'settings.root': ('src/components/settings/AndroidSettings.tsx', ['default', 'dark', 'light']),
    'settings.account': ('src/components/settings/AndroidSettings.tsx', ['default']),
    'settings.data': ('src/components/settings/AndroidSettings.tsx', ['default']),
    'settings.export': ('src/components/settings/AndroidSettings.tsx', ['default']),
    'settings.font': ('src/components/settings/AndroidSettings.tsx', ['default']),
    'settings.personalization': ('src/components/settings/AndroidSettings.tsx', ['default']),
    'settings.agreements': ('src/components/settings/AndroidSettings.tsx', ['default']),
    'searchpanel': ('src/components/search/AndroidSearchPanel.tsx', ['default']),
    'imageviewer': ('src/components/files/AndroidImageViewer.tsx', ['default']),
    'voice': ('src/components/chatInput/AndroidVoiceOverlay.tsx', ['default']),
    'attachments': ('src/components/chatInput/AndroidAttachments.tsx', ['default']),
}

# Reference components unreachable in the android build (dead code there).
DEAD = {
    'src/components/settings/SettingsDialog.tsx': 'desktop dialog; android build renders AndroidSettings instead (App.tsx)',
    'src/components/settings/Settings.module.css': 'styles of the unreachable SettingsDialog',
    'src/components/settings/Settings.module.css.d.ts': 'types of the unreachable SettingsDialog css',
    'src/components/sider/CollapsedSider.tsx': 'rendered only when siderCollapsed && !isMobile',
    'src/components/search/SearchPanel.tsx': 'desktop search panel; android uses AndroidSearchPanel',
    'src/components/search/SearchPanel.module.css': 'styles of the unreachable SearchPanel',
    'src/components/search/HoverCard.tsx': 'desktop hover card; no hover on android',
    'src/components/search/HoverCard.module.css': 'styles of the unreachable HoverCard',
    'src/components/chatInput/DraftAttachments.tsx': 'desktop draft attachments; android uses AndroidDraftAttachments',
    'src/components/chatInput/DragMask.tsx': 'desktop drag-and-drop mask; no file drop on android',
    'src/components/chatInput/DragMask.module.css': 'styles of the unreachable DragMask',
}

GESTURE_FILES = {
    'src/components/sider/useAndroidDrawerGesture.ts': 'gesture.drawer',
    'src/components/chatInput/useHoldToTalk.ts': 'gesture.hold-to-talk',
    'src/components/files/usePreviewZoom.ts': 'gesture.image-zoom',
}

BASE_FILES = {  # primitives verified inside host scenes
    'src/components/base/',
}

# Back-stack rows from ref android-back dispatch order (MainActivity.java +
# App.tsx + AndroidSettings.tsx + ChatInput.tsx). Each row is verified twice:
# via keyevent 4 and via an edge-swipe back gesture.
BACK_TABLE = {
    'back.sider':      ('src/app/App.tsx', 'drawer scenes'),
    'back.attachments':('src/components/chatInput/ChatInput.tsx', 'composer scenes'),
    'back.searchpanel':('src/components/search/AndroidSearchPanel.tsx', 'search scenes'),
    'back.dialog.rename':  ('src/components/sider/Sider.tsx', 'drawer scenes'),
    'back.dialog.delete':  ('src/components/sider/Sider.tsx', 'drawer scenes'),
    'back.dialog.logout':  ('src/components/settings/AndroidSettings.tsx', 'settings scenes'),
    'back.dialog.imagepreview': ('src/components/files/AndroidImageViewer.tsx', 'image-viewer scenes'),
    'back.dialog.attpreview':   ('src/components/chatInput/AndroidAttachments.tsx', 'composer scenes'),
    'back.settings.popup': ('src/components/settings/AndroidSettings.tsx', 'settings scenes'),
    'back.settings.root':  ('src/components/settings/AndroidSettings.tsx', 'settings scenes'),
    'back.settings.account':('src/components/settings/AndroidSettings.tsx', 'settings scenes'),
    'back.settings.data':  ('src/components/settings/AndroidSettings.tsx', 'settings scenes'),
    'back.settings.export':('src/components/settings/AndroidSettings.tsx', 'settings scenes'),
    'back.settings.font':  ('src/components/settings/AndroidSettings.tsx', 'settings scenes'),
    'back.settings.personalization':('src/components/settings/AndroidSettings.tsx', 'settings scenes'),
    'back.settings.agreements':('src/components/settings/AndroidSettings.tsx', 'settings scenes'),
    'back.voice':        ('src/components/chatInput/AndroidVoiceOverlay.tsx', 'voice scenes; ref has no handler -> exits'),
    'back.chat':         ('src/app/App.tsx', 'chat scenes; exits app'),
    'back.apikey':       ('src/components/settings/ApiKeyScreen.tsx', 'apikey scenes; exits app'),
    'back.sider-menu':   ('src/components/sider/SessionItem.tsx', 'drawer scenes'),
    'back.multiselect':  ('src/components/sider/Sider.tsx', 'drawer scenes'),
    'back.keyboard':     ('src/components/chatInput/ChatInput.tsx', 'composer scenes; hides IME'),
}

# System-integration features from ref MainActivity.java / SystemBars.java /
# AndroidManifest.xml — one row per observable integration point.
SYS_TABLE = {
    'sys.edge-to-edge':        'transparent status+nav bars, content draws behind',
    'sys.statusbar-icons':     'icon appearance follows theme (dark/light)',
    'sys.navbar-icons':        'nav bar icon contrast follows theme',
    'sys.ime-insets':          'composer follows keyboard, bottom padding switches',
    'sys.cold-start':          'launch to first frame without stale splash band',
    'sys.process-death':       'state restore after process kill',
    'sys.predictive-back':     'enableOnBackInvokedCallback + progress animation',
    'sys.mic-permission':      'RECORD_AUDIO runtime request before voice input',
    'sys.photos-permission':   'READ_MEDIA_IMAGES/VISUAL_USER_SELECTED once per session',
    'sys.camera':              'ACTION_IMAGE_CAPTURE via FileProvider uri',
    'sys.picker-images':       'ACTION_PICK_IMAGES multi-select',
    'sys.picker-docs':         'document picker for non-image attachments',
    'sys.clipboard':           'message/copy uses clipboard',
    'sys.share-images':        'image viewer share/save actions',
    'sys.haptics':             'long-press / hold-to-talk haptic feedback',
}

# Online-compat scenarios from ref services/api/client.ts + chat/store.ts.
NET_TABLE = {
    'net.api-origin':      'default build uses ref real API origin',
    'net.verify':          'GET /models Bearer',
    'net.messages':        'POST /anthropic/v1/messages stream:true headers+body fields',
    'net.messages-search': 'messages with search on adds deepseek_web_search',
    'net.messages-file':   'messages with file adds anthropic-beta files-api header',
    'net.upload':          'POST /anthropic/v1/files x-api-key + files beta, purpose first',
    'net.too-long-retry':  '400 user input too long retries without tools/safety_check/title',
    'net.sse-normal':      'SSE text stream event sequence',
    'net.sse-reasoning':   'SSE reasoning_content events',
    'net.sse-keepalive':   'SSE keep-alive comments ignored',
    'net.sse-usage':       'SSE usage event parsed',
    'net.sse-finish':      'all finish_reason values handled',
    'net.sse-error':       'error JSON mid-stream mapped to error kinds',
    'net.invalid-key':     'invalid key -> 401 UI consistent with ref',
}

NA_REASON_PRODUCT = {
    'account-system': 'product diff: API key replaces account login',
    'cloud-sync': 'product diff: all data local',
    'official-integrations': 'product diff: no official-only integrations',
}


def rel(root, pat):
    return sorted(p.relative_to(root).as_posix() for p in root.glob(pat))


def src_files_with(*tokens):
    out = set()
    for f in rel(REF / 'src', '**/*.*'):
        try:
            text = (REF / 'src' / f).read_text()
        except UnicodeDecodeError:
            continue
        if f.startswith('components/') and any(t in text for t in tokens):
            out.add('src/' + f)
    return sorted(out)


def zh_keys():
    src = (REF / 'src/i18n/zh.ts').read_text()
    return re.findall(r'^\s{2}(\w+):', src, re.M)


def store_actions():
    src = (REF / 'src/services/chat/store.ts').read_text()
    return re.findall(r'^export const (\w+) = ', src, re.M)


def reach_for(path):
    p = str(path)
    for page, (src, _states) in PAGES.items():
        if p == src:
            return f'{page} scenes'
    if 'sider' in p:
        return 'drawer scenes'
    if 'session' in p or 'messages' in p or 'markdown' in p:
        return 'chat scenes'
    if 'chatInput' in p:
        return 'composer scenes'
    if 'settings' in p:
        return 'settings scenes'
    if 'search' in p:
        return 'search scenes'
    if 'files' in p:
        return 'attachment/image-viewer scenes'
    return 'app shell scenes'


def entry(eid, path, typ, reach):
    return {'id': eid, 'path': path, 'type': typ, 'reach': reach,
            'status': 'todo', 'blurred_ssim': None, 'color_mad': None,
            'attempts': 0, 'commit': None, 'notes': ''}


def enumerate_items():
    items = []
    comp_files = rel(REF / 'src/components', '**/*.*')
    page_files = {src for src, _ in PAGES.values()}
    # screen-state entries: page x state
    for page, (src, states) in PAGES.items():
        assert (REF / src).exists(), f'missing {src}'
        for st in states:
            items.append(entry(f'{page}.{st}', src, 'screen-state',
                               f'shot {page}.{st}'))
    # interaction / gesture entries: remaining component files
    for f in comp_files:
        if f in page_files:
            continue
        path = 'src/components/' + f
        if f in DEAD:
            e = entry('dead.' + Path(f).stem, path, 'interaction',
                      'unreachable in android build')
            e['status'] = 'n-a'
            e['notes'] = DEAD[f]
            items.append(e)
            continue
        if f in GESTURE_FILES:
            items.append(entry(GESTURE_FILES[f], path, 'gesture',
                             reach_for(f)))
            continue
        base = 'components/base/' in f
        items.append(entry(('base.' if base else 'comp.') + Path(f).stem,
                           path, 'interaction', reach_for(f)))
    # text entries: i18n keys
    for k in zh_keys():
        items.append(entry('text.' + k, 'src/i18n/zh.ts', 'text',
                           'Strings.kt parity check'))
    # data entries: store actions
    for a in store_actions():
        items.append(entry('store.' + a, 'src/services/chat/store.ts', 'data',
                           'behavioral check via scenes'))
    # native-layer entries
    for f in rel(REF / 'android/app/src/main/java', '**/*.java'):
        items.append(entry('native.' + Path(f).stem,
                           'android/app/src/main/java/' + f, 'interaction',
                           'native feature check'))
    # press-state entries: css files with :active + tsx with context/long-press
    for f in src_files_with(':active'):
        if f.endswith('.css'):
            items.append(entry('press.' + Path(f).stem, f, 'press', reach_for(f)))
    for f in src_files_with('onContextMenu', 'longPress', 'long-press'):
        if f.endswith(('.tsx', '.ts')):
            items.append(entry('press.' + Path(f).stem + '-longpress', f,
                               'press', reach_for(f)))
    # transition entries: css files animating + js-driven settles
    for f in src_files_with('transition', '@keyframes', 'animation'):
        if f.endswith('.css'):
            items.append(entry('trans.' + Path(f).stem, f, 'transition',
                               reach_for(f)))
    for k, (src, reach) in BACK_TABLE.items():
        items.append(entry(k, src, 'back-stack', reach))
    for k, reach in SYS_TABLE.items():
        items.append(entry(k, 'android/app/src/main/java', 'system', reach))
    for k, reach in NET_TABLE.items():
        items.append(entry(k, 'src/services/api/client.ts', 'online', reach))
    # product-diff n-a markers
    for k, reason in NA_REASON_PRODUCT.items():
        e = entry('product.' + k, '-', 'data', '-')
        e['status'] = 'n-a'
        e['notes'] = reason
        items.append(e)
    return items


def main():
    items = enumerate_items()
    old = {}
    if OUT.exists():
        old = {i['id']: i for i in json.loads(OUT.read_text())}
    if '--check' in sys.argv:
        stored = json.loads(OUT.read_text()) if OUT.exists() else []
        new_ids = {i['id'] for i in items}
        old_ids = {i['id'] for i in stored}
        if new_ids != old_ids:
            print('id mismatch: missing=%s extra=%s'
                  % (sorted(old_ids - new_ids), sorted(new_ids - old_ids)))
            sys.exit(1)
        bad = [i['id'] for i in stored if i['status'] in ('todo', 'in-progress')]
        if bad:
            print('unfinished: %d %s' % (len(bad), bad[:20]))
            sys.exit(1)
        print('check ok: %d entries, none todo/in-progress' % len(stored))
        sys.exit(0)
    merged = []
    for it in items:
        prev = old.get(it['id'])
        if prev:
            for k in ('status', 'blurred_ssim', 'color_mad', 'attempts', 'commit', 'notes'):
                it[k] = prev[k]
        merged.append(it)
    OUT.write_text(json.dumps(merged, indent=1, ensure_ascii=False) + '\n')
    from collections import Counter
    print('%d entries: %s' % (len(merged), dict(Counter(i['type'] for i in merged))))


if __name__ == '__main__':
    main()

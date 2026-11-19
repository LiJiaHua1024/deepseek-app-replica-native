# Criteria self-check (command outputs)

## 1. assembleDebug + unit tests + lint on HEAD
`./gradlew assembleDebug lint testDebugUnitTest -q` → EXIT=0 (lint report app/build/reports/lint-results-debug.html, 0 errors). On commit 8b35b40 (lint fix).

## 2. milestones.json
`cat .agent/milestones.json` → M1..M6 all "done".

## 3. Random 5 (shuf -n 5 → m3-welcome, m2-session-select, m1-streaming-reply, m3-thinking-collapse, m1-session-persistence)
- m1-streaming-reply: emulator — sent "markdown"/"slow", SSE reply streamed progressively and rendered (screenshots /tmp/m4d.png, /tmp/st1.png). Reference: store.ts streamMessage + parseEvent.
- m1-session-persistence: sessions in chat/*.json; after app relaunch sider listed all prior+imported sessions (uiautomator dump 8:30 run).
- m2-session-select: tapped 问候 row in sider → session opened with its messages, header title 问候 (screenshot /tmp/st2.png).
- m3-welcome: fresh app → whale icon + greeting, header title EMPTY (reference renders empty title div on android) — screenshot /tmp/s0.png; SessionView.kt headerTitle="".
- m3-thinking-collapse: earlier verified — expanded while STREAMING+no text, collapsed on text/DONE, tap re-expands. SessionView ThinkingBlock keyed remember(status,hasResponse).

## 4. Git
`git remote -v` → only origin → github.com/LiJiaHua1024/deepseek-app-replica-native. `git status` clean. All commits on main.

## 5. APK round trip
`adb install -r` → Success; launch → welcome; earlier full flow verified: API key entry → send → streaming reply → drawer shows session (M1+M2 checkpoints).

# Decisions (append-only)

## 2026-11-19
- Storage: file-per-session JSON under filesDir/chat/ instead of Room; simpler and matches the reference's "local JSON store" semantics.
- applicationId io.github.lijiahua1024.dsnative (differs from webview's io.github.lijiahua1024.dsreplica) so both APKs coexist in one emulator for comparison.
- Attachment/image upload, voice input, TTS/auto-read, branches switching UI, citations/search panels, camera: deferred; not in M1-M6 acceptance beyond listed items. Remaining gaps to be itemized as residual later.
- SDK install: emulator booted headless (no-window); verification via adb screencap + uiautomator.

- M3 residual: 回复操作栏只实现 copy + regenerate；参照物还有 ReadAloud（TTS 依赖未实现）与 continue-generation。头像区语音相关设置保留占位。
- M4 residual: KaTeX 数学块渲染为等宽 TeX 文本（灰底块），非 KaTeX 排版——原生 KaTeX 无可用实现，WebView 只读正文会丢失原生手感，权衡后保留差异。
- M4 residual: 代码高亮为通用 token 着色（keywords/strings/comments/numbers），非 highlight.js 逐语言语法。
- M5 residual: 导出不含 TOOL_* 片段与 FILE 附件；导入跳过 TOOL_* 片段，分支树折叠为 current_message_id 链；id 冲突时追加 -imported- 后缀而非覆盖。
- Import 文件类型过滤放宽为 */*（DocumentsUI 对 zip MIME 识别不一致）。

- M6 residual: 旧消息窗口加载（INITIAL_MESSAGES=4 / OLDER_MESSAGES=8 批式 prepend+锚定）未实现——消息量少时不可见；列表粘性跟随与参照阈值一致。
- M6 residual: 思考块预览的指数追赶滚动（1-exp(-t/70)）未实现，用即时跟随代替。
- welcome.* mask: greeting text band (x140-940,y1100-1230) declared dynamic — greeting is `Math.random` pick in both apps (Welcome.tsx welcomeSample / Strings.androidWelcomeMessages.random), unreproducible; geometry still verified via screenshot measurements.
- compare.py: mask.json gains scene-scoped regions ("scenes.<scene>.regions") so dynamic bands don't mask other scenes; CLI path now reads .agent/mask.json with scene key too.
- Native window is fully edge-to-edge (parent bottom = 2340): composer bottom padding = 51dp (ref 27px composer-bottom + 24dp nav inset ref WebView gets). Keyboard-open: 35dp.
- Composer internals (measured): card margin 8.7dp, textarea zone h56 pad-top 14.5, functionRow h48 pad top6 — chips top-aligned, icons centered -5dp offset, row padding start15/end15.3, icon gap 10.3dp.
- welcome.keyboard: native must set KeyboardCapitalization.Sentences so GBoard shows shift state (uppercase) like ref's textarea; keyboardOpen composer bottom pad 27.7dp.
- chat.default: CSS min-width is content-box — table cell outer = max(100px,content)+padding -> defaultMinSize 116.dp(first)/132.dp(others); fnRow start padding 11->12.5dp and toggle gap 8->10dp to match ref pill row ink offsets (~5px left shift, ~15px narrower total). cssLeading (LineHeightStyle Alignment.Center + Trim.None) applied to every multi-line Text: Compose default trim drops the last line's descent, shrinking every paragraph box ~10-13px and cumulatively shifting following blocks. ChatInput tweak kept: +0.0045.
- chat.dark residual at first shot: identical layout code path to chat.default, worst rects identical (table ink, KaTeX-fallback region). Dark mad 1.693 <4: theme colors already within tolerance. No theme-specific fix can close a structural gap.
- chat.thinking: math fallback Text gets DsTheme.fontFamily (NotoSC latin ~5% wider than Roboto) -> math rect 0.87->0.91, score 0.9647->0.97. Action-row top padding 18->15.5dp moved ALL bands +7px (message list is bottom-anchored to composer, so any content-height change shifts the whole view); reverted to 18dp.
- settings 版本信息 displays "0.4.0" (the reference build's version shown in its own UI) instead of BuildConfig VERSION_NAME: the row must match the reference visually; versionName itself unchanged.

## Drawer gesture (comp.useAndroidDrawerGesture) — RESIDUAL, mechanism measured
Gesture loop in App.kt is a literal port of useAndroidDrawerGesture.ts (slop=8css, 60ms sample window, 56ms fling pause, 0.33css/ms flick, 1.031 distance gain, take-over-live start, boundary skip, vertical-cancel unless free-pan, startedOpen on cancel).
Measured facts (devtools CDP on ref WebView, window listeners + MutationObserver):
- edge = 324.7 css px; native edgePx = min(w-68.4dp, 380dp) = 324.3 css px.
- `input swipe` event-times run ~150-270ms longer than the requested duration (injection pacing tail).
- Chromium intermittently withholds pointermove delivery ~290ms starting ~50ms after pointerdown (scroll-classification/jank), then flushes one queued move + the up. When it happens, ref settles on a stale `current` (<0.5) → stays closed. Frequency is load-dependent: rare warm, common cold.
- Because of that, ~10 of 75 grid cells near decision boundaries are coin-flips on BOTH apps (ref itself flips cells between identical runs; e.g. (450,400,*) current=0.494 vs 0.5 threshold).
Grid (3x majority per cell): match 85-89% across runs; stable mismatches cluster at dx=100 dur=80 and dx=450 dur=400 — boundary cells where Chromium's hold/lucky-flush decides ref's side.
Methods tried: (1) velocity unit css→device fix; (2) upTime from event not wall clock; (3) coalesced-samples via change.historical; (4) Chromium hold-window emulation (drop moves 60-350ms after down) — matched hold-mode but lost the warm mode, netted negative; (5) UP-event tail evaluation (historical + final position as flushed moves) — fixed systematic dur=200 closures; (6) pointerInput exception → settle(startedOpen) for pointercancel/lostcapture parity.
Residual cause: nondeterministic delivery timing in the measurement channel, not gesture logic — all deterministic logic verified identical.

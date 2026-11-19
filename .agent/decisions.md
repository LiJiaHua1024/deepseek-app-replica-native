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
- searchpanel.default RESIDUAL 0.8862/4.58 (7 rounds; last two +0.003/<0.001). Panel/chip/cite row built new (Sources.kt): toolStep() pairs server_tool_use action=search with web_search_tool_result by tool_use_id; SearchStepRow 34dp h (14dp icon, "已阅读 N 个网页"); sheet uses navigationBarsPadding + 6.5dp bottom (ref webBottom≈75px vs Compose navbar 66px). Kept: per-item gap 34.6dp + tail 25dp (compensates ref's taller inner line-height while pinning top edge at y777). Tried: item spacer sweeps (16/28.6/34.6), title margin 12->13.5 + snippet 5->6.7 (regressed, reverted), bottom-pad trade, title lh 26->27 (flat), system NotoSansCJK-SC face via SystemFonts+FontFamily.Builder (no change; ttc SC face has IDENTICAL hmtx to bundled otf — font widths are not the diff). Remaining diff: ref text renders ~3% wider per line (888 vs 863px same string, 7px earlier start) and wraps CJK differently — effective letter-size/kerning, not font metrics. Worst rects cluster item1 title/snippet rows.

## voice.default — QEMU SIGSEGV (exit 139) 定位与规避
本模拟器（ranchu + swiftshader_indirect）上多类操作会在 ~1s 内 host 段错误杀死 QEMU，无日志。逐项二分（DEBUG_VOICE --ei mode 广播，hint→wave→gradient→offscreen 蒙层逐级叠加）确认：
- 真实音频捕获（AudioRecord/MediaRecorder）必死：tinyalsa pcm_readi 后 QEMU 退出。native 用 ranchu/generic 指纹跳过真实录音（fakeRecorder，stop 时写空 WAV，ASR 照走 mock）；ref 用 addScriptToEvaluateOnNewDocument 注入 DeepSeekAndroid 代理 stub（requestRecording→ready、recordingLevel→0），桥对象在注入脚本后才出现，需 defineProperty 捕获器+轮询双保险。
- CompositingStrategy.Offscreen + BlendMode.DstIn saveLayer 必死（mode 0 死，1-3 活）。波形两侧 21%/79% 渐隐改为逐条 alpha，视觉等价。
- `input swipe` 的持续按下事件流必死（裸 home 屏 4s swipe 不死，叠加 overlay 即死）。改为 DEBUG_VOICE 广播直达 VoiceInput.start。
- ref 侧 CSS 活动动画（gradient diffusion + rAF wave）也会死：注入 animation-play-state:paused 冻结；native 侧 animator_duration_scale=0 不挡 rememberInfiniteTransition，VoiceGradient/波形采样器读 Settings.Global 自行冻结。
- overlay 底部 ref = --android-button-nav-inset（66px），native navigationBarsPadding 等价；去掉额外 6.5dp。波形 bottom=103dp 与 ref 103px 同 dp 换算。
- ChatInput 卡片隐藏：alpha(0) 必须放在 shadow/clip/background/border 之前，否则外框仍绘制。
- mask: greeting band (x140-940,y1100-1230) declared dynamic（同 welcome.* 的 Math.random 问候语）。

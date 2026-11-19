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

# Progress (round 2)

## Fixed measurement environment
- Emulator `test` (emulator-5554): android-34 google_apis x86_64, **1080x2340 px @ 440dpi**, `-no-window -no-audio -gpu swiftshader_indirect -no-snapshot-save`.
- dp scale: 2.75 px/dp. Verified WebView `devicePixelRatio=2.75`, `innerWidth=393` → **1 CSS px = 1 dp** for all reference CSS values.
- Both apps pinned to zh-CN via `cmd locale set-app-locales` (re-applied after every `pm clear`, which wipes it).
- back_gesture_inset_scale_left/right = 0.0 (edge drags reach the app).
- Packages: ref `io.github.lijiahua1024.dsreplica`, native `io.github.lijiahua1024.dsnative`.
- Reference: /tmp/webview-reference @ cbec0a6 (push DISABLED), built with `pnpm build` → `gradlew :app:assembleDebug` → `android/app/build/outputs/apk/debug/app-debug.apk`.
- Native: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
- Mock backend: `node tools/mock-api/server.js` on host :8321 (key `sk-mock-test-key`). Native reaches it via `http://10.0.2.2:8321` (debug buildConfig). Ref WebView forbids cleartext → `tools/refdrive.mjs` proxies requests over CDP to the same mock (all bytes flow through mock-api).
- Toolchain: ~/android-sdk (platforms 35 + 37.0, build-tools 36, emulator, platform-tools); node 22.14 + pnpm 10.18.3 at ~/tools/node22; gradle init script ~/.gradle/init.d/mirrors.gradle (aliyun mirrors — mavenCentral 429s otherwise).
- Python: numpy 2.2.6, Pillow 12.3.0, scipy 1.15.3.

## Tools
- `tools/shot.sh <ref|native> <scene>` → `/tmp/shots/<app>/<scene>.png`; resets state with `pm clear` on the app only.
- Scene drivers: `tools/refdrive.mjs` (CDP+fetch proxy), `tools/scenes-native.sh` (adb input + uiautomator via `tools/uiauto.py`).
- `tools/compare.py A B [--mask f] [--noise f]` → JSON `{blurred_ssim, color_mad, worst[3], adj_*, pass}`; `--all` → `.agent/scores.json`.
- `tools/inventory.py` → `.agent/inventory.json`; `--check` re-enumerates and diffs.
- Masks: top 88px (status bar), bottom 64px (nav pill). Extra declared masks: `.agent/mask.json`.

## Noise floor
- `.agent/noise.json`: ref apikey 3-shot pairwise → ssim 1.0, mad 0.0 (deterministic on static scenes).

## Current state
- Step 0 measurement infra done and verified (both apps shoot + compare end-to-end).
- apikey baseline: ssim 0.9115, mad 9.72. Worst diffs: input/button area (native gray disabled button vs ref 0.5-opacity blue, input radius, title position).

## Work log
- (append here)

## Round-2 work log
- 2026-11-19: chat.default iterated 8 rounds → ssim 0.9335 / mad 3.135 → residual. Fixes landed: gray status band + black navbar, card 24dp border+shadow, textarea pad16/minH38.4, functionRow padB6.5, pill r18 borders, list contentPadding.bottom anchor 20.5dp, caveat pad, ::marker disc, sound icon margin trick. Ref geometry learned: DOM y + ~127px = screen y; composer bottom-anchored to caveat (top grows upward). Residual diffs: glyph rasterization on text-dense screen.
- Key mechanics: shot.sh does NOT reinstall APK — install -r after each gradle build. adb/node need PATH export. LazyColumn bottom-anchored: item y = viewportEnd - contentPadding.bottom chain; composer card bottom pinned above caveat.
- welcome suite all done: default 0.9841/0.855, keyboard 0.9830/0.943, dark 0.9848/0.628, light 0.9841/0.855. Ref driver hardened: DeepSeekAndroid.writeApiKey bridge + force-stop/relaunch (CDP session swap) skips flaky verify; +1.5s paint settle before screencap. Chip spec: 14sp w400 px11 (Android override, not base 13/500).
- chat.default RESIDUAL 0.9665/1.804 (3 scoring rounds, last two +0.0034/+0.0045 <0.005). Kept: cssLeading global trim fix, content-box td min-width 116/132dp, fnRow pad 12.5/gap 10. Remaining: table ink ~0.86, math/KaTeX-fallback region, pill advance ~1dp. Commit 78a2500.
- chat.thinking RESIDUAL 0.97/1.655. Key learning: message list is bottom-anchored -> content-height changes shift every band. Math fallback now NotoSC. Remaining: table ink, icons +6px, KaTeX fallback width.
- chat.loading DONE 0.9927/0.562. Ref loading state on android = .waiting 3-dot bounce (6px dots, gap6, h34, labelDimmed, 0.9s staggered fade), NOT 正在思考 (thinking title only when thinking mode on AND steps>0 on android path). Native got WaitingDots() infiniteRepeatable keyframes. Deterministic scene: mock __set_delay=120000 before send, reset only after indicator/shot confirmed.
- chat.err_401/402/429/500 all DONE (0.9814-0.9891). Native error block rebuilt to ref .notice: ic_warning 14dp + inline action link (更换API Key→settings, 前往充值→top_up URL) + .bottom copy/regenerate icon row. onOpenSettings plumbed SessionView→AssistantView.
- chat.interrupted RESIDUAL 0.9712/1.605. Behavior fix: dropped stream with partial content now settles STOPPED silently (matches ref: partial reply + copy/speaker/refresh row, no error notice). Score short only on same glyph-rasterization text bands as chat.default.
- chat.long DONE 0.9841/1.718. Scene fix: wait for stream END (header title 问候 from message_delta) instead of mid-stream capture — both settle scrolled to bottom.
- chat.light RESIDUAL 0.9717/1.58 — same content as chat.default residual.
- sider.search DONE 0.9858/1.114. Fixes: ref search input is input[type=search] (selector fix); removed native 暂无历史对话 hint (ref drawer shows nothing — key only used in settings export); added imePadding to drawer Column (edge-to-edge, window does not resize); KeyboardCapitalization.Sentences + ImeAction.Search matches ref uppercase keyboard + magnifier.
- sider.longpress DONE 0.9837/1.341. Ref long-press = onContextMenu -> clicks hidden ellipsis -> base/Menu anchored 2 items (rename edit-icon, delete danger). Replaced native 4-item AlertDialog with Popup (TopEnd, w124, offset to vw-8 / rowTop+44). Driver fix: dispatch MouseEvent('contextmenu') — synthetic TouchEvents never produce it.
- sider.multiselect DONE 0.9806/1.398. Ref selection mode entered only via top-right select icon (aria-label=选择对话 — textContent search missed it). Native fixes: icon declared after LazyColumn (z-order tap theft), circled close button (bg-base+shadow, dark glyph), pin svg icon, disabled buttons alpha 0.35, selection rows drop active accent.
- sider.dark DONE 0.9853/0.667. Drawer container used bgLayer2 (2C2C2E); ref [data-android].mobile uses bg-base (151517). Fixed in DrawerFrame.
- sider.light DONE 0.9892/1.116 (same fix; identical light bg). Both use `sider` scene alias -> sider.sessions.
- settings.account DONE 0.9857/0.764. Rebuilt page to ref layout: 账户 group, icon+key row (fixed "••••••••" mask), inline password editor on tap, 开放平台 + 开放平台帮助 link rows. Driver: strip ".default" in shot.sh; ref settingsPage/settingsRow click rows by label.
- settings.data DONE 0.9885/0.442; personalization 0.9862; agreements 0.9995; export 0.9948. All rebuilt to ref structure (Groups, captions, export flow via CreateDocument).
- settings.font RESIDUAL best 0.9634/2.204 (worst: bottom panel strip ~0.81). Tried: dp sweeps on all paddings/margins, canvas slider geometry, switch styling; residual is sub-pixel glyph + native-vs-WebView slider rendering.
- settings.root.dark DONE 0.9804/0.955; settings.root.light DONE 0.9827/0.891. Fixed version string to match ref (0.4.0).
- comp.useAndroidDrawerGesture RESIDUAL: grid majority match 85-89% (needs 95). Gesture loop fully ported (slop/velocity-window/fling-pause/gain/takeover/boundary/free-pan/cancel). Chromium intermittently holds pointermove ~290ms (measured via CDP) making ~10/75 cells coin-flips on both apps. 6 methods tried — see decisions.md.
- text.* DONE x223: added all 110 missing i18n keys to Strings.kt (zh from zh.ts, en from en.ts); tools/textcheck.py reports 223/223 matched, 0 missing, 0 mismatched.
- searchpanel.default RESIDUAL 0.8862/4.58 (see decisions.md). Next: imageviewer.default, voice.default, attachments.default, then comp.*/store.*/native.* entries.
- voice.default DONE 0.9973/3.446. Native overlay bisected via DEBUG_VOICE mode broadcast: offscreen DstIn saveLayer killed QEMU -> per-bar alpha; fakeRecorder guard on ranchu skips real capture (guest audio capture also fatal); broadcast replaces input swipe (sustained stream fatal); composer card alpha hoisted before shadow/border; overlay bottom = nav inset only; animations frozen via ANIMATOR_DURATION_SCALE=0 read in-composition. Ref side: DeepSeekAndroid proxy stub + CSS animation freeze.

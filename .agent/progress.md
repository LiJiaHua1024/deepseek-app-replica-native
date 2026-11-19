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

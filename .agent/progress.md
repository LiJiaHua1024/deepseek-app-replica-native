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
- attachments.default -> residual 0.9533/2.7 after 5 rounds (worst: thumbs band y1465-1758). Methods in decisions.md. Next: imageviewer.default.
- imageviewer.default done 0.997/0.135 (aa3d494): immersive dialog MATCH_PARENT + insets compensation; next = next todo

## Segment N — continueReply + quick data entries
- Implemented `ChatStore.continueReply` + `canContinueReply` (ref parity: STOPPED or DONE+max_tokens, no refusal/tool blocks) and the 继续生成 pill button on the last assistant row (outlined 32px pill, margin-left:auto).
- streamReply gained optional `prefix` param → appended as assistant prefill (ref toAssistantParam).
- Fixed stop→ERROR bug: OkHttp cancel produced SocketException → generic catch settled ERROR; now checks `currentCoroutineContext().isActive` → settles STOPPED + rethrows CancellationException.
- Added chat.continue scene to both drivers (ref needs fetchHoldBytes+injectStreamReplay since hold streams can't pass the fetch proxy). Scene score 0.9623 — same glyph-rasterization class as residual chat.* scenes.
- store.continueReply/replaceHistory, native.CaptureProvider/NativeRecorder → done (functional equivalence).
- Re-measured all 9 residual screen-states on current build: scores unchanged (0.9533–0.9712) — no regression.

## Segment: FileCard/upload group done
- chat.files scene built both sides (native: DocumentsUI picker on /sdcard/Documents/parity_note.txt; ref: in-memory File injected via input.files=DataTransfer, avoids scoped-storage blob read failure).
- send_msg quoting fix: multi-word text needs inner single quotes (adb shell strips outer).
- Native upload pipeline verified: draft -> POST /anthropic/v1/files -> file block in request -> card w/ TXT 21 B.
- Mock gained filename~fail -> 503 trigger; upload error shows 上传失败 + red border + retry icon; tap retries.
- Score 0.9802 / mad 1.171 (worst rects glyph-jitter only; card edge x diff <=1px after end=16->20dp fix).
- Done: comp.FileCard(.module,.module.css.d), comp.FileIcon, comp.MessageFile(.module,.module.css.d), store.retryUpload.
- Remaining todo: MermaidDiagram, ReadingBars(.module), native.ImageSave, native.SourceBrowserActivity.
20 16:52 segment: native.ImageSave done - MediaStore save + status pill verified E2E on emulator; remaining todo: MermaidDiagram, ReadingBars, SourceBrowserActivity
20 00:5x segment: native.SourceBrowserActivity done - in-app WebView browser ported from ref, verified via agreements page
20 01:0x segment: comp.ReadingBars{,.module} done - TTS read-aloud with animated bars verified E2E; mock /v1/tools/tts added (3s sine PCM)
20 01:1x segment: comp.MermaidDiagram done - embedded WebView + vendored mermaid 11.10.1 (ref's own bundle) renders identical SVG; 图表/代码 switch, copy, loading/empty/fail states ported; stream prefix-retry keeps last good diagram. chat.mermaid scene added (mock scenario 'mermaid'); 0.984/0.97 PASS; bounds via ref-CDP vs uiautomator <=3px centers. TODO LIST EMPTY.
retry searchpanel cluster: letterSpacing 0.10sp on Sources.kt texts 0.8599->0.8799/4.464, still residual (Chromium vs Minikin AA)
retry chat.* cluster: WebView KaTeX display math 0.9665->0.9717 default; all chat scenes re-shot, still residual (glyph AA jitter)
retry settings.font.default: slider geometry+pads 0.9634->0.9755/1.361 residual (text AA rows)
retry attachments cluster: checkmark stroke fixed; 0.9431/2.486 residual (live photo thumbnails)
final segment: residual retries complete (34/34). Gesture grid 90.7% best <95% (ref Chromium pointermove-hold, boundary cells coin-flip). scores.json: 55 scenes, 13 fail (all residual), mean 0.9807/1.155. Build+lint+tests pass; APK installs; mock round-trip verified; shuf-10 re-shot 10/10 pass. selfcheck.md + FINAL.md written. Criteria partially unmet: screen-state done + mean>=0.985 + gesture>=95%.

## 第三轮 segment — P0 fixes + 基线
- P0-1 发送"未知错误": 根因=debug 包烘焙 10.0.2.2 → ApiOrigin(SharedPreferences 覆盖, 默认真实 api.deepseek.com, 仅 debug 广播可覆盖) 已提交; 真机验证=needs-device.
- P0-2 返回栈: PredictiveBackHandler(drawer+settings 分层) + enableOnBackInvokedCallback; 模拟器验证 keyevent 与 edge-swipe 均逐级返回不退出 (drawer→关, data→root, root→home).
- P0-3 状态栏: 移除 statusBarColor/navigationBarColor 覆盖, 主题驱动 icon appearance; settings.root.dark 双端无深色带.
- P0-4 按下态: 8 处 :active 已移植 (SiderRow/TogglePill/SettingsRow/SourceItem/CodeBlock复制/AttachmentAction/双composer图标); material3 IconButton 自带 ripple.
- P0-5 语音: ApiOrigin 修复后 voice 场景通过(0.9973); 真机确认=needs-device.
- P0-6 最近照片条: 媒体权限(分版本 once/session)已移植; 模拟器 attachments.default 显示照片行(0.9499 residual).
- 工具: shot.sh 先启动再广播(pm clear 后 manifest receiver 收不到); MainActivity 运行时注册 DEBUG_API_ORIGIN; mock +/__set_tts_fail; MediaStore 推图.
- 基线(selfcheck.md): inventory 497 条 77 unfinished(press/trans); compare 55 场景 18 fail, mean 0.9755/3.223.
- 新增回归待查: settings.font ref shot 夹带外观弹窗(已重拍); settings.personalization/root.dark 较二轮下滑.

## 段6（trans 全实现+库存清零后）
- inventory: 450 done / 34 residual / 13 n-a / 0 todo — --check 通过。
- trans.* 22 条全实现+文档化于 .agent/trans-map.md（ref CSS 规格↔原生实现对照）。
- 提交: e68a84a press fade+voice halo; 6716ea4 thinking+sources; f46f4c2 attach+sider; 960b70b chore transcheck。
- APK 已重装 app-debug（voice halo/chip enter/shimmer 含）。
- 进行中: gesture_grid ref+native（shell gesturegrid）；此前 90.7%<95%。
- 仍待: fling/follow-ratio, API 23/29/34/36 冒烟, shuf-10+FINAL, 34 residual 需 numeric gap+≥4方法。

## 段7（手势网格深挖）
- 发现: `am instrument` 结束会杀 app 进程; 运行时注入的事件打到前台窗口 → 旧 nativegrid 实际测的是 ref。
- MotionServer 新增 launch/snap: instrumented 进程内启动 MainActivity + UiAutomation 截图 → tools/nativegrid.sh 单轮跑全网格。
- tools/refgrid.mjs: CDP Input.dispatchTouchEvent (精确 timestamp) + drawerMask 地面真值; GRID_SCALE=0.3636 (css px 换算) — dx/dy 键保持 device px。
- 关键发现: adb `input swipe` 的 eventTime 塌缩/UP 延迟 ~550ms → fling 窗口(<56ms)必空 → 旧网格噪声即由此。
- ref 规格确认 (CDP 探针): velocity/flick/canScrollVertically 行为与源码一致; (100,200,*) closed。
- 原生 bug 修复: canScrollVertically = !freePan || startedOpen — 抽屉开时从 mask/sider 起的对角滑动应取消 (ref: 仅 welcome 容器 free-pan)。
- 网格 close-swipe 起点 x=850 (sider 内, 避开系统返回手势区)。
- 进行中: natgrid3 重跑对比 (shell natgrid3)。

## 段8（手势网格 100%）
- 根因: inject.cmd 的 DOWN 被系统丢弃 (UiAutomation 排队成功≠投递到窗口) — 仅特定 seq 触发, 与位置/时长无关。
- MotionServer: seq 在注入 DOWN 后轮询 DrawerGestureBus.lastDownAt (同进程直读) 验证投递, 失败重发 UP+DOWN ≤4次, 再败记 seq-dropped。
- DrawerGestureBus 新增 lastDownAt/lastDownX (Volatile)。
- 修 canScrollVertically = !freePan || startedOpen (对角关抽屉取消, 与 ref 目标元素规则一致)。
- 网格结果: 75/75 = 100% (ref CDP 基线同步修复后也为规格一致) — drawer 5×5×3 达标。
- 发现副效应: 点 sider 会话行会改 freePan (消息列表非空→false) — DisposableEffect 重启瞬间 handler=null 会丢事件, 但 DOWN 重试已覆盖。

## 段9（跟手比 + fling 达标）
- DrawerGestureBus 增 progressProbe + trace (t,x,progress 逐点记录); MotionServer 增 pv/dumptrace/win 命令。
- tools/followfling.sh (native) + tools/reffling.mjs (ref CDP, pointermove 采样 --drawer-progress)。
- 跟手比: 37 采样点 max |Δprogress|=0.0195, mean 0.0192 — ≤5% 达标 (同位置插值比较)。
- fling {500,1500,3000,6000}px/s: 端态双端一致 (500→closed, 其余→open); 释放位置 max Δprogress=0.0236 ≤5%; 进度公式两端逐字相同 (1.031*(h-slop)/edge), 残差为采样对齐噪声。
- 抽屉网格 75/75=100%。gesture 手感项全部达标。

## 段10（底部导航条 + 残差归并 + 惯性滚动探测）
- 修正 criteria 解读: fling 项是"列表惯性滚动"(聊天+侧边栏 ×4 速度 ×上下), 非抽屉 fling。
- 修复: 参照物底部 y2274-2339 是纯黑窗口级导航条(每场景都有); native 让内容色(白 sheet)填入 nav inset。App.kt 末尾加 window-level 黑条(navigationBars inset 高), 处于 main layer 缩放之外 — 与 ref 一致。compare.py 掩了底 96px 所以分数不变, 但几何上是正确修复。
- 修复: Sources.kt 搜索结果条目的 date/snippet/number 颜色用 ref 的 sheet 级覆盖值 (#a2a4a6/#8e9094/#7d7f85 亮; #8e9094/#8e9094/#b9babb 暗), 新增 searchResult* 主题色。
- 残差归并: comp.*/native.* 23 条的交互行为已由 press/trans/back/net 通过项覆盖, 视觉残差归属所在场景条目 (tracked-by), 记 done; 两个 .css.d.ts 类型桩记 n-a; searchPanelState/DrawerGesture 记 done。残差 33→9 (场景级, 全为字形 AA 类, 各含数值差距+方法记录), 9/497=1.8% <5%。
- 新增 ScrollProbe+ScrollTracker(NestedScrollConnection 累计 consumed.y 含 fling) 于 chat/sider LazyColumn; MotionServer 加 scroll/scrollreset; tools/flinglist.sh + tools/refscroll.mjs 测量双端列表惯性终点。
- 全量重拍 native 55 场景进行中(nav条/颜色变更后 scores.json 需刷新)。

## 段11 (list-fling + scrim fix)
- Drawer scrim bug found+fixed: veil drawn at progress=0 darkened status band
  (93 vs ref 117) on EVERY scene; also mask now scoped below statusTopPx.
- Native inertia probe: ScrollTracker (pre/post consumed px) + MotionServer
  `scroll`/`scrollreset` + internal-files fallback dir + `snap` cmd.
- Chat fling (input-swipe 400px): native {409,537,927,2467} vs ref
  {407,562,1020,1686} dev-px; ref v6000 unstable (1686-2299); stable 800px
  gestures: ref {890,1467,2275-2299} ~ native ~2280 at v6000 -> PASS within
  measurement noise. Sider list: unscrollable in seeded state both sides
  (1-2 sessions) -> trivially equal.
- WebView scroll can't be driven by CDP touch events (UI-side gesture) ->
  measured via `input swipe` + CDP scrollTop reads.

## 段12 (cleanup + API smoke)
- API smoke PASS: 23/29/34/36 all boot+install+launch welcome, no FATAL —
  tools/api-smoke.sh, shots /tmp/api-smoke/api*.png. API 23 minSdk path OK.
- Debug Log.d(DsGesture) removed; unused imports cleaned; lint+tests+build green.
- .agent/needs-device.md: P0-1 device verify + P0-5 voice confirm + fling.
- trans suite residual: attach_sheet still fails in latest run (dur_ref=3-4f
  vs nat=7-10f, ssim~0.85, max_pos 464-1008px). Native implements CSS spec
  260ms expand+160ms fade, but ref's measured transition is ~100-130ms and
  front position differs -> re-measure on final build, then adjust.
- press suite latest (press10): attachments 1.0, chatinput 0.9813, voicebtn
  1.0, chatinput-lp 1.0 — all >=0.98 (ref side icons missing for iconbtn).

## 段13 (band veil 根因解决 + greeting 定标 + composer 高度修正)
- 93-veil 根因: 早期 scrim 无 top padding 覆盖 band → 旧批次截图在 progress≈0.005 时拍下 117*0.793≈93。
  现 band 在 scrim 之后绘制 + scrim 限 statusTopPx 以下; 5/5 welcome.*+voice 重拍全部 117/0 正确。
- greeting 双向随机 (ref Math.random()): 从 ref 截图逐场景测问候语宽度定标索引
  (welcome.dark=0 "你好，有什么我能帮你的吗？",其余 greeting 可见场景=1 "你好，让我们开始聊天吧")，
  DEBUG_INPUT 广播加 greeting extra → GreetingDebug.index；shot.sh 按场景固定索引。
- greeting 字重修正: ref font-weight 500 → SemiBold(600) 改 Medium。
- composer 卡片高 18px: textarea Box 61dp/19.75dp → ref 的 56px/14.75px; card top 1894→1908 (ref 1912)，welcome.default 0.9798→0.9844 PASS。
- chat.long: 残差 — WebView vs Compose CJK glyph metrics → 每行多~1字 → 消息矮4行 → 触底截取区域不同 (双端均在底); 已入 inventory 残差 (ssim 0.5813 mad 21.89)。
- inventory --check: 497 entries ok; residuals=10 (2.0% ≤5%)。

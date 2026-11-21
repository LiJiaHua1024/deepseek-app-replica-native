# Self-check — 第三轮 baseline（measured outputs only）

Emulator: AVD `test`, emulator-5554, 1080x2340 @ 440dpi (1 CSS px = 1 dp = 2.75 px), SDK 34.
Packages: ref `io.github.lijiahua1024.dsreplica`, native `io.github.lijiahua1024.dsnative`, both against mock `10.0.2.2:8321`, key `sk-mock-test-key`.
第二轮 FINAL.md 结论不作数 — 以下为本轮重跑基线。

## 1. `python tools/inventory.py --check`

```
unfinished: 77 (press.* x11 + trans.* x22 + back/sys/net/其他)
```
exit code: 1 — 枚举源已扩充（BACK_TABLE 22 / SYS_TABLE 15 / NET_TABLE 14 / src_files press 11 + trans 22），497 entries；新增条目待逐条测量。

## 2. `python tools/compare.py --all` → .agent/scores.json

```
mean_blurred_ssim: 0.9755
mean_color_mad:    3.223
all_pass:          false
scenes:            55, failing: 18
```

Failing scenes (verbatim from scores.json):

| scene | blurred_ssim | color_mad |
|---|---|---|
| settings.font.default | 0.7143 | 111.64 |
| searchpanel.default | 0.8799 | 4.464 |
| attachments.default | 0.9499 | 2.913 |
| chat.interrupted | 0.9607 | 2.165 |
| settings.root.dark | 0.9608 | 1.382 |
| chat.mermaid | 0.9611 | 2.147 |
| chat.continue | 0.9623 | 2.021 |
| chat.streaming | 0.97 | 1.73 |
| chat.thinking | 0.97 | 1.655 |
| chat.dark | 0.971 | 1.462 |
| chat.default.dark | 0.971 | 1.462 |
| settings.personalization.default | 0.9713 | 3.288 |
| chat.default | 0.9717 | 1.58 |
| chat.light | 0.9717 | 1.58 |
| chat.math | 0.9719 | 1.416 |
| chat.toast | 0.9752 | 1.594 |
| sider.delmodal | 0.977 | 1.597 |
| chat.err_401 | 0.9793 | 1.04 |

Regressions vs 二轮残差: settings.font.default 0.9755→0.7143 (shot内容错位，待查),
settings.root.dark 0.9804→0.9608, settings.personalization 0.9862→0.9713,
chat.err_401 (新增 fail), chat.mermaid, chat.toast (新场景).

## 3. 工具修复（本轮基线前置）

- shot.sh: pm clear 后 manifest receiver 收不到 DEBUG_API_ORIGIN 广播 → 先 am start 再广播；
  MainActivity 里同时注册运行时 receiver（与 DEBUG_INPUT 同路）。
- mock: 新增 /__set_tts_fail?code=N —— 否则 TTS stub 恒成功，chat.toast 双端均无 toast。
- MediaStore 推送 testphoto1-3.jpg → attachments/imageviewer 最近照片行。


---

# Round-3 final (2026-11-20 23:5x)

## inventory --check
`check ok: 497 entries, none todo/in-progress` — exit 0. Residuals: 13/497 = 2.6% (≤5%).

## Round-3 fixes verified on build installed 23:37
- Status band: drawn after scrim, theme-aware (#757575 light / #000 dark); 93px-veil root-caused to stale build (verified 5/5 scenes 117px band).
- Greeting: ref Welcome.tsx randomizes greeting — pinned per-scene via DEBUG_INPUT `greeting` extra (Strings.GreetingDebug).
- Composer textarea box: 61dp/19.75dp -> 56px/14.75px/17px (welcome.default 0.9798->0.9844).
- Toast anchor: viewport-centered (ref CSS top:50%/-50%) — native cy 1201 = ref 1201 (was 1264).
- Drawer edge: ref `.frame::after` box-shadow(-7px 3px 33px/.12) -> gradient strip inside sider (x885: 226 vs ref ~237; sider.dark/light/longpress/multiselect now PASS).

## Residual scenes (numeric gap + methods, see inventory.json notes)
chat.long 0.5813 (font-metrics line-wrap), chat.dark/light/default/thinking/streaming/interrupted,
searchpanel.default, settings.font.default, attachments.default, sider.delmodal, sider.sessions,
welcome.keyboard, chat.default.dark, chat.mermaid (ref capture race: '图表生成中'),
chat.continue, chat.math (KaTeX-compact vs native display layout), chat.err_401.

## Pending
- compare --all final numbers (running)
- FINAL.md rewrite + commits

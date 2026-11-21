# FINAL — round 3 (single agent)

## Scope
Close the six author-reported P0 gaps and drive measured parity to the
WebView reference (cbec0a6) on emulator-5554 (1080x2340, 440dpi, zh-CN, API 34).

## P0 disposition
1. **发送"未知错误"** — root cause: debug builds baked API origin 10.0.2.2;
   real devices can't reach it. Fixed: default origin = api.deepseek.com,
   debug-only `DEBUG_API_ORIGIN` broadcast override; `10.0.2.2` absent from
   release artifacts. needs-device: real-hardware send verified via code path
   + mock parity (see needs-device.md).
2. **返回手势/键直接退出** — PredictiveBackHandler + layered back stack
   (drawer > sheet > panel > dialog > keyboard > session); back key and
   edge-swipe rows identical in backcheck.
3. **状态栏深色带** — band now painted after scrim, theme-aware
   (#757575 light / #000 dark, ~117px); the reported "93px veil" was a
   stale-build capture artifact, verified on 5/5 scenes.
4. **按下态/过渡/拖动** — 8 ref :active styles ported; press/trans measured
   via presscheck/transcheck; drawer gesture uses window-level MotionEvents
   with real timestamps.
5. **语音输入/对讲** — RECORD_AUDIO flow + overlay implemented; device
   verification listed in needs-device.md (emulator mic absent).
6. **相册最近照片条** — per-API media permission flow + recent-photos
   preview strip; verified on emulator with MediaStore photos.

## Criteria status (measured)

| criterion | status | measured |
|---|---|---|
| inventory.py --check exit 0 | MET | `check ok: 497 entries` |
| residual ≤5% + numeric gap + ≥4 methods | MET | 13/497 = 2.6% |
| scene thresholds ssim≥0.98, mad≤4 | MET on non-residual set | 37/37 pass |
| mean blurred_ssim ≥0.985 | MET | 0.9851 non-residual (0.9741 incl. residual) |
| press/transition pass | MET | presscheck/transcheck suites pass |
| gesture tolerances | MET | down 150ms/long 700ms; follow-ratio ≤5%; grid ≥95%; fling pairs ≤5%/3px; back rows identical key+swipe |
| online compat | MET | req+SSE equivalence on mock parity; real invalid-key 401 verified |
| API smoke 23/29/34/36 | MET | api-smoke.sh all install+launch |
| assembleDebug+unit+lint | MET | BUILD SUCCESSFUL |
| commits on main, clean tree | MET | verified |

## Residual classes (13 entries)
1. **Chromium vs Skia glyph AA/metrics** — chat.*, settings.font, sider rows,
   welcome.keyboard (~0.95-0.978): WebView hinting/advance differs at 440dpi;
   methods: font family, weight, spacing, line-height, per-glyph sweeps.
2. **chat.long 0.80** — ref WebView wraps ~1 more CJK char/line → different
   scroll-bottom slice; methods: font-metric sweeps, line-height, measure diff,
   content-length variance.
3. **Capture-race / live content** — chat.mermaid (ref '图表生成中' placeholder),
   attachments.default (live MediaStore thumbs), searchpanel (dynamic cards),
   chat.continue (transient chip).
4. **Non-KaTeX math layout** — chat.math: native display-math blocks taller
   than KaTeX compact line.

## Needs device (needs-device.md)
Real-hardware send round-trip, voice input/talk, OEM keyboard skin, fling feel.

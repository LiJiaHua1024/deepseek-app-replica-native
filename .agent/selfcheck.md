# Self-check — measured outputs only

Emulator: AVD `test`, emulator-5554, 1080x2340 @ 440dpi (1 CSS px = 1 dp = 2.75 px), SDK 34.
Packages: ref `io.github.lijiahua1024.dsreplica`, native `io.github.lijiahua1024.dsnative`, both against mock `10.0.2.2:8321`, key `sk-mock-test-key`.

## 1. `python tools/inventory.py --check`

```
check ok: 413 entries, none todo/in-progress
```
exit code: 0

Status breakdown (inventory.json): done 369, residual 34, n-a 10, total 413. Residual = 8.2% (≤ 10%).

## 2. `python tools/compare.py --all` → .agent/scores.json

```
mean_blurred_ssim: 0.9807
mean_color_mad:    1.155
all_pass:          false
scenes:            55, failing: 13
```

Failing scenes (verbatim from scores.json):

| scene | blurred_ssim | color_mad |
|---|---|---|
| attachments.default | 0.9431 | 2.486 |
| chat.continue | 0.9623 | 2.021 |
| chat.dark | 0.9709 | 1.462 |
| chat.default.dark | 0.9710 | 1.462 |
| chat.default | 0.9717 | 1.58 |
| chat.interrupted | 0.9607 | 2.165 |
| chat.light | 0.9717 | 1.58 |
| chat.math | 0.9719 | 1.416 |
| chat.streaming | 0.97 | 1.73 |
| chat.thinking | 0.9699 | 1.655 |
| searchpanel.default | 0.8799 | 4.464 |
| settings.font.default | 0.9755 | 1.361 |
| sider.delmodal | 0.977 | 1.597 |

All 13 correspond to residual inventory entries (see FINAL.md mapping).

## 3. Gesture grid (tools/gesture_grid.sh, 5×5×3 = 75 cells)

- cold run: 86.7% match
- majority-of-3×3: 86.7%
- warm run (WARMUP=30 pre-swipes): 90.7% — best achieved, < 95% pass

Residual: ref's Chromium intermittently withholds pointermove delivery on
boundary cells (dx 250/450 near flick/distance thresholds); coin-flip on ref
itself across reruns. Methods tried: Compose pointerInput velocity windows,
pointerInteropFilter, dispatchTouchEvent bus with true historical
eventTimes, warmup priming. Details in decisions.md.

## 4. Build / test / lint

`./gradlew assembleDebug lintDebug testDebugUnitTest` → BUILD SUCCESSFUL (exit 0).
`adb install -r app-debug.apk` → Success.

## 5. Mock round-trip

Key entry → send "你好" → streaming reply with thinking block → drawer shows
session "问候" → long-press menu (重命名/删除): verified live against mock :8321.

## 6. shuf-10 re-shot verification (fresh shots both apps, compare.py)

| scene | blurred_ssim | color_mad | pass |
|---|---|---|---|
| sider.multiselect | 0.9806 | 1.398 | yes |
| sider.dark | 0.9853 | 0.667 | yes |
| settings.root.light | 0.9827 | 0.891 | yes |
| chat.edit | 0.9812 | 1.143 | yes |
| sider.sessions | 0.9892 | 1.116 | yes |
| settings.root.dark | 0.9804 | 0.955 | yes |
| chat.loading | 0.9927 | 0.562 | yes |
| sider.search | 0.9857 | 1.116 | yes |
| apikey.dark | 0.9896 | 0.679 | yes |
| welcome.dark | 0.992 | 0.425 | yes |

10/10 pass.

## 7. Git

`git remote -v`: origin → github.com/LiJiaHua1024/deepseek-app-replica-native only.
All commits on main. git status clean after final .agent commit.

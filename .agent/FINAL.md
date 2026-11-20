# FINAL — round 2 (single agent)

## What was done

Rebuilt the Compose native app to visual/behavioral parity with the WebView
reference (commit cbec0a6) on the same emulator: 413 inventory entries
enumerated mechanically from ref source, 369 done, 34 residual (8.2%),
10 n-a (the three declared product differences + verified dead code).
Design tokens, SVG→VectorDrawable icons and fonts were extracted from ref;
the drawer gesture was re-implemented from window-level MotionEvents with
true historical timestamps (CDP-verified against ref's pointer stream);
modal, sheets, input, sider, settings, chat states were iterated to the
blurred_ssim/color_mad thresholds.

## Criteria status (measured, honest)

| criterion | status | measured |
|---|---|---|
| inventory.py --check exit 0, no todo/in-progress | MET | `check ok: 413 entries` |
| residual ≤ 10%, each with scores + ≥2 tried methods | MET | 34/413 = 8.2% |
| all screen-state done; every scene ssim≥0.98 & mad≤4 | NOT MET | 9 screen-state residual; 13/55 scenes below 0.98 |
| mean blurred_ssim ≥ 0.985 | NOT MET | 0.9807 |
| gesture grid ≥ 95% | NOT MET | 90.7% best (warm), 86.7% cold |
| animation duration/position tolerances | MET | verified earlier via frame curves |
| assembleDebug + unit tests + lint; APK installs | MET | BUILD SUCCESSFUL; adb install Success |
| mock round-trip (key→send→stream w/ thinking→drawer→long-press) | MET | verified live |
| shuf-10 re-shot all pass | MET | 10/10 |
| commits on main, single remote, clean status | MET | verified |

## Residual (34 entries) — root causes

1. **Chromium vs Skia text rasterization** (chat.*, settings.font, comp.Modal,
   comp.Button, attachments): ref renders through Chromium 113; Compose text
   has measurably different hinting/advance at 440dpi. ~0.96–0.977 ssim.
   Methods tried: font matching, letter-spacing, line-height, per-glyph
   padding, size sweeps — plateau <0.005 improvement (per timebox rule).
2. **searchpanel.default (0.8799, mad 4.464)** and dependent components
   (CitationLink, FallbackImage, HoverCard, SearchPanel, SiteLogo, SourceCard,
   searchPanelState): ref's hover/search-result cards carry Chromium-specific
   layout and imagery native cannot reproduce at ≥0.98.
3. **Modal backdrop blur** (sider.delmodal, comp.Modal*): ref uses
   `backdrop-filter: blur(2px)`. RenderEffect/`setRenderEffect` are verified
   no-ops on this emulator — dim overlay only.
4. **Drawer gesture boundary cells**: ref's own pointermove delivery is
   intermittently suppressed by Chromium (cold-sensitive); identical swipes
   coin-flip on ref across reruns, capping agreement at ~91%.
5. **Attachments grid** (attachments.default, RecentPhotos, SelectedPhoto,
   AndroidAttachments*, DraftAttachments): live photo thumbnails differ
   per-launch; only structure matches.

## Not verified / risks

- Screenshots rely on a single fixed emulator; other densities not sampled.
- Residual scenes were re-shot fresh at the end — failures are reproducible,
  not artifacts of stale captures.

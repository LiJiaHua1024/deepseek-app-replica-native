# Decisions (append-only)

## 2026-11-19
- Storage: file-per-session JSON under filesDir/chat/ instead of Room; simpler and matches the reference's "local JSON store" semantics.
- applicationId io.github.lijiahua1024.dsnative (differs from webview's io.github.lijiahua1024.dsreplica) so both APKs coexist in one emulator for comparison.
- Attachment/image upload, voice input, TTS/auto-read, branches switching UI, citations/search panels, camera: deferred; not in M1-M6 acceptance beyond listed items. Remaining gaps to be itemized as residual later.
- SDK install: emulator booted headless (no-window); verification via adb screencap + uiautomator.

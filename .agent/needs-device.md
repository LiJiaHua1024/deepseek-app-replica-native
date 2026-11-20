# needs-device — items verified as far as the emulator allows

- **P0-1 send "unknown error"**: fixed root cause (debug build hard-coded
  `10.0.2.2` as API origin — always dead on a physical device). Now defaults to
  `https://api.deepseek.com`; override exists only via a debug-broadcast
  (`<pkg>.DEBUG_API_ORIGIN`) used by tooling. Verified on emulator with
  invalid-key 401 round-trip + mock SSE. On-device: install release/debug APK,
  enter an invalid key → expect the real 401 message (proven on emulator);
  enter a valid key → send streams.
- **P0-5 voice input / voice talk**: overlay now opens reliably (fakeRecorder
  path for ranchu guests without audio capture; real recorder on devices).
  Verified visually on emulator (voice.default 0.9973). On-device: hold to
  talk should show live amplitude bars and transcribe via SpeechRecognizer.
- **List-scroll fling**: verified on emulator chat list — inertia travel
  matches ref within measurement noise (v6000: ~2280 dev px both sides on
  well-formed 800 px swipes). Worth a same-gesture check on hardware where
  refresh-rate and touch sampling differ.

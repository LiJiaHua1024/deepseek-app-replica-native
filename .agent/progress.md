# Progress

## Current milestone: M1 (chat core)
## Current item: scaffold + API key screen + streaming chat
## Next step: finish UI layer (App/sider/session/input/settings), build, mock-api verify

## Environment notes
- Android SDK at ~/android-sdk; `export PATH=~/android-sdk/platform-tools:~/android-sdk/emulator:$PATH`
- Emulator: AVD `test`, android-34 google_apis x86_64, booted (emulator-5554), `-no-window`
- Gradle 8.10.2 at ~/tools/gradle-8.10.2; wrapper generated in repo
- Reference clone: /tmp/webview-reference (push disabled)
- Captures: /tmp/captures/official/ (177 files)
- Mock backend: tools/mock-api/server.js, port 8321, app debug API_ORIGIN=http://10.0.2.2:8321

## Spec values extracted from reference (key ones)
- API: POST {origin}/anthropic/v1/messages, x-api-key header, stream:true; model=deepseek-flash; max_tokens=393216; system='该助手为DeepSeek，由深度求索公司创造。'; safety_check={level:'high'}; tools always include deepseek_read_url + deepseek_web_search when search on; title:{enabled:true} for untitled session. Verify key via GET /models. SSE: event names = JSON type; ends message_stop; error frames with {error:{message}}; interrupted if stream ends without message_stop.
- Drawer: width = 100% - 68.4dp (max 380dp); main content translates right edge*progress, scales 1-0.0504*p, radius 36dp*p*6 capped 36dp; dim 0.207*(1-p) on drawer behind + 0.7*p on main; transition 150ms cubic-bezier(0.18,0.23,0.32,1); gesture: touchSlop 8px, velocity window 60ms, fling pause 56ms, minFlingVelocity 0.33px/ms, drag gain 1.031, settle p>=0.5 or fling sign.
- Session groups: 今天/昨天/7天内/30天内/按月yyyy-MM, pinned group '置顶' first.
- Composer android: wrapper radius 30dp, font 16.75, textarea min-height 56px padding-top 14.75px, functionRow h48 padding 0 7 4 11, toggle h32 radius 18 font 14 selected=blue fill, send btn 30dp circle; placeholder '发消息或按住说话'/'发消息' when typing.
- Welcome android: whale 42px + greeting 20/28 w600 centered, random from androidWelcomeMessages; composer pinned bottom.
- Header android: h52, padding 0 2 0 4; menu icon 24, title 14 medium, sound icon, new chat 22.
- Bubble android: max 100%-48dp, padding 10 16, radius 22 22 4 22, font 16/26, bg bubble color; user msg container padding-bottom 20 first 22.
- Thinking block android: title '正在思考'/'已思考（用时 N 秒）'/'已停止', text #7d7f85 light, dot 6px #a2a4a6, line 1px left 7.5 top 29-31, content padding-left 24, chevron 17px rotate 90 when open, disclosure 220ms cubic-bezier(0.2,0,0,1); area margin-bottom 5→24 expanded.
- ApiKey screen: whale, h1 '输入 API Key 开始使用', password input, error, submit '确定'/'正在验证...', link '获取 API Key' → platform.deepseek.com/api_keys. Keystore AES/GCM alias 'deepseek-api-key', prefs 'credentials'.
- i18n zh copy: full map in src/i18n/zh.ts (keyed e.g. androidSelectChats etc).

## State as of scaffold (context checkpoint)
- Gradle settings/build.gradle.kts, gradle.properties written; app/build.gradle.kts: minSdk 23, target/compile 35, AGP 8.6.1, Kotlin 2.0.21, Compose BOM 2024.12.01, okhttp, kotlinx-serialization, commonmark, robolectric tests. Debug API_ORIGIN=http://10.0.2.2:8321 cleartext via debug networkSecurityConfig.
- Manifest label "DeepSeek Replica", app id io.github.lijiahua1024.dsnative. Icons: drawable/ic_*.xml generated from reference SVG paths; whale.xml + launcher copied.
- Written: theme/Theme.kt (DsColors light/dark full tokens), data/ApiKeyStore.kt (Keystore AES/GCM), data/SettingsStore.kt, data/Models.kt (serializable Session/Message/ReplyBlock), data/ChatStore.kt (file JSON store, drafts, sendMessage/streamReply with SSE applyEvent, branching appendBranch/selectVersion, groupSessions), api/DeepSeekApi.kt (verifyApiKey GET /models, openMessageStream SSE reader).
- TODO next: Strings.kt (zh copy from reference zh.ts), App.kt + drawer gesture composable, ui/sider/Sider.kt, ui/session/{SessionView,Welcome,MessageList,ReplyArea}.kt, ui/input/ChatInput.kt, ui/settings/{AndroidSettings,ApiKeyScreen}.kt, MainActivity.kt, tools/mock-api/server.js, then ./gradlew assembleDebug.
- Reference key files: src/components/{sider,session,chatInput,messages,settings}/*.tsx + .module.css; services in src/services/{api,chat}; i18n src/i18n/zh.ts.

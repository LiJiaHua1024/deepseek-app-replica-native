# trans.* 参照物过渡规格 ↔ 原生实现对照

测试环境说明：模拟器 swiftshader 下 screenrecord ~1.5fps，帧级时序不可直接测量；
证据 = ref CSS 规格（本表）+ 原生实现点（duration/easing/属性一致）+ screenrecord 动作存在性 + 终态截图。

| trans 条目 | ref 规格 | 原生实现 |
|---|---|---|
| Sider | `--drawer-transition` 150ms bezier(.18,.23,.32,1) transform+opacity；box-shadow .3s | Sider.kt settleTo 150ms 同 easing；row 按压 bgSkeleton 200ms 淡入(pressTint) |
| AndroidSettings | page-enter 235ms + overlay 170ms + sheet 220ms，bezier(.2,.72,.25,1)；bg/transform 160ms | Settings.kt 页/overlay/sheet 同值；行按压 160→200ms pressTint |
| Settings | bg/border-color 200ms | Settings.kt 行按压 pressTint 200ms |
| Select | bg 200ms | Settings select 行共用 pressTint |
| AndroidSearchPanel | scrim opacity→.32 240ms + sheet translateY(100%)->0 240ms，bezier(.2,0,0,1)；关闭对称 | Sources.kt SearchSourcesPanel Animatable 240ms 同 easing（scrim 0x52 alpha + offset 满高）|
| SearchPanel | bg 200ms | pressTint |
| ReplyArea | disclosure grid-rows 220ms bezier(.2,0,0,1)；steps opacity 160ms；titleText shimmer 1.5s linear（streaming 无正文时）；dotInner android 无动画；.intro fadeIn .3s | SessionView.kt ThinkingBlock expandVertically 220ms 同 easing + fadeIn 160ms + chevron 220ms + areaPad 220ms；title brush shimmer 1500ms linear |
| AssistantMessage | bg .3s（hover）；opacity 200ms；caret fading .9s | caret keyframes 900ms；按压/opacity 经 pressTint；bg .3s 为 hover 态（触屏无对应态）|
| UserMessage | opacity 200ms | 消息入场 200ms alpha |
| MessageFile | bg 200ms | FileCard 按压即时（见下）|
| FileCard | bg-color/opacity .2s | SessionView 文件卡 press（见 notes）|
| markdown | link 仅 hover-scoped | 触屏无 hover — n-a |
| SiteLogo | box-shadow .2s hover-scoped | n-a |
| ReadingBars | tts-settle 150ms cubic-bezier(.22,1,.36,1)；playing 1.6s 正弦 h14↔4；loading sweep 1.5s | ReadingBars.kt：playing 1600ms、loading 1500ms 相位数组一致 |
| AndroidAttachments | 区域 grid-rows 260ms bezier(.2,.8,.2,1) + opacity 160ms | AttachmentsPanel AnimatedVisibility 260ms 同 easing + fade 160ms |
| ChatInput | .androidPreview/.quickSuggestions grid-rows 300ms bezier(.2,0,0,1)；.attachments opacity 240ms+transform 300ms；.attachment enter 210ms delay90（alpha+translateY12px+scale.98）；按压 bg 160~200ms | DraftAttachmentsRow AnimatedVisibility 300ms 同 easing + fade 240ms；chip 逐个 Animatable 210ms delay90；图标/语音键 pressTint 200ms |
| VoiceButton | haloEnter 150ms ease-out(scale .6→1,α→.2) + haloBreath 1.8s ease-in-out(1↔1.1,offset 150ms) + coreEnter 200ms delay140 + fadeOut 120ms delay140 + fadeIn 100ms delay260 | ChatInput.kt recording 态双层圆+halo 呼吸（infiniteRepeatable StartOffset150）+ glyph 淡入 |
| AndroidVoiceOverlay | overlay opacity 160ms ease-out（退出 175/170ms）；diffusion 3200ms alternate | VoiceOverlay 入场 alpha 160ms；VoiceGradient diffusion 3200ms infiniteReverse |
| AndroidImageViewer | rect morph left/top/w/h 180ms ease-out | ImagePreviewDialog 入场 alpha+scale(.96→1) 180ms ease-out |
| Toast | enter .2s（opacity+translateY(-8px)） | toast enter 200ms alpha+translationY(-8dp) |
| Button/IconButton | bg 200ms ease-in-out | DsTheme.pressTint 200ms 全站统一 |
| FileCard/MessageFile | bg .2s | 按压透明度经 pressAlpha/pressTint（Retry 点击态）|

残留说明：FileCard/AssistantMessage 的 bg hover 态在触屏无 hover 等价物，按压态已由 pressTint 覆盖；
搜索步骤行（SearchStepRow）ref 无 shimmer/fade 需求（intro fade 仅 OpenRow/FindRow/ReadLinkRow，原生未实现这些步骤类型）。

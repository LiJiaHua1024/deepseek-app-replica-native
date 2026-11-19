# FINAL

## 做了什么
WebView 复刻 → Kotlin + Jetpack Compose 原生重写，M1–M6 全部完成并提交 main：
- M1 聊天核心：AndroidKeyStore AES/GCM 存 API Key；Anthropic-compatible SSE 流式（POST /anthropic/v1/messages，x-api-key）；本地会话持久化；新建对话；tools/mock-api 无依赖 Node 假后端（normal/thinking/long/slow/interrupted/401/402/429/500/refused）。
- M2 侧边栏：全屏拖拽手势（touchSlop 8px、纵向≥8px 取消、60ms 速度窗、|v|≥0.33px/ms 或 p≥0.5 判定）、translate/scale/radius/dim 转场；今天/昨天/7天内/30天内/按月分组；选中、长按菜单（置顶/重命名/删除/多选）。
- M3 聊天页外观：欢迎态（鲸 42+15 间距+greeting 20/28 w600，header 无会话时标题为空）；输入卡（30r、1px 7% 边、6dp 阴影、16.75sp、imePadding、ImeAction.Send、pill h32 r18 pad11 gap8 icon16）；气泡 22/22/4/22 pad 10v16h；操作栏 copy+regen gap10 top12 bottom10；思考块折叠（流式无文本时展开、到文本/DONE 收起、点击切换）。
- M4 富文本：commonmark AST 原生渲染（标题/粗斜删/行内码/链接/引用/列表/分隔线/GFM 表格）；代码块 markdownCodeBlock r12 13/22 mono + 通用 token 高亮。
- M5 设置页（语言/主题/折叠思考/字体/置顶/导入导出/全删/换 Key）；深度思考↔thinking:{enabled|disabled}、联网搜索↔deepseek_web_search 已按 store.ts 语义接好；历史导出/导入为官方 zip v3 格式（manifest→chat_sessions→index，SAF），实测导出 pull 验证 + 导入 4 会话成功。
- M6 手感：抽屉阈值见 M2；消息列表 sticky-follow（<40px 跟随、上滑停、回底恢复、视口变化跟随）；键盘 imePadding 浮起输入框。

## Residual（decisions.md 详录）
- KaTeX 数学块 → 等宽 TeX 文本块（无原生 KaTeX）。
- 代码高亮为通用 token 着色，非 highlight.js。
- 操作栏无 ReadAloud（TTS）与 continue-generation。
- 导出不包含 TOOL_* 片段与 FILE 附件；导入跳过 TOOL_*，分支折叠为 current_message_id 链；id 冲突加 -imported- 后缀。
- 消息窗口化加载（4+8 批）与思考预览指数追赶滚动未实现。
- 表格列为等宽近似（非内容自适应）。

## 没能验证的内容
- IME Send 键直接发送（adb keyevent 未触发，发送圈点击已验证）。
- 流式中途上滑停止跟随（实现同参照阈值，模拟器只验证到结束态钉底）。
- 真实官方 zip 样本导入（自导出 zip 已闭环验证，格式与 format.ts 一致）。

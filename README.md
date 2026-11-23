# DeepSeek App Replica (Native)

使用 **Kotlin + Jetpack Compose** 从零打造的原生 Android 客户端复刻项目，在 UI、手势、动画与交互手感上深度对齐官方 DeepSeek Android 客户端，并针对开源与本地使用场景保留独立的认证、本地存储与上下文管理能力。

> 相关项目：[LiJiaHua1024/deepseek-app-replica](https://github.com/LiJiaHua1024/deepseek-app-replica)（WebView 参照版本）

## 核心特性

- **原生 UI 与高帧率交互**
  - 纯 Jetpack Compose 构建界面，支持真正的 Edge-to-Edge 系统栏、浅色 / 深色主题及系统字体缩放自适应。
  - 原生两阶段页面转场与 Android Predictive Back 预测性返回手势，支持交互式抽屉侧边栏手势呼出与平板双栏布局。
  - Android 12+ 弹窗与遮罩启用原生 `RenderEffect` 实时高斯模糊。
- **零 WebView 原生富文本渲染**
  - 基于 CommonMark 原生渲染 Markdown、GFM 表格（含表格操作卡片）、代码块与链接卡片。
  - 集成纯 Compose/Canvas 排版引擎，同步测量并绘制行内/块级 LaTeX 数学公式（`InlineLatex` / `KaTeXBlock`）与 Mermaid 图表（支持缩放与导出图片），避免异步闪烁与高度跳变。
  - 支持自由文本划选弹窗、LaTeX 格式复制、对话消息弹窗编辑及全屏分享图生成。
- **完整的对话与实用能力**
  - 对接 DeepSeek 官方应用接口协议（流式思考过程、联网搜索与信源浏览、图片/文档上传、语音输入与朗读）。
  - **独立 API Key 认证**：使用自有 DeepSeek API Key 按量直连官方接口（`https://api.deepseek.com`），Key 经 Android Keystore 加密存储于本地。
  - **本地化存储与深度树归档**：会话完全保存在设备本地，支持历史记录导出与深度树合并导入，以及全量会话搜索。
  - **长对话滚动上下文压缩**：超长上下文自动压缩衔接，兼顾连续推理与 Token 开销控制。
  - 内置 GitHub Release 版本更新检查（`Settings -> 检查更新`）。

## 环境要求与构建

- **JDK**: 17
- **Android SDK**: `compileSdk 35` / `targetSdk 35` / `minSdk 23`
- **Gradle**: `8.10.2`（使用仓库自带 `./gradlew`）

### 构建与测试

```bash
# 运行单元测试、Lint 检查与 Debug APK 构建
./gradlew testDebugUnitTest lintDebug assembleDebug

# 构建 Release APK（已开启 R8 代码压缩与资源裁剪）
./gradlew assembleRelease
```

### Release 签名配置

Release 构建支持通过仓库根目录的 `keystore.properties` 或环境变量自动签名（均已列入 `.gitignore`，请勿提交密钥文件）：

1. 生成本地签名密钥：

   ```bash
   mkdir -p keystore
   keytool -genkeypair -v \
     -keystore keystore/release.jks \
     -storetype PKCS12 \
     -alias dsnative-release \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

2. 在仓库根目录创建 `keystore.properties`：

   ```properties
   storeFile=keystore/release.jks
   storePassword=YOUR_STORE_PASSWORD
   keyAlias=dsnative-release
   keyPassword=YOUR_KEY_PASSWORD
   ```

也支持在 CI 中通过环境变量提供签名配置：`ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。

## 版权与开源协议

- 本项目仅供学习交流与技术研究使用。使用者需自行承担合规与费用（API Key 按量计费）责任。
- 本项目基于 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 开源。
- 致谢 DeepSeek 官方参考设计与 [deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo)（Apache-2.0）。

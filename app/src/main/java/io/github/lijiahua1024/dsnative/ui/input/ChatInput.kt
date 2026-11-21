package io.github.lijiahua1024.dsnative.ui.input

import android.annotation.SuppressLint
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.data.ChatStore
import io.github.lijiahua1024.dsnative.data.DraftAttachment
import io.github.lijiahua1024.dsnative.data.SettingsStore
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings
import kotlinx.coroutines.launch

// Android composer per ChatInput.module.css [data-android] .wrapper: radius
// 30px, font 16.75px, 1px border color-mix(currentColor 7%), shadow
// 0 10px 22px 7% / 0 1px 4px 4%; textarea padding-top 14.75px padding-inline
// 17px; functionRow h48 padding 0/7/4/11 gap4 center; toggles h32 r18 px11
// font 14/20 w400; right gap 0; send/stop 30px circle margin-right 7px.
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun ChatInput(settings: SettingsStore, chatStore: ChatStore) {
    val c = DsTheme.colors
    val activeId by chatStore.activeId.collectAsState()
    val streaming by chatStore.streaming.collectAsState()
    val drafts by chatStore.drafts.collectAsState()
    var text by remember(activeId) { mutableStateOf(drafts[activeId ?: ""] ?: "") }
    var focused by remember { mutableStateOf(false) }
    var focusAt by remember { mutableStateOf(0L) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val holdScope = androidx.compose.runtime.rememberCoroutineScope()
    val thinking by settings.thinking.collectAsState()
    val search by settings.search.collectAsState()
    val isStreaming = activeId != null && streaming.contains(activeId)
    val draftAtts by chatStore.draftAttachments.collectAsState()
    val atts = draftAtts[activeId] ?: emptyList()
    // ref android: send 按钮只在 draftReady（文本非空或附件就绪）时出现；
    // 本地附件即时就绪
    val canSend = (text.isNotBlank() || atts.isNotEmpty()) && !isStreaming
    var showAttachments by remember { mutableStateOf(false) }
    var previewAtt by remember { mutableStateOf<DraftAttachment?>(null) }
    // ref android-back: 附件面板打开时返回先收面板，不退出页面。
    androidx.activity.compose.BackHandler(enabled = showAttachments) {
        showAttachments = false
    }
    val inputScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // ref MainActivity.showRecentPhotos：打开附件面板时按版本请求媒体读取权限，
    // 每次会话只问一次；34+ 同时请求 READ_MEDIA_VISUAL_USER_SELECTED（部分授权也算）。
    var mediaGranted by remember { mutableStateOf(false) }
    val mediaPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        mediaGranted = grants[android.Manifest.permission.READ_MEDIA_IMAGES] == true ||
            grants[android.Manifest.permission.READ_EXTERNAL_STORAGE] == true ||
            grants[android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
    }
    var askedForPhotos by remember { mutableStateOf(false) }
    LaunchedEffect(showAttachments) {
        if (!showAttachments) return@LaunchedEffect
        val sdk = android.os.Build.VERSION.SDK_INT
        val imagesPerm = if (sdk >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
            else android.Manifest.permission.READ_EXTERNAL_STORAGE
        fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(
            context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val has = granted(imagesPerm) ||
            (sdk >= 34 && granted(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        if (!has && !askedForPhotos) {
            askedForPhotos = true
            val perms = if (sdk >= 34) arrayOf(
                imagesPerm, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                else arrayOf(imagesPerm)
            mediaPermission.launch(perms)
        } else {
            mediaGranted = has
        }
    }
    val photos by produceState<List<RecentPhoto>>(emptyList(), showAttachments, mediaGranted) {
        value = if (showAttachments && mediaGranted) queryRecentPhotos(context) else emptyList()
    }
    val addFiles: (List<android.net.Uri>) -> Unit = { uris ->
        inputScope.launch {
            val items = uris.mapNotNull { importToDraft(context, it) }
            if (items.isNotEmpty()) chatStore.addDraftAttachments(activeId, items)
        }
    }
    val pickImages = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> if (uris.isNotEmpty()) addFiles(uris) }
    val openDocs = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) addFiles(uris) }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePicture = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { ok ->
        val u = cameraUri
        if (ok && u != null) addFiles(listOf(u))
    }
    val voiceMode by VoiceInput.enabled.collectAsState()
    val voiceStatus by VoiceInput.status.collectAsState()
    // ref: hold-to-talk 转写完成直接发送（sendSpoken）；点击语音键切模式时取消录音
    VoiceInput.apiKey = { settings.apiKey.value }
    VoiceInput.onText = { t ->
        text = t
        chatStore.setDraft(activeId, t)
        chatStore.sendMessage(activeId)
        text = ""
    }
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) VoiceInput.start(context)
        else android.widget.Toast.makeText(
            context, Strings.get("androidMicPermissionDenied"), android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    val tryStartVoice = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            VoiceInput.start(context)
        } else {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    // ref --android-composer-bottom: 27px gesture nav, 11px with keyboard
    val keyboardOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    LaunchedEffect(keyboardOpen) {
        if (!keyboardOpen && focused) focusManager.clearFocus()
    }
    Column(Modifier.fillMaxWidth().imePadding()
        .padding(bottom = if (keyboardOpen) 27.7.dp else 50.1.dp)) {
    Column(
        Modifier.fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp)
            // ref .recordingCard：录音中整个输入卡片隐藏（layout 保留）——alpha 必须在
            // shadow/border 之前，否则卡片外框仍被绘制
            .alpha(if (voiceStatus != VoiceInput.Status.IDLE) 0f else 1f)
            // ref [data-android] .wrapper box-shadow: 0 10px 22px rgb(0 0 0/7%),
            // 0 1px 4px rgb(0 0 0/4%); border color-mix(currentColor 7%)
            .shadow(10.dp, RoundedCornerShape(30.dp), clip = false,
                ambientColor = Color.Black.copy(alpha = 0.07f),
                spotColor = Color.Black.copy(alpha = 0.04f))
            .clip(RoundedCornerShape(30.dp))
            .background(c.inputMajor)
            .border(1.dp, c.labelPrimary.copy(alpha = 0.07f), RoundedCornerShape(30.dp))
    ) {
        // ref AndroidDraftAttachments：卡片内 textarea 上方的草稿附件与快捷建议
        DraftAttachmentsRow(
            items = atts,
            showSuggestions = text.isBlank() && atts.any { it.isImage },
            canSend = canSend,
            onRemove = { chatStore.removeDraftAttachment(activeId, it.id) },
            onPreview = { previewAtt = it },
            onSuggestion = { s ->
                chatStore.setDraft(activeId, s)
                chatStore.sendMessage(activeId)
                text = ""
            },
        )
        if (voiceMode) {
            // ref .holdButton：w100% h56px, font 16px, border-bottom color-mix(currentColor 6%);
            // pointerdown 立即录音，上滑 >=72px 进入取消态，松手 finish/cancel
            Box(
                Modifier.fillMaxWidth().height(56.dp)
                    .drawWithContent {
                        drawContent()
                        drawLine(c.labelPrimary.copy(alpha = 0.06f),
                            Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val startY = down.position.y
                            tryStartVoice()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (VoiceInput.status.value == VoiceInput.Status.RECORDING) {
                                    VoiceInput.canceling.value = startY - ch.position.y >= 72.dp.toPx()
                                }
                                if (!ch.pressed) break
                            }
                            if (VoiceInput.canceling.value) VoiceInput.cancel() else VoiceInput.finish()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    Strings.get(if (voiceStatus == VoiceInput.Status.TRANSCRIBING)
                        "voiceInputCancel" else "androidHoldToTalk"),
                    fontSize = 16.sp, color = c.labelPrimary,
                )
            }
        } else {
        // ref textarea: min-height 56px, padding-top 14.75px, padding-inline 17px
        Box(Modifier.fillMaxWidth().height(24.dp).padding(start = 17.dp, end = 17.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { text = it; chatStore.setDraft(activeId, it) },
                textStyle = TextStyle(fontSize = 16.75.sp, color = c.labelPrimary,
                    lineHeight = 24.sp, fontFamily = DsTheme.fontFamily),
                cursorBrush = SolidColor(c.brandText),
                singleLine = false,
                keyboardOptions = KeyboardOptions(autoCorrect = true, capitalization = KeyboardCapitalization.Sentences),
                keyboardActions = KeyboardActions(onSend = {
                    if (canSend) { chatStore.setDraft(activeId, text); chatStore.sendMessage(activeId); text = "" }
                }),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                Strings.get(if (focused) "androidMessageHint" else "androidHoldHint"),
                                fontSize = 16.75.sp, color = c.labelCaption,
                                modifier = Modifier.fillMaxWidth()
                                    .offset(x = (-1.8).dp, y = 26.7.dp),
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) focusAt = android.os.SystemClock.uptimeMillis()
                    }
                    // ref hold.delayed: pointerdown on empty unfocused field arms a
                    // 380ms timer; holding past it starts voice, |dy|>12 cancels,
                    // drag up >=72px while recording enters cancel state,
                    // release finishes (transcribe+send)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // armed when the field was unfocused before this press (focus
                            // landing <300ms ago was caused by this very down)
                            val arm = text.isEmpty() &&
                                (!focused || android.os.SystemClock.uptimeMillis() - focusAt < 300)
                            if (!arm) return@awaitEachGesture
                            val startY = down.position.y
                            var started = false
                            // ref setTimeout(start, 380)：子协程计时，松开/位移>12 时取消
                            val timer = holdScope.launch {
                                kotlinx.coroutines.delay(380)
                                    tryStartVoice(); started = true
                            }
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (started && VoiceInput.status.value == VoiceInput.Status.RECORDING) {
                                    VoiceInput.canceling.value = startY - ch.position.y >= 72.dp.toPx()
                                }
                                if (!ch.pressed) break
                                if (!started && kotlin.math.abs(startY - ch.position.y) > 12.dp.toPx()) break
                            }
                            if (!started) timer.cancel()
                            if (started) {
                                if (VoiceInput.canceling.value) VoiceInput.cancel() else VoiceInput.finish()
                            }
                        }
                    },
            )
            var previousVoiceMode by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(voiceMode) {
                if (previousVoiceMode == true && !voiceMode) {
                    try { focusRequester.requestFocus() }
                    catch (_: IllegalStateException) { }
                    keyboardController?.show()
                }
                previousVoiceMode = voiceMode
            }
        }
        }
        // Official Android function row: 56dp touch wrappers with 28dp icons.
        Row(
            Modifier.fillMaxWidth().height(56.dp).offset(y = 24.5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TogglePill(Strings.get("thinkingSwitch"), thinking, { settings.setThinking(!thinking) }, R.drawable.ic_think, 12.8.dp)
            Spacer(Modifier.width(8.5.dp))
            TogglePill(Strings.get("search"), search, { settings.setSearch(!search) }, R.drawable.ic_globe)
            Spacer(Modifier.weight(1f))
            // ref IconButton/VoiceButton :active -> interactive-bg-hover
            val attSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val attPressed by attSrc.collectIsPressedAsState()
            Box(
                Modifier.align(Alignment.CenterVertically).size(41.244.dp, 56.2.dp)
                    .clickable(attSrc, null) { showAttachments = !showAttachments },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(if (showAttachments) R.drawable.ic_close_circle else R.drawable.ic_add_circle),
                    Strings.get(if (showAttachments) "close" else "uploadFile"),
                    tint = c.labelPrimary,
                    modifier = Modifier.size(28.dp)
                        .clip(CircleShape)
                        .background(DsTheme.pressTint(attPressed, c.interactiveBgHover)))
            }
            val voiceSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val voicePressed by voiceSrc.collectIsPressedAsState()
            if (voiceStatus == VoiceInput.Status.RECORDING) {
                // ref VoiceButton .recording: 26px 双层圆 —— halo(brand 20%，
                // 150ms 0.6→1 展开后 1.8s ease-in-out 呼吸 1↔1.1) +
                // core(brand 75%，200ms 延迟 140ms 展开) + 白色 mic glyph
                // (100ms 延迟 260ms 淡入)；原 outline glyph 120ms 淡出
                val haloEnter = remember { androidx.compose.animation.core.Animatable(0f) }
                val coreEnter = remember { androidx.compose.animation.core.Animatable(0f) }
                val glyphIn = remember { androidx.compose.animation.core.Animatable(0f) }
                val easeOut = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.58f, 1f)
                val breath = androidx.compose.animation.core.rememberInfiniteTransition(label = "haloBreath")
                val haloS = breath.animateFloat(1f, 1.1f,
                    androidx.compose.animation.core.infiniteRepeatable(
                        androidx.compose.animation.core.tween(1800,
                            easing = androidx.compose.animation.core.CubicBezierEasing(0.42f, 0f, 0.58f, 1f)),
                        androidx.compose.animation.core.RepeatMode.Reverse,
                        androidx.compose.animation.core.StartOffset(150)), label = "haloS")
                LaunchedEffect(Unit) {
                    haloEnter.animateTo(1f, androidx.compose.animation.core.tween(150, easing = easeOut))
                }
                LaunchedEffect(Unit) {
                    coreEnter.animateTo(1f,
                        androidx.compose.animation.core.tween(200, delayMillis = 140, easing = easeOut))
                }
                LaunchedEffect(Unit) {
                    glyphIn.animateTo(1f,
                        androidx.compose.animation.core.tween(100, delayMillis = 260, easing = easeOut))
                }
                Box(
                    Modifier.align(Alignment.CenterVertically).size(57.955.dp, 56.2.dp)
                        .clickable(voiceSrc, null) {
                            VoiceInput.cancel()
                            VoiceInput.enabled.value = !VoiceInput.enabled.value
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.fillMaxSize()
                        .graphicsLayer {
                            val s = (0.6f + 0.4f * haloEnter.value) * haloS.value
                            scaleX = s; scaleY = s; alpha = 0.2f * haloEnter.value
                        }
                        .clip(CircleShape).background(c.brandPrimary))
                    Box(Modifier.fillMaxSize().padding(2.dp)
                        .graphicsLayer {
                            val s = 0.6f + 0.4f * coreEnter.value
                            scaleX = s; scaleY = s; alpha = 0.75f * coreEnter.value
                        }
                        .clip(CircleShape).background(c.brandPrimary))
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_voice_circle), null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                                .graphicsLayer { alpha = glyphIn.value })
                    }
                    Box(Modifier.size(28.dp).align(Alignment.Center)
                        .semantics { contentDescription = Strings.get("voiceInput") }) {}
                }
            } else {
                Box(
                    Modifier.align(Alignment.CenterVertically).size(57.955.dp, 56.2.dp)
                        .clickable(voiceSrc, null) {
                            VoiceInput.cancel()
                            VoiceInput.enabled.value = !VoiceInput.enabled.value
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_voice_circle), Strings.get("voiceInput"),
                        tint = c.labelPrimary,
                        modifier = Modifier.size(28.dp)
                            .clip(CircleShape)
                            .background(DsTheme.pressTint(voicePressed, c.interactiveBgHover)))
                }
            }
            if (canSend || isStreaming) {
                Box(
                    Modifier.align(Alignment.CenterVertically)
                        .padding(start = 6.dp, end = 7.dp)
                        .size(30.dp).clip(CircleShape)
                        .background(c.buttonPrimaryFill)
                        .clickable {
                            if (isStreaming) chatStore.stopReply(activeId!!)
                            else { chatStore.setDraft(activeId, text); chatStore.sendMessage(activeId); text = "" }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(if (isStreaming) R.drawable.ic_stop else R.drawable.ic_send),
                        Strings.get(if (isStreaming) "stopGenerating" else "send"),
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        // ref composer 底部留白：卡片内衬在 chips 行下方
        Spacer(Modifier.height(5.dp))
    }
    // ref AndroidAttachments：卡片外、composer 列内展开的面板
    AttachmentsPanel(
        open = showAttachments,
        photos = photos,
        selectedPhotoIds = atts.mapNotNull { it.recentPhotoId }.toSet(),
        onPhoto = { photo ->
            val existing = atts.firstOrNull { it.recentPhotoId == photo.id }
            if (existing != null) {
                chatStore.removeDraftAttachment(activeId, existing.id)
            } else {
                inputScope.launch {
                    importToDraft(context, photo.uri, photo.id)?.let {
                        chatStore.addDraftAttachments(activeId, listOf(it))
                    }
                }
            }
        },
        onCamera = {
            val dir = java.io.File(context.cacheDir, "attaches").apply { mkdirs() }
            val f = java.io.File(dir, "camera-${System.currentTimeMillis()}.jpg")
            val u = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", f)
            cameraUri = u
            takePicture.launch(u)
        },
        onGallery = {
            showAttachments = false
            pickImages.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onFiles = {
            showAttachments = false
            openDocs.launch(arrayOf("*/*"))
        },
    )
    }
    previewAtt?.let { att ->
        ImagePreviewDialog(att, onClose = { previewAtt = null })
    }
}

@Composable
private fun TogglePill(label: String, on: Boolean, onClick: () -> Unit, icon: Int, hitStart: androidx.compose.ui.unit.Dp = 0.dp) {
    val c = DsTheme.colors
    // ref [data-android] .toggle:active -> transform: scale(.96), 100ms
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 0.96f else 1f,
        androidx.compose.animation.core.tween(100), label = "togglePress")
    Box(
        Modifier
            .size(if (hitStart > 0.dp) 111.2.dp else 97.4.dp, 46.5.dp)
            .toggleable(
                value = on,
                interactionSource = src,
                indication = null,
                role = androidx.compose.ui.semantics.Role.Checkbox,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.offset(x = hitStart).height(32.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(18.dp))
                .background(if (on) c.buttonGhostActiveFill else androidx.compose.ui.graphics.Color.Transparent)
                .border(1.dp, if (on) c.buttonGhostActiveBorder else c.borderL2, RoundedCornerShape(18.dp))
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), null,
                tint = if (on) c.brandText else c.labelPrimary,
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal,
                color = if (on) c.brandText else c.labelPrimary)
        }
    }
}

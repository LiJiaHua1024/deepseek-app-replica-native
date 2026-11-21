package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.data.ReplyBlock
import io.github.lijiahua1024.dsnative.data.Source
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings
import org.json.JSONArray
import org.json.JSONObject

/** 当前消息的搜索来源列表与打开来源面板的回调（面板挂在会话层） */
val LocalSources = compositionLocalOf<List<Source>> { emptyList() }
val LocalOpenSources = compositionLocalOf<(List<Source>) -> Unit> { {} }

private fun siteName(url: String): String = try {
    java.net.URL(url).host.removePrefix("www.")
} catch (_: Exception) { "" }

// org.json optString() can leak the literal "null" for explicit JSON null on
// some versions; NULL-check opt() directly.
private fun optStr(o: JSONObject?, k: String): String? =
    o?.opt(k)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() }

/** ref SourceRegistry：按出现顺序注册、按 url 去重 */
private class Registry {
    val list = ArrayList<Source>()
    private val byUrl = HashMap<String, Source>()

    fun add(url: String?, title: String?, publishedAt: String?, item: JSONObject?): Source {
        val u = url ?: ""
        val existing = byUrl[u]
        if (existing != null) {
            if (existing.siteName == siteName(u) && item != null) {
                val sn = optStr(item, "site_name")
                if (sn != null) {
                    val updated = existing.copy(siteName = sn)
                    list[list.indexOf(existing)] = updated
                    byUrl[u] = updated
                }
            }
            if (existing.siteIcon == null) {
                val icon = optStr(item, "site_icon")
                if (icon != null) {
                    val updated = existing.copy(siteIcon = icon)
                    list[list.indexOf(existing)] = updated
                    byUrl[u] = updated
                }
            }
            return byUrl[u]!!
        }
        val sn = optStr(item, "site_name") ?: siteName(u)
        val s = Source(
            index = list.size + 1,
            url = u,
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: title,
            siteName = sn,
            publishedAt = publishedAt?.takeIf { it.isNotEmpty() },
            snippet = optStr(item, "snippet"),
            siteIcon = optStr(item, "site_icon"),
        )
        list.add(s)
        byUrl[u] = s
        return s
    }
}

/** ref view.ts：把 server_tool_use + 结果块、文本引用注册成来源列表 */
fun searchSources(blocks: List<ReplyBlock>): List<Source> {
    val registry = Registry()
    val results = HashMap<String, JSONObject>()
    for (b in blocks) {
        if (b is ReplyBlock.Tool) {
            val p = JSONObject(b.payload.toString())
            if (p.optString("type") == "web_search_tool_result") {
                results[p.optString("tool_use_id")] = p
            }
        }
    }
    for (b in blocks) {
        when (b) {
            is ReplyBlock.Tool -> {
                val p = JSONObject(b.payload.toString())
                if (p.optString("type") != "server_tool_use") continue
                val input = p.optJSONObject("input") ?: continue
                if (input.optString("action") != "search") continue
                val result = results[p.optString("id")]
                val content = result?.optJSONArray("content") ?: continue
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    if (item.optString("type") != "web_search_result") continue
                    registry.add(optStr(item, "url") ?: "", optStr(item, "title"),
                        optStr(item, "page_age"), item)
                }
            }
            is ReplyBlock.Text -> for (cit in b.citations) {
                registry.add(cit.url, cit.title, null, null)
            }
            else -> {}
        }
    }
    return registry.list
}

/** 把文本块末尾补上 [citation:N] 标记（ref joinTextBlocks 的行为） */
fun withCitationMarkers(blocks: List<ReplyBlock>, sources: List<Source>): List<ReplyBlock> {
    if (sources.isEmpty()) return blocks
    val indexOf = HashMap<String, Int>()
    for (s in sources) indexOf[s.url] = s.index
    return blocks.map { b ->
        if (b is ReplyBlock.Text && b.citations.isNotEmpty()) {
            val marks = b.citations.mapNotNull { indexOf[it.url] }.distinct()
                .joinToString("") { "[citation:$it]" }
            if (marks.isEmpty()) b else b.copy(text = b.text.trimEnd() + marks)
        } else b
    }
}

/** 单个 server_tool_use 块对应一个搜索 step；返回(该次调用注册的来源, 是否已完成) */
fun toolStep(block: ReplyBlock.Tool, allBlocks: List<ReplyBlock>, registry: List<Source>): Pair<List<Source>, Boolean>? {
    val p = JSONObject(block.payload.toString())
    if (p.optString("type") != "server_tool_use") return null
    val input = p.optJSONObject("input") ?: return null
    if (input.optString("action") != "search") return null
    val byUrl = registry.associateBy { it.url }
    var done = false
    val found = ArrayList<Source>()
    for (b in allBlocks) {
        if (b !is ReplyBlock.Tool) continue
        val r = JSONObject(b.payload.toString())
        if (r.optString("type") != "web_search_tool_result" || r.optString("tool_use_id") != p.optString("id")) continue
        done = true
        val content = r.optJSONArray("content") ?: continue
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            if (item.optString("type") != "web_search_result") continue
            optStr(item, "url")?.let { byUrl[it]?.let(found::add) }
        }
    }
    return found to done
}

fun formatSourceDate(iso: String?): String {
    if (iso.isNullOrEmpty()) return ""
    return try {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val d = fmt.parse(iso.substringBefore('.').substringBefore('+'))
        java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US).format(d!!)
    } catch (_: Exception) { iso }
}

/** ref SiteLogo：favicon 加载失败/缺失时的链接占位图标 */
@Composable
fun SiteLogo(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(c.markdownCitation),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_link), null,
            tint = c.labelTertiary, modifier = Modifier.size(size * 0.7f))
    }
}

/** ref SiteThumbnails：前 4 个站点重叠缩略图 */
@Composable
private fun SiteThumbnails(sources: List<Source>) {
    Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        sources.take(4).forEachIndexed { i, _ ->
            val overlap = if (i == 0) 0.dp else (-4).dp
            SiteLogo(16.dp, Modifier
                .offset(x = overlap)
                .border(1.dp, DsTheme.colors.bgBase, CircleShape))
        }
    }
}

/** ref .summary：消息下方的来源汇总胶囊 */
@Composable
fun SourcesSummary(sources: List<Source>, onClick: () -> Unit) {
    val c = DsTheme.colors
    Row(
        Modifier
            .padding(top = 16.dp)
            .border(1.dp, androidx.compose.ui.graphics.Color(0xFFD4D4D4), RoundedCornerShape(100.dp))
            .clip(RoundedCornerShape(100.dp))
            .background(c.bgBase)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteThumbnails(sources)
        Spacer(Modifier.width(6.dp))
        Text(
            Strings.get(if (sources.size == 1) "messageSearchSummary" else "messageSearchSummaries")
                .replace("{count}", sources.size.toString()),
            fontSize = 15.sp,
            color = if (c.dark) androidx.compose.ui.graphics.Color(0xFFE5E5E5)
                    else androidx.compose.ui.graphics.Color(0xFF545557),
        )
    }
}

/** ref SearchRow：「已阅读 N 个网页」步骤行，点击打开来源面板 */
@Composable
fun SearchStepRow(stepSources: List<Source>, done: Boolean, onOpen: (List<Source>) -> Unit) {
    val c = DsTheme.colors
    val clickable = done && stepSources.isNotEmpty()
    Row(
        Modifier
            .height(34.dp)
            .then(if (clickable) Modifier.clickable { onOpen(stepSources) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_search), null, tint = c.labelSecondary,
            modifier = Modifier.padding(1.dp).size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (!done) Strings.get("messageSearching")
            else Strings.get(if (stepSources.size == 1) "messageSearchResult" else "messageSearchResults")
                .replace("{count}", stepSources.size.toString()),
            fontSize = 14.sp, lineHeight = 24.sp, color = c.labelSecondary,
        )
        if (clickable) SiteThumbnails(stepSources)
    }
}

/** ref AndroidSearchPanel：底部来源面板（scrim + 圆角 sheet） */
@Composable
fun SearchSourcesPanel(sources: List<Source>, numbered: Boolean, onClose: () -> Unit) {
    val c = DsTheme.colors
    // ref AndroidSearchPanel.module.css: scrim opacity 0->0.32 与 sheet
    // translateY(100%)->0 共用 240ms cubic-bezier(0.2,0,0,1)；关闭同样 240ms
    val panelEase = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val prog = remember { androidx.compose.animation.core.Animatable(0f) }
    val panelScope = androidx.compose.runtime.rememberCoroutineScope()
    var closing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var sheetH by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        prog.animateTo(1f, androidx.compose.animation.core.tween(240, easing = panelEase))
    }
    val requestClose = {
        if (!closing) {
            closing = true
            panelScope.launch {
                prog.animateTo(0f, androidx.compose.animation.core.tween(240, easing = panelEase))
                onClose()
            }
        }
        Unit
    }
    // Popup in its own window: above caveat text, a11y-visible, back/outside dismiss
    val popupPositionProvider = androidx.compose.runtime.remember {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ) = androidx.compose.ui.unit.IntOffset(-anchorBounds.left, -anchorBounds.top)
        }
    }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = popupPositionProvider,
        properties = androidx.compose.ui.window.PopupProperties(
            focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
        onDismissRequest = requestClose,
    ) {
    // ref 面板底部对齐到 safe.bottom（nav inset + button-nav 余量≈75px@440dpi）
    BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 6.5.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color(0x52000000).copy(alpha = 0x52 / 255f * prog.value))
                .clickable(interactionSource = rememberMutableInteraction(),
                    indication = null) { requestClose() }
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = this.maxHeight - 44.dp)
                .onGloballyPositioned { sheetH = it.size.height }
                .offset { androidx.compose.ui.unit.IntOffset(0, ((1f - prog.value) * sheetH).toInt()) }
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(if (c.dark) androidx.compose.ui.graphics.Color(0xFF292929) else c.bgBase)
        ) {
            // ref .header: 56px, 16.75px/600 居中标题 + 右侧 30px 圆形关闭
            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                Text(Strings.get("searchResult"), fontSize = 16.75.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold, color = c.labelPrimary)
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(c.interactiveBgHover)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    // ref 内联 svg：两条 1.8px 描边对角线（20px viewBox）
                    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                        val stroke = 1.8.dp.toPx()
                        val s = size.width
                        drawLine(c.labelPrimary, androidx.compose.ui.geometry.Offset(5.2f/20f*s, 5.2f/20f*s),
                            androidx.compose.ui.geometry.Offset(14.8f/20f*s, 14.8f/20f*s), strokeWidth = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(c.labelPrimary, androidx.compose.ui.geometry.Offset(14.8f/20f*s, 5.2f/20f*s),
                            androidx.compose.ui.geometry.Offset(5.2f/20f*s, 14.8f/20f*s), strokeWidth = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp)) {
                // measured: first card ink ~6px early vs ref at spec 16px pad
                item { Spacer(Modifier.height(15.3.dp)) }
                itemsIndexed(sources) { i, source ->
                    // ref spec 31px; measured pitch is ~7.5px (2.7dp) short per card
                    if (i > 0) Spacer(Modifier.height(33.7.dp))
                    SourceItem(source, numbered = numbered,
                        single = sources.size == 1)
                }
                // ref spec is 32px; trimmed 5dp to match measured sheet height
                item { Spacer(Modifier.height(27.dp)) }
            }
        }
    }
    }
}

@Composable
private fun rememberMutableInteraction(): androidx.compose.foundation.interaction.MutableInteractionSource =
    androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

/** ref .result：meta(logo+站名+日期+序号) + 标题 + 摘要 */
@Composable
private fun SourceItem(source: Source, numbered: Boolean, single: Boolean) {
    val c = DsTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // ref AndroidSearchPanel .item:active -> opacity .65
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Column(Modifier.fillMaxWidth().alpha(DsTheme.pressAlpha(pressed, 0.65f)).clickable(src, null) {
        try {
            io.github.lijiahua1024.dsnative.SourceBrowserActivity.open(ctx, source.url, c.dark)
        } catch (_: Exception) {}
    }) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            SiteLogo(20.dp)
            Spacer(Modifier.width(10.36.dp))
            // ref .meta: siteName/date flex-shrink, .number { margin-left: auto }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(source.siteName, fontSize = 14.sp, lineHeight = 20.sp,
                    letterSpacing = 0.10.sp,
                    color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                val date = formatSourceDate(source.publishedAt)
                if (date.isNotEmpty()) {
                    Spacer(Modifier.width(10.36.dp))
                    Text(date, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.10.sp, color = c.searchResultDate,
                        maxLines = 1)
                }
            }
            Spacer(Modifier.width(10.36.dp))
            if (numbered) {
                Box(
                    Modifier
                        .size(20.dp)
                        .border(1.dp, c.borderL2, CircleShape)
                        .background(c.interactiveBgHover, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(source.index.toString(), fontSize = 12.sp, lineHeight = 18.sp,
                        color = c.searchResultNumber, fontFamily = FontFamily.Monospace)
                }
            }
        }
        source.title?.let {
            Text(it, fontSize = 17.sp, lineHeight = 26.sp, letterSpacing = 0.10.sp, color = c.labelPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp))
        }
        source.snippet?.let {
            Text(it, fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.10.sp, color = c.searchResultSnippet,
                maxLines = if (single) 4 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp))
        }
    }
}

package io.github.lijiahua1024.dsnative.ui.session

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.lijiahua1024.dsnative.theme.DsColors
import io.github.lijiahua1024.dsnative.theme.Strings
import org.json.JSONObject

// ref CodeBlock.tsx + MermaidDiagram.tsx: a `mermaid` fenced block defaults to
// the rendered diagram with a 图表/代码 switch; while the reply is streaming a
// failed render keeps the last good diagram (prefix retry), only a final
// failure shows mermaidRenderFail. The diagram runs the same mermaid 11.10.1
// bundle as the webview reference inside an embedded WebView.
@Composable
fun MermaidCodeBlock(
    literal: String,
    streaming: Boolean,
    c: DsColors,
    highlight: @Composable (String) -> Unit,
) {
    var showDiagram by remember { mutableStateOf(true) }
    Column(
        Modifier.fillMaxWidth()
            .background(c.markdownCodeBlock, RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ref .md-code-block-switch: gap 4px; pressed = code-block bg + labelPrimary
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                listOf(true to "mermaidDiagram", false to "mermaidCode").forEach { (diagram, key) ->
                    val active = showDiagram == diagram
                    Text(
                        Strings.get(key),
                        fontSize = 13.sp, lineHeight = 20.sp,
                        color = if (active) c.labelPrimary else c.labelSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .then(if (active) Modifier.background(c.markdownCodeBlock) else Modifier)
                            .clickable { showDiagram = diagram }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            val ctx = LocalContext.current
            Row(
                Modifier.height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("mermaid", literal))
                        DsToast.show(Strings.get("copied"))
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(
                        io.github.lijiahua1024.dsnative.R.drawable.ic_copy),
                    null, tint = c.labelSecondary,
                    modifier = Modifier.size(14.dp))
                Text(Strings.get("copy"),
                    fontSize = 13.sp, lineHeight = 20.sp, color = c.labelSecondary,
                    modifier = Modifier.padding(start = 3.dp))
            }
        }
        if (showDiagram) {
            MermaidDiagramView(literal, streaming, c)
        } else {
            Box(Modifier.fillMaxWidth().padding(16.dp)) { highlight(literal.trimEnd('\n')) }
        }
    }
}

private class MermaidWebState {
    var view: WebView? = null
    var pageReady = false
    var renderedKey: String? = null
    var renderSeq = 0
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MermaidDiagramView(code: String, streaming: Boolean, c: DsColors) {
    var status by remember { mutableStateOf("loading") }
    // document height in WebView CSS px; viewport width=device-width makes
    // CSS px == dp on this device (verified via webview_devtools).
    var heightPx by remember { mutableIntStateOf(0) }
    val web = remember { MermaidWebState() }

    fun requestRender(view: WebView) {
        val key = "${c.dark} ${code}"
        if (!web.pageReady || web.renderedKey == key) return
        web.renderedKey = key
        view.evaluateJavascript("render(${JSONObject.quote(code)}, ${c.dark})", null)
    }

    when {
        code.isBlank() -> {
            MermaidStatus(Strings.get("mermaidEmpty"), c)
            return
        }
        status == "error" && !streaming -> {
            // ref MermaidDiagram: 渲染失败 only once the reply has finished
            MermaidStatus(Strings.get("mermaidRenderFail"), c)
            return
        }
    }

    Box(Modifier.fillMaxWidth()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    if (io.github.lijiahua1024.dsnative.BuildConfig.DEBUG) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onDone(px: Int) {
                            post {
                                heightPx = px
                                status = "done"
                            }
                        }

                        @JavascriptInterface
                        fun onError(msg: String?) {
                            // keep the last good diagram if one already rendered
                            post { if (heightPx <= 0) status = "error" }
                        }
                    }, "Android")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            web.pageReady = true
                            requestRender(view)
                        }
                    }
                    loadUrl("file:///android_asset/mermaid/render.html")
                    web.view = this
                }
            },
            // while nothing is measured the view keeps a real viewport
            // (ref .md-mermaid-status min-height 160px) so mermaid's label
            // measurement has layout; afterwards it snaps to content height.
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (heightPx > 0) Modifier.height(heightPx.dp)
                    else Modifier.height(160.dp),
                ),
            update = { view -> requestRender(view) },
        )
        if (status != "done" || heightPx <= 0) {
            // overlay matches the block bg so the WebView frame is invisible
            Box(
                Modifier.fillMaxWidth().height(160.dp)
                    .background(c.markdownCodeBlock),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    Strings.get("mermaidLoading"),
                    fontSize = 14.sp, color = c.labelSecondary,
                )
            }
        }
    }
}

@Composable
private fun MermaidStatus(label: String, c: DsColors) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, color = c.labelSecondary)
    }
}

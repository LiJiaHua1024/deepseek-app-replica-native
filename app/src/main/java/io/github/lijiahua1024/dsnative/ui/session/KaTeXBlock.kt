package io.github.lijiahua1024.dsnative.ui.session

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.lijiahua1024.dsnative.theme.DsColors
import io.github.lijiahua1024.dsnative.ui.excludeFromDrawerGesture
import org.json.JSONObject

// ref MathFormula.tsx: display math runs the same KaTeX 0.16.22 bundle as the
// webview reference inside an embedded WebView (throwOnError:false + dialect
// sanitizer, strict:false, displayMode). On error it falls back to the native
// renderer; wide scrollable formulas exclude horizontal drawer gesture.
// .ds-markdown-math margins (16px 0) are applied as Compose vertical padding.
@Composable
fun KaTeXMath(
    tex: String,
    streaming: Boolean,
    c: DsColors,
    fallback: @Composable (String) -> Unit,
) {
    var status by remember { mutableStateOf("loading") }
    // document height in WebView CSS px == dp (verified via webview_devtools)
    var heightPx by remember { mutableIntStateOf(0) }
    var scrollable by remember { mutableStateOf(false) }
    val cleanedTex = remember(tex) { sanitizeLatexDialect(tex) }
    val web = remember { KaTeXWebState() }

    fun requestRender(view: WebView) {
        val key = "${c.dark} $cleanedTex"
        if (!web.pageReady || web.renderedKey == key) return
        web.renderedKey = key
        view.evaluateJavascript("render(${JSONObject.quote(cleanedTex)}, ${c.dark})", null)
    }

    if (status == "error" && heightPx <= 0 && !streaming) {
        fallback(cleanedTex)
        return
    }

    // ref .ds-markdown > * .ds-markdown-math { margin: 16px 0 }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .then(if (scrollable) Modifier.excludeFromDrawerGesture() else Modifier),
    ) {
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
                        fun onDone(px: Int, isScrollable: Boolean) {
                            post {
                                heightPx = px
                                scrollable = isScrollable
                                status = "done"
                            }
                        }

                        @JavascriptInterface
                        fun onError(msg: String?) {
                            android.util.Log.w("KaTeX", "render error: $msg")
                            post { if (heightPx <= 0) status = "error" }
                        }
                    }, "Android")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            web.pageReady = true
                            requestRender(view)
                        }
                    }
                    loadUrl("file:///android_asset/katex/render.html")
                    web.view = this
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(if (heightPx > 0) heightPx.dp else 40.dp),
            update = { view -> requestRender(view) },
        )
    }
}

private class KaTeXWebState {
    var view: WebView? = null
    var pageReady = false
    var renderedKey: String? = null
}

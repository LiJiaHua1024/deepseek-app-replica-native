package io.github.lijiahua1024.dsnative

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.lijiahua1024.dsnative.theme.Strings

// Порт SourceBrowserActivity из webview-референса: отдельный WebView
// держит внешние страницы вне основного JS-контекста приложения.
class SourceBrowserActivity : Activity() {
    private var page: WebView? = null
    private var root: LinearLayout? = null
    private var content: FrameLayout? = null
    private var loading: LinearLayout? = null
    private var failure: LinearLayout? = null
    private var failureMessage: TextView? = null
    private var initial: Uri? = null
    private var failedUrl: Uri? = null
    private var failed = false

    companion object {
        fun webUri(value: String?): Uri? {
            if (value == null) return null
            val uri = Uri.parse(value)
            val scheme = uri.scheme
            val host = uri.host
            if (host.isNullOrEmpty() || scheme == null ||
                !("https".equals(scheme, true) || "http".equals(scheme, true))
            ) {
                return null
            }
            return uri
        }

        fun open(context: Context, url: String, dark: Boolean, browserLabel: String? = null) {
            val uri = webUri(url) ?: return
            val feedbackBuilder = feedbackFormUri().buildUpon()
                .appendQueryParameter("hide_uid", "1")
            feedbackHiddenFields.forEach { key ->
                feedbackBuilder.appendQueryParameter("hide_$key", "1")
            }
            val target = if (uri.host == "static.deepseek.com" &&
                uri.path == "/faq/index.html") feedbackBuilder.build() else uri
            context.startActivity(
                Intent(context, SourceBrowserActivity::class.java)
                .setData(target)
                    .putExtra("dark", dark)
                    .putExtra("closeLabel", Strings.get("close"))
                    .putExtra("browserLabel", browserLabel ?: Strings.get("openInBrowser")),
            )
        }

        private fun feedbackFormUri(): Uri = Uri.parse(
            "https://trtgsjkv6r.feishu.cn/share/base/form/shrcnhcHE4A6lQaQ3v0raCXmBAg"
        )

        private val feedbackHiddenFields = listOf(
            "source", "app_version", "os_version", "device_brand", "device_model",
            "app_locale", "screen_resolution",
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        initial = webUri(intent.dataString)
        if (initial == null) {
            finish()
            return
        }
        val dark = intent.getBooleanExtra("dark", false)
        val background = Color.parseColor(if (dark) "#0F0F0F" else "#FFFFFF")
        val foreground = Color.parseColor(if (dark) "#F8F8F8" else "#111111")

        val rootLayout = LinearLayout(this)
        rootLayout.orientation = LinearLayout.VERTICAL
        rootLayout.setBackgroundColor(background)
        root = rootLayout
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                    or WindowInsetsCompat.Type.displayCutout()
                    or WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val toolbar = FrameLayout(this)
        toolbar.setBackgroundColor(background)
        rootLayout.addView(toolbar, LinearLayout.LayoutParams(-1, dp(52f)))

        val title = TextView(this)
        title.setTextColor(foreground)
        title.textSize = 16f
        title.gravity = Gravity.CENTER
        title.setSingleLine(true)
        title.ellipsize = TextUtils.TruncateAt.END
        val titleLayout = FrameLayout.LayoutParams(-1, -1)
        titleLayout.setMargins(dp(56f), 0, dp(56f), 0)
        toolbar.addView(title, titleLayout)

        val close = ToolbarIcon(this, false, foreground)
        close.contentDescription = intent.getStringExtra("closeLabel")
        close.setOnClickListener { finish() }
        toolbar.addView(close, FrameLayout.LayoutParams(dp(48f), -1, Gravity.START))

        val browserLabel = intent.getStringExtra("browserLabel")
        if (!browserLabel.isNullOrEmpty()) {
            val external = ToolbarIcon(this, true, foreground)
            external.contentDescription = browserLabel
            external.setOnClickListener {
                val current = webUri(page?.url) ?: return@setOnClickListener
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, current))
                } catch (_: ActivityNotFoundException) {
                    // Устройство без браузера.
                }
            }
            toolbar.addView(external, FrameLayout.LayoutParams(dp(48f), -1, Gravity.END))
        }

        val divider = View(this)
        divider.setBackgroundColor(Color.parseColor(if (dark) "#373737" else "#E5E5E5"))
        rootLayout.addView(divider, LinearLayout.LayoutParams(-1, dp(1f)))

        val contentLayout = FrameLayout(this)
        content = contentLayout

        val web = WebView(this)
        web.setBackgroundColor(Color.WHITE)
        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.textZoom = 100
        web.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, pageTitle: String) {
                title.text = pageTitle
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                showLoading()
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (!failed) hideLoading()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!failed) hideLoading()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) showFailure(request.url)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                response: WebResourceResponse,
            ) {
                if (request.isForMainFrame && response.statusCode >= 400) showFailure(request.url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return webUri(request.url.toString()) == null
            }
        }
        page = web
        contentLayout.addView(web, FrameLayout.LayoutParams(-1, -1))

        val loadingLayout = LinearLayout(this)
        loadingLayout.orientation = LinearLayout.VERTICAL
        loadingLayout.gravity = Gravity.CENTER
        loadingLayout.setBackgroundColor(background)
        val spinner = ProgressBar(this)
        loadingLayout.addView(spinner, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        val loadingLabel = TextView(this)
        loadingLabel.text = Strings.get("sourcePageLoading")
        loadingLabel.setTextColor(Color.parseColor(if (dark) "#B8B8B8" else "#666666"))
        loadingLabel.textSize = 14f
        loadingLabel.gravity = Gravity.CENTER
        val loadingLabelLayout = LinearLayout.LayoutParams(-2, -2)
        loadingLabelLayout.topMargin = dp(12f)
        loadingLayout.addView(loadingLabel, loadingLabelLayout)
        contentLayout.addView(loadingLayout, FrameLayout.LayoutParams(-1, -1))
        loading = loadingLayout
        web.postDelayed({ hideLoading() }, 3000)

        val failureLayout = LinearLayout(this)
        failureLayout.orientation = LinearLayout.VERTICAL
        failureLayout.gravity = Gravity.CENTER
        failureLayout.setBackgroundColor(background)
        failureLayout.setPadding(dp(32f), dp(32f), dp(32f), dp(32f))
        failureLayout.visibility = View.GONE
        val message = TextView(this)
        message.text = Strings.get("sourcePageLoadFailed")
        message.setTextColor(foreground)
        message.textSize = 15f
        message.gravity = Gravity.CENTER
        failureLayout.addView(message, LinearLayout.LayoutParams(-1, -2))
        failureMessage = message
        val retry = Button(this)
        retry.text = Strings.get("retry")
        retry.setOnClickListener {
            val target = failedUrl ?: initial ?: return@setOnClickListener
            showLoading()
            page?.loadUrl(target.toString())
        }
        val retryLayout = LinearLayout.LayoutParams(-2, -2)
        retryLayout.topMargin = dp(20f)
        failureLayout.addView(retry, retryLayout)
        contentLayout.addView(failureLayout, FrameLayout.LayoutParams(-1, -1))
        failure = failureLayout

        rootLayout.addView(contentLayout, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(rootLayout)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }

        val history = if (state == null) null else web.restoreState(state)
        if (history == null) {
            showLoading()
            web.loadUrl(initial.toString())
        } else {
            title.text = history.currentItem?.title
            hideLoading()
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        page?.saveState(state)
        super.onSaveInstanceState(state)
    }

    private fun goBack() {
        val web = page
        if (web != null && web.canGoBack()) web.goBack() else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goBack()
    }

    override fun onDestroy() {
        page?.let { web ->
            root?.removeView(web)
            web.destroy()
            page = null
        }
        super.onDestroy()
    }

    private fun dp(value: Float): Int =
        Math.round(value * resources.displayMetrics.density)

    private fun showLoading() {
        failed = false
        failureMessage?.text = null
        failure?.visibility = View.GONE
        loading?.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loading?.visibility = View.GONE
    }

    private fun showFailure(url: Uri?) {
        failed = true
        failedUrl = url ?: initial
        loading?.visibility = View.GONE
        failureMessage?.text = Strings.get("sourcePageLoadFailed")
        failure?.visibility = View.VISIBLE
    }

    private class ToolbarIcon(context: Context, private val external: Boolean, color: Int) :
        View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            canvas.save()
            canvas.translate(width / 2f + (if (external) 2 else -2) * density, height / 2f)
            canvas.scale(density, density)
            if (external) {
                val outline = Path()
                outline.moveTo(-2f, -7f)
                outline.lineTo(-5f, -7f)
                outline.quadTo(-7f, -7f, -7f, -5f)
                outline.lineTo(-7f, 5f)
                outline.quadTo(-7f, 7f, -5f, 7f)
                outline.lineTo(5f, 7f)
                outline.quadTo(7f, 7f, 7f, 5f)
                outline.lineTo(7f, 2f)
                canvas.drawPath(outline, paint)
                canvas.drawLine(0f, 0f, 8f, -8f, paint)
                canvas.drawLine(2f, -8f, 8f, -8f, paint)
                canvas.drawLine(8f, -8f, 8f, -2f, paint)
            } else {
                canvas.drawLine(-6f, -6f, 6f, 6f, paint)
                canvas.drawLine(6f, -6f, -6f, 6f, paint)
            }
            canvas.restore()
        }
    }
}

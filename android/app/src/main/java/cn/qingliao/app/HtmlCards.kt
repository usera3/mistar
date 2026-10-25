package cn.qingliao.app

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.*
import java.io.ByteArrayInputStream

object HtmlCards {
    const val MAX_LENGTH = 50000
    private fun document(source: String) = """<!doctype html>
        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'none'; connect-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src data:; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
        <style>html{background:#fff;color:#252334}body{font-family:sans-serif;margin:0;padding:14px;overflow-wrap:anywhere}*{box-sizing:border-box}img{max-width:100%;height:auto}pre{white-space:pre-wrap;word-break:break-word}iframe,object,embed,img:not([src^="data:"]){display:none!important}</style>
        </head><body>$source</body></html>""".trimIndent()

    fun create(context: Context, source: String, ready: () -> Unit = {}, unavailable: () -> Unit = {}): WebView {
        return WebView(context).apply {
            setBackgroundColor(Color.WHITE)
            settings.apply {
                javaScriptEnabled = false
                javaScriptCanOpenWindowsAutomatically = false
                domStorageEnabled = false
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                blockNetworkLoads = true
                blockNetworkImage = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                setSupportMultipleWindows(false)
                safeBrowsingEnabled = true
            }
            webViewClient = object : WebViewClient() {
                override fun onPageCommitVisible(view: WebView,url: String?) { ready() }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    return if (request.url.scheme in listOf("about", "data")) null
                    else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy(); unavailable(); return true
                }
            }
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            loadDataWithBaseURL(null, document(source), "text/html", "UTF-8", null)
        }
    }
    fun dispose(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading(); view.loadUrl("about:blank"); view.removeAllViews(); view.destroy()
    }
}

package xyz.nulldev.androidcompat.webkit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.http.SslCertificate
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.print.PrintDocumentAdapter
import android.util.SparseArray
import android.view.DragEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.autofill.AutofillValue
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.WebBackForwardList
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewProvider
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import android.content.Intent
import android.content.res.Configuration
import io.github.oshai.kotlinlogging.KotlinLogging
import org.graalvm.polyglot.Context as GraalContext
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyExecutable
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * Headless WebViewProvider implementation for server-side extensions.
 *
 * Layer 2: Handles evaluateJavascript via:
 *   - Fast path: regex match for localStorage.getItem/setItem → LocalStorageManager
 *   - Slow path: GraalVM Polyglot JS context with localStorage + JS interface bindings
 *
 * Layer 3 delegation: When loadUrl() is called with a real HTTP URL,
 * delegates to PlaywrightWebViewFallback for full browser rendering.
 */
class HeadlessWebViewProvider(private val webView: WebView) : WebViewProvider {
    private val logger = KotlinLogging.logger {}
    private val settings = KcefWebSettings()
    private var webViewClient: WebViewClient? = null
    private var webChromeClient: WebChromeClient? = null
    private var currentOrigin: String = ""
    private var currentUrl: String = ""
    private val jsInterfaces = mutableMapOf<String, Any>()
    private var playwrightFallback: Any? = null  // Lazy — only created if needed

    // Regex patterns for fast-path localStorage interception
    // Keys use non-greedy match within quotes; values match everything between outer quotes
    companion object {
        private val GET_ITEM_PATTERN = Regex("""(?:window\.)?localStorage\.getItem\(\s*['"]([^'"]+)['"]\s*\)""")
        private val SET_ITEM_PATTERN = Regex("""(?:window\.)?localStorage\.setItem\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]*)['"]\s*\)""")
        private val REMOVE_ITEM_PATTERN = Regex("""(?:window\.)?localStorage\.removeItem\(\s*['"]([^'"]+)['"]\s*\)""")
    }

    override fun init(javaScriptInterfaces: Map<String, Any>?, privateBrowsing: Boolean) {
        javaScriptInterfaces?.let { jsInterfaces.putAll(it) }
    }

    override fun getSettings(): WebSettings = settings

    override fun setWebViewClient(client: WebViewClient) {
        webViewClient = client
    }

    override fun getWebViewClient(): WebViewClient? = webViewClient

    override fun setWebChromeClient(client: WebChromeClient) {
        webChromeClient = client
    }

    override fun getWebChromeClient(): WebChromeClient? = webChromeClient

    override fun addJavascriptInterface(obj: Any, interfaceName: String) {
        jsInterfaces[interfaceName] = obj
    }

    override fun removeJavascriptInterface(interfaceName: String) {
        jsInterfaces.remove(interfaceName)
    }

    override fun loadDataWithBaseURL(baseUrl: String?, data: String?, mimeType: String?, encoding: String?, historyUrl: String?) {
        currentOrigin = if (!baseUrl.isNullOrEmpty()) LocalStorageManager.extractOrigin(baseUrl) else ""
        currentUrl = baseUrl ?: ""
        logger.debug { "loadDataWithBaseURL: origin=$currentOrigin" }

        // Fire onPageFinished synchronously on the calling thread.
        // Server-side extensions call evaluateJavascript inside this callback,
        // so synchronous dispatch ensures the calling thread can proceed sequentially.
        webViewClient?.onPageFinished(webView, currentUrl)
    }

    override fun loadUrl(url: String) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            currentOrigin = LocalStorageManager.extractOrigin(url)
            currentUrl = url
            logger.info { "loadUrl: real HTTP URL detected, delegating to Playwright fallback: $url" }
            delegateToPlaywright(url)
        } else {
            loadDataWithBaseURL(url, null, null, null, null)
        }
    }

    override fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>?) {
        loadUrl(url)
    }

    override fun evaluateJavaScript(script: String, resultCallback: ValueCallback<String>?) {
        logger.debug { "evaluateJavascript: ${script.take(100)}..." }

        // If Playwright fallback is active, delegate JS execution to it
        playwrightFallback?.let { fallback ->
            try {
                val method = fallback.javaClass.getMethod("evaluateJavascript", String::class.java)
                val result = method.invoke(fallback, script) as? String
                resultCallback?.onReceiveValue(result ?: "null")
                return
            } catch (e: Exception) {
                logger.error(e) { "Playwright evaluateJavascript failed, falling back to local" }
            }
        }

        // Fast path: localStorage.getItem
        GET_ITEM_PATTERN.find(script)?.let { match ->
            val key = match.groupValues[1]
            val value = LocalStorageManager.getItem(currentOrigin, key)
            val jsonResult = if (value != null) jsonEscapeString(value) else "null"
            resultCallback?.onReceiveValue(jsonResult)
            return
        }

        // Fast path: localStorage.setItem
        SET_ITEM_PATTERN.find(script)?.let { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            LocalStorageManager.setItem(currentOrigin, key, value)
            resultCallback?.onReceiveValue("null")
            return
        }

        // Fast path: localStorage.removeItem
        REMOVE_ITEM_PATTERN.find(script)?.let { match ->
            val key = match.groupValues[1]
            LocalStorageManager.removeItem(currentOrigin, key)
            resultCallback?.onReceiveValue("null")
            return
        }

        // Slow path: GraalVM Polyglot JS execution
        try {
            val result = executeWithGraalVM(script)
            resultCallback?.onReceiveValue(result)
        } catch (e: Exception) {
            logger.error(e) { "GraalVM JS execution failed for script: ${script.take(200)}" }
            resultCallback?.onReceiveValue("null")
        }
    }

    private fun executeWithGraalVM(script: String): String {
        GraalContext.newBuilder("js")
            .allowHostAccess(HostAccess.ALL)
            .option("engine.WarnInterpreterOnly", "false")
            .build().use { context ->
                val bindings = context.getBindings("js")

                // Bind localStorage helper functions as ProxyExecutable so they're callable from JS
                val origin = currentOrigin
                bindings.putMember("__localStorage_getItem", ProxyExecutable { args ->
                    val key = args[0].asString()
                    LocalStorageManager.getItem(origin, key)
                })
                bindings.putMember("__localStorage_setItem", ProxyExecutable { args ->
                    val key = args[0].asString()
                    val value = args[1].asString()
                    LocalStorageManager.setItem(origin, key, value)
                    null
                })
                bindings.putMember("__localStorage_removeItem", ProxyExecutable { args ->
                    val key = args[0].asString()
                    LocalStorageManager.removeItem(origin, key)
                    null
                })
                bindings.putMember("__localStorage_clear", ProxyExecutable {
                    LocalStorageManager.clear(origin)
                    null
                })

                // Create window.localStorage that delegates to the bound functions
                context.eval("js", """
                    var window = this;
                    window.localStorage = {
                        getItem: function(key) { return __localStorage_getItem(key); },
                        setItem: function(key, value) { __localStorage_setItem(key, '' + value); },
                        removeItem: function(key) { __localStorage_removeItem(key); },
                        clear: function() { __localStorage_clear(); }
                    };
                """.trimIndent())

                // Bind addJavascriptInterface objects
                for ((name, obj) in jsInterfaces) {
                    bindJsInterface(context, bindings, name, obj)
                }

                val result = context.eval("js", script)
                return graalValueToJson(result)
            }
    }

    private fun bindJsInterface(context: GraalContext, bindings: Value, name: String, obj: Any) {
        // Expose the Java object directly — GraalVM handles @JavascriptInterface method access
        bindings.putMember(name, obj)
        // Also make it available on window
        context.eval("js", "window.$name = $name;")
    }

    private fun jsonEscapeString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun graalValueToJson(value: Value): String {
        return when {
            value.isNull -> "null"
            value.isString -> jsonEscapeString(value.asString())
            value.isNumber -> value.toString()
            value.isBoolean -> value.asBoolean().toString()
            value.canExecute() -> "null"
            else -> value.toString().let { s ->
                if (s == "undefined") "null" else jsonEscapeString(s)
            }
        }
    }

    private fun delegateToPlaywright(url: String) {
        try {
            // Dynamically load PlaywrightWebViewFallback to avoid hard dependency on playwright
            val fallbackClass = Class.forName("moe.radar.mihon_gateway.browser.PlaywrightWebViewFallback")
            val fallback = fallbackClass
                .getConstructor(WebView::class.java, Map::class.java, WebViewClient::class.java, String::class.java)
                .newInstance(webView, jsInterfaces, webViewClient, url)
            playwrightFallback = fallback

            // Invoke execute method
            fallbackClass.getMethod("execute").invoke(fallback)
        } catch (e: ClassNotFoundException) {
            logger.warn { "Playwright not available — cannot load URL: $url" }
            webViewClient?.onReceivedError(webView, WebViewClient.ERROR_UNSUPPORTED_SCHEME,
                "Playwright not available for full browser rendering", url)
        } catch (e: Exception) {
            logger.error(e) { "Playwright fallback failed for URL: $url" }
            webViewClient?.onReceivedError(webView, WebViewClient.ERROR_UNKNOWN,
                "Playwright error: ${e.message}", url)
        }
    }

    // ==================== Remaining WebViewProvider no-ops ====================

    override fun destroy() {
        jsInterfaces.clear()
        playwrightFallback?.let {
            try {
                it.javaClass.getMethod("destroy").invoke(it)
            } catch (_: Exception) {}
        }
        playwrightFallback = null
    }

    override fun stopLoading() {}
    override fun reload() {}
    override fun canGoBack() = false
    override fun goBack() {}
    override fun canGoForward() = false
    override fun goForward() {}
    override fun canGoBackOrForward(steps: Int) = false
    override fun goBackOrForward(steps: Int) {}
    override fun isPrivateBrowsingEnabled() = false
    override fun pageUp(top: Boolean) = false
    override fun pageDown(bottom: Boolean) = false
    override fun insertVisualStateCallback(requestId: Long, callback: WebView.VisualStateCallback) {}
    override fun clearView() {}
    override fun capturePicture(): Picture? = null
    override fun createPrintDocumentAdapter(documentName: String): PrintDocumentAdapter? = null
    override fun getScale() = 1.0f
    override fun setInitialScale(scaleInPercent: Int) {}
    override fun invokeZoomPicker() {}
    override fun getHitTestResult(): WebView.HitTestResult = WebView.HitTestResult()
    override fun requestFocusNodeHref(hrefMsg: Message?) {}
    override fun requestImageRef(msg: Message?) {}
    override fun getUrl(): String = currentUrl
    override fun getOriginalUrl(): String = currentUrl
    override fun getTitle(): String = ""
    override fun getFavicon(): Bitmap? = null
    override fun getTouchIconUrl(): String? = null
    override fun getProgress() = 100
    override fun getContentHeight() = 0
    override fun getContentWidth() = 0
    override fun pauseTimers() {}
    override fun resumeTimers() {}
    override fun onPause() {}
    override fun onResume() {}
    override fun isPaused() = false
    override fun freeMemory() {}
    override fun clearCache(includeDiskFiles: Boolean) {}
    override fun clearFormData() {}
    override fun clearHistory() {}
    override fun clearSslPreferences() {}
    override fun copyBackForwardList(): WebBackForwardList? = null
    override fun setFindListener(listener: WebView.FindListener?) {}
    override fun findNext(forward: Boolean) {}
    override fun findAll(find: String) = 0
    override fun findAllAsync(find: String) {}
    override fun showFindDialog(text: String?, showIme: Boolean) = false
    override fun clearMatches() {}
    override fun documentHasImages(response: Message?) {}
    override fun getWebViewRenderProcess(): WebViewRenderProcess? = null
    override fun setWebViewRenderProcessClient(executor: Executor?, client: WebViewRenderProcessClient?) {}
    override fun getWebViewRenderProcessClient(): WebViewRenderProcessClient? = null
    override fun setDownloadListener(listener: DownloadListener?) {}
    override fun setPictureListener(listener: WebView.PictureListener?) {}
    override fun createWebMessageChannel(): Array<WebMessagePort>? = null
    override fun postMessageToMainFrame(message: WebMessage?, targetOrigin: Uri?) {}
    override fun setMapTrackballToArrowKeys(setMap: Boolean) {}
    override fun flingScroll(vx: Int, vy: Int) {}
    override fun getZoomControls(): View? = null
    override fun canZoomIn() = false
    override fun canZoomOut() = false
    override fun zoomBy(zoomFactor: Float) = false
    override fun zoomIn() = false
    override fun zoomOut() = false
    override fun dumpViewHierarchyWithProperties(out: BufferedWriter?, level: Int) {}
    override fun findHierarchyView(className: String?, hashCode: Int): View? = null
    override fun setRendererPriorityPolicy(rendererRequestedPriority: Int, waivedWhenNotVisible: Boolean) {}
    override fun getRendererRequestedPriority() = 0
    override fun getRendererPriorityWaivedWhenNotVisible() = false
    override fun setHorizontalScrollbarOverlay(overlay: Boolean) {}
    override fun setVerticalScrollbarOverlay(overlay: Boolean) {}
    override fun overlayHorizontalScrollbar() = false
    override fun overlayVerticalScrollbar() = false
    override fun getVisibleTitleHeight() = 0
    override fun getCertificate(): SslCertificate? = null
    override fun setCertificate(certificate: SslCertificate?) {}
    override fun savePassword(host: String?, username: String?, password: String?) {}
    override fun setHttpAuthUsernamePassword(host: String?, realm: String?, username: String?, password: String?) {}
    override fun getHttpAuthUsernamePassword(host: String?, realm: String?): Array<String>? = null
    override fun setNetworkAvailable(networkUp: Boolean) {}
    override fun saveState(outState: Bundle?): WebBackForwardList? = null
    override fun savePicture(b: Bundle?, dest: File?) = false
    override fun restorePicture(b: Bundle?, src: File?) = false
    override fun restoreState(inState: Bundle?): WebBackForwardList? = null
    override fun postUrl(url: String?, postData: ByteArray?) {}
    override fun loadData(data: String?, mimeType: String?, encoding: String?) {
        loadDataWithBaseURL(null, data, mimeType, encoding, null)
    }
    override fun saveWebArchive(filename: String?) {}
    override fun saveWebArchive(basename: String?, autoname: Boolean, callback: ValueCallback<String>?) {}
    override fun notifyFindDialogDismissed() {}

    override fun getViewDelegate(): WebViewProvider.ViewDelegate = NoOpViewDelegate()
    override fun getScrollDelegate(): WebViewProvider.ScrollDelegate = NoOpScrollDelegate()

    private class NoOpViewDelegate : WebViewProvider.ViewDelegate {
        override fun shouldDelayChildPressedState() = false
        override fun onProvideVirtualStructure(structure: android.view.ViewStructure?) {}
        override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider? = null
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {}
        override fun onInitializeAccessibilityEvent(event: AccessibilityEvent?) {}
        override fun performAccessibilityAction(action: Int, arguments: Bundle?) = false
        override fun setOverScrollMode(mode: Int) {}
        override fun setScrollBarStyle(style: Int) {}
        override fun onDrawVerticalScrollBar(canvas: Canvas?, scrollBar: Drawable?, l: Int, t: Int, r: Int, b: Int) {}
        override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {}
        override fun onWindowVisibilityChanged(visibility: Int) {}
        override fun onDraw(canvas: Canvas?) {}
        override fun setLayoutParams(layoutParams: ViewGroup.LayoutParams?) {}
        override fun performLongClick() = false
        override fun onConfigurationChanged(newConfig: Configuration?) {}
        override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection? = null
        override fun onDragEvent(event: DragEvent?) = false
        override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent?) = false
        override fun onKeyDown(keyCode: Int, event: KeyEvent?) = false
        override fun onKeyUp(keyCode: Int, event: KeyEvent?) = false
        override fun onAttachedToWindow() {}
        override fun onDetachedFromWindow() {}
        override fun onVisibilityChanged(changedView: View?, visibility: Int) {}
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {}
        override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {}
        override fun setFrame(left: Int, top: Int, right: Int, bottom: Int) = false
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {}
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {}
        override fun dispatchKeyEvent(event: KeyEvent?) = false
        override fun onTouchEvent(ev: MotionEvent?) = false
        override fun onHoverEvent(event: MotionEvent?) = false
        override fun onGenericMotionEvent(event: MotionEvent?) = false
        override fun onTrackballEvent(ev: MotionEvent?) = false
        override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?) = false
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {}
        override fun requestChildRectangleOnScreen(child: View?, rect: Rect?, immediate: Boolean) = false
        override fun setBackgroundColor(color: Int) {}
        override fun setLayerType(layerType: Int, paint: Paint?) {}
        override fun preDispatchDraw(canvas: Canvas?) {}
        override fun onStartTemporaryDetach() {}
        override fun onFinishTemporaryDetach() {}
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {}
        override fun getHandler(originalHandler: Handler?): Handler? = originalHandler
        override fun findFocus(originalFocusedView: View?): View? = null
    }

    private class NoOpScrollDelegate : WebViewProvider.ScrollDelegate {
        override fun computeHorizontalScrollRange() = 0
        override fun computeHorizontalScrollOffset() = 0
        override fun computeVerticalScrollRange() = 0
        override fun computeVerticalScrollOffset() = 0
        override fun computeVerticalScrollExtent() = 0
        override fun computeScroll() {}
    }
}

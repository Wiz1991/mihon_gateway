package moe.radar.mihon_gateway.browser

import android.webkit.WebView
import android.webkit.WebViewClient
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import eu.kanade.tachiyomi.network.NetworkHelper
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Layer 3: Playwright-backed WebView fallback for sources needing a full browser.
 *
 * Called by HeadlessWebViewProvider.loadUrl() when a real HTTP URL is loaded.
 * Uses Playwright's headless Chromium to:
 *   - Navigate to the URL with cookies from PersistentCookieStore
 *   - Bind addJavascriptInterface objects via exposeBinding
 *   - Execute evaluateJavascript calls via page.evaluate
 *   - Sync cookies back to PersistentCookieStore after load
 */
class PlaywrightWebViewFallback(
    private val webView: WebView,
    private val jsInterfaces: Map<String, Any>,
    private val webViewClient: WebViewClient?,
    private val url: String,
) : KoinComponent {
    private val logger = KotlinLogging.logger {}
    private val networkHelper: NetworkHelper by inject()
    private var context: BrowserContext? = null
    private var page: Page? = null

    fun execute() {
        logger.info { "Playwright fallback executing for URL: $url" }

        val httpUrl = url.toHttpUrlOrNull() ?: run {
            logger.error { "Invalid URL: $url" }
            return
        }

        // Get cookies for this domain from PersistentCookieStore
        val cookies = networkHelper.cookieStore.get(httpUrl)

        // Create Playwright context with cookies
        context = PlaywrightManager.createContext(cookies)
        page = context!!.newPage()

        // Register JS interface bindings
        for ((name, obj) in jsInterfaces) {
            exposeJsInterface(page!!, name, obj)
        }

        // Navigate and wait for load
        try {
            page!!.navigate(url)
            page!!.waitForLoadState()

            // Sync cookies back to PersistentCookieStore
            syncCookiesBack()

            // Fire onPageFinished
            webViewClient?.onPageFinished(webView, url)
        } catch (e: Exception) {
            logger.error(e) { "Playwright navigation failed for $url" }
            webViewClient?.onReceivedError(
                webView, WebViewClient.ERROR_UNKNOWN,
                "Playwright error: ${e.message}", url
            )
        }
    }

    private fun exposeJsInterface(page: Page, name: String, obj: Any) {
        // Find all @JavascriptInterface annotated methods (use string check to avoid compile-time dep)
        val jsAnnotationClass = try {
            Class.forName("android.webkit.JavascriptInterface") as Class<out Annotation>
        } catch (_: ClassNotFoundException) {
            null
        }
        val methods = obj.javaClass.methods.filter { method ->
            jsAnnotationClass != null && method.isAnnotationPresent(jsAnnotationClass)
        }

        if (methods.isEmpty()) return

        // Expose a single binding per interface name, then create a JS proxy object
        page.exposeBinding("__playwright_$name") { _, args ->
            val methodName = args[0] as String
            val rawArgs = if (args.size > 1) args.drop(1) else emptyList()

            val method = methods.find { it.name == methodName }
                ?: throw IllegalArgumentException("No method '$methodName' on interface '$name'")

            // Convert arguments to match method parameter types
            val paramTypes = method.parameterTypes
            val convertedArgs = rawArgs.mapIndexed { i, arg ->
                if (i < paramTypes.size) convertArg(arg, paramTypes[i]) else arg
            }.toTypedArray()

            method.invoke(obj, *convertedArgs)
        }

        // Create the JS bridge object with async methods that await the binding result.
        // Playwright exposeBinding returns Promises; using async/await ensures return values resolve.
        val methodStubs = methods.joinToString("\n") { method ->
            val params = method.parameters.mapIndexed { i, _ -> "a$i" }.joinToString(", ")
            """${method.name}: async function($params) { return await __playwright_$name('${method.name}', $params); }"""
        }
        page.addInitScript("""
            window.$name = { $methodStubs };
        """.trimIndent())
    }

    private fun convertArg(value: Any?, targetType: Class<*>): Any? {
        if (value == null) return null
        return when (targetType) {
            String::class.java -> value.toString()
            Int::class.java, Integer::class.java -> (value as? Number)?.toInt() ?: value.toString().toIntOrNull()
            Long::class.java, java.lang.Long::class.java -> (value as? Number)?.toLong() ?: value.toString().toLongOrNull()
            Double::class.java, java.lang.Double::class.java -> (value as? Number)?.toDouble() ?: value.toString().toDoubleOrNull()
            Float::class.java, java.lang.Float::class.java -> (value as? Number)?.toFloat() ?: value.toString().toFloatOrNull()
            Boolean::class.java, java.lang.Boolean::class.java -> value as? Boolean ?: value.toString().toBoolean()
            else -> value
        }
    }

    fun evaluateJavascript(script: String): String? {
        return try {
            val result = page?.evaluate(script)
            result?.toString()
        } catch (e: Exception) {
            logger.error(e) { "Playwright evaluate failed: ${script.take(200)}" }
            null
        }
    }

    private fun syncCookiesBack() {
        val playwrightCookies = context?.cookies() ?: return
        val httpUrl = url.toHttpUrlOrNull() ?: return

        val okHttpCookies = playwrightCookies.map { pc ->
            Cookie.Builder()
                .name(pc.name)
                .value(pc.value)
                .domain(pc.domain.removePrefix("."))
                .path(pc.path)
                .apply {
                    if (pc.secure) secure()
                    if (pc.httpOnly) httpOnly()
                    if (pc.expires > 0) expiresAt((pc.expires * 1000).toLong())
                    else expiresAt(Long.MAX_VALUE)
                }
                .build()
        }

        if (okHttpCookies.isNotEmpty()) {
            networkHelper.cookieStore.addAll(httpUrl, okHttpCookies)
            logger.debug { "Synced ${okHttpCookies.size} cookies back to PersistentCookieStore" }
        }
    }

    fun destroy() {
        try {
            page?.close()
            context?.close()
        } catch (e: Exception) {
            logger.warn(e) { "Error closing Playwright context" }
        }
        page = null
        context = null
    }
}

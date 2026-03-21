package moe.radar.mihon_gateway.service

import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebView
import moe.radar.mihon_gateway.test.BaseTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.androidimpl.CustomContext
import xyz.nulldev.androidcompat.webkit.LocalStorageManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Layer 2 integration tests: WebView + HeadlessWebViewProvider + GraalVM evaluateJavascript.
 *
 * Tests the full flow extensions use:
 *   WebView.evaluateJavascript(script, callback) → HeadlessWebViewProvider → fast-path/GraalVM → result
 */
class HeadlessWebViewIntegrationTest : BaseTest() {

    private lateinit var webView: WebView

    companion object {
        @BeforeAll
        @JvmStatic
        fun prepareLooper() {
            // WebView constructor requires current thread to have a Looper
            if (Looper.myLooper() == null) {
                Looper.prepare()
            }
        }
    }

    @BeforeEach
    fun createWebView() {
        webView = WebView(CustomContext())
    }

    /**
     * Helper: evaluate JS and wait for result.
     */
    private fun evaluateAndWait(script: String, timeoutMs: Long = 10000): String? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        webView.evaluateJavascript(script, ValueCallback { value ->
            result.set(value)
            latch.countDown()
        })
        assertTrue(latch.await(timeoutMs, TimeUnit.MILLISECONDS), "evaluateJavascript timed out")
        return result.get()
    }

    // ==================== WebView instantiation ====================

    @Test
    fun `WebView can be instantiated with HeadlessWebViewProvider`() {
        assertNotNull(webView)
        assertNotNull(webView.settings)
    }

    // ==================== Fast-path localStorage tests ====================

    @Test
    fun `fast-path getItem returns stored value`() {
        val origin = "https://test-fastpath.example.com"
        LocalStorageManager.setItem(origin, "authToken", "secret123")

        webView.loadDataWithBaseURL("$origin/page", "<html></html>", "text/html", "utf-8", null)

        val result = evaluateAndWait("localStorage.getItem('authToken')")
        assertEquals("\"secret123\"", result)

        LocalStorageManager.clear(origin)
    }

    @Test
    fun `fast-path getItem returns null for missing key`() {
        val origin = "https://test-fastpath-miss.example.com"
        webView.loadDataWithBaseURL("$origin/page", "", "text/html", "utf-8", null)

        val result = evaluateAndWait("localStorage.getItem('nonexistent')")
        assertEquals("null", result)
    }

    @Test
    fun `fast-path setItem stores value`() {
        val origin = "https://test-fastpath-set.example.com"
        webView.loadDataWithBaseURL("$origin/page", "", "text/html", "utf-8", null)

        evaluateAndWait("localStorage.setItem('myKey', 'myValue')")

        assertEquals("myValue", LocalStorageManager.getItem(origin, "myKey"))

        val result = evaluateAndWait("localStorage.getItem('myKey')")
        assertEquals("\"myValue\"", result)

        LocalStorageManager.clear(origin)
    }

    @Test
    fun `fast-path removeItem deletes key`() {
        val origin = "https://test-fastpath-rm.example.com"
        LocalStorageManager.setItem(origin, "toRemove", "value")
        webView.loadDataWithBaseURL("$origin/page", "", "text/html", "utf-8", null)

        evaluateAndWait("localStorage.removeItem('toRemove')")
        assertNull(LocalStorageManager.getItem(origin, "toRemove"))
    }

    @Test
    fun `fast-path handles window-prefixed localStorage calls`() {
        val origin = "https://test-window-prefix.example.com"
        LocalStorageManager.setItem(origin, "wpKey", "wpValue")
        webView.loadDataWithBaseURL("$origin/page", "", "text/html", "utf-8", null)

        val result = evaluateAndWait("window.localStorage.getItem('wpKey')")
        assertEquals("\"wpValue\"", result)

        LocalStorageManager.clear(origin)
    }

    // ==================== GraalVM slow-path tests ====================

    @Test
    fun `GraalVM evaluates simple JS expressions`() {
        webView.loadDataWithBaseURL("https://graalvm-test.example.com/page", "", "text/html", "utf-8", null)

        assertEquals("42", evaluateAndWait("40 + 2"))
        assertEquals("\"hello\"", evaluateAndWait("'hello'"))
        assertEquals("true", evaluateAndWait("true"))
        assertEquals("null", evaluateAndWait("null"))
    }

    @Test
    fun `GraalVM localStorage getItem works through JS context`() {
        val origin = "https://graalvm-ls.example.com"
        LocalStorageManager.setItem(origin, "token", "graalValue")
        webView.loadDataWithBaseURL("$origin/page", "", "text/html", "utf-8", null)

        // Wrapped in IIFE to bypass fast-path regex
        val result = evaluateAndWait("(function() { return localStorage.getItem('token'); })()")
        assertEquals("\"graalValue\"", result)

        LocalStorageManager.clear(origin)
    }

    @Test
    fun `GraalVM localStorage setItem and getItem round-trip`() {
        val origin = "https://graalvm-roundtrip.example.com"
        webView.loadDataWithBaseURL("$origin/page", "", "text/html", "utf-8", null)

        // Set via GraalVM (wrapped to avoid fast-path)
        evaluateAndWait("(function() { localStorage.setItem('key1', 'val1'); return null; })()")

        // Read back via fast-path
        val result = evaluateAndWait("localStorage.getItem('key1')")
        assertEquals("\"val1\"", result)

        assertEquals("val1", LocalStorageManager.getItem(origin, "key1"))
        LocalStorageManager.clear(origin)
    }

    @Test
    fun `GraalVM localStorage removeItem works`() {
        val origin = "https://graalvm-remove.example.com"
        LocalStorageManager.setItem(origin, "removeMe", "value")
        webView.loadDataWithBaseURL("$origin/page", "", "text/html", "utf-8", null)

        evaluateAndWait("(function() { localStorage.removeItem('removeMe'); return null; })()")
        assertNull(LocalStorageManager.getItem(origin, "removeMe"))
    }

    @Test
    fun `GraalVM localStorage clear works`() {
        val origin = "https://graalvm-clear.example.com"
        LocalStorageManager.setItem(origin, "a", "1")
        LocalStorageManager.setItem(origin, "b", "2")
        webView.loadDataWithBaseURL("$origin/page", "", "text/html", "utf-8", null)

        evaluateAndWait("(function() { localStorage.clear(); return null; })()")
        assertTrue(LocalStorageManager.getAllItems(origin).isEmpty())
    }

    // ==================== JSON escaping ====================

    @Test
    fun `values with special characters are properly JSON-escaped`() {
        val origin = "https://json-escape.example.com"
        LocalStorageManager.setItem(origin, "special", "line1\nline2\ttab\"quote\\backslash")
        webView.loadDataWithBaseURL("$origin/page", "", "text/html", "utf-8", null)

        val result = evaluateAndWait("localStorage.getItem('special')")
        assertNotNull(result)
        assertTrue(result!!.startsWith("\""))
        assertTrue(result.contains("\\n"))
        assertTrue(result.contains("\\t"))
        assertTrue(result.contains("\\\""))
        assertTrue(result.contains("\\\\"))

        LocalStorageManager.clear(origin)
    }

    // ==================== addJavascriptInterface + GraalVM ====================

    @Test
    fun `addJavascriptInterface methods are callable from GraalVM JS`() {
        val bridge = TestJsBridge()
        webView.addJavascriptInterface(bridge, "Android")
        webView.loadDataWithBaseURL("https://jsbridge.example.com/page", "", "text/html", "utf-8", null)

        val result = evaluateAndWait("Android.getData()")
        assertEquals("\"bridge-data-123\"", result)
    }

    @Test
    fun `addJavascriptInterface receives arguments from JS`() {
        val bridge = TestJsBridge()
        webView.addJavascriptInterface(bridge, "Android")
        webView.loadDataWithBaseURL("https://jsbridge-args.example.com/page", "", "text/html", "utf-8", null)

        evaluateAndWait("Android.passData('hello from js')")
        assertEquals("hello from js", bridge.receivedData)
    }

    @Test
    fun `addJavascriptInterface with multiple methods`() {
        val bridge = TestJsBridge()
        webView.addJavascriptInterface(bridge, "Bridge")
        webView.loadDataWithBaseURL("https://jsbridge-multi.example.com/page", "", "text/html", "utf-8", null)

        val data = evaluateAndWait("Bridge.getData()")
        assertEquals("\"bridge-data-123\"", data)

        evaluateAndWait("Bridge.passData('test-input')")
        assertEquals("test-input", bridge.receivedData)
    }

    @Test
    fun `GraalVM handles invalid scripts gracefully`() {
        webView.loadDataWithBaseURL("https://error.example.com/page", "", "text/html", "utf-8", null)

        // Invalid JS should return "null" rather than crashing
        val result = evaluateAndWait("this is not valid javascript !!!")
        assertEquals("null", result)
    }

    /**
     * Test JS bridge — mimics what real extensions expose to WebView.
     */
    class TestJsBridge {
        var receivedData: String? = null

        @JavascriptInterface
        fun getData(): String = "bridge-data-123"

        @JavascriptInterface
        fun passData(data: String) {
            receivedData = data
        }
    }
}

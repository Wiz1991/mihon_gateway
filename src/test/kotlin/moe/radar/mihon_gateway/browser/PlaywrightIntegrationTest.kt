package moe.radar.mihon_gateway.browser

import eu.kanade.tachiyomi.network.NetworkHelper
import moe.radar.mihon_gateway.test.BaseTest
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Layer 3 integration tests: Playwright headless Chromium.
 *
 * Tests PlaywrightManager (browser lifecycle, cookie injection, context isolation)
 * and Playwright page navigation + JS evaluation against a real MockWebServer.
 *
 * Requires Chromium to be installed (npx playwright install chromium).
 */
@Tag("integration")
class PlaywrightIntegrationTest : BaseTest() {

    private val networkHelper: NetworkHelper by inject()
    private lateinit var mockServer: MockWebServer

    companion object {
        @AfterAll
        @JvmStatic
        fun shutdownPlaywright() {
            PlaywrightManager.shutdown()
        }
    }

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
    }

    @AfterEach
    fun teardown() {
        mockServer.shutdown()
    }

    // ==================== PlaywrightManager tests ====================

    @Test
    fun `PlaywrightManager creates browser context`() {
        val ctx = PlaywrightManager.createContext()
        assertNotNull(ctx)
        ctx.close()
    }

    @Test
    fun `PlaywrightManager injects cookies into context`() {
        val domain = "localhost"
        val cookies = listOf(
            Cookie.Builder()
                .name("session")
                .value("abc123")
                .domain(domain)
                .path("/")
                .expiresAt(Long.MAX_VALUE)
                .build()
        )

        val ctx = PlaywrightManager.createContext(cookies)
        val page = ctx.newPage()

        // Navigate to mockserver — cookies should be sent
        mockServer.enqueue(MockResponse().setBody("OK"))
        page.navigate(mockServer.url("/test").toString())

        val request = mockServer.takeRequest()
        val cookieHeader = request.getHeader("Cookie")
        assertNotNull(cookieHeader, "Cookie header should be present")
        assertTrue(cookieHeader!!.contains("session=abc123"), "Cookie value should match")

        page.close()
        ctx.close()
    }

    @Test
    fun `browser contexts are isolated`() {
        // Context 1 has cookie A
        val ctx1 = PlaywrightManager.createContext(listOf(
            Cookie.Builder().name("ctx").value("one").domain("localhost").path("/").expiresAt(Long.MAX_VALUE).build()
        ))
        // Context 2 has cookie B
        val ctx2 = PlaywrightManager.createContext(listOf(
            Cookie.Builder().name("ctx").value("two").domain("localhost").path("/").expiresAt(Long.MAX_VALUE).build()
        ))

        val page1 = ctx1.newPage()
        val page2 = ctx2.newPage()

        mockServer.enqueue(MockResponse().setBody("OK"))
        mockServer.enqueue(MockResponse().setBody("OK"))

        page1.navigate(mockServer.url("/test1").toString())
        page2.navigate(mockServer.url("/test2").toString())

        val req1 = mockServer.takeRequest()
        val req2 = mockServer.takeRequest()

        assertTrue(req1.getHeader("Cookie")!!.contains("ctx=one"))
        assertTrue(req2.getHeader("Cookie")!!.contains("ctx=two"))

        page1.close(); page2.close()
        ctx1.close(); ctx2.close()
    }

    // ==================== Page navigation + JS evaluation ====================

    @Test
    fun `Playwright navigates to page and evaluates JS`() {
        mockServer.enqueue(MockResponse()
            .setHeader("Content-Type", "text/html")
            .setBody("<html><body><script>window.testData = 'hello-from-js';</script></body></html>"))

        val ctx = PlaywrightManager.createContext()
        val page = ctx.newPage()

        page.navigate(mockServer.url("/page").toString())
        page.waitForLoadState()

        val result = page.evaluate("window.testData")
        assertEquals("hello-from-js", result)

        page.close()
        ctx.close()
    }

    @Test
    fun `Playwright evaluates complex JS expressions`() {
        mockServer.enqueue(MockResponse()
            .setHeader("Content-Type", "text/html")
            .setBody("<html><body></body></html>"))

        val ctx = PlaywrightManager.createContext()
        val page = ctx.newPage()

        page.navigate(mockServer.url("/page").toString())

        // Arithmetic
        assertEquals(42, (page.evaluate("20 + 22") as Number).toInt())

        // String manipulation
        assertEquals("HELLO", page.evaluate("'hello'.toUpperCase()"))

        // Object creation
        val obj = page.evaluate("({ key: 'value', num: 123 })") as Map<*, *>
        assertEquals("value", obj["key"])
        assertEquals(123, (obj["num"] as Number).toInt())

        page.close()
        ctx.close()
    }

    // ==================== Cookie sync (Set-Cookie → Playwright → back to OkHttp) ====================

    @Test
    fun `cookies set by server are accessible in Playwright context`() {
        mockServer.enqueue(MockResponse()
            .setHeader("Content-Type", "text/html")
            .addHeader("Set-Cookie", "server_session=xyz789; Path=/; Max-Age=3600")
            .setBody("<html><body>logged in</body></html>"))

        val ctx = PlaywrightManager.createContext()
        val page = ctx.newPage()

        page.navigate(mockServer.url("/login").toString())
        page.waitForLoadState()

        // Verify the cookie was stored in the Playwright context
        val playwrightCookies = ctx.cookies()
        val sessionCookie = playwrightCookies.find { it.name == "server_session" }
        assertNotNull(sessionCookie, "server_session cookie should exist in context")
        assertEquals("xyz789", sessionCookie!!.value)

        page.close()
        ctx.close()
    }

    @Test
    fun `cookies from Playwright can be synced back to PersistentCookieStore`() {
        // Simulate: server sets a cookie via Set-Cookie header
        mockServer.enqueue(MockResponse()
            .setHeader("Content-Type", "text/html")
            .addHeader("Set-Cookie", "auth_token=pw_token_123; Path=/; Max-Age=3600")
            .setBody("<html><body>OK</body></html>"))

        val ctx = PlaywrightManager.createContext()
        val page = ctx.newPage()

        val pageUrl = mockServer.url("/auth").toString()
        page.navigate(pageUrl)
        page.waitForLoadState()

        // Extract cookies from Playwright context and sync to OkHttp store
        // (This is what PlaywrightWebViewFallback.syncCookiesBack does)
        val playwrightCookies = ctx.cookies()
        val httpUrl = pageUrl.toHttpUrlOrNull()!!

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
        networkHelper.cookieStore.addAll(httpUrl, okHttpCookies)

        // Now verify OkHttp sends these cookies on the next request
        mockServer.enqueue(MockResponse().setBody("authenticated"))
        val request = okhttp3.Request.Builder().url(mockServer.url("/api/data")).build()
        networkHelper.client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }

        val apiRequest = mockServer.takeRequest() // skip /auth
        val apiReq2 = mockServer.takeRequest()    // /api/data
        val cookieHeader = apiReq2.getHeader("Cookie")
        assertNotNull(cookieHeader, "Cookie header should be present on subsequent request")
        assertTrue(cookieHeader!!.contains("auth_token=pw_token_123"),
            "Synced cookie should be sent by OkHttp")

        page.close()
        ctx.close()
    }

    // ==================== exposeBinding (JS bridge) ====================

    @Test
    fun `exposeBinding allows JS to call Java methods`() {
        mockServer.enqueue(MockResponse()
            .setHeader("Content-Type", "text/html")
            .setBody("<html><body></body></html>"))

        val ctx = PlaywrightManager.createContext()
        val page = ctx.newPage()

        // Register a binding
        var receivedValue: String? = null
        page.exposeBinding("sendToJava") { _, args ->
            receivedValue = args[0] as String
            "received"
        }

        page.navigate(mockServer.url("/page").toString())

        // Call the binding from JS
        val result = page.evaluate("async () => { return await sendToJava('hello from browser'); }")
        assertEquals("received", result)
        assertEquals("hello from browser", receivedValue)

        page.close()
        ctx.close()
    }

    @Test
    fun `exposeBinding with initScript simulates addJavascriptInterface pattern`() {
        // This tests the same pattern PlaywrightWebViewFallback uses
        mockServer.enqueue(MockResponse()
            .setHeader("Content-Type", "text/html")
            .setBody("<html><body></body></html>"))

        val ctx = PlaywrightManager.createContext()
        val page = ctx.newPage()

        // Simulate the PlaywrightWebViewFallback.exposeJsInterface pattern
        var extractedData: String? = null
        page.exposeBinding("__playwright_Android") { _, args ->
            val methodName = args[0] as String
            when (methodName) {
                "passData" -> {
                    extractedData = args[1] as String
                    null
                }
                "getData" -> "bridge-data-456"
                else -> throw IllegalArgumentException("Unknown method: $methodName")
            }
        }

        // Create the JS bridge object (same pattern as PlaywrightWebViewFallback)
        page.addInitScript("""
            window.Android = {
                passData: async function(data) { return await __playwright_Android('passData', data); },
                getData: async function() { return await __playwright_Android('getData'); }
            };
        """.trimIndent())

        page.navigate(mockServer.url("/page").toString())
        page.waitForLoadState()

        // Call passData from Java→JS→Java
        page.evaluate("async () => { await window.Android.passData('secret-key-789'); }")
        assertEquals("secret-key-789", extractedData)

        // Call getData from Java→JS→Java
        val result = page.evaluate("async () => { return await window.Android.getData(); }")
        assertEquals("bridge-data-456", result)

        page.close()
        ctx.close()
    }

    // ==================== Full round-trip: cookies + navigation + JS + sync ====================

    @Test
    fun `full round-trip - inject cookies, navigate, run JS, sync cookies back`() {
        // Step 1: Inject auth cookies into PersistentCookieStore
        val baseUrl = mockServer.url("/")
        val injectedCookies = listOf(
            Cookie.Builder()
                .name("pre_auth")
                .value("injected_token")
                .domain(baseUrl.host)
                .path("/")
                .expiresAt(Long.MAX_VALUE)
                .build()
        )
        networkHelper.cookieStore.addAll(baseUrl, injectedCookies)

        // Step 2: Server serves page that checks cookies and sets new ones
        mockServer.enqueue(MockResponse()
            .setHeader("Content-Type", "text/html")
            .addHeader("Set-Cookie", "post_auth=new_session_456; Path=/; Max-Age=3600")
            .setBody("""
                <html><body>
                <script>
                    window.extractedData = document.cookie;
                    window.pageLoaded = true;
                </script>
                </body></html>
            """.trimIndent()))

        // Step 3: Navigate with Playwright using cookies from store
        val storedCookies = networkHelper.cookieStore.get(baseUrl)
        val ctx = PlaywrightManager.createContext(storedCookies)
        val page = ctx.newPage()

        page.navigate(mockServer.url("/protected").toString())
        page.waitForLoadState()

        // Step 4: Verify pre-injected cookies were sent
        val request = mockServer.takeRequest()
        val sentCookies = request.getHeader("Cookie")
        assertNotNull(sentCookies)
        assertTrue(sentCookies!!.contains("pre_auth=injected_token"),
            "Pre-injected cookies should be sent to the server")

        // Step 5: Verify JS ran on the page
        val pageLoaded = page.evaluate("window.pageLoaded")
        assertEquals(true, pageLoaded)

        // Step 6: Sync cookies back to OkHttp store
        val pwCookies = ctx.cookies()
        val httpUrl = mockServer.url("/protected")
        val syncedCookies = pwCookies.map { pc ->
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
        networkHelper.cookieStore.addAll(httpUrl, syncedCookies)

        // Step 7: Verify OkHttp now has the new server-set cookie
        mockServer.enqueue(MockResponse().setBody("OK"))
        val apiRequest = okhttp3.Request.Builder().url(mockServer.url("/api")).build()
        networkHelper.client.newCall(apiRequest).execute().use { response ->
            assertEquals(200, response.code)
        }
        val apiRecorded = mockServer.takeRequest()
        val apiCookies = apiRecorded.getHeader("Cookie")
        assertNotNull(apiCookies)
        assertTrue(apiCookies!!.contains("post_auth=new_session_456"),
            "Server-set cookie should be synced back and sent by OkHttp")

        page.close()
        ctx.close()
    }
}

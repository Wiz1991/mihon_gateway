package moe.radar.mihon_gateway.service

import eu.kanade.tachiyomi.network.NetworkHelper
import moe.radar.mihon_gateway.test.BaseTest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Cookie
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.test.inject
import xyz.nulldev.androidcompat.webkit.LocalStorageManager

/**
 * Integration tests for the 3-layer auth system.
 */
class AuthIntegrationTest : BaseTest() {

    private val networkHelper: NetworkHelper by inject()
    private lateinit var mockServer: MockWebServer

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
    }

    @AfterEach
    fun teardown() {
        mockServer.shutdown()
    }

    /**
     * Test 1: Cookie injection → OkHttp round-trip
     * Inject cookies via PersistentCookieStore, verify they appear in HTTP requests.
     */
    @Test
    fun `cookie injection appears in OkHttp requests`() {
        // Setup mock server
        mockServer.enqueue(MockResponse().setBody("OK"))

        val baseUrl = mockServer.url("/api/manga")
        val domain = baseUrl.host

        // Inject auth cookies
        val cookies = listOf(
            Cookie.Builder()
                .name("session_id")
                .value("abc123")
                .domain(domain)
                .path("/")
                .expiresAt(Long.MAX_VALUE)
                .build(),
            Cookie.Builder()
                .name("auth_token")
                .value("xyz789")
                .domain(domain)
                .path("/")
                .expiresAt(Long.MAX_VALUE)
                .build()
        )
        networkHelper.cookieStore.addAll(baseUrl, cookies)

        // Make request through OkHttp client (which uses PersistentCookieJar)
        val request = Request.Builder().url(baseUrl).build()
        networkHelper.client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }

        // Verify cookies were sent in the request
        val recordedRequest = mockServer.takeRequest()
        val cookieHeader = recordedRequest.getHeader("Cookie")
        assertNotNull(cookieHeader)
        assertTrue(cookieHeader!!.contains("session_id=abc123"))
        assertTrue(cookieHeader.contains("auth_token=xyz789"))
    }

    /**
     * Test 2: LocalStorage set/get/remove/clear operations
     */
    @Test
    fun `localStorage operations work correctly`() {
        val origin = "https://example.com"

        // Set and get
        LocalStorageManager.setItem(origin, "token", "abc123")
        assertEquals("abc123", LocalStorageManager.getItem(origin, "token"))

        // Overwrite
        LocalStorageManager.setItem(origin, "token", "newToken")
        assertEquals("newToken", LocalStorageManager.getItem(origin, "token"))

        // Multiple items
        LocalStorageManager.setItem(origin, "userId", "user42")
        val allItems = LocalStorageManager.getAllItems(origin)
        assertEquals(2, allItems.size)
        assertEquals("newToken", allItems["token"])
        assertEquals("user42", allItems["userId"])

        // Remove single item
        LocalStorageManager.removeItem(origin, "token")
        assertNull(LocalStorageManager.getItem(origin, "token"))
        assertEquals("user42", LocalStorageManager.getItem(origin, "userId"))

        // Clear all
        LocalStorageManager.clear(origin)
        assertTrue(LocalStorageManager.getAllItems(origin).isEmpty())
    }

    /**
     * Test 3: Fake login flow (cookie round-trip)
     * MockWebServer login endpoint returns Set-Cookie → OkHttp picks them up →
     * subsequent authenticated request sends cookies.
     */
    @Test
    fun `login flow cookies persist across requests`() {
        // Login endpoint sets cookies
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "session=login123; Path=/; Max-Age=3600")
                .setBody("{\"status\":\"ok\"}")
        )
        // Authenticated endpoint expects cookie
        mockServer.enqueue(MockResponse().setBody("{\"manga\":[]}"))

        val loginUrl = mockServer.url("/login")
        val apiUrl = mockServer.url("/api/manga")

        // Step 1: Login request (client picks up Set-Cookie)
        val loginRequest = Request.Builder().url(loginUrl).build()
        networkHelper.client.newCall(loginRequest).execute().use { response ->
            assertEquals(200, response.code)
        }

        // Step 2: API request should include the session cookie
        val apiRequest = Request.Builder().url(apiUrl).build()
        networkHelper.client.newCall(apiRequest).execute().use { response ->
            assertEquals(200, response.code)
        }

        // Verify login request was made
        mockServer.takeRequest()

        // Verify API request included the cookie
        val apiRecorded = mockServer.takeRequest()
        val cookieHeader = apiRecorded.getHeader("Cookie")
        assertNotNull(cookieHeader)
        assertTrue(cookieHeader!!.contains("session=login123"))
    }

    /**
     * Test 4: LocalStorage origin isolation
     * Verify that localStorage items from different origins don't leak.
     */
    @Test
    fun `localStorage items are isolated per origin`() {
        val origin1 = "https://site-a.com"
        val origin2 = "https://site-b.com"

        LocalStorageManager.setItem(origin1, "token", "token-a")
        LocalStorageManager.setItem(origin2, "token", "token-b")

        assertEquals("token-a", LocalStorageManager.getItem(origin1, "token"))
        assertEquals("token-b", LocalStorageManager.getItem(origin2, "token"))

        // Clear one origin shouldn't affect the other
        LocalStorageManager.clear(origin1)
        assertNull(LocalStorageManager.getItem(origin1, "token"))
        assertEquals("token-b", LocalStorageManager.getItem(origin2, "token"))

        // Cleanup
        LocalStorageManager.clear(origin2)
    }

    /**
     * Test 5: extractOrigin works correctly for various URL formats.
     */
    @Test
    fun `extractOrigin handles various URL formats`() {
        assertEquals("https://example.com", LocalStorageManager.extractOrigin("https://example.com/page?q=1"))
        assertEquals("http://localhost:8080", LocalStorageManager.extractOrigin("http://localhost:8080/api"))
        assertEquals("https://sub.domain.com", LocalStorageManager.extractOrigin("https://sub.domain.com/path"))
    }
}

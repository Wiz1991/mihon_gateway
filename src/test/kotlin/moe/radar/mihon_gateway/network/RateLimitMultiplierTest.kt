package moe.radar.mihon_gateway.network

import com.typesafe.config.ConfigFactory
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.RateLimitInterceptor
import eu.kanade.tachiyomi.network.interceptor.getRateLimitMultiplier
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import moe.radar.mihon_gateway.config.NetworkConfigModule
import moe.radar.mihon_gateway.test.BaseTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.test.inject
import xyz.nulldev.ts.config.GlobalConfigManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@DisplayName("Rate Limit Multiplier Tests")
class RateLimitMultiplierTest : BaseTest() {

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

    @Test
    @DisplayName("getRateLimitMultiplier returns 1 when proxy is disabled")
    fun testMultiplierDisabledProxy() {
        // Default config has proxyEnabled=false
        val multiplier = getRateLimitMultiplier()
        assertEquals(1, multiplier, "Multiplier should be 1 when proxy is disabled")
    }

    @Test
    @DisplayName("getRateLimitMultiplier returns configured value when proxy is enabled")
    fun testMultiplierEnabledProxy() {
        val originalModule = try {
            GlobalConfigManager.module<NetworkConfigModule>()
        } catch (_: Exception) {
            null
        }

        try {
            // Register a config with proxy enabled and multiplier=5
            val config = ConfigFactory.parseString("""
                server.network {
                    proxyEnabled = true
                    proxyType = "HTTP"
                    proxyHost = "localhost"
                    proxyPort = 8080
                    proxyUsername = ""
                    proxyPassword = ""
                    rateLimitMultiplier = 5
                }
            """)
            val module = NetworkConfigModule.register(config)
            GlobalConfigManager.registerModule(module)

            val multiplier = getRateLimitMultiplier()
            assertEquals(5, multiplier, "Multiplier should be 5 when proxy is enabled with multiplier=5")
        } finally {
            // Restore original config
            if (originalModule != null) {
                GlobalConfigManager.registerModule(originalModule)
            } else {
                // Re-register default config
                val defaultConfig = ConfigFactory.parseResources("server-reference.conf")
                GlobalConfigManager.registerModule(NetworkConfigModule.register(defaultConfig))
            }
        }
    }

    @Test
    @DisplayName("getRateLimitMultiplier coerces values below 1 to 1")
    fun testMultiplierCoercion() {
        val originalModule = try {
            GlobalConfigManager.module<NetworkConfigModule>()
        } catch (_: Exception) {
            null
        }

        try {
            val config = ConfigFactory.parseString("""
                server.network {
                    proxyEnabled = true
                    proxyType = "HTTP"
                    proxyHost = "localhost"
                    proxyPort = 8080
                    proxyUsername = ""
                    proxyPassword = ""
                    rateLimitMultiplier = 0
                }
            """)
            GlobalConfigManager.registerModule(NetworkConfigModule.register(config))

            val multiplier = getRateLimitMultiplier()
            assertEquals(1, multiplier, "Multiplier should be coerced to 1 when configured as 0")
        } finally {
            if (originalModule != null) {
                GlobalConfigManager.registerModule(originalModule)
            } else {
                val defaultConfig = ConfigFactory.parseResources("server-reference.conf")
                GlobalConfigManager.registerModule(NetworkConfigModule.register(defaultConfig))
            }
        }
    }

    @Test
    @DisplayName("rateLimit extension applies multiplier to permits")
    fun testRateLimitExtensionAppliesMultiplier() {
        val originalModule = try {
            GlobalConfigManager.module<NetworkConfigModule>()
        } catch (_: Exception) {
            null
        }

        try {
            // Set multiplier to 3
            val config = ConfigFactory.parseString("""
                server.network {
                    proxyEnabled = true
                    proxyType = "HTTP"
                    proxyHost = "localhost"
                    proxyPort = 8080
                    proxyUsername = ""
                    proxyPassword = ""
                    rateLimitMultiplier = 3
                }
            """)
            GlobalConfigManager.registerModule(NetworkConfigModule.register(config))

            // Build a client with rateLimit(2, 1.seconds) — effective should be 6 permits
            val client = networkHelper.client.newBuilder()
                .rateLimit(2, 1.seconds)
                .build()

            // Enqueue 6 responses
            repeat(6) {
                mockServer.enqueue(MockResponse().setBody("OK"))
            }

            // All 6 requests should complete without delay within the rate limit window
            val startTime = System.currentTimeMillis()
            repeat(6) {
                val request = Request.Builder().url(mockServer.url("/test")).build()
                client.newCall(request).execute().use { response ->
                    assertEquals(200, response.code)
                }
            }
            val elapsed = System.currentTimeMillis() - startTime

            // 6 requests with effective permits=6 should complete quickly (under 2 seconds)
            assertTrue(elapsed < 2000, "6 requests should complete quickly with permits=6, took ${elapsed}ms")
        } finally {
            if (originalModule != null) {
                GlobalConfigManager.registerModule(originalModule)
            } else {
                val defaultConfig = ConfigFactory.parseResources("server-reference.conf")
                GlobalConfigManager.registerModule(NetworkConfigModule.register(defaultConfig))
            }
        }
    }

    @Test
    @DisplayName("rateLimitHost extension applies multiplier to permits")
    fun testRateLimitHostExtensionAppliesMultiplier() {
        val originalModule = try {
            GlobalConfigManager.module<NetworkConfigModule>()
        } catch (_: Exception) {
            null
        }

        try {
            // Set multiplier to 3
            val config = ConfigFactory.parseString("""
                server.network {
                    proxyEnabled = true
                    proxyType = "HTTP"
                    proxyHost = "localhost"
                    proxyPort = 8080
                    proxyUsername = ""
                    proxyPassword = ""
                    rateLimitMultiplier = 3
                }
            """)
            GlobalConfigManager.registerModule(NetworkConfigModule.register(config))

            val baseUrl = mockServer.url("/")

            // Build client with rateLimitHost(2) — effective should be 6 permits
            val client = networkHelper.client.newBuilder()
                .rateLimitHost(baseUrl, 2, 1.seconds)
                .build()

            // Enqueue 6 responses
            repeat(6) {
                mockServer.enqueue(MockResponse().setBody("OK"))
            }

            val startTime = System.currentTimeMillis()
            repeat(6) {
                val request = Request.Builder().url(mockServer.url("/test")).build()
                client.newCall(request).execute().use { response ->
                    assertEquals(200, response.code)
                }
            }
            val elapsed = System.currentTimeMillis() - startTime

            assertTrue(elapsed < 2000, "6 requests should complete quickly with permits=6, took ${elapsed}ms")
        } finally {
            if (originalModule != null) {
                GlobalConfigManager.registerModule(originalModule)
            } else {
                val defaultConfig = ConfigFactory.parseResources("server-reference.conf")
                GlobalConfigManager.registerModule(NetworkConfigModule.register(defaultConfig))
            }
        }
    }

    @Test
    @DisplayName("Rate limit without multiplier restricts requests as expected")
    fun testRateLimitWithoutMultiplier() {
        // Default config has proxyEnabled=false, so multiplier=1
        // Build client with rateLimit(2, 1.seconds) — effective is 2 permits
        val client = networkHelper.client.newBuilder()
            .rateLimit(2, 1.seconds)
            .build()

        // Enqueue 3 responses
        repeat(3) {
            mockServer.enqueue(MockResponse().setBody("OK"))
        }

        // First 2 requests should be fast, 3rd should be delayed
        val startTime = System.currentTimeMillis()
        repeat(2) {
            val request = Request.Builder().url(mockServer.url("/test")).build()
            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
            }
        }
        val elapsedForFirst2 = System.currentTimeMillis() - startTime
        assertTrue(elapsedForFirst2 < 1000, "First 2 requests should be fast, took ${elapsedForFirst2}ms")

        // 3rd request should be rate-limited (wait for ~1 second)
        val thirdStart = System.currentTimeMillis()
        val request = Request.Builder().url(mockServer.url("/test")).build()
        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }
        val thirdElapsed = System.currentTimeMillis() - thirdStart
        assertTrue(thirdElapsed >= 500, "3rd request should be rate-limited, only took ${thirdElapsed}ms")
    }

    @Test
    @DisplayName("Multiplier of 1 with proxy enabled has no effect on rate limiting")
    fun testMultiplierOneNoEffect() {
        val originalModule = try {
            GlobalConfigManager.module<NetworkConfigModule>()
        } catch (_: Exception) {
            null
        }

        try {
            val config = ConfigFactory.parseString("""
                server.network {
                    proxyEnabled = true
                    proxyType = "HTTP"
                    proxyHost = "localhost"
                    proxyPort = 8080
                    proxyUsername = ""
                    proxyPassword = ""
                    rateLimitMultiplier = 1
                }
            """)
            GlobalConfigManager.registerModule(NetworkConfigModule.register(config))

            assertEquals(1, getRateLimitMultiplier(), "Multiplier should be 1")
        } finally {
            if (originalModule != null) {
                GlobalConfigManager.registerModule(originalModule)
            } else {
                val defaultConfig = ConfigFactory.parseResources("server-reference.conf")
                GlobalConfigManager.registerModule(NetworkConfigModule.register(defaultConfig))
            }
        }
    }

    @Test
    @DisplayName("Negative multiplier is coerced to 1")
    fun testNegativeMultiplierCoercion() {
        val originalModule = try {
            GlobalConfigManager.module<NetworkConfigModule>()
        } catch (_: Exception) {
            null
        }

        try {
            val config = ConfigFactory.parseString("""
                server.network {
                    proxyEnabled = true
                    proxyType = "HTTP"
                    proxyHost = "localhost"
                    proxyPort = 8080
                    proxyUsername = ""
                    proxyPassword = ""
                    rateLimitMultiplier = -3
                }
            """)
            GlobalConfigManager.registerModule(NetworkConfigModule.register(config))

            val multiplier = getRateLimitMultiplier()
            assertEquals(1, multiplier, "Negative multiplier should be coerced to 1")
        } finally {
            if (originalModule != null) {
                GlobalConfigManager.registerModule(originalModule)
            } else {
                val defaultConfig = ConfigFactory.parseResources("server-reference.conf")
                GlobalConfigManager.registerModule(NetworkConfigModule.register(defaultConfig))
            }
        }
    }
}

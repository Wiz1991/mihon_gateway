package moe.radar.mihon_gateway.network

import com.typesafe.config.ConfigFactory
import eu.kanade.tachiyomi.network.NetworkHelper
import moe.radar.mihon_gateway.config.NetworkConfigModule
import moe.radar.mihon_gateway.test.BaseTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.test.inject
import xyz.nulldev.ts.config.GlobalConfigManager
import java.net.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DisplayName("Proxy Configuration Tests")
class ProxyConfigTest : BaseTest() {

    private val networkHelper: NetworkHelper by inject()

    @Test
    @DisplayName("NetworkConfigModule loads default values from reference config")
    fun testDefaultConfig() {
        val config = ConfigFactory.parseResources("server-reference.conf")
        val module = NetworkConfigModule.register(config)

        assertEquals(false, module.proxyEnabled)
        assertEquals("HTTP", module.proxyType)
        assertEquals("", module.proxyHost)
        assertEquals(0, module.proxyPort)
        assertEquals("", module.proxyUsername)
        assertEquals("", module.proxyPassword)
        assertEquals(1, module.rateLimitMultiplier)
    }

    @Test
    @DisplayName("NetworkConfigModule loads custom proxy values")
    fun testCustomProxyConfig() {
        val config = ConfigFactory.parseString("""
            server.network {
                proxyEnabled = true
                proxyType = "SOCKS"
                proxyHost = "proxy.example.com"
                proxyPort = 1080
                proxyUsername = "user"
                proxyPassword = "pass"
                rateLimitMultiplier = 5
            }
        """)
        val module = NetworkConfigModule.register(config)

        assertEquals(true, module.proxyEnabled)
        assertEquals("SOCKS", module.proxyType)
        assertEquals("proxy.example.com", module.proxyHost)
        assertEquals(1080, module.proxyPort)
        assertEquals("user", module.proxyUsername)
        assertEquals("pass", module.proxyPassword)
        assertEquals(5, module.rateLimitMultiplier)
    }

    @Test
    @DisplayName("NetworkConfigModule user config overrides defaults")
    fun testConfigOverride() {
        val defaults = ConfigFactory.parseResources("server-reference.conf")
        val overrides = ConfigFactory.parseString("""
            server.network {
                proxyEnabled = true
                proxyHost = "myproxy.local"
                proxyPort = 8080
                rateLimitMultiplier = 3
            }
        """)
        val merged = overrides.withFallback(defaults).resolve()
        val module = NetworkConfigModule.register(merged)

        assertEquals(true, module.proxyEnabled)
        assertEquals("HTTP", module.proxyType) // default preserved
        assertEquals("myproxy.local", module.proxyHost)
        assertEquals(8080, module.proxyPort)
        assertEquals("", module.proxyUsername) // default preserved
        assertEquals(3, module.rateLimitMultiplier)
    }

    @Test
    @DisplayName("Default OkHttpClient has no proxy when proxy is disabled")
    fun testNoProxyByDefault() {
        // The default config has proxyEnabled=false, so client should have no proxy
        val proxy = networkHelper.client.proxy
        // proxy is null when no proxy is configured (system default)
        assertNull(proxy, "Client should not have a proxy when proxy is disabled")
    }
}

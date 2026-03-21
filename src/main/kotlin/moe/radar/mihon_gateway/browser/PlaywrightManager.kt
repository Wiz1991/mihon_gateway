package moe.radar.mihon_gateway.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType.LaunchOptions
import com.microsoft.playwright.Playwright
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.Cookie

/**
 * Singleton managing the Chromium lifecycle for Layer 3 (Playwright fallback).
 *
 * Browser is launched lazily on first use — no startup cost if no source needs it.
 * BrowserContexts are lightweight (~5-20MB) and isolated per session.
 */
object PlaywrightManager {
    private val logger = KotlinLogging.logger {}

    @Volatile
    private var playwrightInstance: Playwright? = null

    @Volatile
    private var browserInstance: Browser? = null

    private fun getPlaywright(): Playwright {
        return playwrightInstance ?: synchronized(this) {
            playwrightInstance ?: run {
                logger.info { "Initializing Playwright..." }
                Playwright.create().also { playwrightInstance = it }
            }
        }
    }

    private fun getBrowser(): Browser {
        return browserInstance ?: synchronized(this) {
            browserInstance ?: run {
                logger.info { "Launching headless Chromium..." }
                getPlaywright().chromium().launch(
                    LaunchOptions()
                        .setHeadless(true)
                        .setArgs(listOf("--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage"))
                ).also { browserInstance = it }
            }
        }
    }

    fun createContext(cookies: List<Cookie> = emptyList()): BrowserContext {
        val ctx = getBrowser().newContext()
        if (cookies.isNotEmpty()) {
            ctx.addCookies(cookies.map { cookie ->
                com.microsoft.playwright.options.Cookie(cookie.name, cookie.value)
                    .setDomain(cookie.domain)
                    .setPath(cookie.path)
                    .setSecure(cookie.secure)
                    .setHttpOnly(cookie.httpOnly)
                    .apply {
                        if (cookie.expiresAt < Long.MAX_VALUE) {
                            setExpires((cookie.expiresAt / 1000).toDouble())
                        }
                    }
            })
        }
        return ctx
    }

    fun shutdown() {
        synchronized(this) {
            try {
                browserInstance?.close()
                browserInstance = null
                playwrightInstance?.close()
                playwrightInstance = null
                logger.info { "Playwright shut down" }
            } catch (e: Exception) {
                logger.warn(e) { "Error shutting down Playwright" }
            }
        }
    }
}

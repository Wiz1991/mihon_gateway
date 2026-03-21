package xyz.nulldev.androidcompat

import android.os.Looper
import android.webkit.WebView
import io.github.oshai.kotlinlogging.KotlinLogging
import xyz.nulldev.androidcompat.config.ApplicationInfoConfigModule
import xyz.nulldev.androidcompat.config.FilesConfigModule
import xyz.nulldev.androidcompat.config.SystemConfigModule
import xyz.nulldev.androidcompat.webkit.HeadlessWebViewProvider
import xyz.nulldev.ts.config.GlobalConfigManager
import java.util.concurrent.CountDownLatch

/**
 * Initializes the Android compatibility module
 */
class AndroidCompatInitializer {
    private val logger = KotlinLogging.logger {}

    fun init() {
        // Register config modules
        GlobalConfigManager.registerModules(
            FilesConfigModule.register(GlobalConfigManager.config),
            ApplicationInfoConfigModule.register(GlobalConfigManager.config),
            SystemConfigModule.register(GlobalConfigManager.config),
        )

        // Start the main Looper on a daemon thread — required for WebView and Handler usage
        initMainLooper()

        // Register headless WebView provider factory
        WebView.setProviderFactory { webView -> HeadlessWebViewProvider(webView) }
        logger.info { "HeadlessWebViewProvider registered" }

        // Set some properties extensions use
        System.setProperty(
            "http.agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
        )
    }

    private fun initMainLooper() {
        if (Looper.getMainLooper() != null) {
            logger.debug { "Main Looper already initialized" }
            return
        }

        val latch = CountDownLatch(1)
        val looperThread = Thread({
            Looper.prepareMainLooper()
            latch.countDown()
            Looper.loop()
        }, "MainLooper")
        looperThread.isDaemon = true
        looperThread.start()

        latch.await()
        logger.info { "Main Looper started on daemon thread" }
    }
}

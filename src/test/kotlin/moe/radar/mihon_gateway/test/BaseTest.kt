package moe.radar.mihon_gateway.test

import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.createAppModule
import io.github.oshai.kotlinlogging.KotlinLogging
import moe.radar.mihon_gateway.ApplicationDirs
import moe.radar.mihon_gateway.config.NetworkConfigModule
import org.junit.jupiter.api.BeforeAll
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import xyz.nulldev.androidcompat.AndroidCompat
import xyz.nulldev.androidcompat.AndroidCompatInitializer
import xyz.nulldev.androidcompat.androidCompatModule
import xyz.nulldev.ts.config.GlobalConfigManager
import xyz.nulldev.ts.config.configManagerModule
import java.io.File

/**
 * Base test class providing application setup for tests.
 * Similar to Suwayomi's ApplicationTest but for stateless gRPC service.
 */
abstract class BaseTest : KoinTest {
    companion object {
        private val logger = KotlinLogging.logger {}
        private var initialized = false

        const val TEST_DATA_ROOT = "build/test-data"

        @BeforeAll
        @JvmStatic
        fun setupBase() {
            if (!initialized) {
                initializeApplication()
                initialized = true
            }
        }

        private fun initializeApplication() {
            logger.info { "Initializing test application..." }

            // Create test directories
            val testDirs = ApplicationDirs(
                dataRoot = "$TEST_DATA_ROOT/data",
                tempRoot = "$TEST_DATA_ROOT/temp"
            )

            listOf(
                testDirs.dataRoot,
                testDirs.extensionsRoot,
                testDirs.tempRoot,
                testDirs.tempThumbnailCacheRoot,
                testDirs.tempMangaCacheRoot
            ).forEach {
                File(it).mkdirs()
            }

            // Initialize Koin (exactly like Suwayomi)
            try {
                stopKoin() // Stop any existing instance
            } catch (e: Exception) {
                // Ignore if not started
            }

            val app = App()
            startKoin {
                modules(
                    createAppModule(app),
                    androidCompatModule(),
                    configManagerModule(),
                    module {
                        single { testDirs }
                    }
                )
            }

            // Initialize AndroidCompat (exactly like Suwayomi)
            AndroidCompatInitializer().init()

            // Register network config module (proxy + rate limit multiplier)
            GlobalConfigManager.registerModules(
                NetworkConfigModule.register(GlobalConfigManager.config),
            )

            val androidCompat = AndroidCompat()
            androidCompat.startApp(app)

            logger.info { "Test application initialized successfully" }
        }

        /**
         * Clean up test data directory
         */
        fun cleanupTestData() {
            File(TEST_DATA_ROOT).deleteRecursively()
        }
    }
}

package moe.radar.mihon_gateway

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.cors.CorsService
import com.linecorp.armeria.server.grpc.GrpcService
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.createAppModule
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.protobuf.services.ProtoReflectionService
import kotlinx.coroutines.runBlocking
import moe.radar.mihon_gateway.browser.PlaywrightManager
import moe.radar.mihon_gateway.extension.ExtensionManager
import moe.radar.mihon_gateway.service.ImageProxyService
import moe.radar.mihon_gateway.service.MangaSourceServiceImpl
import org.koin.core.context.startKoin
import org.koin.dsl.module
import moe.radar.mihon_gateway.config.NetworkConfigModule
import xyz.nulldev.androidcompat.AndroidCompat
import xyz.nulldev.androidcompat.AndroidCompatInitializer
import xyz.nulldev.androidcompat.androidCompatModule
import xyz.nulldev.ts.config.GlobalConfigManager
import xyz.nulldev.ts.config.configManagerModule
import java.io.File
import kotlin.system.exitProcess

/**
 * Main entry point for the stateless Mihon gRPC service.
 *
 * Usage:
 *   ./gradlew run
 *   ./gradlew run --args="--port 50051"
 */
object Main {
    private val logger = KotlinLogging.logger {}
    private const val DEFAULT_PORT = 50051

    @JvmStatic
    fun main(args: Array<String>) {
        // Parse command line arguments
        val port = parsePort(args)

        logger.info { "Starting Mihon gRPC Service on port $port..." }

        // Initialize Koin dependency injection (exactly like Suwayomi)
        val app = App()
        startKoin {
            modules(
                createAppModule(app),
                androidCompatModule(),
                configManagerModule(),
                module {
                    single { ApplicationDirs.instance }
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

        // Initialize application directories
        val appDirs = ApplicationDirs.instance
        logger.info { "Extensions directory: ${appDirs.extensionsRoot}" }
        logger.info { "Temp directory: ${appDirs.tempRoot}" }

        // Pre-fetch extension list from GitHub and load installed extensions
        runBlocking {
            try {
                logger.info { "Fetching extension list from GitHub..." }
                ExtensionManager.fetchExtensionsFromGitHub()
                logger.info { "Extension list fetched successfully" }

                // Load previously installed extensions from disk
                ExtensionManager.loadInstalledExtensionsFromDisk()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to fetch extension list on startup (will retry on first request)" }
            }
        }

        // Build gRPC service with gRPC-Web support
        val grpcService = GrpcService.builder()
            .addService(MangaSourceServiceImpl())
            .addService(ProtoReflectionService.newInstance())
            .enableUnframedRequests(true)
            .build()

        // Build Armeria server with CORS for browser clients
        val server = Server.builder()
            .http(port)
            .service(grpcService)
            .service("/proxy/:sourceId", ImageProxyService())
            .decorator(
                CorsService.builderForAnyOrigin()
                    .allowRequestMethods(HttpMethod.POST, HttpMethod.GET, HttpMethod.OPTIONS)
                    .allowRequestHeaders(
                        "content-type", "x-grpc-web", "x-user-agent", "grpc-timeout",
                        "keep-alive", "user-agent", "cache-control", "content-transfer-encoding"
                    )
                    .exposeHeaders(
                        "grpc-status", "grpc-message", "grpc-encoding", "grpc-accept-encoding"
                    )
                    .newDecorator()
            )
            .build()

        server.closeOnJvmShutdown {
            logger.info { "Shutting down gRPC server..." }
            PlaywrightManager.shutdown()
        }.thenRun {
            logger.info { "Server shut down successfully" }
        }

        // Start the server
        server.start().join()
        logger.info { "✓ Mihon gRPC Service started on port $port (gRPC + gRPC-Web)" }
        logger.info { "Ready to accept connections" }

        // Wait for termination
        server.whenClosed().join()
    }

    /**
     * Parse port from command line arguments.
     * Supports: --port 50051
     */
    private fun parsePort(args: Array<String>): Int {
        var port = DEFAULT_PORT

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--port", "-p" -> {
                    if (i + 1 < args.size) {
                        port = args[i + 1].toIntOrNull() ?: DEFAULT_PORT
                        i++
                    }
                }
                "--help", "-h" -> {
                    printHelp()
                    exitProcess(0)
                }
            }
            i++
        }

        return port
    }

    private fun printHelp() {
        println("""
            Mihon gRPC Service - Stateless manga source service

            Usage:
              ./gradlew run [options]

            Options:
              --port, -p <port>    Server port (default: $DEFAULT_PORT)
              --help, -h           Show this help message

            Examples:
              ./gradlew run
              ./gradlew run --args="--port 50051"

            Data directories:
              Extensions: ~/.mihon-grpc/extensions
              Temp:       ~/.mihon-grpc/temp

            Features:
              • Stateless operation (no database)
              • URL-based manga identification
              • Auto-updating extension list from GitHub
              • Per-source rate limiting
              • Streaming search/browse results
        """.trimIndent())
    }
}

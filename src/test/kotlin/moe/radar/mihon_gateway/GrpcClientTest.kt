package moe.radar.mihon_gateway

import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import moe.radar.mihon_gateway.proto.ListExtensionsRequest
import moe.radar.mihon_gateway.proto.MangaSourceServiceGrpcKt
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import kotlin.test.assertTrue

/**
 * Simple integration test to verify the gRPC service is working.
 * Assumes server is running on port 50052.
 *
 * NOTE: This test requires a running server and is disabled by default.
 * To run manually: ./gradlew run --args="--port 50052" (in separate terminal)
 * Then: ./gradlew test --tests GrpcClientTest
 */
class GrpcClientTest {
    @Test
    @Disabled("Requires manually starting server on port 50052")
    fun `test ListExtensions RPC call`() = runBlocking {
        // Create gRPC channel using NettyChannelBuilder directly
        val channel = NettyChannelBuilder
            .forAddress("localhost", 50052)
            .usePlaintext()
            .build()

        try {
            // Create stub
            val stub = MangaSourceServiceGrpcKt.MangaSourceServiceCoroutineStub(channel)

            // Call ListExtensions
            val request = ListExtensionsRequest.getDefaultInstance()
            val response = stub.listExtensions(request)

            // Verify we got extensions back
            println("Received ${response.extensionsCount} extensions")
            assertTrue(response.extensionsCount > 0, "Expected at least some extensions")

            // Print first few extensions
            response.extensionsList.take(5).forEach { ext ->
                println("  - ${ext.name} (${ext.pkgName}) - installed: ${ext.isInstalled}")
            }

            println("✓ gRPC service is working correctly!")
        } finally {
            channel.shutdown()
        }
    }
}

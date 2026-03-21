package eu.kanade.tachiyomi.network.interceptor

import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class CloudflareInterceptor(
    private val setUserAgent: (String) -> Unit,
) : Interceptor {
    private val logger = KotlinLogging.logger {}

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        logger.trace { "CloudflareInterceptor is being used." }

        val originalResponse = chain.proceed(originalRequest)

        // Check if Cloudflare anti-bot is on
        if (!(originalResponse.code in ERROR_CODES && originalResponse.header("Server") in SERVER_CHECK)) {
            return originalResponse
        }

        // In stateless gRPC service, Cloudflare bypass is not supported
        // Client should handle this or use a separate bypass service
        originalResponse.close()
        throw IOException("Cloudflare protection detected - bypass not supported in stateless mode")
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
    }
}

package moe.radar.mihon_gateway.telemetry

import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp interceptor that:
 * 1. Creates an OTLP span for each HTTP request (child of current context)
 * 2. Logs request + response on a single line (no multi-line splits)
 * 3. Records HTTP attributes on the span for filtering in tracing UI
 *
 * Add as a regular interceptor (not network) to capture retries/redirects as separate spans.
 */
class TracingInterceptor : Interceptor {
    private val logger = KotlinLogging.logger {}

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val method = request.method
        val url = request.url
        val spanName = "HTTP $method ${url.host}${url.encodedPath}"

        val span = Telemetry.tracer.spanBuilder(spanName)
            .setSpanKind(SpanKind.CLIENT)
            .setParent(Context.current())
            .setAttribute("http.request.method", method)
            .setAttribute("url.full", url.toString())
            .setAttribute("server.address", url.host)
            .setAttribute("server.port", url.port.toLong())
            .setAttribute("url.path", url.encodedPath)
            .startSpan()

        return span.makeCurrent().use { _ ->
            Telemetry.setMdcFromCurrentSpan()
            val startNs = System.nanoTime()
            try {
                val response = chain.proceed(request)
                val durationMs = (System.nanoTime() - startNs) / 1_000_000

                span.setAttribute("http.response.status_code", response.code.toLong())

                if (response.code >= 400) {
                    span.setStatus(StatusCode.ERROR, "HTTP ${response.code}")
                }

                logger.info {
                    "http_request method=$method url=$url status=${response.code} duration_ms=$durationMs"
                }

                response
            } catch (e: IOException) {
                val durationMs = (System.nanoTime() - startNs) / 1_000_000
                span.setStatus(StatusCode.ERROR, e.message ?: "io_error")
                span.recordException(e)

                logger.warn {
                    "http_request method=$method url=$url error=${e.javaClass.simpleName}:${e.message} duration_ms=$durationMs"
                }
                throw e
            } finally {
                span.end()
            }
        }
    }
}

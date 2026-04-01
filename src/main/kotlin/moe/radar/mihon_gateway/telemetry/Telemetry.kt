package moe.radar.mihon_gateway.telemetry

import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import org.slf4j.MDC

/**
 * Central OpenTelemetry setup.
 *
 * Configured via standard OTEL env vars:
 *   OTEL_SERVICE_NAME=mihon-gateway
 *   OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
 *   OTEL_TRACES_EXPORTER=otlp        (default)
 *   OTEL_METRICS_EXPORTER=none        (disable if not needed)
 *   OTEL_LOGS_EXPORTER=none           (we use logback directly)
 */
object Telemetry {
    private val logger = KotlinLogging.logger {}

    lateinit var openTelemetry: OpenTelemetry
        private set

    lateinit var tracer: Tracer
        private set

    fun init() {
        openTelemetry = AutoConfiguredOpenTelemetrySdk.initialize()
            .openTelemetrySdk

        tracer = openTelemetry.getTracer("mihon-gateway")

        logger.info { "OpenTelemetry initialized" }
    }

    /**
     * Inject current span's traceId/spanId into SLF4J MDC so logback
     * includes them in every JSON log line.
     */
    fun setMdcFromCurrentSpan() {
        val span = Span.current()
        val ctx = span.spanContext
        if (ctx.isValid) {
            MDC.put("traceId", ctx.traceId)
            MDC.put("spanId", ctx.spanId)
            MDC.put("traceFlags", ctx.traceFlags.asHex())
        }
    }

    fun clearMdc() {
        MDC.remove("traceId")
        MDC.remove("spanId")
        MDC.remove("traceFlags")
        MDC.remove("sourceId")
        MDC.remove("sourceName")
    }
}

/**
 * Run [block] inside a new span, propagating the current context.
 * Sets MDC for structured logging and records exceptions.
 */
inline fun <T> withSpan(
    name: String,
    kind: SpanKind = SpanKind.INTERNAL,
    crossinline configure: io.opentelemetry.api.trace.SpanBuilder.() -> io.opentelemetry.api.trace.SpanBuilder = { this },
    block: (Span) -> T,
): T {
    val span = Telemetry.tracer.spanBuilder(name)
        .setSpanKind(kind)
        .setParent(Context.current())
        .configure()
        .startSpan()

    return span.makeCurrent().use { scope ->
        Telemetry.setMdcFromCurrentSpan()
        try {
            block(span)
        } catch (e: Throwable) {
            span.setStatus(StatusCode.ERROR, e.message ?: "error")
            span.recordException(e)
            throw e
        } finally {
            span.end()
        }
    }
}

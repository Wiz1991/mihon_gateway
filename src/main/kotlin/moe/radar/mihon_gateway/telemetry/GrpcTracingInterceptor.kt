package moe.radar.mihon_gateway.telemetry

import io.grpc.Context as GrpcContext
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter

/**
 * gRPC server interceptor that:
 * 1. Extracts W3C trace context (traceparent) from incoming gRPC metadata
 * 2. Creates a server span for each RPC call
 * 3. Propagates the OTEL context so child spans (source calls, HTTP) are linked
 *
 * This enables distributed tracing when the caller sends traceparent headers.
 */
class GrpcTracingInterceptor : ServerInterceptor {

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        // Extract trace context from gRPC metadata (W3C traceparent/tracestate)
        val extractedContext = Telemetry.openTelemetry.propagators
            .textMapPropagator
            .extract(Context.current(), headers, MetadataGetter)

        val methodName = call.methodDescriptor.bareMethodName ?: call.methodDescriptor.fullMethodName
        val serviceName = call.methodDescriptor.serviceName ?: "unknown"

        val span = Telemetry.tracer.spanBuilder("grpc $serviceName/$methodName")
            .setSpanKind(SpanKind.SERVER)
            .setParent(extractedContext)
            .setAttribute("rpc.system", "grpc")
            .setAttribute("rpc.service", serviceName)
            .setAttribute("rpc.method", methodName)
            .startSpan()

        val otelContext = extractedContext.with(span)

        // Store OTEL context in gRPC context so coroutine handlers can access it
        val grpcContext = GrpcContext.current().withValue(OTEL_CONTEXT_KEY, otelContext)

        // Wrap the server call to capture status on close
        val wrappedCall = object : io.grpc.ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            override fun close(status: Status, trailers: Metadata) {
                span.setAttribute("rpc.grpc.status_code", status.code.value().toLong())
                if (!status.isOk) {
                    span.setStatus(StatusCode.ERROR, status.description ?: status.code.name)
                }
                span.end()
                super.close(status, trailers)
            }
        }

        return Contexts.interceptCall(grpcContext, wrappedCall, headers, next)
    }

    companion object {
        /** Key to store OTEL context in gRPC context for downstream access */
        val OTEL_CONTEXT_KEY: GrpcContext.Key<Context> =
            GrpcContext.key("otel-context")
    }
}

/**
 * TextMapGetter for gRPC Metadata - reads propagation headers.
 */
private object MetadataGetter : TextMapGetter<Metadata> {
    override fun keys(carrier: Metadata): Iterable<String> =
        carrier.keys()

    override fun get(carrier: Metadata, key: String): String? =
        carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER))
}

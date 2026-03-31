package moe.radar.mihon_gateway.service

import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import eu.kanade.tachiyomi.network.HttpException

/**
 * gRPC server interceptor that catches unhandled exceptions and maps them
 * to proper gRPC status codes with structured error codes in metadata.
 *
 * Without this, any uncaught exception becomes Status.UNKNOWN with no message,
 * making it impossible for clients to handle errors gracefully.
 */
class GrpcExceptionInterceptor : ServerInterceptor {
    private val logger = KotlinLogging.logger {}

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val listener = next.startCall(call, headers)

        return object : SimpleForwardingServerCallListener<ReqT>(listener) {
            override fun onHalfClose() {
                try {
                    super.onHalfClose()
                } catch (e: StatusException) {
                    // Already a proper gRPC status — pass through
                    closeWithException(call, e.status, e.trailers ?: Metadata(), e)
                } catch (e: StatusRuntimeException) {
                    closeWithException(call, e.status, e.trailers ?: Metadata(), e)
                } catch (e: Exception) {
                    val (status, metadata) = mapException(e, call.methodDescriptor.fullMethodName)
                    closeWithException(call, status, metadata, e)
                }
            }

            private fun <ReqT, RespT> closeWithException(
                call: ServerCall<ReqT, RespT>,
                status: Status,
                metadata: Metadata,
                cause: Exception,
            ) {
                if (status.code != Status.Code.OK) {
                    val level = if (status.code == Status.Code.INTERNAL) "error" else "warn"
                    logger.atLevel(
                        if (status.code == Status.Code.INTERNAL)
                            io.github.oshai.kotlinlogging.Level.ERROR
                        else
                            io.github.oshai.kotlinlogging.Level.WARN
                    ) {
                        this.message = "${call.methodDescriptor.fullMethodName} failed: ${status.code} - ${status.description}"
                        this.cause = if (status.code == Status.Code.INTERNAL) cause else null
                    }
                }
                call.close(status, metadata)
            }
        }
    }

    /**
     * Map common exception types to appropriate gRPC status codes.
     */
    private fun mapException(e: Exception, methodName: String): Pair<Status, Metadata> {
        val metadata = Metadata()

        return when (e) {
            is IllegalArgumentException -> {
                val (errorCode, status) = classifyIllegalArgument(e.message)
                metadata.put(ERROR_CODE_KEY, errorCode.code)
                Pair(
                    status.withDescription("[${errorCode}] ${e.message}"),
                    metadata
                )
            }

            is HttpException -> {
                metadata.put(ERROR_CODE_KEY, ErrorCode.HTTP_ERROR.code)
                Pair(
                    mapHttpCode(e.code).withDescription("[${ErrorCode.HTTP_ERROR}] HTTP error ${e.code}"),
                    metadata
                )
            }

            is java.net.SocketTimeoutException -> {
                metadata.put(ERROR_CODE_KEY, ErrorCode.UPSTREAM_FAILED.code)
                Pair(
                    Status.UNAVAILABLE.withDescription("[${ErrorCode.UPSTREAM_FAILED}] Request timed out: ${e.message}"),
                    metadata
                )
            }

            is java.net.UnknownHostException -> {
                metadata.put(ERROR_CODE_KEY, ErrorCode.UPSTREAM_FAILED.code)
                Pair(
                    Status.UNAVAILABLE.withDescription("[${ErrorCode.UPSTREAM_FAILED}] DNS resolution failed: ${e.message}"),
                    metadata
                )
            }

            is java.net.ConnectException -> {
                metadata.put(ERROR_CODE_KEY, ErrorCode.UPSTREAM_FAILED.code)
                Pair(
                    Status.UNAVAILABLE.withDescription("[${ErrorCode.UPSTREAM_FAILED}] Connection failed: ${e.message}"),
                    metadata
                )
            }

            is java.io.IOException -> {
                metadata.put(ERROR_CODE_KEY, ErrorCode.UPSTREAM_FAILED.code)
                Pair(
                    Status.UNAVAILABLE.withDescription("[${ErrorCode.UPSTREAM_FAILED}] I/O error: ${e.message}"),
                    metadata
                )
            }

            else -> {
                metadata.put(ERROR_CODE_KEY, ErrorCode.INTERNAL_ERROR.code)
                Pair(
                    Status.INTERNAL.withDescription("[${ErrorCode.INTERNAL_ERROR}] ${e.message}"),
                    metadata
                )
            }
        }
    }

    /**
     * Classify IllegalArgumentException based on message content
     * to give more specific error codes.
     */
    private fun classifyIllegalArgument(message: String?): Pair<ErrorCode, Status> {
        val msg = message?.lowercase() ?: return Pair(ErrorCode.INVALID_ARGUMENT, Status.INVALID_ARGUMENT)

        return when {
            msg.contains("source") && msg.contains("not found") ->
                Pair(ErrorCode.SOURCE_NOT_FOUND, Status.NOT_FOUND)
            msg.contains("extension") && msg.contains("not found") ->
                Pair(ErrorCode.EXTENSION_NOT_FOUND, Status.NOT_FOUND)
            msg.contains("extension") && msg.contains("not installed") ->
                Pair(ErrorCode.EXTENSION_NOT_INSTALLED, Status.FAILED_PRECONDITION)
            msg.contains("not a tachiyomi extension") ->
                Pair(ErrorCode.EXTENSION_INVALID, Status.INVALID_ARGUMENT)
            msg.contains("lib version") ->
                Pair(ErrorCode.EXTENSION_INCOMPATIBLE, Status.FAILED_PRECONDITION)
            msg.contains("no repository url") || msg.contains("has no repository") ->
                Pair(ErrorCode.EXTENSION_NOT_FOUND, Status.FAILED_PRECONDITION)
            msg.contains("has no class name") ->
                Pair(ErrorCode.EXTENSION_INVALID, Status.INTERNAL)
            else ->
                Pair(ErrorCode.INVALID_ARGUMENT, Status.INVALID_ARGUMENT)
        }
    }

    /**
     * Map HTTP status codes to gRPC status codes.
     */
    private fun mapHttpCode(httpCode: Int): Status {
        return when (httpCode) {
            400 -> Status.INVALID_ARGUMENT
            401, 403 -> Status.PERMISSION_DENIED
            404 -> Status.NOT_FOUND
            408 -> Status.DEADLINE_EXCEEDED
            429 -> Status.RESOURCE_EXHAUSTED
            in 500..599 -> Status.UNAVAILABLE
            else -> Status.INTERNAL
        }
    }
}

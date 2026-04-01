package moe.radar.mihon_gateway.service

import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException

/**
 * Structured error codes for gRPC responses.
 * Clients can match on [code] for programmatic error handling,
 * while [message] provides a human-readable description.
 *
 * Error codes are conveyed in gRPC trailing metadata under the key "error-code".
 */
enum class ErrorCode(val code: String) {
    // Source errors
    SOURCE_NOT_FOUND("SOURCE_NOT_FOUND"),
    SOURCE_NOT_CONFIGURABLE("SOURCE_NOT_CONFIGURABLE"),
    SOURCE_NOT_HTTP("SOURCE_NOT_HTTP"),
    SOURCE_LATEST_NOT_SUPPORTED("SOURCE_LATEST_NOT_SUPPORTED"),

    // Extension errors
    EXTENSION_NOT_FOUND("EXTENSION_NOT_FOUND"),
    EXTENSION_NOT_INSTALLED("EXTENSION_NOT_INSTALLED"),
    EXTENSION_INSTALL_FAILED("EXTENSION_INSTALL_FAILED"),
    EXTENSION_INCOMPATIBLE("EXTENSION_INCOMPATIBLE"),
    EXTENSION_INVALID("EXTENSION_INVALID"),

    // Preference errors
    PREFERENCE_NOT_FOUND("PREFERENCE_NOT_FOUND"),
    PREFERENCE_TYPE_MISMATCH("PREFERENCE_TYPE_MISMATCH"),
    PREFERENCE_VALUE_INVALID("PREFERENCE_VALUE_INVALID"),
    PREFERENCE_VALUE_REJECTED("PREFERENCE_VALUE_REJECTED"),
    PREFERENCE_NO_VALUE("PREFERENCE_NO_VALUE"),

    // Request errors
    INVALID_URL("INVALID_URL"),
    INVALID_ARGUMENT("INVALID_ARGUMENT"),

    // Upstream / network errors
    HTTP_ERROR("HTTP_ERROR"),
    UPSTREAM_FAILED("UPSTREAM_FAILED"),
    FETCH_FAILED("FETCH_FAILED"),

    // Content errors
    MANGA_LICENSED("MANGA_LICENSED"),

    // Internal
    INTERNAL_ERROR("INTERNAL_ERROR"),
}

/**
 * Metadata key for structured error codes.
 * Clients read this from gRPC trailing metadata.
 */
val ERROR_CODE_KEY: Metadata.Key<String> =
    Metadata.Key.of("error-code", Metadata.ASCII_STRING_MARSHALLER)

/**
 * Metadata key for the upstream HTTP status code (when the source site returns an error).
 * Only present when error-code is HTTP_ERROR.
 */
val HTTP_STATUS_KEY: Metadata.Key<String> =
    Metadata.Key.of("http-status", Metadata.ASCII_STRING_MARSHALLER)

/**
 * Build a [StatusException] with a structured [ErrorCode] in metadata.
 */
fun grpcError(
    status: Status,
    errorCode: ErrorCode,
    message: String,
): StatusException {
    val metadata = Metadata()
    metadata.put(ERROR_CODE_KEY, errorCode.code)
    return status.withDescription("[${errorCode.code}] $message").asException(metadata)
}

/**
 * Build a [StatusException] for an upstream HTTP error, including the raw HTTP status code
 * in metadata so clients can handle specific codes (403, 404, 429, etc.).
 */
fun grpcHttpError(
    httpCode: Int,
    message: String,
): StatusException {
    val metadata = Metadata()
    metadata.put(ERROR_CODE_KEY, ErrorCode.HTTP_ERROR.code)
    metadata.put(HTTP_STATUS_KEY, httpCode.toString())
    val grpcStatus = mapHttpCodeToGrpcStatus(httpCode)
    return grpcStatus.withDescription("[${ErrorCode.HTTP_ERROR.code}] HTTP $httpCode: $message").asException(metadata)
}

/**
 * Map HTTP status codes to appropriate gRPC status codes.
 */
fun mapHttpCodeToGrpcStatus(httpCode: Int): Status = when (httpCode) {
    400 -> Status.INVALID_ARGUMENT
    401, 403 -> Status.PERMISSION_DENIED
    404 -> Status.NOT_FOUND
    408 -> Status.DEADLINE_EXCEEDED
    429 -> Status.RESOURCE_EXHAUSTED
    in 500..599 -> Status.UNAVAILABLE
    else -> Status.INTERNAL
}

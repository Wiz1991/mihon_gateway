package moe.radar.mihon_gateway.service

import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.protobuf.ProtoUtils

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
 * Build a [StatusException] with a structured [ErrorCode] in metadata.
 */
fun grpcError(
    status: Status,
    errorCode: ErrorCode,
    message: String,
): StatusException {
    val metadata = Metadata()
    metadata.put(ERROR_CODE_KEY, errorCode.code)
    return status.withDescription("[$errorCode] $message").asException(metadata)
}

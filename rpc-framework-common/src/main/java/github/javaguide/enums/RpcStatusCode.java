package github.javaguide.enums;

import java.util.Arrays;

/**
 * Stable status codes carried by every RPC response.
 *
 * <p>The numeric values follow the canonical RPC status model used by gRPC. Applications may
 * attach a safe, more specific message, but must not change the meaning of an existing code.
 */
public enum RpcStatusCode {

    OK(0, "OK"),
    CANCELLED(1, "Request cancelled"),
    UNKNOWN(2, "Unknown error"),
    INVALID_ARGUMENT(3, "Invalid argument"),
    DEADLINE_EXCEEDED(4, "Deadline exceeded"),
    NOT_FOUND(5, "Service or resource not found"),
    ALREADY_EXISTS(6, "Resource already exists"),
    PERMISSION_DENIED(7, "Permission denied"),
    RESOURCE_EXHAUSTED(8, "Resource exhausted"),
    FAILED_PRECONDITION(9, "Failed precondition"),
    ABORTED(10, "Operation aborted"),
    OUT_OF_RANGE(11, "Value out of range"),
    UNIMPLEMENTED(12, "Operation is not implemented"),
    INTERNAL(13, "Internal server error"),
    UNAVAILABLE(14, "Service unavailable"),
    DATA_LOSS(15, "Unrecoverable data loss"),
    UNAUTHENTICATED(16, "Unauthenticated");

    private final int code;
    private final String message;

    RpcStatusCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public static RpcStatusCode fromCode(int code) {
        return Arrays.stream(values())
                .filter(statusCode -> statusCode.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown RPC status code: " + code));
    }
}

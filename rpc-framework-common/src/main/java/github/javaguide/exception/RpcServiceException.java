package github.javaguide.exception;

import github.javaguide.enums.RpcStatusCode;

/**
 * An expected service-side failure whose status and message are safe to return to RPC clients.
 */
public final class RpcServiceException extends RpcException {

    public RpcServiceException(RpcStatusCode statusCode) {
        this(statusCode, statusCode.getMessage(), null);
    }

    public RpcServiceException(RpcStatusCode statusCode, String message) {
        this(statusCode, message, null);
    }

    public RpcServiceException(RpcStatusCode statusCode, String message, Throwable cause) {
        super(statusCode, null, message, cause);
    }
}

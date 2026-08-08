package github.javaguide.remoting.handler;

import github.javaguide.enums.RpcStatusCode;
import github.javaguide.exception.RpcException;
import github.javaguide.exception.RpcServiceException;
import github.javaguide.remoting.dto.RpcResponse;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Converts service and framework failures into the stable RPC response contract. */
public final class RpcResponseFactory {

    private RpcResponseFactory() {
    }

    public static RpcResponse<Object> failure(String requestId, Throwable throwable) {
        Throwable cause = unwrap(throwable);
        RpcStatusCode statusCode = statusOf(cause);
        String message = safeMessage(cause, statusCode);
        return RpcResponse.fail(statusCode, requestId, message);
    }

    public static RpcStatusCode statusOf(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof RpcException rpcException) {
            return rpcException.getStatusCode();
        }
        if (cause instanceof CancellationException) {
            return RpcStatusCode.CANCELLED;
        }
        if (cause instanceof TimeoutException) {
            return RpcStatusCode.DEADLINE_EXCEEDED;
        }
        return RpcStatusCode.INTERNAL;
    }

    public static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeMessage(Throwable cause, RpcStatusCode statusCode) {
        if (cause instanceof RpcServiceException && hasMessage(cause)) {
            return cause.getMessage();
        }
        if (cause instanceof RpcException
                && statusCode != RpcStatusCode.INTERNAL
                && statusCode != RpcStatusCode.UNKNOWN
                && hasMessage(cause)) {
            return cause.getMessage();
        }
        return statusCode.getMessage();
    }

    private static boolean hasMessage(Throwable throwable) {
        return throwable.getMessage() != null && !throwable.getMessage().isBlank();
    }
}

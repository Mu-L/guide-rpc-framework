package github.javaguide.exception;

import github.javaguide.enums.RpcErrorMessageEnum;
import github.javaguide.enums.RpcStatusCode;

/**
 * @author shuang.kou
 * @createTime 2020年05月12日 16:48:00
 */
public class RpcException extends RuntimeException {
    private final RpcStatusCode statusCode;
    private final String requestId;

    public RpcException(String message) {
        this(RpcStatusCode.UNKNOWN, null, message, null);
    }

    public RpcException(RpcErrorMessageEnum rpcErrorMessageEnum, String detail) {
        this(RpcStatusCode.UNKNOWN, null,
                rpcErrorMessageEnum.getMessage() + ":" + detail, null);
    }

    public RpcException(String message, Throwable cause) {
        this(RpcStatusCode.UNKNOWN, null, message, cause);
    }

    public RpcException(RpcErrorMessageEnum rpcErrorMessageEnum) {
        this(RpcStatusCode.UNKNOWN, null, rpcErrorMessageEnum.getMessage(), null);
    }

    public RpcException(RpcStatusCode statusCode, String message) {
        this(statusCode, null, message, null);
    }

    public RpcException(RpcStatusCode statusCode, String message, Throwable cause) {
        this(statusCode, null, message, cause);
    }

    public RpcException(RpcStatusCode statusCode, String requestId,
                        String message, Throwable cause) {
        super(message, cause);
        if (statusCode == null || statusCode == RpcStatusCode.OK) {
            throw new IllegalArgumentException("RPC exception status must describe a failure");
        }
        this.statusCode = statusCode;
        this.requestId = requestId;
    }

    public RpcStatusCode getStatusCode() {
        return statusCode;
    }

    public String getRequestId() {
        return requestId;
    }
}

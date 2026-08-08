package github.javaguide.exception;

import github.javaguide.enums.RpcStatusCode;

/** A structured failure returned by the remote RPC server. */
public final class RpcRemoteException extends RpcException {

    public RpcRemoteException(RpcStatusCode statusCode, String requestId, String message) {
        super(statusCode, requestId, message, null);
    }
}

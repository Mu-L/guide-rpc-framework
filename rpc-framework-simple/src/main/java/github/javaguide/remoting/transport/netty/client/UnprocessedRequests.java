package github.javaguide.remoting.transport.netty.client;

import github.javaguide.remoting.dto.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * unprocessed requests by the server.
 *
 * @author shuang.kou
 * @createTime 2020年06月04日 17:30:00
 */
@Slf4j
public class UnprocessedRequests {
    private final Map<String, CompletableFuture<RpcResponse<Object>>> unprocessedResponseFutures =
            new ConcurrentHashMap<>();

    public void put(String requestId, CompletableFuture<RpcResponse<Object>> future) {
        CompletableFuture<RpcResponse<Object>> previous =
                unprocessedResponseFutures.putIfAbsent(requestId, future);
        if (previous != null) {
            throw new IllegalStateException("Duplicate RPC request id: " + requestId);
        }
    }

    public void complete(RpcResponse<?> rpcResponse) {
        CompletableFuture<RpcResponse<Object>> future =
                unprocessedResponseFutures.remove(rpcResponse.getRequestId());
        if (null != future) {
            future.complete(RpcResponse.<Object>builder()
                    .requestId(rpcResponse.getRequestId())
                    .code(rpcResponse.getCode())
                    .message(rpcResponse.getMessage())
                    .data(rpcResponse.getData())
                    .build());
        } else {
            log.warn("Received a late or unknown RPC response: [{}]", rpcResponse.getRequestId());
        }
    }

    public void remove(String requestId) {
        unprocessedResponseFutures.remove(requestId);
    }

    public void failAll(Throwable cause) {
        unprocessedResponseFutures.forEach((requestId, future) -> {
            if (unprocessedResponseFutures.remove(requestId, future)) {
                future.completeExceptionally(cause);
            }
        });
    }
}

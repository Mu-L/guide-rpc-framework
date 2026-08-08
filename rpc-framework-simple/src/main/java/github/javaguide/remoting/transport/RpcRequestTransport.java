package github.javaguide.remoting.transport;

import github.javaguide.extension.SPI;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.dto.RpcResponse;

import java.util.concurrent.CompletableFuture;

/**
 * send RpcRequest。
 *
 * @author shuang.kou
 * @createTime 2020年05月29日 13:26:00
 */
@SPI
public interface RpcRequestTransport {
    /**
     * send rpc request to server and get result
     *
     * @param rpcRequest message body
     * @return a future that completes with the protocol response or a structured transport error
     */
    CompletableFuture<RpcResponse<Object>> sendRpcRequest(RpcRequest rpcRequest);
}

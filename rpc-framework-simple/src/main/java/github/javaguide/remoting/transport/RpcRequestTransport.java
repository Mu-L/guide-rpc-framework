package github.javaguide.remoting.transport;

import github.javaguide.extension.SPI;
import github.javaguide.remoting.dto.RpcRequest;

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
     * @return a transport-specific result: the Socket implementation returns an
     * {@code RpcResponse}, while the Netty implementation returns a
     * {@code CompletableFuture<RpcResponse<Object>>}
     */
    Object sendRpcRequest(RpcRequest rpcRequest);
}

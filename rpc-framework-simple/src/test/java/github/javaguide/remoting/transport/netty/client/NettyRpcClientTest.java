package github.javaguide.remoting.transport.netty.client;

import github.javaguide.config.RpcClientConfig;
import github.javaguide.exception.RpcException;
import github.javaguide.remoting.dto.RpcRequest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertThrows;

class NettyRpcClientTest {

    @Test
    void shouldRejectRequestsAfterClientIsClosedWithoutDiscovery() {
        NettyRpcClient client = new NettyRpcClient(
                RpcClientConfig.from(new Properties()),
                request -> new InetSocketAddress("127.0.0.1", 9998));
        client.close();

        assertThrows(RpcException.class,
                () -> client.sendRpcRequest(sampleRequest()));
    }

    private static RpcRequest sampleRequest() {
        return RpcRequest.builder()
                .requestId("closed-client")
                .interfaceName("example.Service")
                .methodName("call")
                .parameters(new Object[0])
                .paramTypes(new Class<?>[0])
                .group("")
                .version("")
                .build();
    }
}

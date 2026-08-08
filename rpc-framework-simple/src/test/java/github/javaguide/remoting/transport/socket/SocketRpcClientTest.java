package github.javaguide.remoting.transport.socket;

import github.javaguide.config.RpcClientConfig;
import github.javaguide.enums.RpcConfigEnum;
import github.javaguide.exception.RpcException;
import github.javaguide.remoting.dto.RpcRequest;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketRpcClientTest {

    @Test
    void shouldBoundHowLongAReadCanBlock() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(RpcConfigEnum.CONNECT_TIMEOUT_MILLIS.getPropertyValue(), "500");
        properties.setProperty(RpcConfigEnum.REQUEST_TIMEOUT_MILLIS.getPropertyValue(), "100");
        RpcClientConfig config = RpcClientConfig.from(properties);
        CountDownLatch releaseServer = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Future<?> server = executor.submit(() -> {
                try (Socket ignored = serverSocket.accept()) {
                    releaseServer.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            SocketRpcClient client = new SocketRpcClient(
                    request -> serverSocket.getLocalSocketAddress() instanceof java.net.InetSocketAddress
                            ? (java.net.InetSocketAddress) serverSocket.getLocalSocketAddress()
                            : null,
                    config);

            RpcException exception = assertThrows(RpcException.class,
                    () -> client.sendRpcRequest(sampleRequest()));

            assertTrue(exception.getCause() instanceof SocketTimeoutException);
            releaseServer.countDown();
            server.get(2, TimeUnit.SECONDS);
        } finally {
            releaseServer.countDown();
            executor.shutdownNow();
        }
    }

    private static RpcRequest sampleRequest() {
        return RpcRequest.builder()
                .requestId("socket-timeout")
                .interfaceName("example.Service")
                .methodName("call")
                .parameters(new Object[0])
                .paramTypes(new Class<?>[0])
                .group("")
                .version("")
                .build();
    }
}

package github.javaguide.remoting.transport.socket;

import github.javaguide.config.RpcClientConfig;
import github.javaguide.enums.RpcConfigEnum;
import github.javaguide.enums.RpcStatusCode;
import github.javaguide.exception.RpcException;
import github.javaguide.remoting.dto.RpcRequest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketRpcClientTest {

    @Test
    void shouldReturnFutureBeforeServiceDiscoveryCompletes() throws Exception {
        CountDownLatch discoveryStarted = new CountDownLatch(1);
        CountDownLatch releaseDiscovery = new CountDownLatch(1);
        SocketRpcClient client = new SocketRpcClient(request -> {
            discoveryStarted.countDown();
            try {
                releaseDiscovery.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RpcException(RpcStatusCode.CANCELLED,
                        "Service discovery was cancelled", exception);
            }
            return new InetSocketAddress("127.0.0.1", 9998);
        });

        try {
            CompletableFuture<?> resultFuture = client.sendRpcRequest(sampleRequest());

            assertTrue(discoveryStarted.await(1, TimeUnit.SECONDS));
            assertFalse(resultFuture.isDone());
            assertTrue(resultFuture.cancel(true));
        } finally {
            releaseDiscovery.countDown();
            client.close();
        }
    }

    @Test
    void shouldApplyRequestTimeoutToServiceDiscovery() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(
                RpcConfigEnum.REQUEST_TIMEOUT_MILLIS.getPropertyValue(), "100");
        CountDownLatch releaseDiscovery = new CountDownLatch(1);
        try (SocketRpcClient client = new SocketRpcClient(request -> {
            try {
                releaseDiscovery.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RpcException(RpcStatusCode.CANCELLED,
                        "Service discovery was cancelled", exception);
            }
            return new InetSocketAddress("127.0.0.1", 9998);
        }, RpcClientConfig.from(properties))) {
            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> client.sendRpcRequest(sampleRequest())
                            .get(1, TimeUnit.SECONDS));

            RpcException rpcException = (RpcException) exception.getCause();
            assertEquals(RpcStatusCode.DEADLINE_EXCEEDED,
                    rpcException.getStatusCode());
        } finally {
            releaseDiscovery.countDown();
        }
    }

    @Test
    void shouldFailQueuedAndRunningRequestsWhenClientCloses() throws Exception {
        CountDownLatch runningDiscoveries = new CountDownLatch(2);
        CountDownLatch releaseDiscovery = new CountDownLatch(1);
        SocketRpcClient client = new SocketRpcClient(request -> {
            runningDiscoveries.countDown();
            try {
                releaseDiscovery.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RpcException(RpcStatusCode.CANCELLED,
                        "Service discovery was cancelled", exception);
            }
            return new InetSocketAddress("127.0.0.1", 9998);
        });
        List<CompletableFuture<?>> resultFutures = new ArrayList<>();

        try {
            for (int index = 0; index < 3; index++) {
                resultFutures.add(client.sendRpcRequest(sampleRequest()));
            }
            assertTrue(runningDiscoveries.await(1, TimeUnit.SECONDS));

            client.close();

            for (CompletableFuture<?> resultFuture : resultFutures) {
                CompletionException exception = assertThrows(
                        CompletionException.class, resultFuture::join);
                RpcException rpcException = (RpcException) exception.getCause();
                assertEquals(RpcStatusCode.CANCELLED,
                        rpcException.getStatusCode());
            }
        } finally {
            releaseDiscovery.countDown();
            client.close();
        }
    }

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
            try (SocketRpcClient client = new SocketRpcClient(
                    request -> serverSocket.getLocalSocketAddress() instanceof InetSocketAddress
                            ? (InetSocketAddress) serverSocket.getLocalSocketAddress()
                            : null,
                    config)) {
                ExecutionException exception = assertThrows(ExecutionException.class,
                        () -> client.sendRpcRequest(sampleRequest())
                                .get(2, TimeUnit.SECONDS));

                RpcException rpcException = (RpcException) exception.getCause();
                assertEquals(RpcStatusCode.DEADLINE_EXCEEDED,
                        rpcException.getStatusCode());
                assertTrue(rpcException.getCause() instanceof SocketTimeoutException
                        || rpcException.getCause() instanceof TimeoutException);
                releaseServer.countDown();
                server.get(2, TimeUnit.SECONDS);
            }
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

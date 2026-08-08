package github.javaguide.proxy;

import github.javaguide.enums.RpcStatusCode;
import github.javaguide.exception.RpcException;
import github.javaguide.exception.RpcRemoteException;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.dto.RpcResponse;
import github.javaguide.remoting.transport.RpcRequestTransport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcClientProxyTest {

    @Test
    void shouldHandleObjectMethodsLocally() {
        AtomicInteger remoteCalls = new AtomicInteger();
        RpcRequestTransport transport = request -> {
            remoteCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    RpcResponse.success("hello", request.getRequestId()));
        };
        GreetingService service = new RpcClientProxy(transport).getProxy(GreetingService.class);
        GreetingService anotherService =
                new RpcClientProxy(transport).getProxy(GreetingService.class);

        assertTrue(service.equals(service));
        assertNotEquals(service, anotherService);
        assertEquals(System.identityHashCode(service), service.hashCode());
        assertEquals("RpcClientProxy(" + GreetingService.class.getCanonicalName() + ")",
                service.toString());
        assertEquals(0, remoteCalls.get());
    }

    @Test
    void shouldExposeStructuredRemoteFailure() {
        RpcRequestTransport transport = request -> CompletableFuture.completedFuture(
                RpcResponse.fail(RpcStatusCode.UNAVAILABLE,
                        request.getRequestId(), "inventory unavailable"));
        GreetingService service = new RpcClientProxy(transport).getProxy(GreetingService.class);

        RpcRemoteException exception = assertThrows(
                RpcRemoteException.class, service::hello);

        assertEquals(RpcStatusCode.UNAVAILABLE, exception.getStatusCode());
        assertEquals("inventory unavailable", exception.getMessage());
    }

    @Test
    void shouldUseRequestedChildInterfaceForInheritedMethod() {
        AtomicReference<RpcRequest> capturedRequest = new AtomicReference<>();
        RpcRequestTransport transport = request -> {
            capturedRequest.set(request);
            return CompletableFuture.completedFuture(
                    RpcResponse.success("hello", request.getRequestId()));
        };
        ChildGreetingService service =
                new RpcClientProxy(transport).getProxy(ChildGreetingService.class);

        assertEquals("hello", service.hello());
        assertEquals(ChildGreetingService.class.getCanonicalName(),
                capturedRequest.get().getInterfaceName());
    }

    @Test
    void shouldReturnBeforeAsynchronousResponseArrives() {
        AtomicReference<RpcRequest> capturedRequest = new AtomicReference<>();
        CompletableFuture<RpcResponse<Object>> transportFuture = new CompletableFuture<>();
        RpcRequestTransport transport = request -> {
            capturedRequest.set(request);
            return transportFuture;
        };
        AsyncGreetingService service = new RpcClientProxy(transport)
                .getAsyncProxy(AsyncGreetingService.class, GreetingService.class);

        CompletableFuture<String> resultFuture = service.hello();

        assertFalse(resultFuture.isDone());
        assertEquals(GreetingService.class.getCanonicalName(),
                capturedRequest.get().getInterfaceName());
        transportFuture.complete(RpcResponse.success(
                "hello", capturedRequest.get().getRequestId()));
        assertEquals("hello", resultFuture.join());
    }

    @Test
    void shouldPropagateCancellationToTransportFuture() {
        CompletableFuture<RpcResponse<Object>> transportFuture = new CompletableFuture<>();
        AsyncGreetingService service = new RpcClientProxy(request -> transportFuture)
                .getAsyncProxy(AsyncGreetingService.class, GreetingService.class);

        CompletableFuture<String> resultFuture = service.hello();
        resultFuture.cancel(true);

        assertTrue(resultFuture.isCancelled());
        assertTrue(transportFuture.isCancelled());
    }

    @Test
    void shouldKeepStructuredTransportFailureInAsyncChain() {
        RpcRequestTransport transport = request -> CompletableFuture.failedFuture(
                new RpcException(RpcStatusCode.DEADLINE_EXCEEDED,
                        request.getRequestId(), "deadline", null));
        AsyncGreetingService service = new RpcClientProxy(transport)
                .getAsyncProxy(AsyncGreetingService.class, GreetingService.class);

        CompletionException completionException = assertThrows(
                CompletionException.class, () -> service.hello().join());

        RpcException rpcException = (RpcException) completionException.getCause();
        assertEquals(RpcStatusCode.DEADLINE_EXCEEDED, rpcException.getStatusCode());
    }

    @Test
    void shouldRejectUnknownRemoteStatusAsDataLoss() {
        RpcRequestTransport transport = request -> CompletableFuture.completedFuture(
                RpcResponse.<Object>builder()
                        .requestId(request.getRequestId())
                        .code(99)
                        .message("unknown")
                        .build());
        GreetingService service = new RpcClientProxy(transport)
                .getProxy(GreetingService.class);

        RpcException exception = assertThrows(RpcException.class, service::hello);

        assertEquals(RpcStatusCode.DATA_LOSS, exception.getStatusCode());
    }

    @Test
    void shouldRejectInvalidAsynchronousMirrorAtCreationTime() {
        RpcRequestTransport transport = request -> new CompletableFuture<>();
        RpcClientProxy proxy = new RpcClientProxy(transport);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> proxy.getAsyncProxy(InvalidAsyncGreetingService.class,
                        GreetingService.class));

        assertTrue(exception.getMessage().contains("must return CompletableFuture"));
    }

    @Test
    void shouldRejectAsynchronousMirrorWithWrongResultType() {
        RpcRequestTransport transport = request -> new CompletableFuture<>();
        RpcClientProxy proxy = new RpcClientProxy(transport);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> proxy.getAsyncProxy(WrongResultAsyncGreetingService.class,
                        GreetingService.class));

        assertTrue(exception.getMessage().contains("result type does not match"));
    }

    interface GreetingService {
        String hello();
    }

    interface AsyncGreetingService {
        CompletableFuture<String> hello();
    }

    interface InvalidAsyncGreetingService {
        String hello();
    }

    interface WrongResultAsyncGreetingService {
        CompletableFuture<Integer> hello();
    }

    interface ParentGreetingService {
        String hello();
    }

    interface ChildGreetingService extends ParentGreetingService {
    }
}

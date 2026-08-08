package github.javaguide.proxy;

import github.javaguide.enums.RpcResponseCodeEnum;
import github.javaguide.exception.RpcException;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.dto.RpcResponse;
import github.javaguide.remoting.transport.RpcRequestTransport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcClientProxyTest {

    @Test
    void shouldHandleObjectMethodsLocally() {
        AtomicInteger remoteCalls = new AtomicInteger();
        RpcRequestTransport transport = request -> {
            remoteCalls.incrementAndGet();
            return RpcResponse.success("hello", request.getRequestId());
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
    void shouldExposeRemoteFailureMessage() {
        RpcRequestTransport transport = request -> RpcResponse.fail(
                RpcResponseCodeEnum.FAIL, request.getRequestId(), "inventory unavailable");
        GreetingService service = new RpcClientProxy(transport).getProxy(GreetingService.class);

        RpcException exception = assertThrows(RpcException.class, service::hello);

        assertTrue(exception.getMessage().contains("inventory unavailable"));
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

    interface GreetingService {
        String hello();
    }

    interface ParentGreetingService {
        String hello();
    }

    interface ChildGreetingService extends ParentGreetingService {
    }
}

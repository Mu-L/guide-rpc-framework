package github.javaguide.remoting.handler;

import github.javaguide.config.RpcServiceConfig;
import github.javaguide.exception.RpcException;
import github.javaguide.enums.RpcStatusCode;
import github.javaguide.factory.SingletonFactory;
import github.javaguide.provider.ServiceProvider;
import github.javaguide.provider.impl.ZkServiceProviderImpl;
import github.javaguide.remoting.dto.RpcRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RpcRequestHandlerTest {

    @Test
    void shouldInvokeMethodWithoutArguments() {
        ServiceProvider serviceProvider = SingletonFactory.getInstance(ZkServiceProviderImpl.class);
        NoArgumentService service = new NoArgumentServiceImpl();
        RpcServiceConfig serviceConfig = RpcServiceConfig.builder()
                .service(service)
                .group("")
                .version("")
                .build();
        serviceProvider.addService(serviceConfig);
        RpcRequest request = RpcRequest.builder()
                .requestId("no-argument-request")
                .interfaceName(serviceConfig.getServiceName())
                .methodName("hello")
                .parameters(null)
                .paramTypes(new Class<?>[0])
                .group("")
                .version("")
                .build();

        Object result = new RpcRequestHandler().handle(request);

        assertEquals("hello", result);
    }

    @Test
    void shouldRejectPublicImplementationMethodOutsideRpcInterface() {
        ServiceProvider serviceProvider = SingletonFactory.getInstance(ZkServiceProviderImpl.class);
        NoArgumentService service = new NoArgumentServiceImpl();
        RpcServiceConfig serviceConfig = RpcServiceConfig.builder()
                .service(service)
                .group("restricted")
                .version("")
                .build();
        serviceProvider.addService(serviceConfig);
        RpcRequest request = RpcRequest.builder()
                .requestId("implementation-method")
                .interfaceName(serviceConfig.getServiceName())
                .methodName("implementationOnly")
                .parameters(null)
                .paramTypes(new Class<?>[0])
                .group("restricted")
                .version("")
                .build();

        RpcException exception = assertThrows(
                RpcException.class, () -> new RpcRequestHandler().handle(request));
        assertEquals(RpcStatusCode.UNIMPLEMENTED, exception.getStatusCode());
    }

    @Test
    void shouldRejectStaticInterfaceMethod() {
        ServiceProvider serviceProvider = SingletonFactory.getInstance(ZkServiceProviderImpl.class);
        NoArgumentService service = new NoArgumentServiceImpl();
        RpcServiceConfig serviceConfig = RpcServiceConfig.builder()
                .service(service)
                .group("static-method")
                .version("")
                .build();
        serviceProvider.addService(serviceConfig);
        RpcRequest request = RpcRequest.builder()
                .requestId("static-method-request")
                .interfaceName(serviceConfig.getServiceName())
                .methodName("staticHelper")
                .parameters(null)
                .paramTypes(new Class<?>[0])
                .group("static-method")
                .version("")
                .build();

        RpcException exception = assertThrows(
                RpcException.class, () -> new RpcRequestHandler().handle(request));
        assertEquals(RpcStatusCode.UNIMPLEMENTED, exception.getStatusCode());
    }

    public interface NoArgumentService {
        String hello();

        static String staticHelper() {
            return "must not be remotely callable";
        }
    }

    public static class NoArgumentServiceImpl implements NoArgumentService {
        @Override
        public String hello() {
            return "hello";
        }

        public String implementationOnly() {
            return "must not be remotely callable";
        }
    }
}

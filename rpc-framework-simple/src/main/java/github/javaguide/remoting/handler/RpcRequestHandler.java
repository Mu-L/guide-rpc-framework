package github.javaguide.remoting.handler;

import github.javaguide.exception.RpcException;
import github.javaguide.exception.RpcServiceException;
import github.javaguide.enums.RpcStatusCode;
import github.javaguide.factory.SingletonFactory;
import github.javaguide.provider.ServiceProvider;
import github.javaguide.provider.impl.ZkServiceProviderImpl;
import github.javaguide.remoting.dto.RpcRequest;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * RpcRequest processor
 *
 * @author shuang.kou
 * @createTime 2020年05月13日 09:05:00
 */
@Slf4j
public class RpcRequestHandler {
    private final ServiceProvider serviceProvider;

    public RpcRequestHandler() {
        serviceProvider = SingletonFactory.getInstance(ZkServiceProviderImpl.class);
    }

    /**
     * Processing rpcRequest: call the corresponding method, and then return the method
     */
    public Object handle(RpcRequest rpcRequest) {
        if (rpcRequest == null) {
            throw new RpcException(RpcStatusCode.INVALID_ARGUMENT,
                    "RPC request cannot be null");
        }
        if (rpcRequest.getInterfaceName() == null || rpcRequest.getMethodName() == null
                || rpcRequest.getParamTypes() == null) {
            throw new RpcException(RpcStatusCode.INVALID_ARGUMENT,
                    rpcRequest.getRequestId(),
                    "RPC request is missing interface, method or parameter types", null);
        }
        Object service = serviceProvider.getService(rpcRequest.getRpcServiceName());
        return invokeTargetMethod(rpcRequest, service);
    }

    /**
     * get method execution results
     *
     * @param rpcRequest client request
     * @param service    service object
     * @return the result of the target method execution
     */
    private Object invokeTargetMethod(RpcRequest rpcRequest, Object service) {
        Object result;
        try {
            Class<?> serviceInterface = findServiceInterface(
                    service.getClass(), rpcRequest.getInterfaceName());
            if (serviceInterface == null) {
                throw new RpcException(RpcStatusCode.UNIMPLEMENTED,
                        rpcRequest.getRequestId(),
                        "Published service does not implement RPC interface "
                                + rpcRequest.getInterfaceName(), null);
            }
            Method method = serviceInterface.getMethod(
                    rpcRequest.getMethodName(), rpcRequest.getParamTypes());
            if (Modifier.isStatic(method.getModifiers())) {
                throw new RpcException(RpcStatusCode.UNIMPLEMENTED,
                        rpcRequest.getRequestId(),
                        "Static interface methods are not RPC operations: "
                                + rpcRequest.getMethodName(), null);
            }
            result = method.invoke(service, rpcRequest.getParameters());
            log.info("Service invocation succeeded service={} method={}",
                    rpcRequest.getInterfaceName(), rpcRequest.getMethodName());
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            if (targetException instanceof RpcServiceException rpcServiceException) {
                throw rpcServiceException;
            }
            throw new RpcException(RpcStatusCode.INTERNAL,
                    rpcRequest.getRequestId(), RpcStatusCode.INTERNAL.getMessage(),
                    targetException);
        } catch (NoSuchMethodException e) {
            throw new RpcException(RpcStatusCode.UNIMPLEMENTED,
                    rpcRequest.getRequestId(),
                    "RPC method is not implemented: " + rpcRequest.getMethodName(), e);
        } catch (IllegalArgumentException e) {
            throw new RpcException(RpcStatusCode.INVALID_ARGUMENT,
                    rpcRequest.getRequestId(),
                    "RPC method arguments do not match: " + rpcRequest.getMethodName(), e);
        } catch (IllegalAccessException e) {
            throw new RpcException(RpcStatusCode.INTERNAL,
                    rpcRequest.getRequestId(), RpcStatusCode.INTERNAL.getMessage(), e);
        }
        return result;
    }

    private static Class<?> findServiceInterface(Class<?> type, String interfaceName) {
        for (Class<?> implementedInterface : type.getInterfaces()) {
            Class<?> match = findInterface(implementedInterface, interfaceName);
            if (match != null) {
                return match;
            }
        }
        Class<?> superclass = type.getSuperclass();
        return superclass == null
                ? null : findServiceInterface(superclass, interfaceName);
    }

    private static Class<?> findInterface(Class<?> candidate, String interfaceName) {
        if (candidate.getName().equals(interfaceName)
                || candidate.getCanonicalName().equals(interfaceName)) {
            return candidate;
        }
        for (Class<?> parent : candidate.getInterfaces()) {
            Class<?> match = findInterface(parent, interfaceName);
            if (match != null) {
                return match;
            }
        }
        return null;
    }
}

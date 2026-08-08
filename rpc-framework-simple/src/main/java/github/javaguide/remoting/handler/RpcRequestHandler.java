package github.javaguide.remoting.handler;

import github.javaguide.exception.RpcException;
import github.javaguide.factory.SingletonFactory;
import github.javaguide.provider.ServiceProvider;
import github.javaguide.provider.impl.ZkServiceProviderImpl;
import github.javaguide.remoting.dto.RpcRequest;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

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
        Objects.requireNonNull(rpcRequest, "rpcRequest cannot be null");
        if (rpcRequest.getInterfaceName() == null || rpcRequest.getMethodName() == null
                || rpcRequest.getParamTypes() == null) {
            throw new RpcException("RPC request is missing interface, method or parameter types");
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
                throw new RpcException("Published service does not implement RPC interface "
                        + rpcRequest.getInterfaceName());
            }
            Method method = serviceInterface.getMethod(
                    rpcRequest.getMethodName(), rpcRequest.getParamTypes());
            if (Modifier.isStatic(method.getModifiers())) {
                throw new RpcException("Static interface methods are not RPC operations: "
                        + rpcRequest.getMethodName());
            }
            result = method.invoke(service, rpcRequest.getParameters());
            log.info("service:[{}] successful invoke method:[{}]", rpcRequest.getInterfaceName(), rpcRequest.getMethodName());
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            throw new RpcException("Service method " + rpcRequest.getMethodName()
                    + " failed: " + targetException.getMessage(), targetException);
        } catch (NoSuchMethodException | IllegalArgumentException | IllegalAccessException e) {
            throw new RpcException("Failed to invoke service method "
                    + rpcRequest.getMethodName(), e);
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

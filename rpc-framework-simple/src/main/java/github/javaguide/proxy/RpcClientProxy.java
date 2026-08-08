package github.javaguide.proxy;

import github.javaguide.config.RpcServiceConfig;
import github.javaguide.enums.RpcStatusCode;
import github.javaguide.exception.RpcException;
import github.javaguide.exception.RpcRemoteException;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.dto.RpcResponse;
import github.javaguide.remoting.transport.RpcRequestTransport;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * Creates synchronous and asynchronous type-safe RPC client proxies.
 *
 * <p>A normal proxy preserves the service interface. An asynchronous proxy uses a mirror
 * interface whose methods return {@link CompletableFuture}; requests still target the original
 * service interface, so the server implementation does not need a duplicate asynchronous API.
 *
 * @author shuang.kou
 * @createTime 2020年05月10日 19:01:00
 */
@Slf4j
public class RpcClientProxy implements InvocationHandler {

    private final RpcRequestTransport rpcRequestTransport;
    private final RpcServiceConfig rpcServiceConfig;
    private final Class<?> proxyInterface;
    private final Class<?> serviceInterface;

    public RpcClientProxy(RpcRequestTransport rpcRequestTransport,
                          RpcServiceConfig rpcServiceConfig) {
        this(rpcRequestTransport, rpcServiceConfig, null, null);
    }

    public RpcClientProxy(RpcRequestTransport rpcRequestTransport) {
        this(rpcRequestTransport, new RpcServiceConfig(), null, null);
    }

    private RpcClientProxy(RpcRequestTransport rpcRequestTransport,
                           RpcServiceConfig rpcServiceConfig,
                           Class<?> proxyInterface,
                           Class<?> serviceInterface) {
        this.rpcRequestTransport = Objects.requireNonNull(
                rpcRequestTransport, "RPC request transport cannot be null");
        this.rpcServiceConfig = Objects.requireNonNull(
                rpcServiceConfig, "RPC service config cannot be null");
        this.proxyInterface = proxyInterface;
        this.serviceInterface = serviceInterface;
    }

    /** Creates a proxy that preserves synchronous and asynchronous service method signatures. */
    public <T> T getProxy(Class<T> serviceInterface) {
        validateInterface(serviceInterface, "RPC service interface");
        return createProxy(serviceInterface, serviceInterface);
    }

    /**
     * Creates a non-blocking proxy for a conventional synchronous service interface.
     *
     * <p>Every non-static method in {@code asyncInterface} must return {@link CompletableFuture}
     * or {@link CompletionStage}, and must have a method with the same name and parameters in
     * {@code serviceInterface}.
     */
    public <T> T getAsyncProxy(Class<T> asyncInterface, Class<?> serviceInterface) {
        validateAsyncMirror(asyncInterface, serviceInterface);
        return createProxy(asyncInterface, serviceInterface);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return invokeObjectMethod(proxy, method, args);
        }

        RpcRequest rpcRequest = createRequest(method, args);
        CompletableFuture<RpcResponse<Object>> responseFuture = sendRequest(rpcRequest);
        CompletableFuture<Object> resultFuture = adaptResponse(responseFuture, rpcRequest);
        if (isAsyncReturnType(method)) {
            return resultFuture;
        }
        return awaitResponse(resultFuture, rpcRequest);
    }

    @SuppressWarnings("unchecked")
    private <T> T createProxy(Class<T> exposedInterface, Class<?> remoteInterface) {
        RpcClientProxy invocationHandler = new RpcClientProxy(
                rpcRequestTransport, rpcServiceConfig, exposedInterface, remoteInterface);
        return (T) Proxy.newProxyInstance(
                exposedInterface.getClassLoader(),
                new Class<?>[]{exposedInterface},
                invocationHandler);
    }

    private RpcRequest createRequest(Method method, Object[] args) {
        log.info("Invoking RPC method service={} method={}",
                serviceInterface.getCanonicalName(), method.getName());
        return RpcRequest.builder()
                .methodName(method.getName())
                .parameters(args)
                .interfaceName(serviceInterface.getCanonicalName())
                .paramTypes(method.getParameterTypes())
                .requestId(UUID.randomUUID().toString())
                .group(rpcServiceConfig.getGroup())
                .version(rpcServiceConfig.getVersion())
                .build();
    }

    private CompletableFuture<RpcResponse<Object>> sendRequest(RpcRequest rpcRequest) {
        try {
            CompletableFuture<RpcResponse<Object>> responseFuture =
                    rpcRequestTransport.sendRpcRequest(rpcRequest);
            if (responseFuture == null) {
                return CompletableFuture.failedFuture(new RpcException(
                        RpcStatusCode.INTERNAL, rpcRequest.getRequestId(),
                        "RPC transport returned a null future", null));
            }
            return responseFuture;
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(
                    normalizeClientFailure(exception, rpcRequest));
        }
    }

    private CompletableFuture<Object> adaptResponse(
            CompletableFuture<RpcResponse<Object>> responseFuture,
            RpcRequest rpcRequest) {
        CompletableFuture<Object> resultFuture = new CompletableFuture<>();
        responseFuture.whenComplete((rpcResponse, throwable) -> {
            if (throwable != null) {
                resultFuture.completeExceptionally(
                        normalizeClientFailure(throwable, rpcRequest));
                return;
            }
            try {
                resultFuture.complete(readResponse(rpcResponse, rpcRequest));
            } catch (RuntimeException exception) {
                resultFuture.completeExceptionally(exception);
            }
        });
        resultFuture.whenComplete((result, throwable) -> {
            if (resultFuture.isCancelled()) {
                responseFuture.cancel(true);
            }
        });
        return resultFuture;
    }

    private Object readResponse(RpcResponse<Object> rpcResponse, RpcRequest rpcRequest) {
        if (rpcResponse == null) {
            throw protocolFailure(rpcRequest, "RPC transport returned a null response", null);
        }
        if (!rpcRequest.getRequestId().equals(rpcResponse.getRequestId())) {
            throw protocolFailure(rpcRequest,
                    "RPC response requestId does not match the request", null);
        }
        if (rpcResponse.getCode() == null) {
            throw protocolFailure(rpcRequest, "RPC response has no status code", null);
        }

        RpcStatusCode statusCode;
        try {
            statusCode = RpcStatusCode.fromCode(rpcResponse.getCode());
        } catch (IllegalArgumentException exception) {
            throw protocolFailure(rpcRequest,
                    "RPC response contains an unknown status code: " + rpcResponse.getCode(),
                    exception);
        }
        if (statusCode != RpcStatusCode.OK) {
            String message = rpcResponse.getMessage() == null
                    ? statusCode.getMessage() : rpcResponse.getMessage();
            throw new RpcRemoteException(
                    statusCode, rpcRequest.getRequestId(), message);
        }
        return rpcResponse.getData();
    }

    private Object awaitResponse(CompletableFuture<Object> resultFuture,
                                 RpcRequest rpcRequest) {
        try {
            return resultFuture.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            resultFuture.cancel(true);
            throw new RpcException(RpcStatusCode.CANCELLED,
                    rpcRequest.getRequestId(),
                    "Interrupted while waiting for RPC response", exception);
        } catch (CancellationException exception) {
            throw new RpcException(RpcStatusCode.CANCELLED,
                    rpcRequest.getRequestId(),
                    RpcStatusCode.CANCELLED.getMessage(), exception);
        } catch (ExecutionException exception) {
            Throwable cause = unwrap(exception);
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RpcException(RpcStatusCode.UNKNOWN,
                    rpcRequest.getRequestId(),
                    RpcStatusCode.UNKNOWN.getMessage(), cause);
        }
    }

    private RpcException normalizeClientFailure(Throwable throwable, RpcRequest rpcRequest) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof RpcException rpcException) {
            return rpcException;
        }
        if (cause instanceof CancellationException) {
            return new RpcException(RpcStatusCode.CANCELLED,
                    rpcRequest.getRequestId(),
                    RpcStatusCode.CANCELLED.getMessage(), cause);
        }
        return new RpcException(RpcStatusCode.UNAVAILABLE,
                rpcRequest.getRequestId(),
                "RPC transport failed", cause);
    }

    private RpcException protocolFailure(RpcRequest rpcRequest,
                                         String message, Throwable cause) {
        return new RpcException(RpcStatusCode.DATA_LOSS,
                rpcRequest.getRequestId(), message, cause);
    }

    private Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "RpcClientProxy(" + proxyInterface.getCanonicalName() + ")";
            default -> throw new IllegalStateException(
                    "Unsupported Object method: " + method.getName());
        };
    }

    private static void validateAsyncMirror(Class<?> asyncInterface,
                                            Class<?> serviceInterface) {
        validateInterface(asyncInterface, "RPC async interface");
        validateInterface(serviceInterface, "RPC service interface");
        for (Method asyncMethod : asyncInterface.getMethods()) {
            if (Modifier.isStatic(asyncMethod.getModifiers())) {
                continue;
            }
            if (!isAsyncReturnType(asyncMethod)
                    || !asyncMethod.getReturnType().isAssignableFrom(CompletableFuture.class)) {
                throw new IllegalArgumentException(
                        "Async RPC method must return CompletableFuture or CompletionStage: "
                                + asyncMethod.toGenericString());
            }
            try {
                Method serviceMethod = serviceInterface.getMethod(
                        asyncMethod.getName(), asyncMethod.getParameterTypes());
                if (Modifier.isStatic(serviceMethod.getModifiers())) {
                    throw new IllegalArgumentException(
                            "Async RPC method cannot target a static service method: "
                                    + serviceMethod.toGenericString());
                }
                validateAsyncResultType(asyncMethod, serviceMethod);
            } catch (NoSuchMethodException exception) {
                throw new IllegalArgumentException(
                        "Async RPC method has no matching service method: "
                                + asyncMethod.toGenericString(), exception);
            }
        }
    }

    private static void validateAsyncResultType(
            Method asyncMethod, Method serviceMethod) {
        Type asyncResultType = futureResultType(asyncMethod);
        Type serviceResultType = futureResultType(serviceMethod);
        if (asyncResultType instanceof Class<?> asyncResultClass
                && serviceResultType instanceof Class<?> serviceResultClass
                && !asyncResultClass.isAssignableFrom(boxed(serviceResultClass))) {
            throw new IllegalArgumentException(
                    "Async RPC result type does not match service method: "
                            + asyncMethod.toGenericString());
        }
    }

    private static Type futureResultType(Method method) {
        Type returnType = method.getGenericReturnType();
        if (isAsyncReturnType(method) && returnType instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1) {
            return parameterized.getActualTypeArguments()[0];
        }
        return returnType;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == void.class) {
            return Void.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        return Character.class;
    }

    private static void validateInterface(Class<?> type, String description) {
        if (type == null || !type.isInterface()) {
            throw new IllegalArgumentException(description + " must be an interface");
        }
    }

    private static boolean isAsyncReturnType(Method method) {
        return CompletionStage.class.isAssignableFrom(method.getReturnType());
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}

package github.javaguide.remoting.transport.socket;

import github.javaguide.config.RpcClientConfig;
import github.javaguide.enums.RpcStatusCode;
import github.javaguide.enums.ServiceDiscoveryEnum;
import github.javaguide.exception.RpcException;
import github.javaguide.extension.ExtensionLoader;
import github.javaguide.registry.ServiceDiscovery;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.dto.RpcResponse;
import github.javaguide.remoting.transport.RpcRequestTransport;
import github.javaguide.utils.RuntimeUtil;
import github.javaguide.utils.concurrent.threadpool.ThreadPoolFactoryUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Blocking-socket transport adapted to the common asynchronous client contract. */
@Slf4j
public class SocketRpcClient implements RpcRequestTransport, AutoCloseable {

    private static final int REQUEST_QUEUE_CAPACITY = 256;

    private final ServiceDiscovery serviceDiscovery;
    private final RpcClientConfig clientConfig;
    private final ExecutorService requestExecutor;
    private final ScheduledExecutorService timeoutExecutor;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<RpcResponse<Object>>> activeRequests =
            ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SocketRpcClient() {
        this(ExtensionLoader.getExtensionLoader(ServiceDiscovery.class)
                .getExtension(ServiceDiscoveryEnum.ZK.getName()), RpcClientConfig.load());
    }

    public SocketRpcClient(ServiceDiscovery serviceDiscovery) {
        this(serviceDiscovery, RpcClientConfig.load());
    }

    public SocketRpcClient(ServiceDiscovery serviceDiscovery, RpcClientConfig clientConfig) {
        this.serviceDiscovery = serviceDiscovery;
        this.clientConfig = clientConfig;
        this.requestExecutor = createRequestExecutor();
        this.timeoutExecutor = createTimeoutExecutor();
    }

    @Override
    public CompletableFuture<RpcResponse<Object>> sendRpcRequest(RpcRequest rpcRequest) {
        RpcException validationFailure = validateRequest(rpcRequest);
        if (validationFailure != null) {
            return CompletableFuture.failedFuture(validationFailure);
        }

        RequestContext context = new RequestContext();
        activeRequests.add(context.responseFuture);
        ScheduledFuture<?> timeoutFuture;
        try {
            timeoutFuture = scheduleTimeout(rpcRequest, context);
        } catch (RejectedExecutionException exception) {
            activeRequests.remove(context.responseFuture);
            return CompletableFuture.failedFuture(new RpcException(
                    RpcStatusCode.CANCELLED, rpcRequest.getRequestId(),
                    "RPC client is closed", exception));
        }
        registerCompletionCleanup(context, timeoutFuture);
        submitRequest(rpcRequest, context);
        return context.responseFuture;
    }

    private ScheduledFuture<?> scheduleTimeout(
            RpcRequest rpcRequest, RequestContext context) {
        return timeoutExecutor.schedule(
                () -> timeoutRequest(rpcRequest, context),
                clientConfig.getRequestTimeoutMillis(), TimeUnit.MILLISECONDS);
    }

    private void timeoutRequest(RpcRequest rpcRequest, RequestContext context) {
        boolean timedOut = context.responseFuture.completeExceptionally(
                new RpcException(
                        RpcStatusCode.DEADLINE_EXCEEDED,
                        rpcRequest.getRequestId(),
                        "RPC request timed out after "
                                + clientConfig.getRequestTimeoutMillis() + " ms",
                        new TimeoutException(rpcRequest.getRequestId())));
        if (timedOut) {
            cancelRequest(context);
        }
    }

    private void registerCompletionCleanup(
            RequestContext context, ScheduledFuture<?> timeoutFuture) {
        context.responseFuture.whenComplete((response, throwable) -> {
            activeRequests.remove(context.responseFuture);
            timeoutFuture.cancel(false);
            if (context.responseFuture.isCancelled()) {
                cancelRequest(context);
            }
        });
    }

    private void submitRequest(RpcRequest rpcRequest, RequestContext context) {
        try {
            Future<?> task = requestExecutor.submit(
                    () -> executeRequest(rpcRequest, context));
            context.taskReference.set(task);
            if (context.responseFuture.isDone()) {
                task.cancel(true);
            }
        } catch (RejectedExecutionException exception) {
            context.responseFuture.completeExceptionally(new RpcException(
                    closed.get() ? RpcStatusCode.CANCELLED
                            : RpcStatusCode.RESOURCE_EXHAUSTED,
                    rpcRequest.getRequestId(),
                    closed.get() ? "RPC client is closed"
                            : "Socket RPC request queue is full",
                    exception));
        }
    }

    private void executeRequest(RpcRequest rpcRequest, RequestContext context) {
        try {
            InetSocketAddress address = serviceDiscovery.lookupService(rpcRequest);
            if (context.responseFuture.isDone()) {
                return;
            }
            context.responseFuture.complete(exchange(rpcRequest, address, context));
        } catch (IOException | ClassNotFoundException | RuntimeException exception) {
            completeFailure(rpcRequest, context, exception);
        } finally {
            Socket socket = context.socketReference.getAndSet(null);
            if (socket != null) {
                activeSockets.remove(socket);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private RpcResponse<Object> exchange(
            RpcRequest rpcRequest,
            InetSocketAddress address,
            RequestContext context) throws IOException, ClassNotFoundException {
        try (Socket socket = new Socket()) {
            context.socketReference.set(socket);
            activeSockets.add(socket);
            socket.connect(address, clientConfig.getConnectTimeoutMillis());
            socket.setSoTimeout((int) Math.min(
                    clientConfig.getRequestTimeoutMillis(), Integer.MAX_VALUE));
            try (ObjectOutputStream outputStream =
                         new ObjectOutputStream(socket.getOutputStream())) {
                outputStream.writeObject(rpcRequest);
                outputStream.flush();
                try (ObjectInputStream inputStream =
                             new ObjectInputStream(socket.getInputStream())) {
                    Object response = inputStream.readObject();
                    if (response instanceof RpcResponse<?> rpcResponse) {
                        return (RpcResponse<Object>) rpcResponse;
                    }
                    throw new RpcException(RpcStatusCode.DATA_LOSS,
                            rpcRequest.getRequestId(),
                            "Socket transport received an invalid RPC response", null);
                }
            }
        }
    }

    private void completeFailure(
            RpcRequest rpcRequest, RequestContext context, Throwable throwable) {
        if (context.responseFuture.isDone()) {
            return;
        }
        RpcException rpcException;
        if (throwable instanceof RpcException existing) {
            rpcException = existing;
        } else if (throwable instanceof SocketTimeoutException) {
            rpcException = new RpcException(
                    RpcStatusCode.DEADLINE_EXCEEDED, rpcRequest.getRequestId(),
                    "RPC request timed out after "
                            + clientConfig.getRequestTimeoutMillis() + " ms",
                    throwable);
        } else if (throwable instanceof ClassNotFoundException) {
            rpcException = new RpcException(
                    RpcStatusCode.DATA_LOSS, rpcRequest.getRequestId(),
                    "Failed to deserialize RPC response", throwable);
        } else {
            rpcException = new RpcException(
                    closed.get() ? RpcStatusCode.CANCELLED : RpcStatusCode.UNAVAILABLE,
                    rpcRequest.getRequestId(),
                    closed.get() ? "RPC client is closed" : "Socket RPC transport failed",
                    throwable);
        }
        context.responseFuture.completeExceptionally(rpcException);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        activeRequests.forEach(requestFuture -> requestFuture.completeExceptionally(
                new RpcException(RpcStatusCode.CANCELLED, "RPC client is closed")));
        activeSockets.forEach(SocketRpcClient::closeQuietly);
        activeSockets.clear();
        requestExecutor.shutdownNow();
        timeoutExecutor.shutdownNow();
    }

    private RpcException validateRequest(RpcRequest rpcRequest) {
        if (closed.get()) {
            return new RpcException(RpcStatusCode.CANCELLED, requestId(rpcRequest),
                    "RPC client is closed", null);
        }
        if (rpcRequest == null || rpcRequest.getRequestId() == null
                || rpcRequest.getRequestId().isBlank()) {
            return new RpcException(RpcStatusCode.INVALID_ARGUMENT,
                    "RPC request and requestId must not be blank");
        }
        return null;
    }

    private static void cancelRequest(RequestContext context) {
        closeQuietly(context.socketReference.get());
        Future<?> task = context.taskReference.get();
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }
    }

    private static ExecutorService createRequestExecutor() {
        int corePoolSize = Math.max(2, Math.min(4, RuntimeUtil.cpus()));
        int maximumPoolSize = Math.max(corePoolSize, RuntimeUtil.cpus() * 2);
        return new ThreadPoolExecutor(
                corePoolSize,
                maximumPoolSize,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(REQUEST_QUEUE_CAPACITY),
                ThreadPoolFactoryUtil.createThreadFactory(
                        "socket-client-request", true),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ScheduledExecutorService createTimeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1,
                ThreadPoolFactoryUtil.createThreadFactory(
                        "socket-client-timeout", true));
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static String requestId(RpcRequest request) {
        return request == null ? null : request.getRequestId();
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException exception) {
            log.debug("Failed to close socket during cancellation", exception);
        }
    }

    private static final class RequestContext {
        private final CompletableFuture<RpcResponse<Object>> responseFuture =
                new CompletableFuture<>();
        private final AtomicReference<Socket> socketReference = new AtomicReference<>();
        private final AtomicReference<Future<?>> taskReference = new AtomicReference<>();
    }
}

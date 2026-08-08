package github.javaguide.remoting.transport.netty.client;

import github.javaguide.config.RpcClientConfig;
import github.javaguide.enums.RpcStatusCode;
import github.javaguide.enums.ServiceDiscoveryEnum;
import github.javaguide.exception.RpcException;
import github.javaguide.extension.ExtensionLoader;
import github.javaguide.registry.ServiceDiscovery;
import github.javaguide.remoting.constants.RpcConstants;
import github.javaguide.remoting.dto.RpcMessage;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.dto.RpcResponse;
import github.javaguide.remoting.transport.RpcRequestTransport;
import github.javaguide.remoting.transport.netty.codec.RpcMessageCodec;
import github.javaguide.remoting.transport.netty.codec.RpcMessageFrameDecoder;
import github.javaguide.utils.RuntimeUtil;
import github.javaguide.utils.concurrent.threadpool.ThreadPoolFactoryUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Netty RPC client with a non-blocking public request contract. */
@Slf4j
public final class NettyRpcClient implements RpcRequestTransport, AutoCloseable {

    private static final int DISPATCH_QUEUE_CAPACITY = 256;
    private static final AtomicInteger REQUEST_ID_GENERATOR = new AtomicInteger();

    private final ServiceDiscovery serviceDiscovery;
    private final UnprocessedRequests unprocessedRequests;
    private final ChannelProvider channelProvider;
    private final Bootstrap bootstrap;
    private final EventLoopGroup eventLoopGroup;
    private final ExecutorService dispatchExecutor;
    private final RpcClientConfig clientConfig;
    private final Set<CompletableFuture<RpcResponse<Object>>> activeRequests =
            ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    public NettyRpcClient() {
        this(RpcClientConfig.load());
    }

    public NettyRpcClient(RpcClientConfig clientConfig) {
        this(clientConfig, ExtensionLoader.getExtensionLoader(ServiceDiscovery.class)
                .getExtension(ServiceDiscoveryEnum.ZK.getName()));
    }

    NettyRpcClient(RpcClientConfig clientConfig, ServiceDiscovery serviceDiscovery) {
        this.clientConfig = clientConfig;
        this.serviceDiscovery = serviceDiscovery;
        this.unprocessedRequests = new UnprocessedRequests();
        this.channelProvider = new ChannelProvider();
        this.eventLoopGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        this.dispatchExecutor = createDispatchExecutor();

        RpcMessageCodec rpcMessageCodec = new RpcMessageCodec();
        bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        clientConfig.getConnectTimeoutMillis())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline()
                                .addLast(new IdleStateHandler(0, 5, 0, TimeUnit.SECONDS))
                                .addLast(new RpcMessageFrameDecoder())
                                .addLast(rpcMessageCodec)
                                .addLast(new NettyRpcClientHandler(
                                        unprocessedRequests, channelProvider));
                    }
                });
    }

    /** Connects on a dispatcher thread; callers should normally use {@link #sendRpcRequest}. */
    public Channel doConnect(InetSocketAddress address) {
        CompletableFuture<Channel> channelFuture = new CompletableFuture<>();
        bootstrap.connect(address).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                log.info("Connected to RPC server address={}", address);
                channelFuture.complete(future.channel());
            } else {
                channelFuture.completeExceptionally(future.cause());
            }
        });
        try {
            return channelFuture.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RpcException(RpcStatusCode.CANCELLED,
                    "Interrupted while connecting to " + address, exception);
        } catch (ExecutionException exception) {
            throw new RpcException(RpcStatusCode.UNAVAILABLE,
                    "Failed to connect to " + address, exception.getCause());
        }
    }

    @Override
    public CompletableFuture<RpcResponse<Object>> sendRpcRequest(RpcRequest rpcRequest) {
        RpcException validationFailure = validateRequest(rpcRequest);
        if (validationFailure != null) {
            return CompletableFuture.failedFuture(validationFailure);
        }

        RequestContext context = new RequestContext(rpcRequest);
        activeRequests.add(context.resultFuture);
        ScheduledFuture<?> timeoutFuture;
        try {
            timeoutFuture = scheduleTimeout(rpcRequest, context);
        } catch (RejectedExecutionException exception) {
            activeRequests.remove(context.resultFuture);
            return CompletableFuture.failedFuture(
                    clientClosedFailure(rpcRequest, exception));
        }
        registerCompletionCleanup(rpcRequest, context, timeoutFuture);
        submitDispatch(rpcRequest, context);
        return context.resultFuture;
    }

    private ScheduledFuture<?> scheduleTimeout(
            RpcRequest rpcRequest, RequestContext context) {
        return eventLoopGroup.next().schedule(
                () -> context.resultFuture.completeExceptionally(
                        timeoutFailure(rpcRequest)),
                clientConfig.getRequestTimeoutMillis(), TimeUnit.MILLISECONDS);
    }

    private void registerCompletionCleanup(
            RpcRequest rpcRequest,
            RequestContext context,
            ScheduledFuture<?> timeoutFuture) {
        context.resultFuture.whenComplete((response, throwable) -> {
            activeRequests.remove(context.resultFuture);
            timeoutFuture.cancel(false);
            unprocessedRequests.remove(rpcRequest.getRequestId());
            Channel channel = context.channelReference.get();
            if (channel != null) {
                channel.closeFuture().removeListener(context.closeListener);
            }
            Future<?> dispatchTask = context.dispatchReference.get();
            if (dispatchTask != null && !dispatchTask.isDone()) {
                dispatchTask.cancel(true);
            }
        });
    }

    private void submitDispatch(RpcRequest rpcRequest, RequestContext context) {
        try {
            Future<?> dispatchTask = dispatchExecutor.submit(() -> dispatch(
                    rpcRequest, context));
            context.dispatchReference.set(dispatchTask);
            if (context.resultFuture.isDone()) {
                dispatchTask.cancel(true);
            }
        } catch (RejectedExecutionException exception) {
            context.resultFuture.completeExceptionally(new RpcException(
                    closed.get() ? RpcStatusCode.CANCELLED : RpcStatusCode.RESOURCE_EXHAUSTED,
                    rpcRequest.getRequestId(),
                    closed.get() ? "RPC client is closed" : "RPC client dispatch queue is full",
                    exception));
        }
    }

    private void dispatch(RpcRequest rpcRequest, RequestContext context) {
        try {
            InetSocketAddress address = serviceDiscovery.lookupService(rpcRequest);
            if (context.resultFuture.isDone()) {
                return;
            }
            Channel channel = getChannel(address);
            if (context.resultFuture.isDone()) {
                return;
            }
            if (!channel.isActive()) {
                throw new RpcException(RpcStatusCode.UNAVAILABLE,
                        rpcRequest.getRequestId(),
                        "RPC channel is inactive: " + address, null);
            }
            context.channelReference.set(channel);
            channel.closeFuture().addListener(context.closeListener);
            if (context.resultFuture.isDone()) {
                channel.closeFuture().removeListener(context.closeListener);
                return;
            }

            unprocessedRequests.put(rpcRequest.getRequestId(), context.resultFuture);
            if (context.resultFuture.isDone()) {
                unprocessedRequests.remove(rpcRequest.getRequestId());
                return;
            }
            writeRequest(channel, rpcRequest, context.resultFuture);
        } catch (RuntimeException exception) {
            context.resultFuture.completeExceptionally(normalizeTransportFailure(
                    rpcRequest, exception));
        }
    }

    private void writeRequest(Channel channel, RpcRequest rpcRequest,
                              CompletableFuture<RpcResponse<Object>> resultFuture) {
        RpcMessage rpcMessage = RpcMessage.builder()
                .data(rpcRequest)
                .requestId(REQUEST_ID_GENERATOR.incrementAndGet())
                .codec(clientConfig.getSerializationCode())
                .compress(clientConfig.getCompressCode())
                .messageType(RpcConstants.REQUEST_TYPE)
                .build();
        channel.writeAndFlush(rpcMessage).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                log.info("Sent RPC request requestId={} wireRequestId={}",
                        rpcRequest.getRequestId(), rpcMessage.getRequestId());
                return;
            }
            resultFuture.completeExceptionally(new RpcException(
                    RpcStatusCode.UNAVAILABLE, rpcRequest.getRequestId(),
                    "Failed to write RPC request", future.cause()));
            future.channel().close();
        });
    }

    public Channel getChannel(InetSocketAddress address) {
        Channel channel = channelProvider.get(address);
        if (channel != null) {
            return channel;
        }
        synchronized (channelProvider) {
            channel = channelProvider.get(address);
            if (channel == null) {
                channel = doConnect(address);
                if (closed.get()) {
                    channel.close();
                    throw clientClosedFailure(null, null);
                }
                channelProvider.set(address, channel);
            }
            return channel;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        dispatchExecutor.shutdownNow();
        activeRequests.forEach(requestFuture -> requestFuture.completeExceptionally(
                new RpcException(RpcStatusCode.CANCELLED, "RPC client is closed")));
        synchronized (lifecycleLock) {
            unprocessedRequests.failAll(new RpcException(
                    RpcStatusCode.CANCELLED, "RPC client is closed"));
            channelProvider.closeAll();
        }
        eventLoopGroup.shutdownGracefully().syncUninterruptibly();
    }

    private RpcException validateRequest(RpcRequest rpcRequest) {
        if (closed.get()) {
            return clientClosedFailure(rpcRequest, null);
        }
        if (rpcRequest == null || rpcRequest.getRequestId() == null
                || rpcRequest.getRequestId().isBlank()) {
            return new RpcException(RpcStatusCode.INVALID_ARGUMENT,
                    "RPC request and requestId must not be blank");
        }
        return null;
    }

    private RpcException timeoutFailure(RpcRequest rpcRequest) {
        String message = "RPC request timed out after "
                + clientConfig.getRequestTimeoutMillis() + " ms";
        return new RpcException(RpcStatusCode.DEADLINE_EXCEEDED,
                rpcRequest.getRequestId(), message,
                new TimeoutException(rpcRequest.getRequestId()));
    }

    private RpcException clientClosedFailure(RpcRequest rpcRequest, Throwable cause) {
        return new RpcException(RpcStatusCode.CANCELLED,
                rpcRequest == null ? null : rpcRequest.getRequestId(),
                "RPC client is closed", cause);
    }

    private RpcException normalizeTransportFailure(RpcRequest rpcRequest,
                                                   RuntimeException exception) {
        if (exception instanceof RpcException rpcException) {
            return rpcException;
        }
        return new RpcException(RpcStatusCode.UNAVAILABLE,
                rpcRequest.getRequestId(), "RPC transport failed", exception);
    }

    private static ExecutorService createDispatchExecutor() {
        int corePoolSize = Math.max(2, Math.min(4, RuntimeUtil.cpus()));
        int maximumPoolSize = Math.max(corePoolSize, RuntimeUtil.cpus() * 2);
        return new ThreadPoolExecutor(
                corePoolSize,
                maximumPoolSize,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(DISPATCH_QUEUE_CAPACITY),
                ThreadPoolFactoryUtil.createThreadFactory(
                        "netty-client-dispatch", true),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static final class RequestContext {
        private final CompletableFuture<RpcResponse<Object>> resultFuture =
                new CompletableFuture<>();
        private final AtomicReference<Channel> channelReference = new AtomicReference<>();
        private final AtomicReference<Future<?>> dispatchReference = new AtomicReference<>();
        private final ChannelFutureListener closeListener;

        private RequestContext(RpcRequest rpcRequest) {
            this.closeListener = future -> resultFuture.completeExceptionally(
                    new RpcException(
                            RpcStatusCode.UNAVAILABLE,
                            rpcRequest.getRequestId(),
                            "RPC channel closed before receiving a response",
                            null));
        }
    }
}

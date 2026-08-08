package github.javaguide.remoting.transport.netty.client;


import github.javaguide.config.RpcClientConfig;
import github.javaguide.enums.RpcErrorMessageEnum;
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
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * initialize and close Bootstrap object
 *
 * @author shuang.kou
 * @createTime 2020年05月29日 17:51:00
 */
@Slf4j
public final class NettyRpcClient implements RpcRequestTransport {
    private static final AtomicInteger REQUEST_ID_GENERATOR = new AtomicInteger();

    private final ServiceDiscovery serviceDiscovery;
    private final UnprocessedRequests unprocessedRequests;
    private final ChannelProvider channelProvider;
    private final Bootstrap bootstrap;
    private final EventLoopGroup eventLoopGroup;
    private final RpcClientConfig clientConfig;
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
        // initialize resources such as EventLoopGroup, Bootstrap
        eventLoopGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        RpcMessageCodec rpcMessageCodec = new RpcMessageCodec();
        bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                //  The timeout period of the connection.
                //  If this time is exceeded or the connection cannot be established, the connection fails.
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        clientConfig.getConnectTimeoutMillis())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        // If no data is sent to the server within 15 seconds, a heartbeat request is sent
                        p.addLast(new IdleStateHandler(0, 5, 0, TimeUnit.SECONDS));
                        // RPCMessageFrame  解码器
                        p.addLast(new RpcMessageFrameDecoder());
                        p.addLast(rpcMessageCodec);
                        p.addLast(new NettyRpcClientHandler(
                                unprocessedRequests, channelProvider));
                    }
                });
    }

    /**
     * connect server and get the channel ,so that you can send rpc message to server
     *
     * @param inetSocketAddress server address
     * @return the channel
     */
    public Channel doConnect(InetSocketAddress inetSocketAddress) {
        CompletableFuture<Channel> completableFuture = new CompletableFuture<>();
        bootstrap.connect(inetSocketAddress).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                log.info("The client has connected [{}] successful!", inetSocketAddress.toString());
                completableFuture.complete(future.channel());
            } else {
                completableFuture.completeExceptionally(future.cause());
            }
        });
        try {
            return completableFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcException("Interrupted while connecting to " + inetSocketAddress, e);
        } catch (ExecutionException e) {
            throw new RpcException("Failed to connect to " + inetSocketAddress, e.getCause());
        }
    }

    @Override
    public CompletableFuture<RpcResponse<Object>> sendRpcRequest(RpcRequest rpcRequest) {
        if (closed.get()) {
            throw new RpcException("RPC client is closed");
        }
        if (rpcRequest == null || rpcRequest.getRequestId() == null
                || rpcRequest.getRequestId().trim().isEmpty()) {
            throw new IllegalArgumentException("RPC request and requestId must not be blank");
        }
        CompletableFuture<RpcResponse<Object>> resultFuture = new CompletableFuture<>();
        // 1. 获取服务的地址
        InetSocketAddress inetSocketAddress = serviceDiscovery.lookupService(rpcRequest);
        // 2. 获取channel
        Channel channel = getChannel(inetSocketAddress);
        if (channel.isActive()) {
            // 3.发送请求
            RpcMessage rpcMessage = RpcMessage.builder().data(rpcRequest)
                    .requestId(REQUEST_ID_GENERATOR.incrementAndGet())
                    .codec(clientConfig.getSerializationCode())
                    .compress(clientConfig.getCompressCode())
                    .messageType(RpcConstants.REQUEST_TYPE).build();
            ScheduledFuture<?> timeoutFuture;
            synchronized (lifecycleLock) {
                if (closed.get()) {
                    channel.close();
                    throw new RpcException("RPC client is closed");
                }
                unprocessedRequests.put(rpcRequest.getRequestId(), resultFuture);
                timeoutFuture = eventLoopGroup.next().schedule(
                        () -> resultFuture.completeExceptionally(new RpcException(
                                "RPC request timed out after "
                                        + clientConfig.getRequestTimeoutMillis() + " ms: "
                                        + rpcRequest.getRequestId(),
                                new TimeoutException(rpcRequest.getRequestId()))),
                        clientConfig.getRequestTimeoutMillis(), TimeUnit.MILLISECONDS);
            }
            ChannelFutureListener closeListener = future -> resultFuture.completeExceptionally(
                    new RpcException("RPC channel closed before receiving response: "
                            + rpcRequest.getRequestId()));
            channel.closeFuture().addListener(closeListener);
            resultFuture.whenComplete((response, throwable) -> {
                timeoutFuture.cancel(false);
                unprocessedRequests.remove(rpcRequest.getRequestId());
                channel.closeFuture().removeListener(closeListener);
            });
            channel.writeAndFlush(rpcMessage).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    log.info("Sent RPC request requestId={} wireRequestId={}",
                            rpcRequest.getRequestId(), rpcMessage.getRequestId());
                } else {
                    future.channel().close();
                    resultFuture.completeExceptionally(future.cause());
                    log.error("Send failed:", future.cause());
                }
            });
        } else {
            throw new RpcException(RpcErrorMessageEnum.CLIENT_CONNECT_SERVER_FAILURE,
                    inetSocketAddress.toString());
        }
        return resultFuture;
    }

    public Channel getChannel(InetSocketAddress inetSocketAddress) {
        Channel channel = channelProvider.get(inetSocketAddress);
        if (channel != null) {
            return channel;
        }
        synchronized (channelProvider) {
            channel = channelProvider.get(inetSocketAddress);
            if (channel == null) {
                channel = doConnect(inetSocketAddress);
                if (closed.get()) {
                    channel.close();
                    throw new RpcException("RPC client is closed");
                }
                channelProvider.set(inetSocketAddress, channel);
            }
            return channel;
        }
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (lifecycleLock) {
            channelProvider.closeAll();
            unprocessedRequests.failAll(new RpcException(
                    "RPC client is closed", new IllegalStateException("client closed")));
        }
        eventLoopGroup.shutdownGracefully().syncUninterruptibly();
    }
}

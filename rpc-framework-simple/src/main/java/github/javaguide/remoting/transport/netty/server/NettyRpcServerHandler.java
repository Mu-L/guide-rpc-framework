package github.javaguide.remoting.transport.netty.server;

import github.javaguide.enums.RpcStatusCode;
import github.javaguide.factory.SingletonFactory;
import github.javaguide.remoting.constants.RpcConstants;
import github.javaguide.remoting.dto.RpcMessage;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.dto.RpcResponse;
import github.javaguide.remoting.handler.RpcRequestHandler;
import github.javaguide.remoting.handler.RpcResponseFactory;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.ImmediateEventExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

/**
 * Customize the ChannelHandler of the server to process the data sent by the client.
 * <p>
 * 如果继承自 SimpleChannelInboundHandler 的话就不要考虑 ByteBuf 的释放，
 * {@link SimpleChannelInboundHandler} 内部的 channelRead 方法会替你释放 ByteBuf，
 * 避免可能导致的内存泄露问题。详见《Netty进阶之路 跟着案例学 Netty》
 *
 * @author shuang.kou
 * @createTime 2020年05月25日 20:44:00
 */
@Slf4j
@ChannelHandler.Sharable
public class NettyRpcServerHandler extends ChannelInboundHandlerAdapter {

    private static final AttributeKey<EventExecutor> SERVICE_EXECUTOR_KEY =
            AttributeKey.valueOf(NettyRpcServerHandler.class, "serviceExecutor");

    private final RpcRequestHandler rpcRequestHandler;
    private final EventExecutorGroup serviceExecutorGroup;

    public NettyRpcServerHandler() {
        this(ImmediateEventExecutor.INSTANCE);
    }

    NettyRpcServerHandler(EventExecutorGroup serviceExecutorGroup) {
        this.rpcRequestHandler = SingletonFactory.getInstance(RpcRequestHandler.class);
        this.serviceExecutorGroup = Objects.requireNonNull(
                serviceExecutorGroup, "Service executor group cannot be null");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (!(msg instanceof RpcMessage)) {
                throw new IllegalArgumentException("Unsupported inbound message: " + msg);
            }
            RpcMessage requestMessage = (RpcMessage) msg;
            log.info("Received RPC message type={} wireRequestId={}",
                    requestMessage.getMessageType(), requestMessage.getRequestId());
            RpcMessage responseMessage = RpcMessage.builder()
                    .codec(requestMessage.getCodec())
                    .compress(requestMessage.getCompress())
                    .requestId(requestMessage.getRequestId())
                    .build();
            if (requestMessage.getMessageType() == RpcConstants.HEARTBEAT_REQUEST_TYPE) {
                responseMessage.setMessageType(RpcConstants.HEARTBEAT_RESPONSE_TYPE);
                responseMessage.setData(RpcConstants.PONG);
            } else if (requestMessage.getMessageType() == RpcConstants.REQUEST_TYPE) {
                responseMessage.setMessageType(RpcConstants.RESPONSE_TYPE);
                dispatchRequest(ctx, responseMessage, (RpcRequest) requestMessage.getData());
                return;
            } else {
                throw new IllegalArgumentException(
                        "Server cannot handle message type: " + requestMessage.getMessageType());
            }
            ctx.writeAndFlush(responseMessage).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        } finally {
            //Ensure that ByteBuf is released, otherwise there may be memory leaks
            ReferenceCountUtil.release(msg);
        }
    }

    private void dispatchRequest(ChannelHandlerContext ctx, RpcMessage responseMessage,
                                 RpcRequest rpcRequest) {
        try {
            serviceExecutor(ctx).execute(() -> handleRequest(rpcRequest)
                    .whenComplete((rpcResponse, throwable) -> {
                        RpcResponse<Object> response = throwable == null
                                ? rpcResponse
                                : failureResponse(rpcRequest, throwable);
                        responseMessage.setData(response);
                        ctx.writeAndFlush(responseMessage)
                                .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                    }));
        } catch (RejectedExecutionException e) {
            log.warn("RPC service executor rejected request requestId={}",
                    rpcRequest == null ? null : rpcRequest.getRequestId(), e);
            responseMessage.setData(RpcResponse.fail(
                    RpcStatusCode.RESOURCE_EXHAUSTED,
                    rpcRequest == null ? null : rpcRequest.getRequestId(),
                    "RPC service executor is overloaded"));
            ctx.writeAndFlush(responseMessage)
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        }
    }

    private EventExecutor serviceExecutor(ChannelHandlerContext ctx) {
        Attribute<EventExecutor> attribute = ctx.channel().attr(SERVICE_EXECUTOR_KEY);
        EventExecutor executor = attribute.get();
        if (executor != null) {
            return executor;
        }
        EventExecutor selected = serviceExecutorGroup.next();
        EventExecutor existing = attribute.setIfAbsent(selected);
        return existing == null ? selected : existing;
    }

    private CompletionStage<RpcResponse<Object>> handleRequest(RpcRequest rpcRequest) {
        String requestId = rpcRequest == null ? null : rpcRequest.getRequestId();
        try {
            Object result = rpcRequestHandler.handle(rpcRequest);
            if (result instanceof CompletionStage<?> resultStage) {
                return resultStage.handle((value, throwable) -> {
                    if (throwable != null) {
                        return failureResponse(rpcRequest, throwable);
                    }
                    log.info("Completed asynchronous RPC request requestId={}", requestId);
                    return RpcResponse.success(value, requestId);
                });
            }
            log.info("Completed RPC request requestId={}", requestId);
            return CompletableFuture.completedFuture(
                    RpcResponse.success(result, requestId));
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(
                    failureResponse(rpcRequest, exception));
        }
    }

    private RpcResponse<Object> failureResponse(RpcRequest rpcRequest, Throwable throwable) {
        String requestId = rpcRequest == null ? null : rpcRequest.getRequestId();
        RpcStatusCode statusCode = RpcResponseFactory.statusOf(throwable);
        if (statusCode == RpcStatusCode.INTERNAL
                || statusCode == RpcStatusCode.UNKNOWN
                || statusCode == RpcStatusCode.DATA_LOSS) {
            log.error("RPC invocation failed status={} requestId={} service={} method={}",
                    statusCode, requestId,
                    rpcRequest == null ? null : rpcRequest.getInterfaceName(),
                    rpcRequest == null ? null : rpcRequest.getMethodName(), throwable);
        } else {
            log.warn("RPC invocation rejected status={} requestId={} service={} method={}",
                    statusCode, requestId,
                    rpcRequest == null ? null : rpcRequest.getInterfaceName(),
                    rpcRequest == null ? null : rpcRequest.getMethodName());
        }
        return RpcResponseFactory.failure(requestId, throwable);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleState state = ((IdleStateEvent) evt).state();
            if (state == IdleState.READER_IDLE) {
                log.info("idle check happen, so close the connection");
                ctx.close();
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Server transport exception", cause);
        ctx.close();
    }
}

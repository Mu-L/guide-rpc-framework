package github.javaguide.remoting.transport.netty.server;

import github.javaguide.config.RpcServiceConfig;
import github.javaguide.enums.CompressTypeEnum;
import github.javaguide.enums.RpcResponseCodeEnum;
import github.javaguide.enums.SerializationTypeEnum;
import github.javaguide.factory.SingletonFactory;
import github.javaguide.provider.ServiceProvider;
import github.javaguide.provider.impl.ZkServiceProviderImpl;
import github.javaguide.remoting.constants.RpcConstants;
import github.javaguide.remoting.dto.RpcMessage;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.dto.RpcResponse;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyRpcServerHandlerTest {

    @BeforeAll
    static void registerService() {
        ServiceProvider serviceProvider = SingletonFactory.getInstance(ZkServiceProviderImpl.class);
        serviceProvider.addService(RpcServiceConfig.builder()
                .service(new TeachingServiceImpl())
                .group("")
                .version("")
                .build());
    }

    @Test
    void shouldReturnFailureResponseWithoutClosingChannelWhenServiceThrows() {
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRpcServerHandler());

        channel.writeInbound(requestMessage("failing-request", "fail"));
        RpcMessage responseMessage = channel.readOutbound();
        RpcResponse<?> response = (RpcResponse<?>) responseMessage.getData();

        assertEquals(RpcConstants.RESPONSE_TYPE, responseMessage.getMessageType());
        assertEquals(SerializationTypeEnum.HESSIAN.getCode(), responseMessage.getCodec());
        assertEquals(CompressTypeEnum.GZIP.getCode(), responseMessage.getCompress());
        assertEquals("failing-request", response.getRequestId());
        assertEquals(RpcResponseCodeEnum.FAIL.getCode(), response.getCode());
        assertTrue(response.getMessage().contains("teaching failure"));
        assertTrue(channel.isActive());

        channel.finishAndReleaseAll();
    }

    @Test
    void shouldAllowSuccessfulServiceToReturnNull() {
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRpcServerHandler());

        channel.writeInbound(requestMessage("null-request", "returnNull"));
        RpcMessage responseMessage = channel.readOutbound();
        RpcResponse<?> response = (RpcResponse<?>) responseMessage.getData();

        assertEquals(RpcResponseCodeEnum.SUCCESS.getCode(), response.getCode());
        assertEquals("null-request", response.getRequestId());
        assertNull(response.getData());

        channel.finishAndReleaseAll();
    }

    @Test
    void shouldBeReusableAcrossIndependentClientChannels() {
        NettyRpcServerHandler handler = new NettyRpcServerHandler();
        EmbeddedChannel firstClient = new EmbeddedChannel(handler);
        EmbeddedChannel secondClient = new EmbeddedChannel(handler);

        firstClient.writeInbound(requestMessage("first-request", "returnNull"));
        secondClient.writeInbound(requestMessage("second-request", "returnNull"));

        assertEquals("first-request", responseFrom(firstClient).getRequestId());
        assertEquals("second-request", responseFrom(secondClient).getRequestId());
        firstClient.finishAndReleaseAll();
        secondClient.finishAndReleaseAll();
    }

    @Test
    void shouldReturnFailureResponseForInvalidRequestWithoutClosingChannel() {
        RpcRequest invalidRequest = RpcRequest.builder()
                .requestId("invalid-request")
                .methodName("returnNull")
                .parameters(null)
                .paramTypes(new Class<?>[0])
                .group("")
                .version("")
                .build();
        RpcMessage requestMessage = RpcMessage.builder()
                .requestId(43)
                .messageType(RpcConstants.REQUEST_TYPE)
                .codec(SerializationTypeEnum.HESSIAN.getCode())
                .compress(CompressTypeEnum.GZIP.getCode())
                .data(invalidRequest)
                .build();
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRpcServerHandler());

        channel.writeInbound(requestMessage);
        RpcMessage responseMessage = channel.readOutbound();
        RpcResponse<?> response = (RpcResponse<?>) responseMessage.getData();

        assertEquals(43, responseMessage.getRequestId());
        assertEquals("invalid-request", response.getRequestId());
        assertEquals(RpcResponseCodeEnum.FAIL.getCode(), response.getCode());
        assertTrue(channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldProcessRequestWithDedicatedServiceExecutor() {
        DefaultEventExecutorGroup executorGroup = new DefaultEventExecutorGroup(1);
        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyRpcServerHandler(executorGroup));
        try {
            channel.writeInbound(requestMessage("offloaded-request", "returnNull"));
            executorGroup.submit(() -> { }).syncUninterruptibly();
            channel.runPendingTasks();

            RpcMessage responseMessage = channel.readOutbound();
            RpcResponse<?> response = (RpcResponse<?>) responseMessage.getData();
            assertEquals("offloaded-request", response.getRequestId());
            assertEquals(RpcResponseCodeEnum.SUCCESS.getCode(), response.getCode());
        } finally {
            channel.finishAndReleaseAll();
            executorGroup.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static RpcResponse<?> responseFrom(EmbeddedChannel channel) {
        RpcMessage responseMessage = channel.readOutbound();
        assertEquals(42, responseMessage.getRequestId());
        return (RpcResponse<?>) responseMessage.getData();
    }

    private static RpcMessage requestMessage(String requestId, String methodName) {
        RpcRequest request = RpcRequest.builder()
                .requestId(requestId)
                .interfaceName(TeachingService.class.getCanonicalName())
                .methodName(methodName)
                .parameters(null)
                .paramTypes(new Class<?>[0])
                .group("")
                .version("")
                .build();
        return RpcMessage.builder()
                .requestId(42)
                .messageType(RpcConstants.REQUEST_TYPE)
                .codec(SerializationTypeEnum.HESSIAN.getCode())
                .compress(CompressTypeEnum.GZIP.getCode())
                .data(request)
                .build();
    }

    public interface TeachingService {
        String fail();

        String returnNull();
    }

    public static class TeachingServiceImpl implements TeachingService {
        @Override
        public String fail() {
            throw new IllegalStateException("teaching failure");
        }

        @Override
        public String returnNull() {
            return null;
        }
    }
}

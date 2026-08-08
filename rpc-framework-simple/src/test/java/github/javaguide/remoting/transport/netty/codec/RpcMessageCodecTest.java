package github.javaguide.remoting.transport.netty.codec;

import github.javaguide.enums.CompressTypeEnum;
import github.javaguide.enums.SerializationTypeEnum;
import github.javaguide.remoting.constants.RpcConstants;
import github.javaguide.remoting.dto.RpcMessage;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.dto.RpcResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcMessageCodecTest {

    @Test
    void shouldRoundTripRequestAndPreserveProtocolMetadata() {
        RpcRequest request = RpcRequest.builder()
                .requestId("application-request-id")
                .interfaceName("example.EchoService")
                .methodName("echo")
                .parameters(new Object[]{"hello"})
                .paramTypes(new Class<?>[]{String.class})
                .group("")
                .version("")
                .build();
        RpcMessage message = RpcMessage.builder()
                .requestId(7)
                .messageType(RpcConstants.REQUEST_TYPE)
                .codec(SerializationTypeEnum.HESSIAN.getCode())
                .compress(CompressTypeEnum.GZIP.getCode())
                .data(request)
                .build();

        EmbeddedChannel encoder = new EmbeddedChannel(new RpcMessageCodec());
        assertTrue(encoder.writeOutbound(message));
        ByteBuf encoded = encoder.readOutbound();
        assertEquals(message.getCompress(), encoded.getByte(11));

        EmbeddedChannel decoder = new EmbeddedChannel(
                new RpcMessageFrameDecoder(), new RpcMessageCodec());
        assertTrue(decoder.writeInbound(encoded));
        RpcMessage decoded = decoder.readInbound();
        RpcRequest decodedRequest = (RpcRequest) decoded.getData();

        assertEquals(message.getCodec(), decoded.getCodec());
        assertEquals(message.getCompress(), decoded.getCompress());
        assertEquals(message.getRequestId(), decoded.getRequestId());
        assertEquals(request.getRequestId(), decodedRequest.getRequestId());
        assertEquals(request.getMethodName(), decodedRequest.getMethodName());

        encoder.finishAndReleaseAll();
        decoder.finishAndReleaseAll();
    }

    @Test
    void shouldRejectUnknownSerializerDuringEncoding() {
        RpcMessage message = RpcMessage.builder()
                .requestId(8)
                .messageType(RpcConstants.REQUEST_TYPE)
                .codec((byte) 127)
                .compress(CompressTypeEnum.GZIP.getCode())
                .data(sampleRequest())
                .build();
        EmbeddedChannel channel = new EmbeddedChannel(new RpcMessageCodec());

        assertThrows(EncoderException.class, () -> channel.writeOutbound(message));

        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRejectInvalidMagicNumber() {
        ByteBuf frame = heartbeatFrame();
        frame.setByte(0, 'x');
        EmbeddedChannel channel = new EmbeddedChannel(new RpcMessageCodec());

        assertThrows(DecoderException.class, () -> channel.writeInbound(frame));

        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRejectRequestWithoutBody() {
        ByteBuf frame = heartbeatFrame();
        frame.setByte(9, RpcConstants.REQUEST_TYPE);
        EmbeddedChannel channel = new EmbeddedChannel(new RpcMessageCodec());

        assertThrows(DecoderException.class, () -> channel.writeInbound(frame));

        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRejectOversizedBodyBeforeCompression() {
        RpcMessage message = RpcMessage.builder()
                .requestId(10)
                .messageType(RpcConstants.RESPONSE_TYPE)
                .codec(SerializationTypeEnum.HESSIAN.getCode())
                .compress(CompressTypeEnum.GZIP.getCode())
                .data(RpcResponse.success(
                        new byte[RpcConstants.MAX_DECOMPRESSED_BODY_LENGTH + 1],
                        "oversized-response"))
                .build();
        EmbeddedChannel channel = new EmbeddedChannel(new RpcMessageCodec());

        assertThrows(EncoderException.class, () -> channel.writeOutbound(message));

        channel.finishAndReleaseAll();
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyCodecClassesShouldUseTheValidatedProtocolImplementation() {
        RpcMessage message = RpcMessage.builder()
                .requestId(77)
                .messageType(RpcConstants.REQUEST_TYPE)
                .codec(SerializationTypeEnum.HESSIAN.getCode())
                .compress(CompressTypeEnum.GZIP.getCode())
                .data(sampleRequest())
                .build();
        EmbeddedChannel encoder = new EmbeddedChannel(new RpcMessageEncoder());
        EmbeddedChannel decoder = new EmbeddedChannel(new RpcMessageDecoder());

        assertTrue(encoder.writeOutbound(message));
        assertTrue(decoder.writeInbound((ByteBuf) encoder.readOutbound()));
        RpcMessage decoded = decoder.readInbound();

        assertEquals(77, decoded.getRequestId());
        assertEquals(CompressTypeEnum.GZIP.getCode(), decoded.getCompress());
        encoder.finishAndReleaseAll();
        decoder.finishAndReleaseAll();
    }

    private static RpcRequest sampleRequest() {
        return RpcRequest.builder()
                .requestId("sample-request")
                .interfaceName("example.EchoService")
                .methodName("echo")
                .parameters(new Object[]{"hello"})
                .paramTypes(new Class<?>[]{String.class})
                .group("")
                .version("")
                .build();
    }

    private static ByteBuf heartbeatFrame() {
        ByteBuf frame = Unpooled.buffer(RpcConstants.HEAD_LENGTH);
        frame.writeBytes(RpcConstants.MAGIC_NUMBER);
        frame.writeByte(RpcConstants.VERSION);
        frame.writeInt(RpcConstants.HEAD_LENGTH);
        frame.writeByte(RpcConstants.HEARTBEAT_REQUEST_TYPE);
        frame.writeByte(SerializationTypeEnum.HESSIAN.getCode());
        frame.writeByte(CompressTypeEnum.GZIP.getCode());
        frame.writeInt(9);
        return frame;
    }
}

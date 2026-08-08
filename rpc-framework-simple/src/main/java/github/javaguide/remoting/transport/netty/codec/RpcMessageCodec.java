package github.javaguide.remoting.transport.netty.codec;

import github.javaguide.compress.Compress;
import github.javaguide.enums.CompressTypeEnum;
import github.javaguide.enums.SerializationTypeEnum;
import github.javaguide.extension.ExtensionLoader;
import github.javaguide.remoting.constants.RpcConstants;
import github.javaguide.remoting.dto.RpcMessage;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.dto.RpcResponse;
import github.javaguide.serialize.Serializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

import static github.javaguide.remoting.constants.RpcConstants.HEARTBEAT_REQUEST_TYPE;
import static github.javaguide.remoting.constants.RpcConstants.HEARTBEAT_RESPONSE_TYPE;

/**
 * Shared encoder and decoder for the RPC wire protocol.
 */
@Slf4j
@ChannelHandler.Sharable
public class RpcMessageCodec extends MessageToMessageCodec<ByteBuf, RpcMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, RpcMessage rpcMessage, List<Object> outList) {
        outList.add(encodeFrame(ctx, rpcMessage));
    }

    static ByteBuf encodeFrame(ChannelHandlerContext ctx, RpcMessage rpcMessage) {
        validateMessageType(rpcMessage.getMessageType());
        byte[] bodyBytes = serializeBody(rpcMessage);
        int fullLength = RpcConstants.HEAD_LENGTH + (bodyBytes == null ? 0 : bodyBytes.length);
        if (fullLength > RpcConstants.MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException(
                    "RPC frame exceeds " + RpcConstants.MAX_FRAME_LENGTH + " bytes");
        }
        ByteBuf out = ctx.alloc().buffer(fullLength);
        boolean success = false;
        try {
            out.writeBytes(RpcConstants.MAGIC_NUMBER);
            out.writeByte(RpcConstants.VERSION);
            out.writeInt(fullLength);
            out.writeByte(rpcMessage.getMessageType());
            out.writeByte(rpcMessage.getCodec());
            out.writeByte(rpcMessage.getCompress());
            out.writeInt(rpcMessage.getRequestId());
            if (bodyBytes != null) {
                out.writeBytes(bodyBytes);
            }
            success = true;
            return out;
        } finally {
            if (!success) {
                out.release();
            }
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> outList) {
        outList.add(decodeFrame(in));
    }

    static RpcMessage decodeFrame(ByteBuf in) {
        int actualLength = in.readableBytes();
        checkMagicNumber(in);
        checkVersion(in);
        int declaredLength = in.readInt();
        if (declaredLength != actualLength || declaredLength < RpcConstants.HEAD_LENGTH) {
            throw new IllegalArgumentException(
                    "Invalid RPC frame length: declared=" + declaredLength + ", actual=" + actualLength);
        }

        byte messageType = in.readByte();
        validateMessageType(messageType);
        byte codecType = in.readByte();
        byte compressType = in.readByte();
        int requestId = in.readInt();
        RpcMessage rpcMessage = RpcMessage.builder()
                .messageType(messageType)
                .codec(codecType)
                .compress(compressType)
                .requestId(requestId)
                .build();

        if (isHeartbeat(messageType)) {
            if (declaredLength != RpcConstants.HEAD_LENGTH) {
                throw new IllegalArgumentException("Heartbeat frame must not contain a body");
            }
            rpcMessage.setData(messageType == HEARTBEAT_REQUEST_TYPE
                    ? RpcConstants.PING : RpcConstants.PONG);
            return rpcMessage;
        }

        int bodyLength = declaredLength - RpcConstants.HEAD_LENGTH;
        if (bodyLength <= 0) {
            throw new IllegalArgumentException("RPC request/response frame must contain a body");
        }
        byte[] bodyBytes = new byte[bodyLength];
        in.readBytes(bodyBytes);
        Compress compress = ExtensionLoader.getExtensionLoader(Compress.class)
                .getExtension(CompressTypeEnum.getName(compressType));
        log.debug("before decompress request body size: [{}]", bodyBytes.length);
        bodyBytes = compress.decompress(bodyBytes);
        log.debug("after decompress request body size: [{}]", bodyBytes.length);
        Serializer serializer = ExtensionLoader.getExtensionLoader(Serializer.class)
                .getExtension(SerializationTypeEnum.getName(codecType));
        rpcMessage.setData(messageType == RpcConstants.REQUEST_TYPE
                ? serializer.deserialize(bodyBytes, RpcRequest.class)
                : serializer.deserialize(bodyBytes, RpcResponse.class));
        return rpcMessage;
    }

    private static byte[] serializeBody(RpcMessage rpcMessage) {
        if (isHeartbeat(rpcMessage.getMessageType())) {
            return null;
        }
        if (rpcMessage.getData() == null) {
            throw new IllegalArgumentException("RPC request/response data cannot be null");
        }
        Serializer serializer = ExtensionLoader.getExtensionLoader(Serializer.class)
                .getExtension(SerializationTypeEnum.getName(rpcMessage.getCodec()));
        byte[] bodyBytes = serializer.serialize(rpcMessage.getData());
        if (bodyBytes.length > RpcConstants.MAX_DECOMPRESSED_BODY_LENGTH) {
            throw new IllegalArgumentException(
                    "Serialized RPC body exceeds "
                            + RpcConstants.MAX_DECOMPRESSED_BODY_LENGTH + " bytes");
        }
        Compress compress = ExtensionLoader.getExtensionLoader(Compress.class)
                .getExtension(CompressTypeEnum.getName(rpcMessage.getCompress()));
        log.debug("before compress request body size: [{}]", bodyBytes.length);
        byte[] compressed = compress.compress(bodyBytes);
        log.debug("after compress request body size: [{}]", compressed.length);
        return compressed;
    }

    private static void checkVersion(ByteBuf in) {
        byte version = in.readByte();
        if (version != RpcConstants.VERSION) {
            throw new IllegalArgumentException("Unsupported RPC protocol version: " + version);
        }
    }

    private static void checkMagicNumber(ByteBuf in) {
        byte[] actual = new byte[RpcConstants.MAGIC_NUMBER.length];
        in.readBytes(actual);
        if (!Arrays.equals(actual, RpcConstants.MAGIC_NUMBER)) {
            throw new IllegalArgumentException("Unknown magic code: " + Arrays.toString(actual));
        }
    }

    private static boolean isHeartbeat(byte messageType) {
        return messageType == HEARTBEAT_REQUEST_TYPE || messageType == HEARTBEAT_RESPONSE_TYPE;
    }

    private static void validateMessageType(byte messageType) {
        if (messageType != RpcConstants.REQUEST_TYPE
                && messageType != RpcConstants.RESPONSE_TYPE
                && !isHeartbeat(messageType)) {
            throw new IllegalArgumentException("Unknown RPC message type: " + (messageType & 0xFF));
        }
    }
}

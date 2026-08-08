package github.javaguide.remoting.transport.netty.codec;

import github.javaguide.remoting.dto.RpcMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Legacy encoder kept for source compatibility.
 *
 * @deprecated use the shared {@link RpcMessageCodec} in new pipelines.
 */
@Deprecated
public class RpcMessageEncoder extends MessageToByteEncoder<RpcMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, RpcMessage rpcMessage, ByteBuf out) {
        ByteBuf encoded = RpcMessageCodec.encodeFrame(ctx, rpcMessage);
        try {
            out.writeBytes(encoded);
        } finally {
            encoded.release();
        }
    }
}

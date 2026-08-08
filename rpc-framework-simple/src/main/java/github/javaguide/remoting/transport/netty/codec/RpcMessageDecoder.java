package github.javaguide.remoting.transport.netty.codec;

import github.javaguide.remoting.constants.RpcConstants;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * Legacy frame decoder kept for source compatibility.
 *
 * @deprecated use {@link RpcMessageFrameDecoder} followed by the shared
 * {@link RpcMessageCodec} in new pipelines.
 */
@Deprecated
public class RpcMessageDecoder extends LengthFieldBasedFrameDecoder {

    public RpcMessageDecoder() {
        this(RpcConstants.MAX_FRAME_LENGTH, 5, 4, -9, 0);
    }

    public RpcMessageDecoder(int maxFrameLength, int lengthFieldOffset,
                             int lengthFieldLength, int lengthAdjustment,
                             int initialBytesToStrip) {
        super(maxFrameLength, lengthFieldOffset, lengthFieldLength,
                lengthAdjustment, initialBytesToStrip);
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        Object decoded = super.decode(ctx, in);
        if (!(decoded instanceof ByteBuf)) {
            return decoded;
        }
        ByteBuf frame = (ByteBuf) decoded;
        try {
            return RpcMessageCodec.decodeFrame(frame);
        } finally {
            frame.release();
        }
    }
}

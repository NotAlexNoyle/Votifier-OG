package com.vexsoftware.votifier.net.protocol;

import com.vexsoftware.votifier.net.VotifierSession;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Attempts to determine if original protocol or protocol v2 is being used.
 */
public class VotifierProtocolDifferentiator extends ByteToMessageDecoder {
    private static final short PROTOCOL_2_MAGIC = 0x733A;
    private final boolean testMode;

    public VotifierProtocolDifferentiator() {
        this(false);
    }

    public VotifierProtocolDifferentiator(boolean testMode) {
        this.testMode = testMode;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buf, List<Object> list) throws Exception {
        // Determine the number of bytes that are available.
        int readable = buf.readableBytes();
        buf.retain(); // TODO: Is this needed?

        if (readable < 2) {
            // Some retarded voting sites (PMC?) seem to send empty buffers for no good reason.
            // TODO: How can we handle this?
            throw new CorruptedFrameException("Frame is too short");
        }

        short readMagic = buf.readShort();

        // Reset reader index again
        buf.readerIndex(0);

        VotifierSession session = ctx.channel().attr(VotifierSession.KEY).get();

        if (readMagic == PROTOCOL_2_MAGIC) {
            // Short 0x733A + Message = Protocol v2 Vote
            if (!testMode) {
                ctx.pipeline().addAfter("protocolDifferentiator", "protocol2LengthDecoder", new LengthFieldBasedFrameDecoder(1024, 2, 2, 0, 4));
                ctx.pipeline().addAfter("protocol2LengthDecoder", "protocol2StringDecoder", new StringDecoder(StandardCharsets.UTF_8));
                ctx.pipeline().addAfter("protocol2StringDecoder", "protocol2VoteDecoder", new VotifierProtocol2Decoder());
                ctx.pipeline().addAfter("protocol2VoteDecoder", "protocol2StringEncoder", new StringEncoder(StandardCharsets.UTF_8));
                ctx.pipeline().remove(this);
            }
            session.setVersion(VotifierSession.ProtocolVersion.TWO);
        } else {
            // Probably Protocol v1 Vote Message
            if (!testMode) {
                ctx.pipeline().addAfter("protocolDifferentiator", "protocol1Handler", new VotifierProtocol1Decoder());
                ctx.pipeline().remove(this);
            }
            session.setVersion(VotifierSession.ProtocolVersion.ONE);
        }
    }
}

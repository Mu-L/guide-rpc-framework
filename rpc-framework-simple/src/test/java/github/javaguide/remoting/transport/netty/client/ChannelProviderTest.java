package github.javaguide.remoting.transport.netty.client;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertSame;

class ChannelProviderTest {

    @Test
    void inactiveOldChannelMustNotRemoveReplacementChannel() {
        ChannelProvider provider = new ChannelProvider();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 9998);
        EmbeddedChannel oldChannel = new EmbeddedChannel();
        EmbeddedChannel replacementChannel = new EmbeddedChannel();
        try {
            provider.set(address, oldChannel);
            provider.set(address, replacementChannel);

            provider.remove(address, oldChannel);

            assertSame(replacementChannel, provider.get(address));
        } finally {
            oldChannel.finishAndReleaseAll();
            replacementChannel.finishAndReleaseAll();
        }
    }
}

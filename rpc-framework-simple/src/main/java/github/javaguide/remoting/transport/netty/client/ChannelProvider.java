package github.javaguide.remoting.transport.netty.client;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * store and get Channel object
 *
 * @author shuang.kou
 * @createTime 2020年05月29日 16:36:00
 */
@Slf4j
public class ChannelProvider {

    private final Map<InetSocketAddress, Channel> channelMap;

    public ChannelProvider() {
        channelMap = new ConcurrentHashMap<>();
    }

    public Channel get(InetSocketAddress inetSocketAddress) {
        Channel channel = channelMap.get(inetSocketAddress);
        if (channel != null && channel.isActive()) {
            return channel;
        }
        if (channel != null) {
            channelMap.remove(inetSocketAddress, channel);
        }
        return null;
    }

    public void set(InetSocketAddress inetSocketAddress, Channel channel) {
        channelMap.put(inetSocketAddress, channel);
    }

    public void remove(InetSocketAddress inetSocketAddress, Channel channel) {
        // An inactive event from an old connection must not evict a newer connection that was
        // installed for the same server address during reconnect.
        channelMap.remove(inetSocketAddress, channel);
        log.info("Channel map size :[{}]", channelMap.size());
    }

    public void closeAll() {
        channelMap.forEach((address, channel) -> channel.close());
        channelMap.clear();
    }
}

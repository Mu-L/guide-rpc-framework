package github.javaguide.config;

import github.javaguide.registry.zk.util.CuratorUtils;
import github.javaguide.remoting.transport.netty.server.NettyRpcServer;
import github.javaguide.utils.concurrent.threadpool.ThreadPoolFactoryUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * When the server  is closed, do something such as unregister all services
 *
 * @author shuang.kou
 * @createTime 2020年06月04日 13:11:00
 */
@Slf4j
public class CustomShutdownHook {
    private static final CustomShutdownHook CUSTOM_SHUTDOWN_HOOK = new CustomShutdownHook();
    private final AtomicBoolean registered = new AtomicBoolean();

    public static CustomShutdownHook getCustomShutdownHook() {
        return CUSTOM_SHUTDOWN_HOOK;
    }

    public void clearAll() {
        if (!registered.compareAndSet(false, true)) {
            return;
        }
        log.info("addShutdownHook for clearAll");
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownResources,
                "rpc-server-shutdown-hook"));
    }

    private void shutdownResources() {
        clearRegistry();
        try {
            CuratorUtils.closeZkClient();
        } finally {
            ThreadPoolFactoryUtil.shutDownAllThreadPool();
        }
    }

    /**
     * Removes services published by this server address without closing the shared ZooKeeper
     * client. Server transports call this when they stop while the JVM is still running.
     */
    public void clearRegistry() {
        try {
            clearRegistry(RpcServerAddressUtil.getServerAddress(NettyRpcServer.PORT));
        } catch (RuntimeException e) {
            log.error("Failed to resolve RPC server address during shutdown", e);
        }
    }

    public void clearRegistry(InetSocketAddress serverAddress) {
        try {
            org.apache.curator.framework.CuratorFramework client =
                    CuratorUtils.getExistingZkClient();
            if (client != null) {
                CuratorUtils.clearRegistry(client, serverAddress);
            }
        } catch (RuntimeException e) {
            log.error("Failed to clear RPC registry during shutdown", e);
        }
    }
}

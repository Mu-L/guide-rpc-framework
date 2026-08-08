package github.javaguide.remoting.transport.socket;

import github.javaguide.config.CustomShutdownHook;
import github.javaguide.config.RpcServiceConfig;
import github.javaguide.config.RpcServerAddressUtil;
import github.javaguide.factory.SingletonFactory;
import github.javaguide.provider.ServiceProvider;
import github.javaguide.provider.impl.ZkServiceProviderImpl;
import github.javaguide.remoting.constants.RpcConstants;
import github.javaguide.utils.concurrent.threadpool.ThreadPoolFactoryUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static github.javaguide.remoting.transport.netty.server.NettyRpcServer.PORT;

/**
 * @author shuang.kou
 * @createTime 2020年05月10日 08:01:00
 */
@Slf4j
public class SocketRpcServer {

    private final ExecutorService threadPool;
    private final ServiceProvider serviceProvider;


    public SocketRpcServer() {
        threadPool = ThreadPoolFactoryUtil.createCustomThreadPoolIfAbsent("socket-server-rpc-pool");
        serviceProvider = SingletonFactory.getInstance(ZkServiceProviderImpl.class);
    }

    public void registerService(RpcServiceConfig rpcServiceConfig) {
        serviceProvider.addService(rpcServiceConfig);
    }

    public void start() {
        CustomShutdownHook.getCustomShutdownHook().clearAll();
        InetSocketAddress publishedAddress = null;
        try (ServerSocket server = new ServerSocket()) {
            String host = RpcServerAddressUtil.getBindHost();
            server.bind(new InetSocketAddress(host, PORT));
            publishedAddress = RpcServerAddressUtil.getServerAddress(PORT);
            serviceProvider.publishAllServices(publishedAddress);
            Socket socket;
            while ((socket = server.accept()) != null) {
                log.info("client connected [{}]", socket.getInetAddress());
                try {
                    // Do not allow a peer that connects but never sends a request to occupy a
                    // worker thread forever in this blocking-I/O transport.
                    socket.setSoTimeout((int) RpcConstants.RPC_REQUEST_TIMEOUT_MILLIS);
                    threadPool.execute(new SocketRpcRequestHandlerRunnable(socket));
                } catch (IOException | RejectedExecutionException e) {
                    log.warn("Socket RPC client cannot be handed to a worker [{}]",
                            socket.getRemoteSocketAddress(), e);
                    closeQuietly(socket);
                }
            }
        } catch (IOException e) {
            log.error("occur IOException:", e);
        } finally {
            threadPool.shutdown();
            if (publishedAddress != null) {
                CustomShutdownHook.getCustomShutdownHook().clearRegistry(publishedAddress);
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            log.debug("Failed to close rejected client socket", e);
        }
    }

}

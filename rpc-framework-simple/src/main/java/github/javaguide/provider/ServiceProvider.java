package github.javaguide.provider;

import github.javaguide.config.RpcServiceConfig;

import java.net.InetSocketAddress;

/**
 * store and provide service object.
 *
 * @author shuang.kou
 * @createTime 2020年05月31日 16:52:00
 */
public interface ServiceProvider {

    /**
     * @param rpcServiceConfig rpc service related attributes
     */
    void addService(RpcServiceConfig rpcServiceConfig);

    /**
     * @param rpcServiceName rpc service name
     * @return service object
     */
    Object getService(String rpcServiceName);

    /**
     * @param rpcServiceConfig rpc service related attributes
     */
    void publishService(RpcServiceConfig rpcServiceConfig);

    /**
     * Publishes all locally registered services after the server has bound its listening socket.
     */
    void publishAllServices(InetSocketAddress serverAddress);

}

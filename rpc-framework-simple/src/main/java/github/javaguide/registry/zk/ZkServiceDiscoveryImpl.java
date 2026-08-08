package github.javaguide.registry.zk;

import github.javaguide.enums.LoadBalanceEnum;
import github.javaguide.enums.RpcStatusCode;
import github.javaguide.exception.RpcException;
import github.javaguide.extension.ExtensionLoader;
import github.javaguide.loadbalance.LoadBalance;
import github.javaguide.registry.ServiceDiscovery;
import github.javaguide.registry.zk.util.CuratorUtils;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.utils.CollectionUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * service discovery based on zookeeper
 *
 * @author shuang.kou
 * @createTime 2020年06月01日 15:16:00
 */
@Slf4j
public class ZkServiceDiscoveryImpl implements ServiceDiscovery {
    private final LoadBalance loadBalance;

    public ZkServiceDiscoveryImpl() {
        this.loadBalance = ExtensionLoader.getExtensionLoader(LoadBalance.class)
                .getExtension(LoadBalanceEnum.LOADBALANCENEW.getName());
    }

    @Override
    public InetSocketAddress lookupService(RpcRequest rpcRequest) {
        String rpcServiceName = rpcRequest.getRpcServiceName();
        CuratorFramework zkClient = CuratorUtils.getZkClient();
        List<String> serviceUrlList = CuratorUtils.getChildrenNodes(zkClient, rpcServiceName);
        if (CollectionUtil.isEmpty(serviceUrlList)) {
            throw new RpcException(RpcStatusCode.NOT_FOUND,
                    "No provider found for RPC service: " + rpcServiceName);
        }
        // load balancing
        String targetServiceUrl = loadBalance.selectServiceAddress(serviceUrlList, rpcRequest);
        log.info("Successfully found the service address:[{}]", targetServiceUrl);
        return parseServiceAddress(targetServiceUrl);
    }

    static InetSocketAddress parseServiceAddress(String targetServiceUrl) {
        if (targetServiceUrl == null) {
            throw invalidServiceAddress(null, null);
        }
        int portSeparator = targetServiceUrl.lastIndexOf(':');
        if (portSeparator <= 0 || portSeparator == targetServiceUrl.length() - 1) {
            throw invalidServiceAddress(targetServiceUrl, null);
        }
        String host = targetServiceUrl.substring(0, portSeparator);
        try {
            int port = Integer.parseInt(targetServiceUrl.substring(portSeparator + 1));
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("port out of range");
            }
            return new InetSocketAddress(host, port);
        } catch (IllegalArgumentException e) {
            throw invalidServiceAddress(targetServiceUrl, e);
        }
    }

    private static RpcException invalidServiceAddress(String address, Throwable cause) {
        return new RpcException(RpcStatusCode.DATA_LOSS,
                "Invalid service address: " + address,
                cause == null ? new IllegalArgumentException(String.valueOf(address)) : cause);
    }
}

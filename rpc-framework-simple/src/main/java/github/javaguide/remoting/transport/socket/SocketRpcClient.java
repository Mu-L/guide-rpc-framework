package github.javaguide.remoting.transport.socket;

import github.javaguide.config.RpcClientConfig;
import github.javaguide.enums.ServiceDiscoveryEnum;
import github.javaguide.exception.RpcException;
import github.javaguide.extension.ExtensionLoader;
import github.javaguide.registry.ServiceDiscovery;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.transport.RpcRequestTransport;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 基于 Socket 传输 RpcRequest
 *
 * @author shuang.kou
 * @createTime 2020年05月10日 18:40:00
 */
@Slf4j
public class SocketRpcClient implements RpcRequestTransport {
    private final ServiceDiscovery serviceDiscovery;
    private final RpcClientConfig clientConfig;

    public SocketRpcClient() {
        this(ExtensionLoader.getExtensionLoader(ServiceDiscovery.class)
                .getExtension(ServiceDiscoveryEnum.ZK.getName()), RpcClientConfig.load());
    }

    public SocketRpcClient(ServiceDiscovery serviceDiscovery) {
        this(serviceDiscovery, RpcClientConfig.load());
    }

    public SocketRpcClient(ServiceDiscovery serviceDiscovery, RpcClientConfig clientConfig) {
        this.serviceDiscovery = serviceDiscovery;
        this.clientConfig = clientConfig;
    }

    @Override
    public Object sendRpcRequest(RpcRequest rpcRequest) {
        InetSocketAddress inetSocketAddress = serviceDiscovery.lookupService(rpcRequest);
        try (Socket socket = new Socket()) {
            socket.connect(inetSocketAddress, clientConfig.getConnectTimeoutMillis());
            socket.setSoTimeout((int) Math.min(clientConfig.getRequestTimeoutMillis(),
                    Integer.MAX_VALUE));
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(socket.getOutputStream());
            // Send data to the server through the output stream
            objectOutputStream.writeObject(rpcRequest);
            objectOutputStream.flush();
            ObjectInputStream objectInputStream = new ObjectInputStream(socket.getInputStream());
            // Read RpcResponse from the input stream
            return objectInputStream.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RpcException("调用服务失败:", e);
        }
    }
}

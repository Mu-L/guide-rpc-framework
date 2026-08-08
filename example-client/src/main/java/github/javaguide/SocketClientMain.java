package github.javaguide;

import github.javaguide.config.RpcServiceConfig;
import github.javaguide.proxy.RpcClientProxy;
import github.javaguide.remoting.transport.socket.SocketRpcClient;

/**
 * @author shuang.kou
 * @createTime 2020年05月10日 07:25:00
 */
public class SocketClientMain {
    public static void main(String[] args) {
        RpcServiceConfig serviceConfig = new RpcServiceConfig();
        try (SocketRpcClient transport = new SocketRpcClient()) {
            RpcClientProxy clientProxy = new RpcClientProxy(transport, serviceConfig);

            HelloService helloService = clientProxy.getProxy(HelloService.class);
            System.out.println(helloService.hello(new Hello("sync", "synchronous call")));

            HelloServiceAsync asyncService = clientProxy.getAsyncProxy(
                    HelloServiceAsync.class, HelloService.class);
            asyncService.hello(new Hello("async", "non-blocking call"))
                    .thenAccept(System.out::println)
                    .join();
        }
    }
}

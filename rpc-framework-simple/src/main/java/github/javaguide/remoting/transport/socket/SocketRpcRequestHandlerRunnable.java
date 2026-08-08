package github.javaguide.remoting.transport.socket;

import github.javaguide.factory.SingletonFactory;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.dto.RpcResponse;
import github.javaguide.remoting.handler.RpcRequestHandler;
import github.javaguide.remoting.handler.RpcResponseFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.CompletionStage;

/**
 * @author shuang.kou
 * @createTime 2020年05月10日 09:18:00
 */
@Slf4j
public class SocketRpcRequestHandlerRunnable implements Runnable {
    private final Socket socket;
    private final RpcRequestHandler rpcRequestHandler;


    public SocketRpcRequestHandlerRunnable(Socket socket) {
        this.socket = socket;
        this.rpcRequestHandler = SingletonFactory.getInstance(RpcRequestHandler.class);
    }

    @Override
    public void run() {
        log.info("server handle message from client by thread: [{}]", Thread.currentThread().getName());
        try (Socket clientSocket = socket;
             ObjectInputStream objectInputStream = new ObjectInputStream(clientSocket.getInputStream());
             ObjectOutputStream objectOutputStream = new ObjectOutputStream(socket.getOutputStream())) {
            RpcRequest rpcRequest = (RpcRequest) objectInputStream.readObject();
            String requestId = rpcRequest == null ? null : rpcRequest.getRequestId();
            RpcResponse<Object> rpcResponse;
            try {
                Object result = rpcRequestHandler.handle(rpcRequest);
                if (result instanceof CompletionStage<?> resultStage) {
                    result = resultStage.toCompletableFuture().join();
                }
                rpcResponse = RpcResponse.success(result, requestId);
            } catch (RuntimeException e) {
                log.error("Remote invocation failed requestId={} service={} method={}",
                        requestId,
                        rpcRequest == null ? null : rpcRequest.getInterfaceName(),
                        rpcRequest == null ? null : rpcRequest.getMethodName(), e);
                rpcResponse = RpcResponseFactory.failure(requestId, e);
            }
            objectOutputStream.writeObject(rpcResponse);
            objectOutputStream.flush();
        } catch (IOException | ClassNotFoundException e) {
            log.error("occur exception:", e);
        }
    }

}

# 使用 CompletableFuture 实现真正的异步 RPC 调用

早期版本使用 Netty 的 `AttributeMap` 保存响应。客户端发送请求后，需要调用 `channel.closeFuture().sync()` 等待网络结果，同一个 `Channel` 上的并发请求也很难独立关联。后来引入 `CompletableFuture` 和 `requestId`，解决了请求与响应的配对问题，但公开代理仍然会执行 `Future.get()`。调用方看到的还是同步方法：

```java
String result = helloService.hello(request);
```

这一版继续向前走了一步：Socket 和 Netty 传输层统一返回 `CompletableFuture<RpcResponse<Object>>`，代理层同时提供同步接口和真正非阻塞的异步接口。错误也不再依赖一段临时拼接的字符串，而是通过稳定状态码、`requestId` 和类型化异常传播。

## 从响应关联到异步公开 API

`UnprocessedRequests` 保存 `requestId` 和 Future 的对应关系。客户端收到响应时，根据 `requestId` 找到 Future 并完成它：

```java
public void complete(RpcResponse<Object> rpcResponse) {
    CompletableFuture<RpcResponse<Object>> future =
            unprocessedRequests.remove(rpcResponse.getRequestId());
    if (future != null) {
        future.complete(rpcResponse);
    }
}
```

这一步让 Netty 可以在同一个 `Channel` 上并发发送多个请求，但它只解决了传输层的响应关联。只要代理层马上调用 `future.get()`，业务线程仍然会一直等到远端返回。

当前传输接口不再使用含糊的 `Object`：

```java
@SPI
public interface RpcRequestTransport {
    CompletableFuture<RpcResponse<Object>> sendRpcRequest(RpcRequest rpcRequest);
}
```

Socket 内部虽然使用阻塞 I/O，但服务发现、连接、读写都在有界工作线程池中执行。调用 `sendRpcRequest()` 的线程只负责创建请求上下文、注册超时任务并提交工作，随后立即拿到 Future。Netty 同样把可能阻塞的服务发现和首次建连移到独立调度线程池。

## 为什么使用异步镜像接口

服务提供方继续实现普通同步接口：

```java
public interface HelloService {
    String hello(Hello hello);
}
```

客户端增加一个方法名和参数相同、返回 `CompletableFuture<T>` 的镜像接口：

```java
public interface HelloServiceAsync {
    CompletableFuture<String> hello(Hello hello);
}
```

创建代理时同时传入异步接口和远程服务接口：

```java
RpcServiceConfig serviceConfig = RpcServiceConfig.builder()
        .group("test1")
        .version("version1")
        .build();

try (NettyRpcClient transport = new NettyRpcClient()) {
    RpcClientProxy clientProxy = new RpcClientProxy(transport, serviceConfig);
    HelloServiceAsync asyncService = clientProxy.getAsyncProxy(
            HelloServiceAsync.class, HelloService.class);

    CompletableFuture<String> resultFuture = asyncService.hello(
            new Hello("async", "non-blocking call"));

    resultFuture
            .thenApply(String::toUpperCase)
            .thenAccept(System.out::println)
            .join();
}
```

异步接口只存在于客户端。代理发送的接口名仍然是 `HelloService`，服务端不需要维护一份重复的 `HelloServiceAsync` 实现。创建代理时会检查这些条件：

- 异步接口和远程服务类型都必须是接口。
- 异步方法必须返回 `CompletableFuture` 或 `CompletionStage`。
- 远程接口中必须存在方法名和参数列表相同的方法。
- Future 中声明的结果类型必须与远程方法返回类型兼容。

原有同步写法仍然可用：

```java
HelloService service = clientProxy.getProxy(HelloService.class);
String result = service.hello(request);
```

同步代理和异步代理共用请求构造、服务发现、发送、超时、响应校验和错误映射。同步代理只在最外层执行等待，异步代理直接把转换后的 Future 交给调用方。

## 一次异步请求如何完成

以 Netty 客户端为例，一次调用会经过这些步骤：

1. `RpcClientProxy` 创建带唯一 `requestId` 的 `RpcRequest`。
2. `NettyRpcClient` 创建 `RequestContext`，先注册覆盖整个请求生命周期的超时任务。
3. 调度线程执行服务发现，获取或创建可复用的 `Channel`。
4. 请求写入前，将 `requestId` 和响应 Future 放入 `UnprocessedRequests`。
5. `NettyRpcClientHandler` 收到响应，根据 `requestId` 完成对应 Future。
6. 代理检查响应中的 `requestId`、状态码和数据，再完成公开 Future。

服务发现、排队、建连、写入和等待响应都计算在 `rpc.request.timeout-millis` 内。旧实现如果在连接成功后才开始计时，服务发现或任务排队就可能无限拖延。

## 取消、超时和关闭如何清理资源

异步 API 还要处理“请求没有正常返回”的情况。只创建 Future、不处理清理，会留下待处理映射、超时任务、Socket 和排队任务。

当前实现为每个请求保存必要的生命周期状态：

- Netty 请求上下文保存响应 Future、调度任务、`Channel` 和关闭监听器。
- Socket 请求上下文保存响应 Future、工作任务和当前 `Socket`。
- 客户端保存所有已接受但尚未完成的请求，关闭时统一完成为 `CANCELLED`。

调用方取消公开 Future 后，取消会继续传递给传输 Future。Netty 会移除 pending request 和关闭监听器；Socket 会关闭正在读写的连接并中断任务。超时统一映射为 `DEADLINE_EXCEEDED`。

```java
CompletableFuture<String> future = asyncService.hello(request);
boolean cancelled = future.cancel(true);
```

当前取消只负责停止客户端等待并清理本地资源，还没有向服务端发送取消控制帧。远端方法已经开始执行时，它仍可能继续运行。逐调用 deadline 和服务端协作取消可以在后续协议扩展中实现。

## 使用稳定的错误模型

`RpcResponse` 中的 `code` 使用 `RpcStatusCode`。数值与 gRPC canonical status code 对齐，现有状态的含义不能被业务代码重新定义。

当前代码已经使用了取消、参数错误、超时、未找到、资源耗尽、前置条件、未实现、内部错误、服务不可用和数据损坏等状态。其余状态先作为协议保留值，后续加入认证、授权或业务资源操作时可以继续沿用，不需要重新分配数字。

| 状态 | Code | 典型场景 |
| --- | ---: | --- |
| `OK` | 0 | 调用成功 |
| `CANCELLED` | 1 | 调用被取消或客户端关闭 |
| `UNKNOWN` | 2 | 无法归类的错误 |
| `INVALID_ARGUMENT` | 3 | 请求结构或参数不合法 |
| `DEADLINE_EXCEEDED` | 4 | 请求超过截止时间 |
| `NOT_FOUND` | 5 | 服务或资源不存在 |
| `ALREADY_EXISTS` | 6 | 目标资源已经存在 |
| `PERMISSION_DENIED` | 7 | 调用方没有执行权限 |
| `RESOURCE_EXHAUSTED` | 8 | 线程池队列、配额或其他资源已满 |
| `FAILED_PRECONDITION` | 9 | 当前状态不满足执行条件 |
| `ABORTED` | 10 | 操作因冲突等原因中止 |
| `OUT_OF_RANGE` | 11 | 参数超出允许范围 |
| `UNIMPLEMENTED` | 12 | RPC 方法不存在或未实现 |
| `INTERNAL` | 13 | 服务端内部异常 |
| `UNAVAILABLE` | 14 | 服务、连接或传输暂不可用 |
| `DATA_LOSS` | 15 | 响应或协议数据无效 |
| `UNAUTHENTICATED` | 16 | 调用方身份未认证 |

异常按来源分为三类：

- `RpcServiceException`：服务实现主动抛出的预期失败，状态码和安全消息可以返回客户端。
- `RpcRemoteException`：客户端收到远端非 `OK` 响应后抛出的异常，包含状态码和 `requestId`。
- `RpcException`：客户端本地的服务发现、连接、超时、取消、协议和反序列化错误。

服务实现可以这样表达一个允许公开的业务失败：

```java
if (order.isPaid()) {
    throw new RpcServiceException(
            RpcStatusCode.FAILED_PRECONDITION,
            "Order has already been paid");
}
```

异步调用方可以稳定判断远端状态：

```java
try {
    asyncService.hello(request).join();
} catch (CompletionException exception) {
    if (exception.getCause() instanceof RpcRemoteException remoteException) {
        System.out.println(remoteException.getStatusCode());
        System.out.println(remoteException.getRequestId());
    }
}
```

普通的 `IllegalArgumentException` 或数据库异常不会被当作可公开的业务提示。服务端保留完整堆栈，客户端只收到：

```text
code = 13
message = Internal server error
```

这条规则避免把 SQL、文件路径、内部地址或第三方 SDK 信息带到远端响应中。

## 服务端也支持异步结果

如果服务实现返回 `CompletionStage`，Netty 服务端不会把 Future 当作普通对象序列化，也不会阻塞 I/O 线程等待结果。`NettyRpcServerHandler` 会继续组合异步结果，完成后再生成 `RpcResponse`。

服务线程池拒绝任务时返回 `RESOURCE_EXHAUSTED`；找不到方法时返回 `UNIMPLEMENTED`；未知服务异常返回 `INTERNAL`。这些预期失败不会直接关闭可复用连接。

## 协议版本为什么升级到 2

早期版本只有成功和失败两个宽泛状态。当前响应使用 0—16 的稳定状态集合，客户端也会按新语义解析错误。版本 1 客户端无法可靠理解版本 2 服务端的响应，因此协议版本已经升级为 2。

客户端和服务端必须一起升级，不应混合部署协议版本 1 和版本 2。

## 如何验证实现

除了正常返回，还应固定以下契约：

- 远端响应到达前，异步代理已经返回一个未完成 Future。
- 同步代理和异步代理发送的是同一个远程服务接口。
- 取消公开 Future 会继续取消传输 Future。
- 超时覆盖排队、服务发现、建连、写入和响应阶段。
- 客户端关闭后，排队中和执行中的请求都会完成为 `CANCELLED`。
- 未知状态码、错误 `requestId` 和非法响应映射为 `DATA_LOSS`。
- `RpcServiceException` 的安全消息可以返回，未知服务端异常细节不会泄漏。

项目当前在 JDK 25 下执行 `mvn verify`，6 个模块共 82 个测试全部通过。一次真实的 ZooKeeper、Netty 服务端和客户端联调还验证了两个结果：异步方法返回时 Future 尚未完成；请求不存在的 `missingMethod` 得到 `UNIMPLEMENTED(12)`，响应保留了原始 `requestId`。

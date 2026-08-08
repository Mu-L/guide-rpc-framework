# guide-rpc-framework

[中文](./README.md)|English

Sorry, I did not fully translate the Chinese readme. I have translated the important parts. You can translate the rest by yourself through Google.

## Preface

Although the principle of RPC is not difficult, I encountered many problems in the process of implementation. [guide-rpc-framework](https://github.com/Snailclimb/guide-rpc-framework)  implements only the most basic features of the RPC framework, and some of the optimizations are mentioned below for those interested.

With this simple wheel, you can learn the underlying principles and principles of RPC  framework as well as various Java coding practices.

You can even use the  [guide-rpc-framework](https://github.com/Snailclimb/guide-rpc-framework)  as a choice for your graduation/project experience, which is very great! Compared to other job seekers whose project experience is based on a variety of systems, building wheels is a sure way to win an interviewer's favor.

If you're going to use the  [guide-rpc-framework](https://github.com/Snailclimb/guide-rpc-framework)  as your graduation/project experience, I want you to understand it rather than just copy and paste my ideas. You can fork my project and then optimize it. If you think the optimization is valuable, you can submit PR to me, and I will deal with it as soon as possible.

##  Introduction

 [guide-rpc-framework](https://github.com/Snailclimb/guide-rpc-framework) is an RPC framework based on Netty, ZooKeeper, and pluggable serializers. Detailed code comments and a clear structure make it ideal for reading and learning.

### 🚀 Project Features

- **High-Performance Network Communication**: Based on Netty for high-performance network transmission
- **Multiple Serialization Methods**: Supports Kryo, Protostuff, Hessian and other serialization frameworks
- **Service Registration and Discovery**: Integrated with Zookeeper as registry center
- **Load Balancing**: Includes random and consistent-hash implementations; service discovery uses consistent hashing by default
- **Spring Integration**: Simplifies service registration and consumption through annotations
- **Heartbeat Detection**: Supports heartbeat detection mechanism for both client and server
- **Synchronous and Asynchronous Public APIs**: Keeps the conventional proxy and adds a non-blocking `CompletableFuture<T>` mirror proxy
- **Service Grouping and Version Control**: Supports service grouping and version management
- **Timeout Protection**: Both connection and request timeouts are configurable
- **Standard Error Model**: Stable `RpcStatusCode` values and typed exceptions distinguish remote, timeout, cancellation, transport, and protocol failures

### 📁 Project Structure

```
guide-rpc-framework/
├── rpc-framework-simple/     # RPC framework core implementation
├── rpc-framework-common/     # Common utilities and constants
├── hello-service-api/        # Example service interface definition
├── example-server/           # Service provider example
├── example-client/           # Service consumer example
├── docs/                     # Related documentation
└── images/                   # Project image resources
```

Due to the limited energy and ability of me, if you think there is something to be improved and perfected, welcome to fork this project, then clone it to local, and submit PR to me after local modification, I will Review your code as soon as possible.

Let's start with a basic RPC framework design idea!

> **note** ：The RPC framework we mentioned here refers to a framework that allows clients to directly call server-side methods as simple as calling local methods, similar to the Dubbo, Motan, and gRPC I introduced earlier. If you need to deal with the HTTP protocol, parse and encapsulate HTTP requests and responses. Type frameworks are not considered "RPC frameworks", such as Feign.

A schematic diagram of the simplest RPC framework usage is shown in the figure below, which is also the current architecture of [guide-rpc-framework](https://github.com/Snailclimb/guide-rpc-framework):

![](./images/rpc-architure.png)

The service provider Server registers the service with the registry, and the service consumer Client gets the service-related information through the registry, and then requests the service provider Server through the network.

As a leader in the field of RPC framework [Dubbo](https://github.com/apache/dubbo), the architecture is shown in the figure below, which is roughly the same as what we drew above.

<img src="./images/dubbo-architure.jpg" style="zoom:80%;" />

**Under normal circumstances, the RPC framework must not only provide service discovery functions, but also provide load balancing, fault tolerance and other functions. Such an RPC framework is truly qualified. ** 

**Please let me simply talk about the idea of designing a most basic RPC framework:**

![](./images/rpc-architure-detail.png)

1. **Registration Center**: The registration center is required first, and Zookeeper is recommended. The registration center is responsible for the registration and search of service addresses, which is equivalent to a directory service. When the server starts, the service name and its corresponding address (ip+port) are registered in the registry, and the service consumer finds the corresponding service address according to the service name. With the service address, the service consumer can request the server through the network.
2. **Network Transmission**: Since you want to call a remote method, you must send a request. The request must at least include the class name, method name, and related parameters you call! Recommend the Netty framework based on NIO.
3. **Serialization**: Since network transmission is involved, serialization must be involved. You can't directly use the serialization that comes with JDK! The serialization that comes with the JDK is inefficient and has security vulnerabilities. Therefore, you have to consider which serialization protocol to use. The more commonly used ones are hession2, kryo, and protostuff.
4. **Dynamic Proxy**: In addition, a dynamic proxy is also required. Because the main purpose of RPC is to allow us to call remote methods as easy as calling local methods, the use of dynamic proxy can shield the details of remote method calls such as network transmission. That is to say, when you call a remote method, the network request will actually be transmitted through the proxy object. Otherwise, how could it be possible to call the remote method directly?
2. **Load Balancing**: Load balancing is also required. Why? For example, a certain service in our system has very high traffic. We deploy this service on multiple servers. When a client initiates a request, multiple servers can handle the request. Then, how to correctly select the server that processes the request is critical. If you need one server to handle requests for the service, the meaning of deploying the service on multiple servers no longer exists. Load balancing is to avoid a single server responding to the same request, which is likely to cause server downtime, crashes and other problems. We can clearly feel its meaning from the four words of load balancing.

## RPC framework design ideas

![](./images/rpc-architure.png)

**Consumption side:**

1. **Dynamic proxy** : Dynamic proxy is used to shield the details of remote method invocation, which makes the invocation of remote method as natural as the invocation of local method. When you call the method of an object, the method call will be forwarded to the invoke method of the object associated with it.
2. **Load balancing** : In order to avoid the situation that a single server is under too much pressure and goes down, we can increase the number of servers to share the pressure. After the number of servers increases, we need to use a certain strategy to achieve load balancing.
3. **Transport** : Since the target service may be deployed on a remote server, we need to send the request to the remote server through the network, and the request must follow a certain protocol. Here, I use socket to realize the network transmission. You can also use Netty (based on NIO) which has higher performance.
4. **Serialization** : Since network transmission is involved, serialization is definitely needed. You can't directly use the serialization that comes with JDK, because it's inefficient and has security vulnerabilities. So, here I use Kryo to complete the serialization.

**Provider side:**

1. **Registration service** : The provider registers the service with the registry so that the consumer can discover the service.
2. **Provide services** : The provider provides services and waits for consumer calls.

## Usage

### Environment Requirements

- **JDK**: 25+
- **Maven**: 3.9+
- **Zookeeper**: 3.9.5+ (as registry center)
- **IDE**: IntelliJ IDEA (recommended)

### Quick Start

1. **Start Zookeeper**

   Using Docker to download and run:
   ```bash
   docker pull zookeeper:3.9.5
   docker run -d --name zookeeper -p 2181:2181 zookeeper:3.9.5
   ```

2. **Clone and Build Project**

   ```bash
   git clone https://github.com/Snailclimb/guide-rpc-framework.git
   cd guide-rpc-framework
   mvn clean install
   ```

3. **Import Project**

   Open IntelliJ IDEA: **File** -> **Open** -> **guide-rpc-framework**

### Define Service Interface

First, define the service interface in the `hello-service-api` module:

```java
public interface HelloService {
    String hello(Hello hello);
}
```

### Service Provider

1. **Implement Service Interface**

   ```java
   @RpcService(group = "test1", version = "version1")
   public class HelloServiceImpl implements HelloService {
       private static final Logger logger = LoggerFactory.getLogger(HelloServiceImpl.class);
   
       static {
           System.out.println("HelloServiceImpl被创建");
       }
   
       @Override
       public String hello(Hello hello) {
           logger.info("HelloServiceImpl收到: {}.", hello.getMessage());
           String result = "Hello description is " + hello.getDescription();
           logger.info("HelloServiceImpl返回: {}.", result);
           return result;
       }
   }
   ```

2. **Start Service Provider**

   ```java
   @RpcScan(basePackage = {"github.javaguide.serviceimpl"})
   public class NettyServerMain {
       public static void main(String[] args) {
           try (AnnotationConfigApplicationContext context =
                        new AnnotationConfigApplicationContext(NettyServerMain.class)) {
               context.getBean(NettyRpcServer.class).start();
           }
       }
   }
   ```

### Service Consumer

1. **Inject Service Reference**

   ```java
   @Component
   public class HelloController {
   
       @RpcReference(version = "version1", group = "test1")
       private HelloService helloService;
   
       public void test() throws InterruptedException {
           String hello = this.helloService.hello(new Hello("111", "222"));
           assert "Hello description is 222".equals(hello);
       }
   }
   ```

2. **Start Service Consumer**

   ```java
   @RpcScan(basePackage = {"github.javaguide"})
   public class NettyClientMain {
       public static void main(String[] args) throws InterruptedException {
           try (AnnotationConfigApplicationContext applicationContext =
                        new AnnotationConfigApplicationContext(NettyClientMain.class)) {
               HelloController helloController = applicationContext.getBean(HelloController.class);
               helloController.test();
           }
       }
   }
   ```

### Running Steps

1. **Start Zookeeper**
   ```bash
   docker run -d --name zookeeper -p 2181:2181 zookeeper:3.9.5
   ```
   Ensure Zookeeper is running on `127.0.0.1:2181`

2. **Start Service Provider**
   Run the `NettyServerMain` class in the `example-server` module

3. **Start Service Consumer**
   Run the `NettyClientMain` class in the `example-client` module

### Core Annotations

- **@RpcService**: Used to mark service implementation classes for automatic registration
  - `group`: Service group, used for service grouping
  - `version`: Service version, used for version control

- **@RpcReference**: Used to inject remote service references
  - `group`: Service group, must match the provider
  - `version`: Service version, must match the provider

- **@RpcScan**: Used to specify the package path for scanning RPC services
  - `basePackage`: Base package path for scanning

### Configuration

- **Registry Center**: Configure with `rpc.zookeeper.address`; defaults to `127.0.0.1:2181`
- **ZooKeeper Timeouts**: Configure with `rpc.zookeeper.connection-timeout-millis` and `rpc.zookeeper.session-timeout-millis`; defaults to `15000` ms and `60000` ms
- **Server Address**: `rpc.server.bind-host` controls the local listening address (default `0.0.0.0`); `rpc.server.host` is the reachable address advertised through ZooKeeper and must not be a wildcard
- **Service Identity**: Registry keys contain the interface name plus URL-safe Base64 encoded `group` and `version`, preventing field-boundary collisions and ZooKeeper path injection; providers and consumers in an existing deployment must be upgraded together
- **Serialization**: Configure with `rpc.serialization`; supports `kryo`, `protostuff`, and `hessian`, default `hessian`
- **Compression**: Configure with `rpc.compress`; currently supports `gzip`
- **Connection Timeout**: Configure with `rpc.connect.timeout-millis`, default `5000` ms
- **Request Timeout**: Configure with `rpc.request.timeout-millis`, default `10000` ms
- **Transport Protocol**: Default uses Netty for network communication
- **Load Balancing**: Includes random and consistent-hash implementations; service discovery currently defaults to consistent hashing

JVM system properties override values from `rpc.properties`. Invalid serializer, compressor, or non-positive timeout values fail fast during client initialization.

### Synchronous and Asynchronous Calls

The conventional proxy preserves the service interface. For a non-blocking public API, define a client-only mirror with the same method name and parameters and a `CompletableFuture<T>` result:

```java
public interface HelloServiceAsync {
    CompletableFuture<String> hello(Hello hello);
}

RpcServiceConfig serviceConfig = RpcServiceConfig.builder()
        .group("test1")
        .version("version1")
        .build();

try (NettyRpcClient transport = new NettyRpcClient()) {
    RpcClientProxy clientProxy = new RpcClientProxy(transport, serviceConfig);
    HelloServiceAsync asyncService = clientProxy.getAsyncProxy(
            HelloServiceAsync.class, HelloService.class);
    CompletableFuture<String> resultFuture = asyncService.hello(request);
    resultFuture.thenAccept(System.out::println).join();
}
```

The asynchronous proxy returns before service discovery, connection establishment, and the remote response complete. Cancelling the public future propagates cancellation to the transport and removes pending request state. `@RpcReference` currently injects the conventional synchronous proxy; asynchronous mirrors are created explicitly with `RpcClientProxy#getAsyncProxy`.

Responses use stable `RpcStatusCode` values aligned with gRPC canonical status codes. Expected service failures use `RpcServiceException`; clients receive a typed `RpcRemoteException` with the status and request ID. Unexpected server exceptions are logged on the provider and exposed as `INTERNAL` without implementation details.

The wire protocol version is now 2. Version 1 and version 2 peers must not be mixed in the same deployment. See [the CompletableFuture and error model guide](./docs/使用CompletableFuture优化接受服务提供端返回结果.md) for the implementation, cancellation semantics, status table, and test cases.

### Troubleshooting

**Common Issues:**

1. **Failed to connect to Zookeeper**
   - Check if Zookeeper service is running
   - Verify Zookeeper address and port configuration
   - Check network connectivity

2. **Service registration failed**
   - Ensure `@RpcService` annotation is correctly configured
   - Check if the service implementation class is in the scan path
   - Verify Zookeeper connection is normal

3. **Service call timeout**
   - Check if the service provider is running normally
   - Verify network connectivity between client and server
   - Check if service group and version match

4. **Serialization exception**
   - Ensure entity classes implement `Serializable` interface
   - Check if custom serialization configuration is correct
   - Verify data type compatibility

**Debugging Tips:**
- Enable DEBUG level logging to view detailed call information
- Use network tools to check port connectivity
- Check Zookeeper node registration status

### Performance Optimization

1. **Connection Pool**: Use connection pooling to reduce connection creation overhead
2. **Batch Calls**: Use batch calls for multiple requests to reduce network overhead
3. **Serialization Selection**: Choose appropriate serialization method based on data characteristics
4. **Load Balancing**: Select optimal load balancing strategy based on business scenarios
5. **Monitoring and Alerting**: Implement comprehensive monitoring and alerting mechanisms

## Contributing

We welcome contributions to improve this RPC framework! Here's how you can contribute:

1. **Fork the Repository**: Fork this project to your own GitHub account
2. **Clone Locally**: Clone the forked repository to your local machine
3. **Create a Branch**: Create a new branch for your feature or bug fix
4. **Make Changes**: Implement your changes with clear commit messages
5. **Test**: Ensure all tests pass and add new tests if necessary
6. **Submit PR**: Submit a Pull Request with a detailed description of your changes

### Development Guidelines

- Follow the existing code style and conventions
- Add appropriate comments and documentation
- Write unit tests for new features
- Ensure backward compatibility when possible

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Thanks to all contributors who have helped improve this project
- Special thanks to the open source community for inspiration and support

---

**Note**: This is a learning-oriented RPC framework. For production use, consider mature solutions like Apache Dubbo, gRPC, or Spring Cloud.

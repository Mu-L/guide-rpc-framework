package github.javaguide.registry;

import github.javaguide.DemoRpcService;
import github.javaguide.DemoRpcServiceImpl;
import github.javaguide.config.RpcServiceConfig;
import github.javaguide.registry.zk.ZkServiceDiscoveryImpl;
import github.javaguide.registry.zk.ZkServiceRegistryImpl;
import github.javaguide.registry.zk.util.CuratorUtils;
import github.javaguide.remoting.dto.RpcRequest;
import org.apache.curator.test.TestingServer;
import org.apache.curator.test.KillSession;
import org.apache.curator.framework.CuratorFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * @author shuang.kou
 * @createTime 2020年05月31日 16:25:00
 */
class ZkServiceRegistryImplTest {

    private static final String ZOOKEEPER_ADDRESS_PROPERTY = "rpc.zookeeper.address";
    private static final String ZOOKEEPER_CONNECTION_TIMEOUT_PROPERTY =
            "rpc.zookeeper.connection-timeout-millis";
    private static final String ZOOKEEPER_SESSION_TIMEOUT_PROPERTY =
            "rpc.zookeeper.session-timeout-millis";
    private TestingServer testingServer;

    @BeforeEach
    void startZooKeeper() throws Exception {
        testingServer = new TestingServer();
        System.setProperty(ZOOKEEPER_ADDRESS_PROPERTY, testingServer.getConnectString());
        System.setProperty(ZOOKEEPER_CONNECTION_TIMEOUT_PROPERTY, "5000");
        System.setProperty(ZOOKEEPER_SESSION_TIMEOUT_PROPERTY, "6000");
    }

    @AfterEach
    void stopZooKeeper() throws Exception {
        CuratorUtils.closeZkClient();
        System.clearProperty(ZOOKEEPER_ADDRESS_PROPERTY);
        System.clearProperty(ZOOKEEPER_CONNECTION_TIMEOUT_PROPERTY);
        System.clearProperty(ZOOKEEPER_SESSION_TIMEOUT_PROPERTY);
        testingServer.close();
    }

    @Test
    void should_register_service_successful_and_lookup_service_by_service_name() {
        ServiceRegistry zkServiceRegistry = new ZkServiceRegistryImpl();
        InetSocketAddress givenInetSocketAddress = new InetSocketAddress("127.0.0.1", 9333);
        DemoRpcService demoRpcService = new DemoRpcServiceImpl();
        RpcServiceConfig rpcServiceConfig = RpcServiceConfig.builder()
                .group("test2").version("version2").service(demoRpcService).build();
        zkServiceRegistry.registerService(rpcServiceConfig.getRpcServiceName(), givenInetSocketAddress);
        ServiceDiscovery zkServiceDiscovery = new ZkServiceDiscoveryImpl();
        RpcRequest rpcRequest = RpcRequest.builder()
//                .parameters(args)
                .interfaceName(rpcServiceConfig.getServiceName())
//                .paramTypes(method.getParameterTypes())
                .requestId(UUID.randomUUID().toString())
                .group(rpcServiceConfig.getGroup())
                .version(rpcServiceConfig.getVersion())
                .build();
        InetSocketAddress acquiredInetSocketAddress = zkServiceDiscovery.lookupService(rpcRequest);
        assertEquals(givenInetSocketAddress.toString(), acquiredInetSocketAddress.toString());
    }

    @Test
    void shouldRestoreEphemeralServiceNodeAfterSessionExpiration() throws Exception {
        ServiceRegistry registry = new ZkServiceRegistryImpl();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 9444);
        String serviceName = "session-recovery-" + UUID.randomUUID();
        String path = CuratorUtils.buildRegisterPath(serviceName, address);
        registry.registerService(serviceName, address);
        CuratorFramework client = CuratorUtils.getZkClient();
        long originalSessionId = client.getZookeeperClient().getZooKeeper().getSessionId();

        KillSession.kill(client.getZookeeperClient().getZooKeeper());

        assertEventually(() -> {
            try {
                long recoveredSessionId =
                        client.getZookeeperClient().getZooKeeper().getSessionId();
                org.apache.zookeeper.data.Stat stat = client.checkExists().forPath(path);
                return recoveredSessionId != originalSessionId
                        && stat != null
                        && stat.getEphemeralOwner() == recoveredSessionId;
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    void shouldDiscoverServiceRegisteredAfterAnInitiallyEmptyLookup() throws Exception {
        String serviceName = "late-service-" + UUID.randomUUID();
        CuratorFramework client = CuratorUtils.getZkClient();
        assertTrue(CuratorUtils.getChildrenNodes(client, serviceName).isEmpty());

        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 9555);
        new ZkServiceRegistryImpl().registerService(serviceName, address);

        assertEventually(() -> CuratorUtils.getChildrenNodes(client, serviceName)
                .contains("127.0.0.1:9555"));
    }

    private static void assertEventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        org.junit.jupiter.api.Assertions.fail("Condition was not satisfied before timeout");
    }
}

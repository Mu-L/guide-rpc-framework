package github.javaguide.registry.zk.util;

import github.javaguide.enums.RpcConfigEnum;
import github.javaguide.utils.PropertiesFileUtil;
import github.javaguide.utils.concurrent.threadpool.CustomThreadPoolConfig;
import github.javaguide.utils.concurrent.threadpool.ThreadPoolFactoryUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Curator(zookeeper client) utils
 *
 * @author shuang.kou
 * @createTime 2020年05月31日 11:38:00
 */
@Slf4j
public final class CuratorUtils {

    private static final int BASE_SLEEP_TIME = 1000;
    private static final int MAX_RETRIES = 3;
    private static final int DEFAULT_ZK_CONNECTION_TIMEOUT_MILLIS = 15_000;
    private static final int DEFAULT_ZK_SESSION_TIMEOUT_MILLIS = 60_000;
    private static final int SESSION_RECOVERY_MAX_ATTEMPTS = 120;
    private static final long SESSION_RECOVERY_RETRY_DELAY_MILLIS = 100L;
    public static final String ZK_REGISTER_ROOT_PATH = "/my-rpc";
    private static final Map<String, List<String>> SERVICE_ADDRESS_MAP = new ConcurrentHashMap<>();
    private static final Map<String, CuratorCache> SERVICE_WATCHER_MAP = new ConcurrentHashMap<>();
    private static final Set<String> REGISTERED_PATH_SET = ConcurrentHashMap.newKeySet();
    private static final Object ZK_CLIENT_LOCK = new Object();
    private static final Object REGISTER_PATH_LOCK = new Object();
    private static volatile CuratorFramework zkClient;
    private static final String DEFAULT_ZOOKEEPER_ADDRESS = "127.0.0.1:2181";

    private CuratorUtils() {
    }

    public static String buildRegisterPath(String rpcServiceName, InetSocketAddress address) {
        return ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName + "/"
                + address.getHostString() + ":" + address.getPort();
    }

    /**
     * Gets the children under a node
     *
     * @param rpcServiceName rpc service name eg:github.javaguide.HelloServicetest2version1
     * @return All child nodes under the specified node
     */
    public static List<String> getChildrenNodes(CuratorFramework zkClient, String rpcServiceName) {
        List<String> cachedAddresses = SERVICE_ADDRESS_MAP.get(rpcServiceName);
        if (cachedAddresses != null) {
            return cachedAddresses;
        }
        String servicePath = ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName;
        try {
            registerWatcher(rpcServiceName, zkClient);
            List<String> result = immutableCopy(zkClient.getChildren().forPath(servicePath));
            List<String> previous = SERVICE_ADDRESS_MAP.putIfAbsent(rpcServiceName, result);
            return previous == null ? result : previous;
        } catch (KeeperException.NoNodeException e) {
            List<String> empty = Collections.emptyList();
            SERVICE_ADDRESS_MAP.put(rpcServiceName, empty);
            return empty;
        } catch (Exception e) {
            SERVICE_ADDRESS_MAP.remove(rpcServiceName);
            throw new IllegalStateException("Failed to get service addresses for path " + servicePath, e);
        }
    }

    /**
     * Empty the registry of data
     */
    public static void clearRegistry(CuratorFramework zkClient, InetSocketAddress inetSocketAddress) {
        String address = inetSocketAddress.getHostString() + ":" + inetSocketAddress.getPort();
        for (String path : new HashSet<>(REGISTERED_PATH_SET)) {
            if (!path.endsWith("/" + address)) {
                continue;
            }
            try {
                if (zkClient.checkExists().forPath(path) != null) {
                    zkClient.delete().forPath(path);
                }
                REGISTERED_PATH_SET.remove(path);
            } catch (Exception e) {
                log.error("Failed to clear registry path [{}]", path, e);
            }
        }
        log.info("All registered services on the server are cleared:[{}]", REGISTERED_PATH_SET);
    }

    public static CuratorFramework getZkClient() {
        CuratorFramework currentClient = zkClient;
        if (isStarted(currentClient)) {
            return currentClient;
        }
        synchronized (ZK_CLIENT_LOCK) {
            currentClient = zkClient;
            if (isStarted(currentClient)) {
                return currentClient;
            }

            Properties properties = PropertiesFileUtil.readPropertiesFile(
                    RpcConfigEnum.RPC_CONFIG_PATH.getPropertyValue());
            String propertyName = RpcConfigEnum.ZK_ADDRESS.getPropertyValue();
            String zookeeperAddress = System.getProperty(propertyName);
            if (zookeeperAddress == null || zookeeperAddress.trim().isEmpty()) {
                zookeeperAddress = properties == null
                        ? DEFAULT_ZOOKEEPER_ADDRESS
                        : properties.getProperty(propertyName, DEFAULT_ZOOKEEPER_ADDRESS);
            }
            zookeeperAddress = zookeeperAddress.trim();
            int connectionTimeoutMillis = getPositiveIntProperty(properties,
                    RpcConfigEnum.ZK_CONNECTION_TIMEOUT_MILLIS,
                    DEFAULT_ZK_CONNECTION_TIMEOUT_MILLIS);
            int sessionTimeoutMillis = getPositiveIntProperty(properties,
                    RpcConfigEnum.ZK_SESSION_TIMEOUT_MILLIS,
                    DEFAULT_ZK_SESSION_TIMEOUT_MILLIS);
            RetryPolicy retryPolicy = new ExponentialBackoffRetry(BASE_SLEEP_TIME, MAX_RETRIES);
            CuratorFramework newClient = CuratorFrameworkFactory.builder()
                    .connectString(zookeeperAddress)
                    .connectionTimeoutMs(connectionTimeoutMillis)
                    .sessionTimeoutMs(sessionTimeoutMillis)
                    .retryPolicy(retryPolicy)
                    .build();
            newClient.getConnectionStateListenable().addListener((client, state) -> {
                if (state == ConnectionState.RECONNECTED) {
                    CustomThreadPoolConfig recoveryPoolConfig =
                            new CustomThreadPoolConfig();
                    recoveryPoolConfig.setCorePoolSize(1);
                    recoveryPoolConfig.setMaximumPoolSize(1);
                    ThreadPoolFactoryUtil.createCustomThreadPoolIfAbsent(
                                    recoveryPoolConfig, "zk-session-recovery", true)
                            .execute(() -> reRegisterServices(client));
                }
            });
            newClient.start();
            try {
                if (!newClient.blockUntilConnected(30, TimeUnit.SECONDS)) {
                    newClient.close();
                    throw new IllegalStateException(
                            "Timed out waiting to connect to ZooKeeper at " + zookeeperAddress);
                }
            } catch (InterruptedException e) {
                newClient.close();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while connecting to ZooKeeper", e);
            }
            zkClient = newClient;
            return newClient;
        }
    }

    /**
     * Closes registry watchers and the shared ZooKeeper client.
     */
    public static void closeZkClient() {
        synchronized (ZK_CLIENT_LOCK) {
            for (CuratorCache watcher : SERVICE_WATCHER_MAP.values()) {
                try {
                    watcher.close();
                } catch (Exception e) {
                    log.warn("Failed to close ZooKeeper service watcher", e);
                }
            }
            SERVICE_WATCHER_MAP.clear();
            SERVICE_ADDRESS_MAP.clear();
            REGISTERED_PATH_SET.clear();
            if (zkClient != null) {
                zkClient.close();
                zkClient = null;
            }
        }
    }

    public static CuratorFramework getExistingZkClient() {
        return zkClient;
    }

    /**
     * Registers to listen for changes to the specified node
     *
     * @param rpcServiceName rpc service name eg:github.javaguide.HelloServicetest2version
     */
    private static synchronized void registerWatcher(
            String rpcServiceName, CuratorFramework zkClient) throws Exception {
        if (SERVICE_WATCHER_MAP.containsKey(rpcServiceName)) {
            return;
        }
        String servicePath = ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName;
        CuratorCache curatorCache = CuratorCache.build(zkClient, servicePath);
        CuratorCacheListener curatorCacheListener = CuratorCacheListener.builder().forAll((type, oldData, data) -> {
            try {
                List<String> serviceAddresses = immutableCopy(
                        zkClient.getChildren().forPath(servicePath));
                SERVICE_ADDRESS_MAP.put(rpcServiceName, serviceAddresses);
            } catch (KeeperException.NoNodeException e) {
                SERVICE_ADDRESS_MAP.put(rpcServiceName, Collections.emptyList());
            } catch (Exception e) {
                SERVICE_ADDRESS_MAP.remove(rpcServiceName);
                log.error("Failed to refresh service addresses for path [{}]", servicePath, e);
            }
        }).build();
        curatorCache.listenable().addListener(curatorCacheListener);
        try {
            curatorCache.start();
            SERVICE_WATCHER_MAP.put(rpcServiceName, curatorCache);
        } catch (RuntimeException e) {
            curatorCache.close();
            throw e;
        }
    }

    /**
     * Creates an ephemeral service node so ZooKeeper removes it when the provider session ends.
     */
    public static void createEphemeralNode(CuratorFramework zkClient, String path) {
        synchronized (REGISTER_PATH_LOCK) {
            try {
                Stat existingNode = zkClient.checkExists().forPath(path);
                if (existingNode != null) {
                    long currentSessionId =
                            zkClient.getZookeeperClient().getZooKeeper().getSessionId();
                    if (REGISTERED_PATH_SET.contains(path)
                            && existingNode.getEphemeralOwner() == currentSessionId) {
                        return;
                    }
                    if (existingNode.getEphemeralOwner() == 0) {
                        throw new IllegalStateException(
                                "A persistent node already exists at service path " + path);
                    }
                    throw new IllegalStateException(
                            "The service path is already owned by another ZooKeeper session: " + path);
                }
                zkClient.create()
                        .creatingParentsIfNeeded()
                        .withMode(CreateMode.EPHEMERAL)
                        .forPath(path, "status:ok".getBytes(StandardCharsets.UTF_8));
                REGISTERED_PATH_SET.add(path);
                log.info("Created ephemeral service node [{}]", path);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create ephemeral service node " + path, e);
            }
        }
    }

    public static void deleteEphemeralNode(CuratorFramework zkClient, String path) {
        try {
            if (zkClient.checkExists().forPath(path) != null) {
                zkClient.delete().forPath(path);
            }
            REGISTERED_PATH_SET.remove(path);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete ephemeral service node " + path, e);
        }
    }

    private static boolean isStarted(CuratorFramework client) {
        return client != null && client.getState() == CuratorFrameworkState.STARTED;
    }

    private static void reRegisterServices(CuratorFramework client) {
        if (client != zkClient || !isStarted(client)) {
            return;
        }
        for (String path : new HashSet<>(REGISTERED_PATH_SET)) {
            RuntimeException lastFailure = null;
            for (int attempt = 1; attempt <= SESSION_RECOVERY_MAX_ATTEMPTS; attempt++) {
                if (client != zkClient || !isStarted(client)
                        || !REGISTERED_PATH_SET.contains(path)) {
                    lastFailure = null;
                    break;
                }
                try {
                    createEphemeralNode(client, path);
                    log.info("Restored ephemeral service node [{}] after reconnect", path);
                    lastFailure = null;
                    break;
                } catch (RuntimeException e) {
                    lastFailure = e;
                    if (!waitBeforeSessionRecoveryRetry(path)) {
                        break;
                    }
                }
            }
            if (lastFailure != null) {
                log.error("Failed to restore ephemeral service node [{}] after reconnect",
                        path, lastFailure);
            }
        }
    }

    private static boolean waitBeforeSessionRecoveryRetry(String path) {
        try {
            TimeUnit.MILLISECONDS.sleep(SESSION_RECOVERY_RETRY_DELAY_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while restoring ephemeral service node [{}]", path);
            return false;
        }
    }

    private static int getPositiveIntProperty(Properties properties, RpcConfigEnum key,
                                              int defaultValue) {
        String propertyName = key.getPropertyValue();
        String value = System.getProperty(propertyName);
        if (value == null || value.trim().isEmpty()) {
            value = properties == null
                    ? String.valueOf(defaultValue)
                    : properties.getProperty(propertyName, String.valueOf(defaultValue));
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed <= 0) {
                throw new NumberFormatException("not positive");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    propertyName + " must be a positive integer, but was: " + value, e);
        }
    }

    private static List<String> immutableCopy(List<String> addresses) {
        return Collections.unmodifiableList(new ArrayList<>(addresses));
    }
}

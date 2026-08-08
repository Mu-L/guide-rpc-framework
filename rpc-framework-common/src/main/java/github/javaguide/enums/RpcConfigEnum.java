package github.javaguide.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum RpcConfigEnum {

    RPC_CONFIG_PATH("rpc.properties"),
    ZK_ADDRESS("rpc.zookeeper.address"),
    ZK_CONNECTION_TIMEOUT_MILLIS("rpc.zookeeper.connection-timeout-millis"),
    ZK_SESSION_TIMEOUT_MILLIS("rpc.zookeeper.session-timeout-millis"),
    SERVER_HOST("rpc.server.host"),
    SERVER_BIND_HOST("rpc.server.bind-host"),
    SERIALIZATION("rpc.serialization"),
    COMPRESS("rpc.compress"),
    CONNECT_TIMEOUT_MILLIS("rpc.connect.timeout-millis"),
    REQUEST_TIMEOUT_MILLIS("rpc.request.timeout-millis");

    private final String propertyValue;

}

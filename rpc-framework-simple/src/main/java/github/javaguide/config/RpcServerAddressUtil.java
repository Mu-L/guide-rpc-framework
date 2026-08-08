package github.javaguide.config;

import github.javaguide.enums.RpcConfigEnum;
import github.javaguide.utils.PropertiesFileUtil;
import github.javaguide.utils.StringUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Properties;

/**
 * Resolves the server address used for binding and service registration.
 */
public final class RpcServerAddressUtil {
    private static final String DEFAULT_BIND_HOST = "0.0.0.0";

    private RpcServerAddressUtil() {
    }

    public static InetSocketAddress getServerAddress(int port) {
        return new InetSocketAddress(getAdvertisedHost(), port);
    }

    /**
     * @deprecated use {@link #getAdvertisedHost()} for registry addresses or
     * {@link #getBindHost()} for the local listening address.
     */
    @Deprecated
    public static String getServerHost() {
        return getAdvertisedHost();
    }

    public static String getAdvertisedHost() {
        Properties properties = PropertiesFileUtil.readPropertiesFile(
                RpcConfigEnum.RPC_CONFIG_PATH.getPropertyValue());
        String configuredHost = getConfiguredValue(properties, RpcConfigEnum.SERVER_HOST);
        if (!StringUtil.isBlank(configuredHost)) {
            String host = configuredHost.trim();
            rejectWildcardAdvertisedHost(host);
            return host;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Failed to resolve the RPC server host", e);
        }
    }

    public static String getBindHost() {
        Properties properties = PropertiesFileUtil.readPropertiesFile(
                RpcConfigEnum.RPC_CONFIG_PATH.getPropertyValue());
        String configuredHost = getConfiguredValue(properties, RpcConfigEnum.SERVER_BIND_HOST);
        return StringUtil.isBlank(configuredHost) ? DEFAULT_BIND_HOST : configuredHost.trim();
    }

    private static String getConfiguredValue(Properties properties, RpcConfigEnum key) {
        String systemValue = System.getProperty(key.getPropertyValue());
        if (!StringUtil.isBlank(systemValue)) {
            return systemValue;
        }
        return properties == null ? null : properties.getProperty(key.getPropertyValue());
    }

    private static void rejectWildcardAdvertisedHost(String host) {
        if ("0.0.0.0".equals(host) || "::".equals(host)
                || "0:0:0:0:0:0:0:0".equals(host)
                || host.indexOf('/') >= 0 || host.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "rpc.server.host must be reachable by clients and safe for a registry path: "
                            + host);
        }
    }
}

package github.javaguide.utils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Builds an unambiguous registry key from an RPC interface, group and version.
 */
public final class RpcServiceNameBuilder {

    private RpcServiceNameBuilder() {
    }

    public static String build(String interfaceName, String group, String version) {
        if (StringUtil.isBlank(interfaceName)) {
            throw new IllegalArgumentException("RPC interface name cannot be blank");
        }
        if (interfaceName.indexOf('/') >= 0 || interfaceName.indexOf(':') >= 0
                || interfaceName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "RPC interface name contains a registry-path delimiter: " + interfaceName);
        }
        return interfaceName + ':' + encode(group) + ':' + encode(version);
    }

    private static String encode(String value) {
        String normalized = value == null ? "" : value;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                normalized.getBytes(StandardCharsets.UTF_8));
    }
}

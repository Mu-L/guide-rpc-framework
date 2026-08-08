package github.javaguide.registry.zk.util;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CuratorUtilsTest {

    @Test
    void shouldBuildOneServiceLeafForHostname() {
        String path = CuratorUtils.buildRegisterPath("example.Service",
                InetSocketAddress.createUnresolved("rpc-provider.internal", 9998));

        assertEquals("/my-rpc/example.Service/rpc-provider.internal:9998", path);
    }
}

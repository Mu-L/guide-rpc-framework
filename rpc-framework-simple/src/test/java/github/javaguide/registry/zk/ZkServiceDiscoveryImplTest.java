package github.javaguide.registry.zk;

import github.javaguide.exception.RpcException;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZkServiceDiscoveryImplTest {

    @Test
    void shouldParseIpv4AndIpv6ProviderAddresses() {
        assertEquals(new InetSocketAddress("127.0.0.1", 9998),
                ZkServiceDiscoveryImpl.parseServiceAddress("127.0.0.1:9998"));
        assertEquals(new InetSocketAddress("::1", 9998),
                ZkServiceDiscoveryImpl.parseServiceAddress("::1:9998"));
    }

    @Test
    void shouldRejectMalformedOrOutOfRangeProviderAddress() {
        assertThrows(RpcException.class,
                () -> ZkServiceDiscoveryImpl.parseServiceAddress("127.0.0.1:not-a-port"));
        assertThrows(RpcException.class,
                () -> ZkServiceDiscoveryImpl.parseServiceAddress("127.0.0.1:70000"));
    }
}

package github.javaguide.config;

import github.javaguide.enums.RpcConfigEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RpcServerAddressUtilTest {

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty(RpcConfigEnum.SERVER_HOST.getPropertyValue());
        System.clearProperty(RpcConfigEnum.SERVER_BIND_HOST.getPropertyValue());
    }

    @Test
    void shouldResolveBindAndAdvertisedHostsIndependently() {
        System.setProperty(RpcConfigEnum.SERVER_HOST.getPropertyValue(), "rpc.example.test");
        System.setProperty(RpcConfigEnum.SERVER_BIND_HOST.getPropertyValue(), "127.0.0.1");

        assertEquals("rpc.example.test", RpcServerAddressUtil.getAdvertisedHost());
        assertEquals("127.0.0.1", RpcServerAddressUtil.getBindHost());
    }

    @Test
    void shouldRejectWildcardAdvertisedHost() {
        System.setProperty(RpcConfigEnum.SERVER_HOST.getPropertyValue(), "0.0.0.0");

        assertThrows(IllegalArgumentException.class,
                RpcServerAddressUtil::getAdvertisedHost);
    }
}

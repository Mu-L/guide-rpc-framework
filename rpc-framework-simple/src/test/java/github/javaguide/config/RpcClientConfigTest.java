package github.javaguide.config;

import github.javaguide.enums.CompressTypeEnum;
import github.javaguide.enums.RpcConfigEnum;
import github.javaguide.enums.SerializationTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcClientConfigTest {

    @Test
    void shouldLoadDefaultsWhenPropertiesAreEmpty() {
        RpcClientConfig config = RpcClientConfig.from(new Properties());

        assertEquals(SerializationTypeEnum.HESSIAN.getCode(), config.getSerializationCode());
        assertEquals(CompressTypeEnum.GZIP.getCode(), config.getCompressCode());
        assertEquals(RpcClientConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                config.getConnectTimeoutMillis());
        assertEquals(RpcClientConfig.DEFAULT_REQUEST_TIMEOUT_MILLIS,
                config.getRequestTimeoutMillis());
    }

    @Test
    void shouldLoadConfiguredSerializerAndTimeouts() {
        Properties properties = new Properties();
        properties.setProperty(RpcConfigEnum.SERIALIZATION.getPropertyValue(), "kryo");
        properties.setProperty(RpcConfigEnum.COMPRESS.getPropertyValue(), "gzip");
        properties.setProperty(RpcConfigEnum.CONNECT_TIMEOUT_MILLIS.getPropertyValue(), "1200");
        properties.setProperty(RpcConfigEnum.REQUEST_TIMEOUT_MILLIS.getPropertyValue(), "3400");

        RpcClientConfig config = RpcClientConfig.from(properties);

        assertEquals(SerializationTypeEnum.KRYO.getCode(), config.getSerializationCode());
        assertEquals(CompressTypeEnum.GZIP.getCode(), config.getCompressCode());
        assertEquals(1200, config.getConnectTimeoutMillis());
        assertEquals(3400L, config.getRequestTimeoutMillis());
    }

    @Test
    void shouldRejectInvalidConfigurationAtStartup() {
        Properties properties = new Properties();
        properties.setProperty(RpcConfigEnum.REQUEST_TIMEOUT_MILLIS.getPropertyValue(), "0");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> RpcClientConfig.from(properties));

        assertTrue(exception.getMessage().contains(
                RpcConfigEnum.REQUEST_TIMEOUT_MILLIS.getPropertyValue()));
    }

    @Test
    void shouldNameInvalidSerializerProperty() {
        Properties properties = new Properties();
        properties.setProperty(RpcConfigEnum.SERIALIZATION.getPropertyValue(), "unknown");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> RpcClientConfig.from(properties));

        assertTrue(exception.getMessage().contains(
                RpcConfigEnum.SERIALIZATION.getPropertyValue()));
    }
}

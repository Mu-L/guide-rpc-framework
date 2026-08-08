package github.javaguide.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RpcServiceConfigTest {

    @Test
    void builderShouldKeepEmptyGroupAndVersionDefaults() {
        RpcServiceConfig config = RpcServiceConfig.builder()
                .service(new ExampleServiceImpl())
                .build();

        assertEquals("", config.getGroup());
        assertEquals("", config.getVersion());
        assertEquals(ExampleService.class.getCanonicalName() + "::",
                config.getRpcServiceName());
    }

    @Test
    void serviceNameShouldNotCollideWhenGroupAndVersionBoundariesDiffer() {
        RpcServiceConfig first = RpcServiceConfig.builder()
                .service(new ExampleServiceImpl())
                .group("a")
                .version("bc")
                .build();
        RpcServiceConfig second = RpcServiceConfig.builder()
                .service(new ExampleServiceImpl())
                .group("ab")
                .version("c")
                .build();

        assertNotEquals(first.getRpcServiceName(), second.getRpcServiceName());
    }

    @Test
    void shouldRejectInterfaceNameThatCanEscapeRegistryPath() {
        RpcServiceConfig config = RpcServiceConfig.builder()
                .serviceName("example.Service/../../other")
                .group("")
                .version("")
                .build();

        assertThrows(IllegalArgumentException.class, config::getRpcServiceName);
    }

    interface ExampleService {
    }

    static class ExampleServiceImpl implements ExampleService {
    }
}

package github.javaguide.spring;

import github.javaguide.annotation.RpcReference;
import github.javaguide.annotation.RpcService;
import github.javaguide.config.RpcServiceConfig;
import github.javaguide.factory.SingletonFactory;
import github.javaguide.provider.ServiceProvider;
import github.javaguide.provider.impl.ZkServiceProviderImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringBeanPostProcessorTest {

    @Test
    void shouldInjectInheritedReferenceBeforeInitAndRegisterServiceAfterInit()
            throws Exception {
        SpringBeanPostProcessor processor = new SpringBeanPostProcessor();
        try {
            ClientBean client = new ClientBean();
            processor.postProcessBeforeInitialization(client, "clientBean");
            Field reference = ClientBase.class.getDeclaredField("service");
            reference.setAccessible(true);
            assertNotNull(reference.get(client));

            SpringServiceImpl service = new SpringServiceImpl();
            processor.postProcessAfterInitialization(service, "springService");
            RpcServiceConfig serviceConfig = RpcServiceConfig.builder()
                    .serviceName(SpringService.class.getCanonicalName())
                    .group("spring-test")
                    .version("v1")
                    .service(service)
                    .build();
            ServiceProvider serviceProvider =
                    SingletonFactory.getInstance(ZkServiceProviderImpl.class);
            assertSame(service, serviceProvider.getService(serviceConfig.getRpcServiceName()));
        } finally {
            processor.destroy();
        }
    }

    @Test
    void shouldFailFastForFinalRpcReference() {
        SpringBeanPostProcessor processor = new SpringBeanPostProcessor();

        assertThrows(BeanCreationException.class,
                () -> processor.postProcessBeforeInitialization(
                        new InvalidClientBean(), "invalidClientBean"));
    }

    interface SpringService {
        String call();
    }

    @RpcService(group = "spring-test", version = "v1")
    static class SpringServiceImpl implements SpringService {
        @Override
        public String call() {
            return "ok";
        }
    }

    static class ClientBase {
        @RpcReference(group = "spring-test", version = "v1")
        private SpringService service;
    }

    static class ClientBean extends ClientBase {
    }

    static class InvalidClientBean {
        @RpcReference
        private final SpringService service = null;
    }
}

package github.javaguide.spring;

import github.javaguide.annotation.RpcReference;
import github.javaguide.annotation.RpcService;
import github.javaguide.config.RpcServiceConfig;
import github.javaguide.enums.RpcRequestTransportEnum;
import github.javaguide.extension.ExtensionLoader;
import github.javaguide.factory.SingletonFactory;
import github.javaguide.provider.ServiceProvider;
import github.javaguide.provider.impl.ZkServiceProviderImpl;
import github.javaguide.proxy.RpcClientProxy;
import github.javaguide.remoting.transport.RpcRequestTransport;
import github.javaguide.remoting.transport.netty.client.NettyRpcClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * call this method before creating the bean to see if the class is annotated
 *
 * @author shuang.kou
 * @createTime 2020年07月14日 16:42:00
 */
@Slf4j
@Component
public class SpringBeanPostProcessor implements BeanPostProcessor, DisposableBean {

    private final ServiceProvider serviceProvider;
    private volatile RpcRequestTransport rpcClient;

    public SpringBeanPostProcessor() {
        this.serviceProvider = SingletonFactory.getInstance(ZkServiceProviderImpl.class);
    }

    /**
     * 客户端进行rpc调用的时候，让clientProxy帮忙调用而不是真正自己进行通信调用
     *
     * 为标注了@RpcReference注解的字段动态生成代理对象，从而实现远程服务的调用
     *
     * 这里并不需要进行缓存，因为创建的bean实际上已经被Spring容器缓存了。也就是说，
     * 对于单例模式的Bean，这个方法只会待用一次，之后bean就会被Sprin缓存起来了
     * */
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName)
            throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        injectRpcReferences(bean, beanName, targetClass);
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        registerRpcService(bean, AopUtils.getTargetClass(bean));
        return bean;
    }

    private void registerRpcService(Object bean, Class<?> targetClass) {
        RpcService rpcService = AnnotationUtils.findAnnotation(targetClass, RpcService.class);
        if (rpcService == null) {
            return;
        }
        Class<?>[] interfaces = targetClass.getInterfaces();
        if (interfaces.length == 0) {
            throw new BeanCreationException(targetClass.getName(),
                    "@RpcService class must implement an interface");
        }
        log.info("[{}] is annotated with [{}]", targetClass.getName(),
                RpcService.class.getCanonicalName());
        serviceProvider.addService(RpcServiceConfig.builder()
                .group(rpcService.group())
                .version(rpcService.version())
                .serviceName(interfaces[0].getCanonicalName())
                .service(bean)
                .build());
    }

    private void injectRpcReferences(Object bean, String beanName, Class<?> targetClass) {
        for (Class<?> current = targetClass;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                injectRpcReference(bean, beanName, field);
            }
        }
    }

    private void injectRpcReference(Object bean, String beanName, Field declaredField) {
        RpcReference rpcReference = declaredField.getAnnotation(RpcReference.class);
        if (rpcReference == null) {
            return;
        }
        int modifiers = declaredField.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)
                || !declaredField.getType().isInterface()) {
            throw new BeanCreationException(beanName,
                    "@RpcReference field must be a non-static, non-final interface: "
                            + declaredField);
        }
        RpcServiceConfig rpcServiceConfig = RpcServiceConfig.builder()
                .group(rpcReference.group())
                .version(rpcReference.version()).build();
        Object clientProxy = new RpcClientProxy(getRpcClient(), rpcServiceConfig)
                .getProxy(declaredField.getType());
        try {
            declaredField.setAccessible(true);
            declaredField.set(bean, clientProxy);
        } catch (IllegalAccessException | RuntimeException e) {
            throw new BeanCreationException(beanName,
                    "Failed to inject RPC reference into " + declaredField, e);
        }
    }

    private RpcRequestTransport getRpcClient() {
        RpcRequestTransport client = rpcClient;
        if (client != null) {
            return client;
        }
        synchronized (this) {
            if (rpcClient == null) {
                rpcClient = ExtensionLoader.getExtensionLoader(RpcRequestTransport.class)
                        .getExtension(RpcRequestTransportEnum.NETTY.getName());
            }
            return rpcClient;
        }
    }

    @Override
    public void destroy() {
        RpcRequestTransport client = rpcClient;
        if (client instanceof NettyRpcClient) {
            ((NettyRpcClient) client).close();
        }
    }
}

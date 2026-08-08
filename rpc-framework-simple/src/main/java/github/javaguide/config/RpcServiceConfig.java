package github.javaguide.config;

import github.javaguide.enums.RpcStatusCode;
import github.javaguide.exception.RpcException;
import github.javaguide.utils.StringUtil;
import github.javaguide.utils.RpcServiceNameBuilder;
import lombok.*;

import java.util.Objects;

/**
 * @author shuang.kou
 * @createTime 2020年07月21日 20:23:00
 **/
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class RpcServiceConfig {
    /**
     * service version
     */
    @Builder.Default
    private String version = "";
    /**
     * when the interface has multiple implementation classes, distinguish by group
     */
    @Builder.Default
    private String group = "";

    /**
     * target service
     */
    private Object service;

    /**
     * Explicit interface name used when the runtime service is wrapped by a proxy.
     */
    private String serviceName;

    public String getRpcServiceName() {
        return RpcServiceNameBuilder.build(
                this.getServiceName(), this.getGroup(), this.getVersion());
    }

    public String getServiceName() {
        if (!StringUtil.isBlank(serviceName)) {
            return serviceName;
        }
        Object targetService = Objects.requireNonNull(service, "service cannot be null");
        Class<?>[] interfaces = targetService.getClass().getInterfaces();
        if (interfaces.length == 0) {
            throw new RpcException(RpcStatusCode.FAILED_PRECONDITION,
                    "Registered RPC service must implement an interface");
        }
        return interfaces[0].getCanonicalName();
    }
}

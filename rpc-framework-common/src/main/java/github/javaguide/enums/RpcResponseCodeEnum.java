package github.javaguide.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * @author shuang.kou
 * @createTime 2020年05月12日 16:24:00
 */
@AllArgsConstructor
@Getter
@ToString
public enum RpcResponseCodeEnum {

    /** @deprecated use {@link RpcStatusCode#OK}. */
    @Deprecated
    SUCCESS(RpcStatusCode.OK.getCode(), RpcStatusCode.OK.getMessage()),
    /** @deprecated use a specific {@link RpcStatusCode}; generic failures map to INTERNAL. */
    @Deprecated
    FAIL(RpcStatusCode.INTERNAL.getCode(), RpcStatusCode.INTERNAL.getMessage());
    private final int code;

    private final String message;

}

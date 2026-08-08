package github.javaguide.remoting.dto;

import github.javaguide.enums.RpcResponseCodeEnum;
import github.javaguide.enums.RpcStatusCode;
import lombok.*;

import java.io.Serializable;

/**
 * @author shuang.kou
 * @createTime 2020年05月12日 16:15:00
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class RpcResponse<T> implements Serializable {

    private static final long serialVersionUID = 715745410605631233L;
    private String requestId;
    /**
     * response code
     */
    private Integer code;
    /**
     * response message
     */
    private String message;
    /**
     * response body
     */
    private T data;

    public static <T> RpcResponse<T> success(T data, String requestId) {
        RpcResponse<T> response = new RpcResponse<>();
        response.setCode(RpcStatusCode.OK.getCode());
        response.setMessage(RpcStatusCode.OK.getMessage());
        response.setRequestId(requestId);
        response.setData(data);
        return response;
    }

    /** @deprecated use {@link #fail(RpcStatusCode)}. */
    @Deprecated
    public static <T> RpcResponse<T> fail(RpcResponseCodeEnum rpcResponseCodeEnum) {
        return fail(rpcResponseCodeEnum, null, rpcResponseCodeEnum.getMessage());
    }

    /** @deprecated use {@link #fail(RpcStatusCode, String, String)}. */
    @Deprecated
    public static <T> RpcResponse<T> fail(RpcResponseCodeEnum rpcResponseCodeEnum,
                                          String requestId, String message) {
        return buildFailure(rpcResponseCodeEnum.getCode(), requestId, message);
    }

    public static <T> RpcResponse<T> fail(RpcStatusCode statusCode) {
        return fail(statusCode, null, statusCode.getMessage());
    }

    public static <T> RpcResponse<T> fail(RpcStatusCode statusCode,
                                          String requestId, String message) {
        if (statusCode == null || statusCode == RpcStatusCode.OK) {
            throw new IllegalArgumentException("Failure response requires a failure status");
        }
        return buildFailure(statusCode.getCode(), requestId, message);
    }

    public boolean isSuccess() {
        return Integer.valueOf(RpcStatusCode.OK.getCode()).equals(code);
    }

    private static <T> RpcResponse<T> buildFailure(int code,
                                                   String requestId, String message) {
        RpcResponse<T> response = new RpcResponse<>();
        response.setCode(code);
        response.setMessage(message);
        response.setRequestId(requestId);
        return response;
    }

}

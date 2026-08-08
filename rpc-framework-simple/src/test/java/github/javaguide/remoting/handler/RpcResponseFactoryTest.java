package github.javaguide.remoting.handler;

import github.javaguide.enums.RpcStatusCode;
import github.javaguide.exception.RpcServiceException;
import github.javaguide.remoting.dto.RpcResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RpcResponseFactoryTest {

    @Test
    void shouldExposeExplicitServiceFailure() {
        RpcResponse<Object> response = RpcResponseFactory.failure(
                "business-request",
                new RpcServiceException(
                        RpcStatusCode.FAILED_PRECONDITION, "order already paid"));

        assertEquals(RpcStatusCode.FAILED_PRECONDITION.getCode(), response.getCode());
        assertEquals("order already paid", response.getMessage());
        assertEquals("business-request", response.getRequestId());
    }

    @Test
    void shouldHideUnexpectedFailureDetails() {
        RpcResponse<Object> response = RpcResponseFactory.failure(
                "internal-request",
                new IllegalStateException("database password was exposed"));

        assertEquals(RpcStatusCode.INTERNAL.getCode(), response.getCode());
        assertEquals(RpcStatusCode.INTERNAL.getMessage(), response.getMessage());
        assertFalse(response.getMessage().contains("password"));
    }

    @Test
    void shouldUnwrapCompletionFailure() {
        RpcResponse<Object> response = RpcResponseFactory.failure(
                "async-request",
                new CompletionException(new RpcServiceException(
                        RpcStatusCode.NOT_FOUND, "order not found")));

        assertEquals(RpcStatusCode.NOT_FOUND.getCode(), response.getCode());
        assertEquals("order not found", response.getMessage());
    }
}

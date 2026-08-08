package github.javaguide.remoting.transport.netty.client;

import github.javaguide.remoting.dto.RpcResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnprocessedRequestsTest {

    @Test
    void shouldRejectDuplicateRequestIds() {
        UnprocessedRequests requests = new UnprocessedRequests();
        requests.put("same-id", new CompletableFuture<>());

        assertThrows(IllegalStateException.class,
                () -> requests.put("same-id", new CompletableFuture<>()));
    }

    @Test
    void shouldCompleteMatchingRequestAndFailOutstandingRequests() {
        UnprocessedRequests requests = new UnprocessedRequests();
        CompletableFuture<RpcResponse<Object>> completed = new CompletableFuture<>();
        CompletableFuture<RpcResponse<Object>> failed = new CompletableFuture<>();
        requests.put("completed", completed);
        requests.put("failed", failed);

        requests.complete(RpcResponse.success("ok", "completed"));
        requests.failAll(new IllegalStateException("client closed"));

        assertEquals("ok", completed.join().getData());
        CompletionException exception = assertThrows(CompletionException.class, failed::join);
        assertTrue(exception.getCause() instanceof IllegalStateException);
    }
}

package github.javaguide;

import java.util.concurrent.CompletableFuture;

/** Asynchronous client view of {@link HelloService}. */
public interface HelloServiceAsync {

    CompletableFuture<String> hello(Hello hello);
}

package github.javaguide.factory;

import org.junit.jupiter.api.Test;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SingletonFactoryTest {

    @Test
    void shouldCreateOnlyOneInstanceWhenCalledConcurrently() throws InterruptedException {
        int threadCount = 32;
        AtomicInteger constructorCalls = new AtomicInteger();
        Queue<TestSingleton> instances = new ConcurrentLinkedQueue<>();
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    instances.add(SingletonFactory.getInstance(() -> {
                        constructorCalls.incrementAndGet();
                        return new TestSingleton();
                    }, TestSingleton.class));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[i].start();
        }

        ready.await();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        TestSingleton expected = instances.peek();
        assertEquals(1, constructorCalls.get());
        assertEquals(threadCount, instances.size());
        instances.forEach(instance -> assertSame(expected, instance));
    }

    @Test
    void shouldAllowNestedSingletonCreation() {
        OuterSingleton outer = SingletonFactory.getInstance(
                () -> new OuterSingleton(SingletonFactory.getInstance(InnerSingleton.class)),
                OuterSingleton.class);

        assertSame(SingletonFactory.getInstance(InnerSingleton.class), outer.inner);
    }

    private static final class TestSingleton {
    }

    private static final class OuterSingleton {
        private final InnerSingleton inner;

        private OuterSingleton(InnerSingleton inner) {
            this.inner = inner;
        }
    }

    private static final class InnerSingleton {
    }
}

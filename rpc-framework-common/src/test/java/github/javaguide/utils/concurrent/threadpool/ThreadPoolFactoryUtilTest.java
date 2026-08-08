package github.javaguide.utils.concurrent.threadpool;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadPoolFactoryUtilTest {

    @Test
    void shouldCreateSequentiallyNamedDaemonThreads() {
        ThreadFactory threadFactory =
                ThreadPoolFactoryUtil.createThreadFactory("rpc-worker", true);

        Thread first = threadFactory.newThread(() -> { });
        Thread second = threadFactory.newThread(() -> { });

        assertEquals("rpc-worker-0", first.getName());
        assertEquals("rpc-worker-1", second.getName());
        assertTrue(first.isDaemon());
        assertTrue(second.isDaemon());
    }

    @Test
    void shouldPreserveDefaultDaemonSettingWhenNotSpecified() {
        Thread thread = ThreadPoolFactoryUtil
                .createThreadFactory("rpc-worker", null)
                .newThread(() -> { });

        assertEquals("rpc-worker-0", thread.getName());
        assertFalse(thread.isDaemon());
    }
}

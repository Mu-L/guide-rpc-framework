package github.javaguide.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RpcStatusCodeTest {

    @Test
    void shouldResolveStableWireCode() {
        assertEquals(RpcStatusCode.DEADLINE_EXCEEDED,
                RpcStatusCode.fromCode(4));
    }

    @Test
    void shouldRejectUnknownWireCode() {
        assertThrows(IllegalArgumentException.class,
                () -> RpcStatusCode.fromCode(99));
    }
}

package github.javaguide.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SerializationTypeEnumTest {

    @Test
    void shouldReturnCorrectKryoName() {
        assertEquals("kryo", SerializationTypeEnum.getName((byte) 0x01));
    }

    @Test
    void shouldRejectUnknownSerializationCode() {
        assertThrows(IllegalArgumentException.class,
                () -> SerializationTypeEnum.getName((byte) 0x7F));
    }
}

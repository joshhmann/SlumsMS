package net;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;

/** rc6 bug hunt (P3, stock Cosmic): getHandler checked {@code >} the length, so the first id past the end threw. */
class PacketProcessorBoundsTest {

    @Test
    void anOpcodeOutsideTheTableHasNoHandlerInsteadOfThrowing() throws Exception {
        Constructor<PacketProcessor> c = PacketProcessor.class.getDeclaredConstructor();
        c.setAccessible(true);
        PacketProcessor p = c.newInstance();
        Field f = PacketProcessor.class.getDeclaredField("handlers");
        f.setAccessible(true);
        int length = ((Object[]) f.get(p)).length;

        assertDoesNotThrow(() -> assertNull(p.getHandler((short) length)), "exactly the length");
        assertDoesNotThrow(() -> assertNull(p.getHandler((short) -1)), "a negative opcode");
        assertDoesNotThrow(() -> assertNull(p.getHandler(Short.MIN_VALUE)));
        assertNull(p.getHandler((short) 0), "inside the table: whatever is registered (nothing here)");
    }
}

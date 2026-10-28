package client.command.commands.gm3;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 1.6.1: !tp x y is a GM 3 same-map move (the client's NPC screenshot pass). */
class TpCommandTest {

    @Test
    void itTakesExactlyTwoWholeNumbers() {
        assertEquals(new Point(1040, 274), TpCommand.parse(new String[]{"1040", "274"}));
        assertEquals(new Point(-300, -85), TpCommand.parse(new String[]{"-300", " -85"}));
        assertNull(TpCommand.parse(new String[]{}));
        assertNull(TpCommand.parse(new String[]{"1040"}));
        assertNull(TpCommand.parse(new String[]{"1040", "274", "9"}));
        assertNull(TpCommand.parse(new String[]{"x", "274"}));
        assertNull(TpCommand.parse(new String[]{"10.5", "274"}));
        assertNull(TpCommand.parse(null));
    }
}

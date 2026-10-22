package client.command.commands.gm3;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BotGroundCommandTest {

    private static FootholdTree floor() {
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        tree.insert(new Foothold(new Point(938, 334), new Point(1350, 334), 1));
        return tree;
    }

    @Test
    void listsOnlyTheBotsOffTheirFoothold() {
        FootholdTree fh = floor();
        assertAll(
                () -> assertNull(BotGroundCommand.describe(fh, "Admin", new Point(1000, 334), 4, 0)),
                () -> assertNull(BotGroundCommand.describe(fh, "Slope", new Point(1000, 335), 4, 0), "1 px is rounding"),
                () -> assertEquals("NachoHat08 at (1148, 363): 29 px below its foothold (y 334), stance 4",
                        BotGroundCommand.describe(fh, "NachoHat08", new Point(1148, 363), 4, 0)),
                () -> assertEquals("Jumper at (1000, 300): 34 px above its foothold (y 334), stance 6, chair 3010000",
                        BotGroundCommand.describe(fh, "Jumper", new Point(1000, 300), 6, 3010000)),
                () -> assertEquals("Lost at (5000, 0): no foothold under it, stance 4",
                        BotGroundCommand.describe(fh, "Lost", new Point(5000, 0), 4, 0)));
    }

    @Test
    void buckets() {
        assertAll(
                () -> assertEquals(0, BotGroundCommand.bucket(0)),
                () -> assertEquals(1, BotGroundCommand.bucket(29)),
                () -> assertEquals(2, BotGroundCommand.bucket(-20)),
                () -> assertEquals(2, BotGroundCommand.bucket(Integer.MIN_VALUE)));
    }
}

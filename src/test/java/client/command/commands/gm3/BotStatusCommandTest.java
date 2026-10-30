package client.command.commands.gm3;

import client.BotClient;
import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ticket 157 / bug b045: one line per bot on the GM's map, with its character id. */
class BotStatusCommandTest {

    private static Character chr(int id, String name, boolean bot) {
        if (!bot) {
            Character player = org.mockito.Mockito.mock(Character.class); // a real player's client is no BotClient
            org.mockito.Mockito.when(player.getName()).thenReturn(name);
            return player;
        }
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(1_000_000_000 + id);
        c.setName(name);
        c.setJob(Job.HUNTER);
        c.setLevel(34);
        c.setPosition(new Point(120, -85));
        return c;
    }

    @Test
    void oneLinePerBotByNameAndPlayersLeftOut() {
        List<String> lines = BotStatusCommand.lines(100000000, "full",
                List.of(chr(7, "zed", true), chr(3, "GM", false), chr(5, "Amy", true)));
        assertEquals("Bots on map 100000000 (full): 2", lines.get(0));
        assertEquals(3, lines.size());
        assertEquals("1000000005 Amy Lv34 HUNTER offline-brain (120,-85) nav off", lines.get(1));
        assertEquals("1000000007 zed Lv34 HUNTER offline-brain (120,-85) nav off", lines.get(2));
    }

    @Test
    void theLineFormat() {
        assertEquals("1000000001 Bob Lv70 PRIEST training/grind (1,2) nav exec",
                BotStatusCommand.line(1_000_000_001, "Bob", 70, "PRIEST", "training", "grind", 1, 2, "exec"));
    }
}

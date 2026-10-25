package scripting.npc;

import client.Client;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** 1.5.2: a number box's bounds were only enforced by the client; a forged reply reached the script as is. */
class NumberBoxBoundsTest {
    @Test
    void aForgedAnswerIsKeptInsideTheBox() {
        NPCConversationManager cm = new NPCConversationManager(mock(Client.class), 1300009, "memory");
        cm.sendGetNumber("How many pairs do you want to play with?", 10, 2, 150);
        assertEquals(150, cm.boundNumber(Integer.MAX_VALUE), "memory.js built 2^31 cards");
        assertEquals(2, cm.boundNumber(-5), "and a negative quantity never reaches a trade");
        assertEquals(37, cm.boundNumber(37));
    }

    @Test
    void reversedBoundsStillBound() {
        NPCConversationManager cm = new NPCConversationManager(mock(Client.class), 1, "x");
        cm.sendGetNumber("?", 1, 100, 1);
        assertEquals(100, cm.boundNumber(1000));
        assertEquals(1, cm.boundNumber(0));
    }
}

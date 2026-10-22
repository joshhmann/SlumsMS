package soloMapling.Gachapon;

import client.BotClient;
import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GachaAnnouncerTest {

    final AtomicLong now = new AtomicLong(1_000_000_000L);
    final AtomicBoolean gateOpen = new AtomicBoolean(true);
    final AtomicInteger gateNotes = new AtomicInteger();
    final List<String> posted = new ArrayList<>();
    final GachaAnnouncer a = GachaAnnouncer.get();
    final Item item = new Item(2049100, (short) 0, (short) 1);

    @BeforeEach
    void setUp() {
        a.useForTest(now::get, gateOpen::get, gateNotes::incrementAndGet,
                n -> posted.add(n.winner().getName() + (n.bot() ? " (bot)" : "") + " @" + n.townName()));
    }

    @AfterEach
    void tearDown() {
        a.useForTest(null, null, null, null);
    }

    static Character player(int id, String name) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        return c;
    }

    static Character bot(int id) throws Exception {
        Character b = Character.getDefault(new BotClient(0, 0));
        Field f = Character.class.getDeclaredField("id");
        f.setAccessible(true);
        f.setInt(b, id);
        b.setName("bot" + id);
        return b;
    }

    @Test
    void aPlayersWinGoesOutAndClaimsTheBotMegaphoneBudget() {
        assertTrue(a.announce(player(1, "Alice"), item, "Henesys"));
        assertEquals(List.of("Alice @Henesys"), posted);
        assertEquals(1, gateNotes.get());
    }

    @Test
    void aSecondPlayerInsideTheGapWaitsThenGoesOut() {
        assertTrue(a.announce(player(1, "Alice"), item, "Henesys"));
        assertFalse(a.announce(player(2, "Bob"), item, "Perion"));
        assertEquals(1, posted.size(), "queued, not dropped");
        now.addAndGet(10_000);
        assertEquals(0, a.flush(), "the 20 s gap is not over");
        now.addAndGet(10_000);
        assertEquals(1, a.flush());
        assertEquals(List.of("Alice @Henesys", "Bob @Perion"), posted);
    }

    @Test
    void aQueuedNoticeThatWaitedTooLongIsDropped() {
        a.announce(player(1, "Alice"), item, "Henesys");
        a.announce(player(2, "Bob"), item, "Perion");
        now.addAndGet(301_000);
        assertEquals(0, a.flush());
        assertEquals(1, posted.size());
    }

    @Test
    void oneNoticePerPlayerPerMinute() {
        Character alice = player(1, "Alice");
        assertTrue(a.announce(alice, item, "Henesys"));
        now.addAndGet(30_000);
        assertFalse(a.announce(alice, item, "Henesys"));
        now.addAndGet(31_000);
        assertTrue(a.announce(alice, item, "Henesys"));
        assertEquals(2, posted.size());
    }

    @Test
    void aBotNeedsTheBotMegaphoneBudgetAndNeverWaits() throws Exception {
        gateOpen.set(false);
        assertFalse(a.announce(bot(500_001), item, "Henesys"));
        assertEquals(0, a.flush());
        assertEquals(List.of(), posted, "a bot that misses the gate is not announced later");
        gateOpen.set(true);
        assertTrue(a.announce(bot(500_002), item, "Henesys"));
        assertEquals(List.of("bot500002 (bot) @Henesys"), posted);
        assertEquals(0, gateNotes.get(), "the bot took the budget through the gate itself");
    }

    @Test
    void botsAreAnnouncedAtMostOncePer15Minutes() throws Exception {
        assertTrue(a.announce(bot(500_001), item, "Henesys"));
        now.addAndGet(600_000);
        assertFalse(a.announce(bot(500_002), item, "Ellinia"));
        now.addAndGet(300_001);
        assertTrue(a.announce(bot(500_003), item, "Ellinia"));
        assertEquals(2, posted.size());
    }

    @Test
    void offSwitch() {
        System.setProperty("solomapling.gachaNotices", "false");
        try {
            assertFalse(a.announce(player(1, "Alice"), item, "Henesys"));
            assertEquals(List.of(), posted);
        } finally {
            System.clearProperty("solomapling.gachaNotices");
        }
    }
}

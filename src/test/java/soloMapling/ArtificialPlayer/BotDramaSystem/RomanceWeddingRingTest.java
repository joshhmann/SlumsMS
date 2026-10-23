package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import client.inventory.Equip;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;
import tools.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.5.0 rc6 bug hunt (P2): makeItReal removed the engagement tokens, ignored whether the ring reached the player and
 * marked the pair married anyway, so a full equip tab lost the ring. The ring is now delivered first; a failed
 * delivery deletes the ring rows and leaves the tokens and the engagement alone.
 */
class RomanceWeddingRingTest {

    static final int PLAYER = 4242;
    static final int BOT_SLOT = 77;
    static final int BOT = BotStore.BOT_ID_BASE + BOT_SLOT;

    final List<String> steps = new ArrayList<>();
    boolean room = true;
    boolean delivers = true;
    Character player;
    Character bot;
    final Equip ring = mock(Equip.class);

    @BeforeEach
    void setUp() {
        BotRomance.useForTest(new RomanceStore.Memory(), id -> null, id -> null, new AtomicLong(1_000_000)::get);
        BotRomance.putForTest(new Romance(PLAYER, BOT_SLOT, Romance.Stage.ENGAGED, 0, 0, 0, 1112803, 0, false));
        player = mock(Character.class);
        bot = mock(Character.class);
        when(player.getId()).thenReturn(PLAYER);
        when(player.getName()).thenReturn("Player");
        when(player.getPartnerId()).thenReturn(BOT);
        when(player.getMarriageItemId()).thenReturn(2240000);
        when(bot.getId()).thenReturn(BOT);
        when(bot.getName()).thenReturn("Bot");
        RomanceWedding.ops = new RomanceWedding.Ops() {
            @Override boolean hasRoom(Character p, int ringItem) { steps.add("room"); return room; }
            @Override Equip newRing(int ringItem) { return ring; }
            @Override Pair<Integer, Integer> createRings(int ringItem, Character p, Character b) {
                steps.add("rings");
                return new Pair<>(501, 502);
            }
            @Override boolean deliver(Character p, Equip ring) { steps.add("deliver"); return delivers; }
            @Override void deleteRings(int a, int b, Character who, int ringItem) { steps.add("delete:" + a + "/" + b); }
            @Override void removeTokens(Character p, int recorded) { steps.add("tokens"); }
            @Override void bond(Romance r, Character p, Character b, int ringItem, Pair<Integer, Integer> rings, long now) {
                steps.add("bond");
            }
        };
    }

    @AfterEach
    void tearDown() {
        RomanceWedding.ops = new RomanceWedding.Ops();
        BotRomance.useForTest(null, null, null, null);
    }

    @Test
    void theRingIsDeliveredBeforeTheTokensGoAndThePairIsMarried() {
        assertNull(RomanceWedding.makeItReal(player, bot, 1));
        assertEquals(List.of("room", "rings", "deliver", "tokens", "bond"), steps);
        org.mockito.Mockito.verify(ring).setRingId(501);
    }

    @Test
    void aRingThatCantBeDeliveredUndoesTheRowsAndKeepsTheEngagement() {
        delivers = false;
        String problem = RomanceWedding.makeItReal(player, bot, 1);
        assertNotNull(problem);
        assertEquals(List.of("room", "rings", "deliver", "delete:501/502"), steps,
                "no tokens taken, nothing marked married, the ring rows deleted");
        assertEquals(Romance.Stage.ENGAGED, BotRomance.with(PLAYER, BOT).stage());
    }

    @Test
    void aFullEquipTabStopsBeforeAnyRowIsWritten() {
        room = false;
        String problem = RomanceWedding.makeItReal(player, bot, 1);
        assertTrue(problem.contains("make room"));
        assertEquals(List.of("room"), steps);
    }
}

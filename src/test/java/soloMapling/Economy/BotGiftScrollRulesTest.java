package soloMapling.Economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

/** rc6 auto-QA triage (t-gf-1): the scroll gift's rules, named, so "nothing suitable" can say which one failed. */
class BotGiftScrollRulesTest {

    @Test
    void namesTheRuleThatTurnsAScrollAway() {
        assertNull(BotGifts.scrollProblem(null, 60, 400_000, 500_000));
        assertEquals("bots may not sell it (untradeable)", BotGifts.scrollProblem("untradeable", 60, 0, 500_000));
        assertEquals("success 10% < 60%", BotGifts.scrollProblem(null, 10, 0, 500_000));
        assertEquals("worth 900,000 > 500,000", BotGifts.scrollProblem(null, 60, 900_000, 500_000));
    }

    @Test
    void noItemGiftsWhereDropsVanish() {
        // FM room 1 (910000001) has fieldLimit 4202732, which includes DROP_LIMIT 0x400000
        assertFalse(BotGifts.dropsAllowed(4202732));
        assertTrue(BotGifts.dropsAllowed(0));
        assertEquals(List.of(BotGifts.Kind.MESOS), BotGifts.order(null, BotGifts.Kind.SCROLL, false));
        assertEquals(List.of(), BotGifts.order(BotGifts.Kind.SCROLL, BotGifts.Kind.MESOS, false), "a forced scroll: nothing");
        assertEquals(List.of(BotGifts.Kind.SCROLL), BotGifts.order(BotGifts.Kind.SCROLL, BotGifts.Kind.MESOS, true));
        assertEquals(3, BotGifts.order(null, BotGifts.Kind.POTIONS, true).size());
    }
}

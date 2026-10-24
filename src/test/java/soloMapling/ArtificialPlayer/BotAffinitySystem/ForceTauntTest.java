package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** rc6 auto-QA triage (t-af-2): "!qa do grudge" taunts now, so the check does not wait on a later sighting. */
class ForceTauntTest {

    static final int PLAYER = 7171;
    static final int BOT = BotStore.BOT_ID_BASE + 77;

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, System::currentTimeMillis, id -> true);
        AffinityReactions.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotAffinity.useForTest(null, null, null, null);
        AffinityReactions.resetForTest();
    }

    static Character chr(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("c" + id);
        return c;
    }

    @Test
    void aRivalTauntsOnDemandAndANeutralBotDoesNot() {
        Character bot = chr(BOT), player = chr(PLAYER);
        BotAffinity.stage(BOT, PLAYER, AffinityState.RIVAL);
        assertTrue(AffinityReactions.forceTaunt(mock(BotSM.class), bot, player));
        assertFalse(AffinityReactions.take(AffinityReactions.Action.TAUNT, BOT, PLAYER, System.currentTimeMillis()),
                "the forced taunt starts the pair's cooldown like a real one");
        BotAffinity.stage(BOT, PLAYER, AffinityState.NEUTRAL);
        assertFalse(AffinityReactions.forceTaunt(mock(BotSM.class), bot, player), "only a rival or nemesis taunts");
    }
}

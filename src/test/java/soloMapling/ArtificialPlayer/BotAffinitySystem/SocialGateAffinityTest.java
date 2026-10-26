package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.SocialGate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.0 S5 through the affinity reactions: a rival only starts beef with a player who started it with it; the player's
 * own KS or rude line starts it; a staged grudge counts; a flirty line records the flirt-first unlock.
 */
class SocialGateAffinityTest {

    static final int PLAYER = 7272;
    static final int BOT = BotStore.BOT_ID_BASE + 88;

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, System::currentTimeMillis, id -> true);
        AffinityReactions.resetForTest();
        SocialGate.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotAffinity.useForTest(null, null, null, null);
        AffinityReactions.resetForTest();
        SocialGate.resetForTest();
    }

    static Character chr(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("c" + id);
        return c;
    }

    @Test
    void aRivalTheFreshPlayerNeverProvokedDoesNotTauntUntilTheyKsIt() {
        Character bot = chr(BOT), player = chr(PLAYER);
        assertTrue(BotAffinity.stage(BOT, PLAYER, AffinityState.RIVAL));
        SocialGate.forget(BOT, PLAYER); // a rival from somewhere else (gossip, an old tie): the player started nothing
        long now = System.currentTimeMillis();
        assertNull(AffinityReactions.pickHostile(mock(BotSM.class), bot, player, AffinityState.RIVAL, now),
                "no beef until the player started it");

        assertTrue(BotAffinity.record(BOT, PLAYER, AffinityEvent.KS, AffinityEvent.KS.delta(), "p", "b", 0));
        assertTrue(SocialGate.startedIt(BOT, PLAYER), "the player's KS started it");
        assertEquals(AffinityReactions.Action.TAUNT,
                AffinityReactions.pickHostile(mock(BotSM.class), bot, player, AffinityState.RIVAL, now));
    }

    @Test
    void aRudeLineStartsItAndAKindOneDoesNot() {
        BotAffinity.stage(BOT, PLAYER, AffinityState.FRIEND);
        BotAffinity.record(BOT, PLAYER, AffinityEvent.KIND_CHAT, AffinityEvent.KIND_CHAT.delta(), "p", "b", 0);
        assertFalse(SocialGate.startedIt(BOT, PLAYER));
        BotAffinity.record(BOT, PLAYER, AffinityEvent.RUDE_CHAT, AffinityEvent.RUDE_CHAT.delta(), "p", "b", 0);
        assertTrue(SocialGate.startedIt(BOT, PLAYER), "a roast or insult starts it");
    }

    @Test
    void aStagedGrudgeCountsAsStarted() {
        BotAffinity.stage(BOT, PLAYER, AffinityState.NEMESIS);
        assertTrue(SocialGate.startedIt(BOT, PLAYER), "GM / !qa / drama staging keeps the rival scenarios working");
        assertEquals(AffinityReactions.Action.TAUNT, AffinityReactions.pickHostile(mock(BotSM.class), chr(BOT),
                chr(PLAYER), AffinityState.RIVAL, System.currentTimeMillis()));
    }

    @Test
    void aFlirtyLineRecordsTheFlirtFirstUnlockForThatBot() {
        BotAffinity.stage(BOT, PLAYER, AffinityState.FRIEND); // loads the player's ties (judgeTone needs them)
        assertFalse(SocialGate.flirtedFirst(BOT, PLAYER));
        AffinityReactions.judgeTone(chr(BOT), chr(PLAYER), ChatTone.Tone.PLAIN, true, null);
        assertTrue(SocialGate.flirtedFirst(BOT, PLAYER));
        assertFalse(SocialGate.flirtedFirst(BOT + 1, PLAYER), "only the bot they flirted with");
    }

    @Test
    void aCrewMateOfAWrongedBotSideEyesButNeverKsesOrTaunts() {
        int mate = BOT + 1;
        SocialGate.addCrewSource((a, b) -> (a == BOT && b == mate) || (a == mate && b == BOT));
        System.setProperty("solomapling.social.sideEyeChance", "1");
        try {
            SocialGate.playerStarted(BOT, PLAYER, SocialGate.Provocation.KS);
            long now = System.currentTimeMillis();
            assertEquals(AffinityReactions.Action.SIDE_EYE, AffinityReactions.pickSideEye(chr(mate), chr(PLAYER), now));
            assertNull(AffinityReactions.pickSideEye(chr(mate), chr(PLAYER), now), "then its cooldown holds");
            assertNull(AffinityReactions.pickSideEye(chr(BOT + 2), chr(PLAYER), now), "not crew: nothing");
            BotAffinity.stage(mate, PLAYER, AffinityState.RIVAL);
            SocialGate.forget(mate, PLAYER);
            assertNull(AffinityReactions.pickHostile(mock(BotSM.class), chr(mate), chr(PLAYER), AffinityState.RIVAL,
                    now), "a crew mate gets no taunt, KS or defame of its own");
        } finally {
            System.clearProperty("solomapling.social.sideEyeChance");
        }
    }
}

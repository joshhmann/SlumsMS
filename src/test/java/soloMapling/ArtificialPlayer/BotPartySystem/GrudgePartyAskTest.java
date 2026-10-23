package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * 1.5.0 rc6 (QA t-af-2): "was able to party with grudge when double clicking their name and chat window comes up ask
 * for party". The chat window and the bots' menus roll the party ask through rollPartyAsk, which had no feelings
 * check: a rival said yes at 80% and then took the invite. Now a rival or nemesis always says no there too.
 */
class GrudgePartyAskTest {

    static final int PLAYER = 7171;
    final AtomicLong clock = new AtomicLong(1_000_000_000_000L);

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, clock::get, id -> true);
    }

    @AfterEach
    void tearDown() {
        BotAffinity.useForTest(null, null, null, null);
    }

    static Character chr(int id, int level) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("c" + id);
        when(c.getLevel()).thenReturn(level);
        return c;
    }

    @Test
    void aRivalNeverSaysYesToAPartyAsk() {
        Character bot = chr(BotStore.BOT_ID_BASE + 301, 40);
        Character player = chr(PLAYER, 40);
        assertTrue(BotAffinity.stage(bot.getId(), PLAYER, AffinityState.RIVAL));
        assertTrue(BotRecruitManager.holdsAGrudge(bot, player));
        for (int i = 0; i < 20; i++) {
            assertEquals(BotRecruitManager.RecruitAnswer.DECLINED,
                    BotRecruitManager.rollPartyAsk(bot, player, 1.0, false));
            BotRecruitManager.clearCooldownForTest(bot.getId(), PLAYER);
        }
        assertFalse(BotRecruitManager.isArmed(bot.getId()), "no invite window for a rival");
    }

    @Test
    void aNemesisToo_butAFriendStillSaysYes() {
        Character nemesis = chr(BotStore.BOT_ID_BASE + 302, 40);
        Character friend = chr(BotStore.BOT_ID_BASE + 303, 40);
        Character player = chr(PLAYER, 40);
        BotAffinity.stage(nemesis.getId(), PLAYER, AffinityState.NEMESIS);
        BotAffinity.stage(friend.getId(), PLAYER, AffinityState.FRIEND);
        assertEquals(BotRecruitManager.RecruitAnswer.DECLINED, BotRecruitManager.rollPartyAsk(nemesis, player, 1.0, false));
        assertEquals(BotRecruitManager.RecruitAnswer.ACCEPTED, BotRecruitManager.rollPartyAsk(friend, player, 1.0, false));
        BotRecruitManager.clearArmedForTest(friend.getId());
    }
}

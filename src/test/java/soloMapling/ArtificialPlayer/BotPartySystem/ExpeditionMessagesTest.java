package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import server.expeditions.Expedition;
import testutil.PropsRestored;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.5 exped-msgs (QA's exped.door ON run, TEST 6 Nov 21:17): the leader was told "A bot is not at the altar and will
 * not join." on a start where every member came - the line was about AqClientAlt2, dismissed before the start, which
 * exped.door had already taken off the member list while the expedition hold still listed it. And the lines broke the
 * name rule: a player reads "regulars", never "bots".
 */
@ExtendWith(PropsRestored.class)
class ExpeditionMessagesTest {

    private static final int GONE = 1_000_000_106;
    private static final int LEFT = 1_000_553_063;

    @AfterEach
    void release() {
        ExpeditionHold.release(exped);
    }

    private final Expedition exped = mock(Expedition.class);

    private Character setUp(Map<Integer, String> members) {
        System.setProperty(ExpeditionDoor.PROPERTY, "true");
        Character leader = mock(Character.class);
        server.maps.MapleMap altar = mock(server.maps.MapleMap.class);
        net.server.world.World world = mock(net.server.world.World.class);
        net.server.PlayerStorage storage = mock(net.server.PlayerStorage.class);
        when(altar.getWorldServer()).thenReturn(world);
        when(altar.getId()).thenReturn(240_050_400);
        when(world.getPlayerStorage()).thenReturn(storage);
        when(exped.getLeader()).thenReturn(leader);
        when(exped.getRecruitingMap()).thenReturn(altar);
        when(exped.getMembers()).thenReturn(members);
        return leader;
    }

    @Test
    void aMemberThatAlreadyLeftTheListIsNotReportedAsLeftBehind() {
        Character leader = setUp(Map.of(46, "AqClient"));
        ExpeditionHold.hold(GONE, exped); // still held, no longer a member: the dismissed alt
        ExpeditionBots.warnNotAtTheAltar(exped);
        verify(leader, never()).dropMessage(anyInt(), anyString());
    }

    @Test
    void aMemberLeftBehindIsNamedInPlainWords() {
        Character leader = setUp(Map.of(46, "AqClient", LEFT, "GokuBucc"));
        ExpeditionHold.hold(LEFT, exped); // a member, and not standing at the altar
        ExpeditionBots.warnNotAtTheAltar(exped);
        verify(leader).dropMessage(6, "[Expedition] GokuBucc isn't at the altar and won't join.");
    }

    @Test
    void thePlayerVisibleLinesSayRegularsNotBots() {
        soloMapling.mechanics.MechanicsModel.Content ht = soloMapling.mechanics.MechanicsRuns.contentFor("HorntailBattle");
        String one = soloMapling.mechanics.MechanicsRuns.fightLine(1, ht);
        String four = soloMapling.mechanics.MechanicsRuns.fightLine(4, ht);
        assertEquals("[Expedition] 4 regulars are running this fight with you (Horntail).", four);
        assertTrue(one.contains("1 regular is"), one);
        when(exped.getMembers()).thenReturn(Map.of(46, "AqClient"));
        when(exped.getMinSize()).thenReturn(6);
        when(exped.getType()).thenReturn(server.expeditions.ExpeditionType.CHAOS_ZAKUM);
        String recruiter = ExpeditionBots.shortfall(null, exped);
        assertTrue(recruiter.contains("regulars and alts join you"), recruiter);
        for (String line : new String[]{one, four, ExpeditionBots.notAtTheAltarLine("x"), recruiter,
                ExpeditionBots.CHAOS_QUOTA_TEXT,
                ExpeditionBots.describe("owner", server.expeditions.ExpeditionType.CHAOS_ZAKUM, 120),
                ExpeditionBots.describe("quota", server.expeditions.ExpeditionType.CHAOS_ZAKUM, 120)}) {
            assertFalse(line.toLowerCase().matches(".*\\bbots?\\b.*"), line);
        }
    }
}

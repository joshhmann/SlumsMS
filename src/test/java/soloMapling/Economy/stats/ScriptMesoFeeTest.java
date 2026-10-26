package soloMapling.Economy.stats;

import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;
import server.quest.actions.MesoAction;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Owner 2026-10-26: mesos an NPC or quest script takes (taxis, Spinel, job-advance fees) leave the game: an NPC fee
 * (sink, key npc_fee), not a quest reward. A script's gain stays a quest reward (source).
 */
class ScriptMesoFeeTest {

    @BeforeEach
    @AfterEach
    void clean() {
        EconomyCounters.resetForTest();
    }

    /** A character whose purse moves like the real one (a loss is capped at what it holds). */
    private static Character purse(int start) {
        AtomicInteger meso = new AtomicInteger(start);
        Character chr = mock(Character.class);
        when(chr.getMeso()).thenAnswer(i -> meso.get());
        doAnswer(i -> {
            int g = i.getArgument(0);
            meso.set((int) Math.max(0, (long) meso.get() + g));
            return null;
        }).when(chr).gainMeso(anyInt());
        doAnswer(i -> {
            int g = i.getArgument(0);
            meso.set((int) Math.max(0, (long) meso.get() + g));
            return null;
        }).when(chr).gainMeso(anyInt(), anyBoolean());
        doAnswer(i -> {
            int g = i.getArgument(0);
            meso.set((int) Math.max(0, (long) meso.get() + g));
            return null;
        }).when(chr).gainMeso(anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
        return chr;
    }

    private static NPCConversationManager cm(Character chr) {
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(chr);
        return new NPCConversationManager(c, 1002000, "taxi");
    }

    @Test
    void aScriptFeeIsAnNpcFeeSinkNotAReward() {
        NPCConversationManager cm = cm(purse(10_000));
        cm.gainMeso(-1_200);           // a taxi
        cm.gainMeso(-800, false);      // Spinel
        var snap = EconomyCounters.peek();
        assertEquals(2_000, snap.npcFeeMesos());
        assertEquals(0, snap.questRewardMesos());
        assertEquals(2_000, snap.totalSinks());
    }

    @Test
    void aScriptGainIsStillAReward() {
        NPCConversationManager cm = cm(purse(0));
        cm.gainMeso(3_000);
        assertEquals(3_000, EconomyCounters.peek().questRewardMesos());
        assertEquals(0, EconomyCounters.peek().npcFeeMesos());
    }

    @Test
    void aFeeCountsOnlyWhatWasTaken() {
        cm(purse(500)).gainMeso(-2_000);
        assertEquals(500, EconomyCounters.peek().npcFeeMesos());
    }

    @Test
    void aQuestThatTakesMesosIsAnNpcFee() {
        MesoAction.runAction(purse(5_000), -1_000);
        assertEquals(1_000, EconomyCounters.peek().npcFeeMesos());
        assertEquals(0, EconomyCounters.peek().questRewardMesos());
    }
}

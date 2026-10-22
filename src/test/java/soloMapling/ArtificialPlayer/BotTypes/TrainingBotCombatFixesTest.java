package soloMapling.ArtificialPlayer.BotTypes;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

/** 1.5.0 rc3 pre-release review fixes in TrainingBot: KS-manners map moves, deaths mid-break, channel moves. */
class TrainingBotCombatFixesTest {

    private static final int ID = 1_000_777_031;

    private static Character newBot() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(ID);
        chr.setName("BreakBot");
        chr.setLevel(30);
        chr.setMap(org.mockito.Mockito.mock(server.maps.MapleMap.class));
        Character s = spy(chr);
        doNothing().when(s).respawn(anyInt());
        return s;
    }

    private static Object get(Object o, Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void set(Object o, Class<?> c, String name, Object v) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    @SuppressWarnings("unchecked")
    private static Set<TrainingBot> grinders() throws Exception {
        return (Set<TrainingBot>) get(null, TrainingBot.class, "ACTIVE_GRINDERS");
    }

    // Review P2: a player's platform used to strand a bot once its 2 crowd hops were spent.
    @Test
    void aMapWhereOnlyAPlayersPlatformsAreLeftIsAlwaysLeft() {
        assertEquals(TrainingBot.CrowdBail.HOP, TrainingBot.crowdBail(false, true, 2, 0), "past the crowd hop cap");
        assertEquals(TrainingBot.CrowdBail.HOP, TrainingBot.crowdBail(true, true, 99, 3));
        assertEquals(TrainingBot.CrowdBail.TOWN,
                TrainingBot.crowdBail(false, true, 0, TrainingBot.MAX_YIELD_HOPS_PER_EPISODE), "else town");
        assertEquals(TrainingBot.CrowdBail.HOP, TrainingBot.crowdBail(true, false, 1, 0));
        assertEquals(TrainingBot.CrowdBail.STAY, TrainingBot.crowdBail(true, false, 2, 0), "plain crowding settles");
        assertEquals(TrainingBot.CrowdBail.STAY, TrainingBot.crowdBail(false, false, 0, 0));
    }

    private static TrainingBot onBreak(Character chr, TrainingBot.Phase phase) throws Exception {
        TrainingBot train = new TrainingBot(chr);
        set(train, TrainingBot.class, "phase", phase);
        grinders().add(train);
        Object breaks = get(train, TrainingBot.class, "breaks");
        set(breaks, GrindBreakRoutine.class, "signSet", true);
        set(breaks, GrindBreakRoutine.class, "resumeRemainingMs", 600_000L);
        chr.setChalkboard("brb");
        return train;
    }

    @SuppressWarnings("unchecked")
    private static void lieDeadReadyToRespawn(int id) throws Exception {
        Map<Integer, Long> lying = (Map<Integer, Long>) get(null, BotDeaths.class, "lyingUntil");
        lying.put(id, 0L);
    }

    // Review P2: handleDeath only left the grind in GRIND; a death on a break kept the sign, the grinder entry and
    // the saved remainder (the bot "resumed" the old session after town).
    @Test
    void aDeathOnAGrindBreakClearsTheBreak() throws Exception {
        for (TrainingBot.Phase phase : new TrainingBot.Phase[]{TrainingBot.Phase.BREAK_TRAVEL,
                TrainingBot.Phase.BREAK_REST}) {
            Character chr = newBot();
            TrainingBot train = onBreak(chr, phase);
            try (MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
                lieDeadReadyToRespawn(ID);
                Method handle = TrainingBot.class.getDeclaredMethod("handleDeath", Character.class);
                handle.setAccessible(true);
                assertTrue((Boolean) handle.invoke(train, chr));
                Object breaks = get(train, TrainingBot.class, "breaks");
                assertNull(chr.getChalkboard(), phase + ": sign down");
                assertFalse((Boolean) get(breaks, GrindBreakRoutine.class, "signSet"));
                assertEquals(0L, get(breaks, GrindBreakRoutine.class, "resumeRemainingMs"), phase + ": no remainder");
                assertFalse(grinders().contains(train), phase + ": not an active grinder");
                movement.verify(() -> GCMovement.setGrinding(chr, false));
                movement.verify(() -> GCMovement.clearIdle(chr), org.mockito.Mockito.atLeastOnce());
                movement.verify(() -> GCMovement.setRestHold(chr, false), org.mockito.Mockito.atLeastOnce());
                assertEquals(TrainingBot.Phase.IN_TOWN, get(train, TrainingBot.class, "phase"));
            } finally {
                grinders().remove(train);
                BotDeaths.clearBot(ID);
            }
        }
    }

    // Review P2: a couple / guild channel move took a bot mid-grind; it now leaves the grind (or break) cleanly.
    @Test
    void aChannelMoveLeavesTheGrindCleanly() throws Exception {
        Character chr = newBot();
        TrainingBot train = onBreak(chr, TrainingBot.Phase.BREAK_REST);
        try (MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            train.leaveForChannelMove();
            assertFalse(grinders().contains(train));
            assertNull(chr.getChalkboard());
            assertEquals(0L, get(get(train, TrainingBot.class, "breaks"), GrindBreakRoutine.class,
                    "resumeRemainingMs"));
            movement.verify(() -> GCMovement.setGrinding(chr, false));
            assertEquals(TrainingBot.Phase.DECIDE, get(train, TrainingBot.class, "phase"));
        } finally {
            grinders().remove(train);
        }
    }
}

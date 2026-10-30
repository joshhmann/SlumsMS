package soloMapling.worldevents;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockingDetails;

/**
 * 1.6.2 owner report 2026-10-30: "during the events the birthday cake drops scrolls but I noticed no one picks up the
 * items". The cake's drops land after the event is over, and the release sent every called bot off the map on its
 * next tick (TEST ev-1 11:25:45: WEVENT END, then the bots picked GRIND elsewhere and HANG_OUT within a second, and
 * no loot at all). Now the called bots get a loot window: they wait out the drops' 15 s ownership and sweep what is
 * left with their normal grind loot, then go.
 */
class EventLootScrambleTest {

    private static final int HENESYS = 100000000;
    private static final int BASE = BotStore.BOT_ID_BASE + 871_000;
    private static final long END = 50_000_000L;
    private final List<Integer> added = new ArrayList<>();

    @BeforeEach
    void clean() {
        WorldEventSteer.clearForTest();
        System.clearProperty("solomapling.worldEvents.lootAftermath");
    }

    @AfterEach
    void after() {
        added.forEach(CharacterStorage::removeActiveBot);
        WorldEventSteer.clearForTest();
        System.clearProperty("solomapling.worldEvents.lootAftermath");
    }

    // ── who stays, and how long ──────────────────────────────────────────────────────────────────

    @Test
    void aBotWaitsOutTheDropsOwnershipEvenWithNothingItMayTakeYet() {
        long until = END + 45_000;
        assertTrue(EventLootScramble.stays(END + 1_000, END, until, false), "the pile is still owned: it waits");
        assertTrue(EventLootScramble.stays(END + EventLootScramble.OWNER_MS + 1_000, END, until, false),
                "a moment to notice the pile is free");
    }

    @Test
    void itStaysWhileThereIsLootItCouldTakeAndGoesWhenThePileIsGone() {
        long until = END + 45_000;
        long later = END + EventLootScramble.OWNER_MS + EventLootScramble.GRACE_MS + 1;
        assertTrue(EventLootScramble.stays(later, END, until, true));
        assertFalse(EventLootScramble.stays(later, END, until, false), "nothing left for it: off it goes");
    }

    @Test
    void itsPatienceEndsTheScrambleEvenWithLootLeft() {
        long until = END + 45_000;
        assertFalse(EventLootScramble.stays(until, END, until, true), "leftovers expire on the floor as usual");
        assertFalse(EventLootScramble.stays(END + 1, END, END, true), "a bot that does not bother never stays");
    }

    @Test
    void mostBotsStayLongEnoughToOutlastTheOwnershipAndSomeDoNotBother() {
        Random rng = new Random(7);
        int skip = 0;
        for (int i = 0; i < 10_000; i++) {
            long until = EventLootScramble.stayUntil(END, rng);
            if (until == END) {
                skip++;
                continue;
            }
            assertTrue(until - END >= EventLootScramble.STAY_MIN_MS && until - END <= EventLootScramble.STAY_MAX_MS);
            assertTrue(until - END > EventLootScramble.OWNER_MS + EventLootScramble.GRACE_MS,
                    "every stayer outlasts the ownership window");
        }
        assertTrue(skip > 1_000 && skip < 2_000, "about 15% go right away: " + skip);
    }

    @Test
    void onlyTheFightsLeaveAPile() {
        assertTrue(EventLootScramble.fights(EventTemplate.Kind.GIANT_CAKE));
        assertTrue(EventLootScramble.fights(EventTemplate.Kind.INVASION));
        assertTrue(EventLootScramble.fights(EventTemplate.Kind.BOSS_SIGHTING));
        assertFalse(EventLootScramble.fights(EventTemplate.Kind.HOT_SPOT));
        assertFalse(EventLootScramble.fights(EventTemplate.Kind.TREASURE_HUNT));
        assertFalse(EventLootScramble.fights(EventTemplate.Kind.WANDERING_NPC));
    }

    // ── the release: fail-before (every bot got eventOver(map) and left at once) ─────────────────

    @Test
    void whenTheCakeFallsTheCalledBotsGetALootWindow() {
        ActiveEvent e = event(EventTemplate.Kind.GIANT_CAKE, 1);
        List<TrainingBot> bots = joined(e, 40);
        WorldEventReactions.release(e);
        int stay = 0;
        for (TrainingBot tb : bots) {
            long until = lootUntil(tb, e);
            if (until > END) {
                stay++;
                assertTrue(until > END + EventLootScramble.OWNER_MS, "stays past the drops' ownership");
            }
        }
        assertTrue(stay >= 25, "most of the crowd stays for the pile: " + stay + "/40");
    }

    @Test
    void theSwitchOffOrANonFightEventsSendsThemOffAtOnce() {
        System.setProperty("solomapling.worldEvents.lootAftermath", "false");
        ActiveEvent cake = event(EventTemplate.Kind.GIANT_CAKE, 2);
        List<TrainingBot> cakeBots = joined(cake, 10);
        WorldEventReactions.release(cake);
        for (TrainingBot tb : cakeBots) {
            assertEquals(END, lootUntil(tb, cake), "switch off: the old behaviour");
        }
        System.clearProperty("solomapling.worldEvents.lootAftermath");
        ActiveEvent hot = event(EventTemplate.Kind.HOT_SPOT, 3);
        List<TrainingBot> hotBots = joined(hot, 10);
        WorldEventReactions.release(hot);
        for (TrainingBot tb : hotBots) {
            assertEquals(END, lootUntil(tb, hot), "no fight, no pile");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static ActiveEvent event(EventTemplate.Kind kind, int id) {
        EventTemplate t = new EventTemplate("t" + id, kind, "Test " + kind, 1, 30, 600, 1, 200, 40,
                List.of(EventTemplate.Spot.map(HENESYS)), List.of(), 9400606, 0, 1, List.of(), true);
        ActiveEvent e = new ActiveEvent(id, t, t.spots().get(0), 1, END - 600_000, "test", 1, 200);
        e.finished = true;
        e.endedAt = END;
        return e;
    }

    private List<TrainingBot> joined(ActiveEvent e, int n) {
        List<TrainingBot> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int id = BASE + e.id * 100 + i;
            TrainingBot tb = mock(TrainingBot.class);
            Character chr = mock(Character.class);
            when(chr.getId()).thenReturn(id);
            when(chr.getMapId()).thenReturn(HENESYS);
            when(tb.getChr()).thenReturn(chr);
            CharacterStorage.addActiveBot(id, tb);
            added.add(id);
            e.joinedBots.add(id);
            out.add(tb);
        }
        return out;
    }

    /** The loot deadline release handed this bot (exactly one eventOver call, for the event's map and end). */
    private static long lootUntil(TrainingBot tb, ActiveEvent e) {
        verify(tb).eventOver(eq(e.mapId()), eq(END), anyLong());
        return mockingDetails(tb).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("eventOver") && inv.getArguments().length == 3)
                .mapToLong(inv -> (Long) inv.getArguments()[2]).findFirst().orElseThrow();
    }
}

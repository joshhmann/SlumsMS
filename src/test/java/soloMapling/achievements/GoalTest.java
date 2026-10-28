package soloMapling.achievements;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GoalTest {
    static Goal g(Trigger t, int target, List<Integer> mobs, List<String> scripts, List<Integer> maps) {
        return new Goal("g", "G", "do it", 1142000, t, target, mobs, scripts, maps);
    }

    @Test
    void countsAddAndOtherEventsAreIgnored() {
        Goal fish = g(Trigger.FISH, 3, List.of(), List.of(), List.of());
        int p = 0;
        p = fish.next(p, Event.of(Trigger.FISH));
        p = fish.next(p, Event.of(Trigger.QUEST));
        p = fish.next(p, Event.of(Trigger.FISH));
        assertEquals(2, p);
        assertFalse(fish.done(p));
        assertTrue(fish.done(fish.next(p, Event.of(Trigger.FISH))));
        assertEquals("2/3", fish.progressText(2));
    }

    @Test
    void tiersAndLevelsKeepTheHighestAndNeverGoDown() {
        Goal job2 = g(Trigger.JOB, 2, List.of(), List.of(), List.of());
        assertEquals(1, job2.next(0, new Event(Trigger.JOB, 1)));
        assertEquals(3, job2.next(1, new Event(Trigger.JOB, 3)));
        assertEquals(3, job2.next(3, new Event(Trigger.JOB, 0)), "a rebirth to beginner loses nothing");
        assertTrue(job2.done(3));
        assertFalse(job2.keepsProgress(), "tiers are not written until done");
    }

    @Test
    void onlyTheNamedMobAndScriptCount() {
        Goal mano = g(Trigger.MOB_KILL, 1, List.of(2220000), List.of(), List.of());
        assertEquals(0, mano.next(0, new Event(Trigger.MOB_KILL, 100100)));
        assertEquals(1, mano.next(0, new Event(Trigger.MOB_KILL, 2220000)));
        assertTrue(mano.concerns(new Event(Trigger.MOB_KILL, 2220000)));
        assertFalse(mano.concerns(new Event(Trigger.MOB_KILL, 100100)));
        Goal kpq = g(Trigger.CLEAR, 1, List.of(), List.of("kerningpq"), List.of());
        assertEquals(1, kpq.next(0, new Event(Trigger.CLEAR, 1, "KerningPQ")));
        assertEquals(1, kpq.next(0, new Event(Trigger.CLEAR, 1, "KerningPQ_3")));
        assertEquals(0, kpq.next(0, new Event(Trigger.CLEAR, 1, "LudiPQ")));
        assertEquals(0, kpq.next(0, new Event(Trigger.CLEAR, 1, null)));
    }

    @Test
    void aVisitGoalNeedsEveryMapOnce() {
        Goal tour = g(Trigger.VISIT, 1, List.of(), List.of(), List.of(10, 20, 30));
        int p = 0;
        p = tour.next(p, new Event(Trigger.VISIT, 10));
        p = tour.next(p, new Event(Trigger.VISIT, 10));
        p = tour.next(p, new Event(Trigger.VISIT, 99));
        assertEquals(1, p);
        assertEquals("1/3 places", tour.progressText(p));
        p = tour.next(p, new Event(Trigger.VISIT, 30));
        assertFalse(tour.done(p));
        p = tour.next(p, new Event(Trigger.VISIT, 20));
        assertTrue(tour.done(p));
    }

    @Test
    void jobTiersFromJobIds() {
        assertEquals(0, Achievements.jobTier(0));
        assertEquals(0, Achievements.jobTier(1000));
        assertEquals(0, Achievements.jobTier(2001), "Evan's beginner");
        assertEquals(1, Achievements.jobTier(100));
        assertEquals(2, Achievements.jobTier(110));
        assertEquals(3, Achievements.jobTier(111));
        assertEquals(4, Achievements.jobTier(112));
        assertEquals(4, Achievements.jobTier(212));
        assertEquals(1, Achievements.jobTier(1100), "Dawn Warrior");
        assertEquals(1, Achievements.jobTier(2100), "Aran");
        assertEquals(4, Achievements.jobTier(2218), "Evan's last stage");
    }
}

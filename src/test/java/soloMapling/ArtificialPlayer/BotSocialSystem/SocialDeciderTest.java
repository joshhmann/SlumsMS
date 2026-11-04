package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Emotion;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Need;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 181 stage 4: the social decision tick: who decides, from what, how often, and what the switch does. */
class SocialDeciderTest extends SocialActTestBase {

    private void pair() {
        world.put(ALICE, "Alice", 0, 0);
        world.put(BOB, "Bob", 200, 0);
        mem(ALICE);
        mem(BOB);
        SocialTestKit.befriend(ALICE, BOB, false, now());
        SocialTestKit.befriend(BOB, ALICE, false, now());
        mem(ALICE).mind(m -> { m.feel(Emotion.LONELINESS, 0.8, now()); m.satisfy(Need.COMPANIONSHIP, -1, now()); return null; });
        mem(BOB).mind(m -> { m.feel(Emotion.LONELINESS, 0.8, now()); m.satisfy(Need.COMPANIONSHIP, -1, now()); return null; });
    }

    @Test
    void aLonelyBotWithAFriendNearbyActsOnItThroughTheRegistry() {
        pair();
        int acted = SocialDecider.onMap(new int[]{ALICE, BOB}, now());
        assertTrue(acted >= 1, "someone did something: " + SocialMetrics.actCounters());
        long landed = SocialMetrics.actCounters().entrySet().stream().filter(e -> e.getKey().endsWith(".ok"))
                .mapToLong(java.util.Map.Entry::getValue).sum();
        assertTrue(landed >= 1);
        assertEquals(2L, SocialMetrics.actCounters().get("decision.decisions"), "both were due");
    }

    @Test
    void aBotDecidesOncePerPaceNotEveryTick() {
        pair();
        SocialDecider.onMap(new int[]{ALICE, BOB}, now());
        long first = SocialMetrics.actCounters().get("decision.decisions");
        for (int i = 0; i < 10; i++) {
            tick(1_000);
            SocialDecider.onMap(new int[]{ALICE, BOB}, now());
        }
        assertEquals(first, (long) SocialMetrics.actCounters().get("decision.decisions"), "not due again for 45 s");
        tick(SocialDecider.PACE_MAX_MS * 2);
        SocialDecider.onMap(new int[]{ALICE, BOB}, now());
        assertTrue(SocialMetrics.actCounters().get("decision.decisions") > first, "due again after its pace");
    }

    @Test
    void atMostTwoBotsDecidePerMapPerTick() {
        pair();
        int c = SocialTestKit.BASE + 3, d = SocialTestKit.BASE + 4, e = SocialTestKit.BASE + 5;
        for (int id : new int[]{c, d, e}) {
            world.put(id, "Bot" + id, 300, 0);
            mem(id);
        }
        SocialDecider.onMap(new int[]{ALICE, BOB, c, d, e}, now());
        assertEquals(SocialDecider.PER_MAP, (long) SocialMetrics.actCounters().get("decision.decisions"));
    }

    @Test
    void busyBotsAndBotsWithNoMindAndStrangersAreSkippedNotChosen() {
        world.put(ALICE, "Alice", 0, 0);
        world.put(BOB, "Bob", 200, 0);
        // no memory loaded at all
        SocialDecider.onMap(new int[]{ALICE, BOB}, now());
        assertEquals(2L, (long) SocialMetrics.actCounters().get("decision.skipped-no-mind"));
        SocialDecider.resetForTest();
        mem(ALICE);
        mem(BOB);
        world.busy.add(ALICE);
        SocialDecider.onMap(new int[]{ALICE, BOB}, now());
        assertEquals(1L, (long) SocialMetrics.actCounters().get("decision.skipped-busy"));
        // Bob is free but knows nobody: nobody to act on
        assertEquals(1L, (long) SocialMetrics.actCounters().get("decision.idle-nobody-known"));
        assertTrue(world.said.isEmpty() && flows.calls.isEmpty());
    }

    @Test
    void candidatesAreNearestKnownBotsNeverPlayersOrStrangers() {
        pair();
        int stranger = SocialTestKit.BASE + 9;
        world.put(stranger, "Stranger", 100, 0);
        world.put(7, "RealPlayer", 50, 0);
        List<Integer> candidates = SocialDecider.candidates(ALICE, world, now());
        assertEquals(List.of(BOB), candidates);
        // an open invitation from a stranger makes it a candidate (it has to be answered)
        SocialActions.openInvite(stranger, ALICE, "a duo party", 100000000, "payload", now());
        assertEquals(List.of(BOB, stranger), SocialDecider.candidates(ALICE, world, now()).stream().sorted(
                (x, y) -> Integer.compare(x == BOB ? 0 : 1, y == BOB ? 0 : 1)).toList());
    }

    @Test
    void theSwitchStopsTheDeciderCompletely() {
        pair();
        System.setProperty(SocialActions.SWITCH, "false");
        assertEquals(0, SocialDecider.onMap(new int[]{ALICE, BOB}, now()));
        SocialDecider.upkeep(now());
        assertTrue(SocialMetrics.actCounters().isEmpty());
        assertTrue(world.said.isEmpty() && flows.calls.isEmpty() && world.walks.isEmpty());
        assertEquals(0.0, SocialDecider.socialPull(ALICE, now()), 0.0);
    }

    @Test
    void theMindAddsALittleToTheLifeBrainsSocialNeedAndNeverMoreThanAThird() {
        pair();
        double lonely = SocialDecider.socialPull(ALICE, now());
        assertTrue(lonely > 0.1 && lonely <= 0.4, "pull of a lonely bot: " + lonely);
        mem(ALICE).mind(m -> { m.satisfy(Need.COMPANIONSHIP, 1, now()); m.feel(Emotion.LONELINESS, -1, now()); return null; });
        assertTrue(SocialDecider.socialPull(ALICE, now()) < lonely, "company met: a smaller pull");
        assertEquals(0.0, SocialDecider.socialPull(SocialTestKit.BASE + 800, now()), 0.0, "no memory loaded: none");
    }

    @Test
    void talkInputsReadTheMindForTheTalkDecisions() {
        pair();
        mem(BOB).mind(m -> { m.feel(Emotion.ANGER, 0.8, now()); return null; });
        SocialDecider.TalkInputs in = SocialDecider.talkInputs(mem(BOB), now());
        assertTrue(in.anger() > 0.4 && in.mood() < 0.2);
        assertTrue(in.companionship() >= 0 && in.companionship() <= 1);
        assertFalse(in.sociability() < 0);
    }

    @Test
    void unwatchedBotsDecideLessOftenAndDormantOnesNever() {
        pair();
        world.pace = 1.0;
        SocialSim.useForTest(clock::get, 5L); // the same draw each time, so only the factor differs
        long normal = SocialDecider.pace(ALICE, now());
        world.pace = 4.0;
        SocialSim.useForTest(clock::get, 5L);
        long background = SocialDecider.pace(ALICE, now());
        SocialSim.useForTest(clock::get, 5L);
        assertTrue(background > 3 * normal, "the social LOD stretches a background bot's pace: " + normal + " -> " + background);
        world.pace = Double.POSITIVE_INFINITY;
        assertTrue(SocialDecider.pace(ALICE, now()) > 24L * 3_600_000L * 365, "a dormant bot is never due again");
    }
}

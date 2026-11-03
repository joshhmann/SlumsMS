package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotRunOutcome;
import soloMapling.ArtificialPlayer.BotRunOutcome.Pending;
import soloMapling.ArtificialPlayer.BotRunOutcome.Pool;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Tier;

/**
 * 1.7.0 run chatter, the run's side: which bots of a run that ended say something, and what. The speaker rule is the
 * leader plus one other bot picked with the random source it is given, so every assertion here is deterministic.
 */
class RunChatterTest {

    private static final long NOW = 9_000_000L;
    private static final int LEADER = 11;

    @AfterEach
    void switchBackToDefault() {
        System.clearProperty(Mechanics.CHATTER_FLAG);
    }

    private static Content row(String eventScript) {
        Content content = MechanicsRuns.contentFor(eventScript);
        assertNotNull(content, eventScript + " must be a listed content row");
        return content;
    }

    private static Content kpq() {
        return KpqStageDataTest.kpq();
    }

    private static Content zakum() {
        return row("ZakumBattle");
    }

    /** Eight bots, the first of them the leader (a bot leader, as in a bot-only lobby run). */
    private static List<RunMember> botParty() {
        List<RunMember> out = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            out.add(new RunMember(LEADER + i, i == 0, true, MechanicsPersona.PERFECT));
        }
        return out;
    }

    /** A real leader and six bots: the shape of a player's expedition. */
    private static List<RunMember> humanLedParty() {
        List<RunMember> out = new ArrayList<>();
        out.add(new RunMember(1, true, false, MechanicsPersona.PERFECT));
        for (int i = 0; i < 6; i++) {
            out.add(new RunMember(LEADER + i, false, true, MechanicsPersona.PERFECT));
        }
        return out;
    }

    private static RunState won(Content content, List<RunMember> members) {
        RunState run = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0).start(content, Tier.NORMAL, members);
        run.markCleared(60_000);
        run.end("cleared");
        return run;
    }

    private static RunState lost(Content content, List<RunMember> members) {
        RunState run = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0).start(content, Tier.NORMAL, members);
        run.fail("time budget of 30 min ran out on stage 2");
        return run;
    }

    private static RunState disbanded(Content content, List<RunMember> members) {
        RunState run = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0).start(content, Tier.NORMAL, members);
        run.end("the leader left (no longer in the stage)");
        return run;
    }

    /** What the run was, as MechanicsRuns writes it when the run ends (members as they are: bots and players). */
    private static RunOutcome outcomeOf(RunState run) {
        List<RunOutcome.Participant> participants = new ArrayList<>();
        for (RunMember m : run.members()) {
            participants.add(new RunOutcome.Participant(m.characterId(),
                    m.bot() ? RunOutcome.ParticipantKind.BOT : RunOutcome.ParticipantKind.PLAYER, m.leader()));
        }
        return RunOutcome.of("test-run", "Kerning0 w1 ch 1", run, RealRunPool.Origin.KPQ_LOBBY, RunOutcome.Mode.REAL,
                NOW - 60_000, NOW, participants);
    }

    /** The chatter reads the outcome and nothing else: this is the old call, through the record. */
    private static Map<Integer, Pending> plan(RunState run, java.util.Collection<Integer> bots, long now, Random rng) {
        return RunChatter.plan(outcomeOf(run), bots, now, rng);
    }

    private static List<Integer> ids(List<RunMember> members) {
        return members.stream().filter(RunMember::bot).map(RunMember::characterId).toList();
    }

    // ---- what is said -----------------------------------------------------------------------------

    @Test
    void aWonRunRecordsAWinLineThatNamesThePartyQuest() {
        List<RunMember> party = botParty();
        Map<Integer, Pending> plan = plan(won(kpq(), party), ids(party), NOW, new Random(3));

        assertEquals(2, plan.size());
        Set<String> wins = new HashSet<>(BotRunOutcome.lines(Pool.WIN_PQ, "Kerning PQ", 99, new Random(0)));
        for (Pending p : plan.values()) {
            assertTrue(wins.contains(p.line()), "a win line for Kerning PQ: " + p.line());
            assertEquals(NOW + BotRunOutcome.TTL_MS, p.expiresAtMs());
        }
        assertTrue(plan.values().stream().anyMatch(p -> p.line().contains("Kerning PQ")), plan.toString());
    }

    @Test
    void aLostRunRecordsALossLineThatNamesTheBoss() {
        List<RunMember> party = humanLedParty();
        Map<Integer, Pending> plan = plan(lost(zakum(), party), ids(party), NOW, new Random(3));

        assertEquals(2, plan.size());
        Set<String> losses = new HashSet<>(BotRunOutcome.lines(Pool.LOSS_BOSS, "Zakum", 99, new Random(0)));
        for (Pending p : plan.values()) {
            assertTrue(losses.contains(p.line()), "a loss line for Zakum: " + p.line());
        }
        assertTrue(plan.values().stream().anyMatch(p -> p.line().contains("Zakum")), plan.toString());
    }

    @Test
    void aRunTheHumanDisbandedOrThatNeverStartedRecordsNothing() {
        List<RunMember> party = botParty();
        assertTrue(plan(disbanded(kpq(), party), ids(party), NOW, new Random(3)).isEmpty());
        assertTrue(plan(disbanded(zakum(), humanLedParty()), ids(humanLedParty()), NOW, new Random(3)).isEmpty());

        RunState refused = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0)
                .start(kpq(), Tier.NORMAL, List.of(new RunMember(1, true, false, MechanicsPersona.PERFECT)));
        assertTrue(refused.ended());
        assertTrue(plan(refused, List.of(), NOW, new Random(3)).isEmpty());

        RunState expired = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0)
                .start(kpq(), Tier.NORMAL, party);
        new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0).abort(expired, "the event ended");
        assertTrue(plan(expired, ids(party), NOW, new Random(3)).isEmpty(),
                "an instance that expired is not the boss's doing");
    }

    @Test
    void aRunStillGoingRecordsNothing() {
        List<RunMember> party = botParty();
        RunState going = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0).start(kpq(), Tier.NORMAL, party);
        assertTrue(plan(going, ids(party), NOW, new Random(3)).isEmpty());
    }

    // ---- who says it ------------------------------------------------------------------------------

    @Test
    void aBotLeaderAndOneOtherBotSpeakNeverMore() {
        List<RunMember> party = botParty();
        Set<Integer> secondSpeakers = new HashSet<>();
        for (int seed = 0; seed < 300; seed++) {
            List<Integer> who = RunChatter.speakers(ids(party), LEADER, new Random(seed), RunChatter.MAX_SPEAKERS);
            assertEquals(2, who.size(), "seed " + seed);
            assertEquals(LEADER, who.get(0), "the leader is the first voice");
            assertTrue(who.get(1) != LEADER && ids(party).contains(who.get(1)), who.toString());
            secondSpeakers.add(who.get(1));
        }
        assertEquals(7, secondSpeakers.size(), "the other voice is any of the seven others: " + secondSpeakers);
    }

    @Test
    void theSameRandomSourceGivesTheSameSpeakersAndLines() {
        List<RunMember> party = botParty();
        RunState run = won(kpq(), party);
        assertEquals(plan(run, ids(party), NOW, new Random(42)), plan(run, ids(party), NOW, new Random(42)));
        assertEquals(RunChatter.speakers(ids(party), LEADER, new Random(5), 2),
                RunChatter.speakers(new ArrayList<>(ids(party)).reversed(), LEADER, new Random(5), 2),
                "the order the bots are listed in does not matter");
    }

    @Test
    void aHumanLeaderSpeaksForHimselfSoTwoRandomBotsAnswer() {
        List<RunMember> party = humanLedParty();
        Set<Integer> speakers = new HashSet<>();
        for (int seed = 0; seed < 300; seed++) {
            List<Integer> who = RunChatter.speakers(ids(party), 1, new Random(seed), RunChatter.MAX_SPEAKERS);
            assertEquals(2, who.size());
            assertTrue(ids(party).containsAll(who), "only bots: " + who);
            assertEquals(2, new HashSet<>(who).size());
            speakers.addAll(who);
        }
        assertEquals(6, speakers.size(), "any bot can be picked");
    }

    @Test
    void thePartyOfOneSpeaksAndAnEmptyPartyDoesNot() {
        assertEquals(List.of(LEADER), RunChatter.speakers(List.of(LEADER), LEADER, new Random(1), 2));
        assertEquals(List.of(LEADER + 1), RunChatter.speakers(List.of(LEADER + 1), LEADER, new Random(1), 2));
        assertEquals(List.of(), RunChatter.speakers(List.of(), LEADER, new Random(1), 2));
        List<RunMember> party = botParty();
        assertTrue(plan(won(kpq(), party), List.of(), NOW, new Random(1)).isEmpty());
    }

    @Test
    void theTwoSpeakersNeverSayTheSameLine() {
        List<RunMember> party = botParty();
        RunState run = won(kpq(), party);
        for (int seed = 0; seed < 200; seed++) {
            Map<Integer, Pending> plan = plan(run, ids(party), NOW, new Random(seed));
            List<String> lines = plan.values().stream().map(Pending::line).toList();
            assertEquals(2, new HashSet<>(lines).size(), lines.toString());
        }
    }

    // ---- the content's name -----------------------------------------------------------------------

    @Test
    void theContentIsNamedTheWayPlayersSayIt() {
        assertEquals("Kerning PQ", RunChatter.nameFor(kpq()));
        assertEquals("Ludi PQ", RunChatter.nameFor(row("LudiPQ")));
        assertEquals("Orbis PQ", RunChatter.nameFor(row("OrbisPQ")));
        assertEquals("Zakum", RunChatter.nameFor(zakum()));
        assertEquals("Horntail", RunChatter.nameFor(row("HorntailBattle")));
        Map<String, String> seen = new HashMap<>();
        for (Content c : StageData.loadDir(java.nio.file.Path.of(Mechanics.DEFAULT_DIR)).content()) {
            String name = RunChatter.nameFor(c);
            seen.put(c.id(), name);
            assertFalse(name.isBlank(), c.id());
            assertFalse(name.contains("(") || name.contains("Party Quest"), c.id() + " -> " + name);
        }
        assertTrue(seen.size() > 20, "every row gets a name: " + seen);
    }

    @Test
    void bossRowsAreAKillAndEverythingElseAPartyQuest() {
        assertTrue(RunChatter.boss(zakum()));
        assertFalse(RunChatter.boss(kpq()));
    }

    // ---- the switch -------------------------------------------------------------------------------

    @Test
    void theSwitchIsOnByDefaultAndOffRecordsNothingAtAll() {
        List<RunMember> party = botParty();
        assertTrue(Mechanics.chatterEnabled(), "-Dslums.mechanics.chatter defaults on");
        assertEquals("slums.mechanics.chatter", Mechanics.CHATTER_FLAG);
        assertFalse(plan(won(kpq(), party), ids(party), NOW, new Random(1)).isEmpty());

        System.setProperty(Mechanics.CHATTER_FLAG, "false");
        assertFalse(Mechanics.chatterEnabled());
        assertTrue(plan(won(kpq(), party), ids(party), NOW, new Random(1)).isEmpty());
        assertTrue(plan(lost(kpq(), party), ids(party), NOW, new Random(1)).isEmpty());

        System.setProperty(Mechanics.CHATTER_FLAG, "FALSE");
        assertFalse(Mechanics.chatterEnabled(), "any spelling of false");
        System.setProperty(Mechanics.CHATTER_FLAG, "true");
        assertTrue(Mechanics.chatterEnabled());
        System.setProperty(Mechanics.CHATTER_FLAG, "");
        assertTrue(Mechanics.chatterEnabled(), "an empty value is not an off");
    }
}

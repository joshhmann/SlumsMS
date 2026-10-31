package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 1.5.0 bot guild scenarios (gu-1 .. gu-7) against a recording world. */
class GuildScenariosTest {
    @TempDir
    Path dir;

    final FakeQaWorld world = new FakeQaWorld();
    final QaTestSupport.ManualScheduler scheduler = new QaTestSupport.ManualScheduler();
    QaSuite suite;

    @AfterEach
    void reset() {
        QaLog.reset();
        MoveTrace.reset();
    }

    private Character start(String id, int mapId, String... args) {
        suite = QaTestSupport.suite(QaScenarios.all(), world, dir, scheduler);
        Character gm = QaTestSupport.gm(1, "Admin", mapId);
        world.online = gm;
        suite.start(gm, id, args);
        return gm;
    }

    private boolean called(String prefix) {
        return world.calls.stream().anyMatch(c -> c.startsWith(prefix));
    }

    @Test
    void everyGuildScenarioIsRegistered() {
        List<String> ids = QaScenarios.all().stream().map(QaScenario::id).toList();
        for (String id : List.of("gu-1", "gu-2", "gu-3", "gu-4", "gu-5", "gu-6", "gu-7", "gu-8")) {
            assertTrue(ids.contains(id), id);
        }
    }

    @Test
    void gu1StagesAFriendlyOfficerAndLeavesTheJoinedGuildAtStop() {
        start("gu-1", 100000000);
        assertTrue(called("pickguild for"), "a guild whose leader would take the GM (rc6 auto-QA: HOSTILE leader)");
        assertTrue(called("guildbot friend Legendz r2"));
        world.gmGuild = 77; // the GM accepted the invite during the run
        suite.stop(1, "!qa stop");
        assertTrue(world.calls.contains("leave guild"));
        assertEquals(1, world.guildBotsUnstaged.size());
    }

    @Test
    void gu1RefusesAGmInAGuildAndWhenNoBotGuildExists() {
        world.gmGuild = 5;
        assertThrows(QaRefusal.class, () -> start("gu-1", 100000000));
        world.gmGuild = 0;
        world.botGuild = null;
        assertThrows(QaRefusal.class, () -> start("gu-1", 100000000));
    }

    @Test
    void gu2NeedsAGuildStagesFriendsAndANeutralAndJoinAddsAMember() {
        assertThrows(QaRefusal.class, () -> start("gu-2", 100000000));
        world.gmGuild = 9;
        start("gu-2", 100000000, "3");
        assertEquals(3, world.calls.stream().filter(c -> c.startsWith("guildbot friend null")).count());
        assertTrue(called("guildbot neutral null"));
        assertTrue(called("guild sure "), "staged friends take an invite under the soft limit for sure");
        suite.action(world.online, "join", new String[0]);
        assertTrue(called("guildbot friend mine r5"));
        suite.stop(1, "!qa stop");
        assertEquals(5, world.guildBotsUnstaged.size());
        assertTrue(called("guild unsure "), "and real dice again at stop");
    }

    @Test
    void gu3WarpsToHenesysStagesAnOfficerAndRecruits() {
        start("gu-3", 104040000);
        assertTrue(world.calls.contains("warp 100000000"));
        assertTrue(world.calls.contains("guild recruit Legendz"));
        suite.action(world.online, "recruit", new String[0]);
        assertEquals(2, world.calls.stream().filter(c -> c.equals("guild recruit Legendz")).count());
        assertThrows(QaRefusal.class, () -> suite.action(world.online, "hug", new String[0]));
        suite.stop(1, "!qa stop");
    }

    @Test
    void gu4StagesBothRivalsInTheFmAndJabs() {
        start("gu-4", 100000000);
        assertTrue(world.calls.contains("warp 910000000"));
        assertTrue(called("guildbot neutral Legendz"));
        assertTrue(called("guildbot neutral ZakumSlayerz"));
        assertTrue(world.calls.contains("guild jab Legendz"));
        suite.stop(1, "!qa stop");
        assertEquals(2, world.guildBotsUnstaged.size());
    }

    @Test
    void gu5StartsAFastRunAndStopsItAtStop() {
        start("gu-5", 100000000, "zakum");
        assertTrue(world.calls.contains("guild bossrun Legendz zakum"));
        suite.stop(1, "!qa stop");
        assertTrue(world.calls.contains("guild bossstop"));
    }

    /** t-gu-5 (TEST d4f103b0e): the world's readiest guild is used, its offline crew logged in, and out at stop. */
    @Test
    void gu5LogsInTheCrewItNeedsAndLogsThemOutAtStop() {
        world.guildAnswers.put("bossready", List.of("stage|Oldies|1000000041,1000000042"));
        start("gu-5", 100000000);
        assertTrue(world.calls.contains("guild bossready * *"));
        assertTrue(world.calls.contains("guild bossstage 1000000041 1000000042"));
        assertTrue(world.calls.contains("guild bossrun Oldies"), world.calls.toString());
        suite.stop(1, "!qa stop");
        int stop = world.calls.indexOf("guild bossstop");
        int out = world.calls.indexOf("guild bossunstage 1000000041 1000000042");
        assertTrue(stop >= 0 && out > stop, "the run ends, then its staged crew logs out: " + world.calls);
    }

    @Test
    void gu5UsesAReadyGuildAsItIs() {
        world.guildAnswers.put("bossready", List.of("ready|Oldies"));
        start("gu-5", 100000000, "zakum");
        assertTrue(world.calls.contains("guild bossready * zakum"));
        assertTrue(world.calls.contains("guild bossrun Oldies zakum"));
        assertTrue(world.calls.stream().noneMatch(c -> c.startsWith("guild bossstage")));
    }

    @Test
    void gu5RefusesWhenTheRunCannotStart() {
        world.bossRunAnswer = List.of("no boss run: too few members online");
        assertThrows(QaRefusal.class, () -> start("gu-5", 100000000));
    }

    @Test
    void gu6StagesAMemberAndTheRivalStepRunsTheQuitCheck() {
        world.gmGuild = 3;
        start("gu-6", 100000000);
        assertTrue(called("guildbot friend mine r5"));
        suite.action(world.online, "rival", new String[0]);
        assertTrue(called("guild rival "));
        suite.action(world.online, "ignore", new String[0]);
        assertTrue(called("guild ignore "));
        suite.stop(1, "!qa stop");
    }

    @Test
    void gu6OnAMemberThatAlreadyLeftStagesAFreshOneAndRunsTheStepOnIt() {
        // rc6, owner QA 2026-10-23: "run the step again" read as start over, so the ignore check never ran
        world.gmGuild = 3;
        start("gu-6", 100000000);
        world.guildAnswers.put("ignore", List.of("bot 1 is in no guild"));
        suite.action(world.online, "ignore", new String[0]);
        assertEquals(2, world.calls.stream().filter(c -> c.startsWith("guildbot friend mine r5")).count(),
                "a fresh member was staged");
        assertEquals(2, world.calls.stream().filter(c -> c.startsWith("guild ignore ")).count(),
                "and the ignore check ran on it at once");
        suite.stop(1, "!qa stop");
    }

    @Test
    void gu7StagesAFriendANeutralAndARival() {
        start("gu-7", 104040000);
        assertTrue(called("guildbot friend null"));
        assertTrue(called("guildbot neutral null"));
        assertTrue(called("guildbot rival null"));
        suite.stop(1, "!qa stop");
        assertEquals(3, world.guildBotsUnstaged.size());
    }

    @Test
    void gu8JoinsTheGmToABotGuildAndKickAndVoteRunTheKickCheck() {
        start("gu-8", 100000000, "Legendz");
        assertTrue(world.calls.contains("join guild Legendz"));
        assertTrue(called("guildbot friend Legendz r5"));
        suite.action(world.online, "kick", new String[0]);
        assertTrue(world.calls.contains("guild kick Legendz"));
        suite.action(world.online, "vote", new String[0]);
        assertEquals(2, world.calls.stream().filter(c -> c.startsWith("guildbot rival Legendz")).count());
        assertTrue(world.calls.contains("guild kickcheck Legendz"));
        assertThrows(QaRefusal.class, () -> suite.action(world.online, "hug", new String[0]));
        suite.stop(1, "!qa stop");
        assertTrue(world.calls.contains("leave guild"));
        assertTrue(world.calls.contains("guild unkick Legendz"), "the staged rival leader is undone at stop");
        assertEquals(3, world.guildBotsUnstaged.size());
    }

    @Test
    void gu8RefusesWhenTheGmCannotJoin() {
        world.joinWorks = false;
        assertThrows(QaRefusal.class, () -> start("gu-8", 100000000));
    }
}

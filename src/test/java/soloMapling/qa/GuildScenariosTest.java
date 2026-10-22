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
        suite.action(world.online, "join", new String[0]);
        assertTrue(called("guildbot friend mine r5"));
        suite.stop(1, "!qa stop");
        assertEquals(5, world.guildBotsUnstaged.size());
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
        assertEquals(3, world.guildBotsUnstaged.size());
    }

    @Test
    void gu8RefusesWhenTheGmCannotJoin() {
        world.joinWorks = false;
        assertThrows(QaRefusal.class, () -> start("gu-8", 100000000));
    }
}

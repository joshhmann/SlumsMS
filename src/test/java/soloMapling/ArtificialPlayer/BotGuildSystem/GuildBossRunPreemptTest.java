package soloMapling.ArtificialPlayer.BotGuildSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore.GuildRow;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auto-QA on TEST 2026-10-24: t-gw-4 (alliance joint boss run) logged no {@code BOT_GUILD BOSS RUN} line and t-gu-5
 * was skipped with "a boss run is already on (SlumLords)" - a bot guild's run held the one server-wide slot, and the
 * run the QA check staged could not take it. A GM or !qa start now stops the run on first; bot-started runs never
 * preempt each other.
 */
class GuildBossRunPreemptTest {

    @BeforeEach
    void setUp() {
        // no bot is online: a start past the "already on" gate stops at the member check, before any map is touched
        BotGuilds.useForTest(new GuildStore.Memory(), id -> null, List::of);
    }

    @AfterEach
    void tearDown() {
        GuildBossRuns.resetForTest();
        BotGuilds.useForTest(null, null, null);
    }

    private static void runOn(int guildId, String name) {
        GuildBossRuns.setForTest(new GuildBossRuns.Run(guildId, name, GuildRules.boss("pap"), 1,
                List.of(1_000_000_901), 60_000, 120_000, GuildBossRuns.BOT_STARTER, System.currentTimeMillis()));
    }

    private static GuildRow guild(int id, String name) {
        return new GuildRow(id, name, 0, GuildRules.Vibe.CASUAL, 0, 10, 0L);
    }

    @Test
    void onlyAGmOrQaStarterMayPreempt() {
        assertFalse(GuildBossRuns.mayPreempt(GuildBossRuns.BOT_STARTER), "a bot guild's run never takes over");
        assertTrue(GuildBossRuns.mayPreempt("qa"), "!qa");
        assertTrue(GuildBossRuns.mayPreempt("GmJosh"), "!botguild boss / !qa run by a GM");
    }

    @Test
    void aSecondBotRunIsStillRefused() {
        runOn(1, "Legendz");
        assertEquals("a boss run is already on (Legendz)",
                GuildBossRuns.start(guild(2, "SlumLords"), GuildRules.boss("pap"), true, GuildBossRuns.BOT_STARTER, 1),
                "bot-initiated runs still never preempt each other");
        assertTrue(GuildBossRuns.running(), "the run on is untouched");
        assertEquals(1, GuildBossRuns.currentForTest().guildId);
        assertEquals(GuildBossRuns.Phase.GATHER, GuildBossRuns.currentForTest().phase);
    }

    @Test
    void aQaOrGmStartStopsTheRunOnFirst() {
        runOn(1, "Legendz");
        String problem = GuildBossRuns.start(guild(2, "SlumLords"), GuildRules.boss("pap"), true, "GmJosh", 1);
        assertTrue(problem != null && !problem.startsWith("a boss run is already on"),
                "past the gate that refused t-gw-4/t-gu-5, got: " + problem);
        assertTrue(problem.contains("free members online"), "stopped at the (empty) member check: " + problem);
        assertFalse(GuildBossRuns.running(), "the old run was stopped");
        assertEquals(1, GuildBossRuns.currentForTest().guildId, "the stopped one is the old run");
        assertEquals(GuildBossRuns.Phase.DONE, GuildBossRuns.currentForTest().phase);
    }

    @Test
    void aQaStartWithNoRunOnIsNotBlockedByTheGate() {
        assertNull(GuildBossRuns.currentForTest());
        String problem = GuildBossRuns.start(guild(2, "SlumLords"), GuildRules.boss("pap"), true, "qa", 1);
        assertNotNull(problem, "no member online, so it stops at the member check");
        assertFalse(problem.startsWith("a boss run is already on"), problem);
    }
}

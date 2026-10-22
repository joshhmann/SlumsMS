package soloMapling.ArtificialPlayer.BotGuildSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildRules.QuitReason;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildRules.Refusal;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildRules.Vibe;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuildRulesTest {

    static final long DAY = TimeUnit.DAYS.toMillis(1);

    @Test
    void onlyFriendsAcceptAGuildInviteAndGetPickierPastHalfBots() {
        assertEquals(0, GuildRules.inviteAcceptChance(AffinityState.NEUTRAL, 1, 5));
        assertEquals(0, GuildRules.inviteAcceptChance(AffinityState.RIVAL, 1, 5));
        assertEquals(0.95, GuildRules.inviteAcceptChance(AffinityState.FRIEND, 2, 5), 1e-9);
        assertEquals(0.95, GuildRules.inviteAcceptChance(AffinityState.FRIEND, 5, 10), 1e-9); // exactly half
        double sixty = GuildRules.inviteAcceptChance(AffinityState.FRIEND, 6, 10);
        double seventy = GuildRules.inviteAcceptChance(AffinityState.FRIEND, 7, 10);
        assertTrue(sixty < 0.95 && seventy < sixty, sixty + " " + seventy);
        assertEquals(0.95 * 0.05, GuildRules.inviteAcceptChance(AffinityState.FRIEND, 9, 10), 1e-9); // soft: never 0
    }

    @Test
    void theLeaderDecidesAnApplicationByFeelingsAndLevel() {
        assertEquals(Refusal.IN_GUILD, GuildRules.application(AffinityState.FRIEND, 50, 3, 10, Vibe.CASUAL, 5, 30, 0));
        assertEquals(Refusal.FULL, GuildRules.application(AffinityState.FRIEND, 50, 0, 10, Vibe.CASUAL, 30, 30, 0));
        assertEquals(Refusal.HOSTILE, GuildRules.application(AffinityState.RIVAL, 99, 0, 10, Vibe.CASUAL, 5, 30, 0));
        assertNull(GuildRules.application(AffinityState.FRIEND, 1, 0, 60, Vibe.HARDCORE, 5, 30, 0.99)); // friends skip the floor
        assertEquals(Refusal.LEVEL, GuildRules.application(AffinityState.NEUTRAL, 20, 0, 30, Vibe.CASUAL, 5, 30, 0));
        assertEquals(Refusal.LEVEL, GuildRules.application(AffinityState.NEUTRAL, 45, 0, 10, Vibe.HARDCORE, 5, 30, 0));
        assertNull(GuildRules.application(AffinityState.NEUTRAL, 45, 0, 10, Vibe.CASUAL, 5, 30, 0.1));
        assertEquals(Refusal.NOT_NOW, GuildRules.application(AffinityState.NEUTRAL, 45, 0, 10, Vibe.CASUAL, 5, 30, 0.95));
    }

    @Test
    void aBotQuitsOverRivalsOrWeeksOfBeingIgnored() {
        long now = 100 * DAY;
        assertEquals(QuitReason.RIVALS, GuildRules.quit(true, true, 0, 0, now - DAY, now, now));
        assertEquals(QuitReason.RIVALS, GuildRules.quit(true, false, 1, 2, now - DAY, now, now)); // half its players
        assertNull(GuildRules.quit(true, false, 1, 3, now - DAY, now, now)); // one of three: stays
        assertNull(GuildRules.quit(false, false, 2, 2, now - DAY, now, now), "a bot guild never loses a bot to rivals");
        assertNull(GuildRules.quit(false, true, 5, 5, now - DAY, now, now));
        assertEquals(QuitReason.IGNORED, GuildRules.quit(true, false, 0, 0, now - 30 * DAY, now - 22 * DAY, now));
        assertNull(GuildRules.quit(true, false, 0, 0, now - 30 * DAY, now - 3 * DAY, now)); // noticed lately
        assertNull(GuildRules.quit(true, false, 0, 0, now - 5 * DAY, 0, now)); // new member
        assertNull(GuildRules.quit(false, false, 0, 0, now - 90 * DAY, 0, now)); // bot guilds keep their bots
    }

    @Test
    void partyInvitesFriendsYesNeutralSometimesRivalsNever() {
        assertEquals(1, GuildRules.partyAcceptChance(AffinityState.FRIEND, false, false));
        assertEquals(0, GuildRules.partyAcceptChance(AffinityState.RIVAL, true, true));
        assertEquals(0, GuildRules.partyAcceptChance(AffinityState.NEMESIS, false, false));
        assertEquals(0.3, GuildRules.partyAcceptChance(AffinityState.NEUTRAL, false, false), 1e-9);
        assertEquals(0.9, GuildRules.partyAcceptChance(AffinityState.NEUTRAL, true, false), 1e-9);
        assertEquals(1, GuildRules.partyAcceptChance(AffinityState.NEUTRAL, false, true)); // a boss-run crew member
    }

    @Test
    void theHardestBossTheGroupCanTake() {
        assertNull(GuildRules.pickBoss(new int[]{20, 25, 28}, 3));
        assertEquals("gpq", GuildRules.pickBoss(new int[]{35, 40, 45}, 3).key());
        assertEquals("zakum", GuildRules.pickBoss(new int[]{55, 60, 62, 20}, 3).key());
        assertEquals("horntail", GuildRules.pickBoss(new int[]{120, 130, 115, 140}, 3).key());
        assertEquals("zakum", GuildRules.pickBoss(new int[]{120, 60, 55}, 3).key()); // only one can do ht
        assertNotNull(GuildRules.boss("Zakum"));
        assertNull(GuildRules.boss("chaoszakum"));
    }

    @Test
    void mostRunsClearAndAStrongGroupClearsMore() {
        GuildRules.Boss zak = GuildRules.boss("zakum");
        assertTrue(GuildRules.runClears(51, zak, 0.6));
        assertFalse(GuildRules.runClears(51, zak, 0.7));
        assertTrue(GuildRules.runClears(90, zak, 0.85));
        assertFalse(GuildRules.runClears(200, zak, 0.95));
    }

    @Test
    void theSpecsMatchTheOwnersShape() {
        List<GuildPlan.Spec> specs = GuildPlan.SPECS;
        assertEquals(10, specs.size());
        Set<String> names = new HashSet<>();
        for (GuildPlan.Spec s : specs) {
            assertTrue(s.name().matches("[A-Za-z]{3,12}"), s.name()); // the client's guild-name rule
            assertTrue(names.add(s.name().toLowerCase()));
            assertTrue(s.size() <= 40);
            if (s.rival() != null) {
                GuildPlan.Spec r = specs.stream().filter(x -> x.name().equals(s.rival())).findFirst().orElseThrow();
                assertEquals(s.name(), r.rival(), "rivals are pairs");
            }
        }
        assertEquals(4, specs.stream().filter(s -> s.rival() != null).count(), "two rival pairs");
        assertTrue(specs.stream().filter(s -> s.size() >= 30).count() >= 2, "a few big");
        assertEquals(2, specs.stream().filter(s -> s.size() < 10).count(), "a couple of tiny new ones");
        assertEquals(6, specs.stream().filter(s -> s.size() >= 15 && s.size() < 30).count(), "mostly mid-size");
    }

    @Test
    void assignFillsGuildsByLevelBandLeaderFirstAndNoBotTwice() {
        Random rng = new Random(7);
        List<GuildPlan.Candidate> roster = new java.util.ArrayList<>();
        for (int i = 0; i < 600; i++) {
            roster.add(new GuildPlan.Candidate(100 + i, 1 + rng.nextInt(160)));
        }
        List<GuildPlan.Founding> plan = GuildPlan.assign(roster, GuildPlan.SPECS, rng);
        assertEquals(10, plan.size());
        Map<Integer, String> seen = new HashMap<>();
        for (GuildPlan.Founding f : plan) {
            assertEquals(f.spec().size(), f.members().size(), f.spec().name());
            for (GuildPlan.Candidate c : f.members()) {
                assertNull(seen.put(c.botId(), f.spec().name()), "bot in two guilds");
            }
            int leaderLevel = f.leader().level();
            assertTrue(f.members().stream().allMatch(c -> c.level() <= leaderLevel), "the strongest leads");
            assertEquals(1, f.rankAt(0));
            assertEquals(2, f.rankAt(2));
            assertEquals(5, f.rankAt(f.members().size() - 1));
        }
        GuildPlan.Founding lion = plan.stream().filter(f -> f.spec().name().equals("Legendz")).findFirst().orElseThrow();
        assertTrue(lion.members().stream().allMatch(c -> c.level() >= 60), "hardcore band");
    }

    @Test
    void aSmallRosterShrinksTheGuildsToHalfOfIt() {
        List<GuildPlan.Candidate> roster = new java.util.ArrayList<>();
        for (int i = 0; i < 120; i++) {
            roster.add(new GuildPlan.Candidate(i, 10 + i));
        }
        List<GuildPlan.Founding> plan = GuildPlan.assign(roster, GuildPlan.SPECS, new Random(1));
        int total = plan.stream().mapToInt(f -> f.members().size()).sum();
        assertTrue(total <= 70, "about half the roster: " + total);
        assertTrue(plan.stream().allMatch(f -> f.members().size() >= 3));
    }

    @Test
    void capacityLeavesRoomForPlayers() {
        assertEquals(15, GuildPlan.capacityFor(3));
        assertEquals(50, GuildPlan.capacityFor(38));
        assertEquals(100, GuildPlan.capacityFor(99));
    }

    @Test
    void aBotGuildKicksTheLeadersRivalOrARivalOfMostMembers() {
        assertTrue(GuildRules.kick(true, 0, 0));
        assertTrue(GuildRules.kick(false, 2, 3));
        assertFalse(GuildRules.kick(false, 1, 2), "half is not most");
        assertFalse(GuildRules.kick(false, 0, 0), "nobody has feelings: stays");
        assertFalse(GuildRules.kick(false, 1, 4));
        assertFalse(GuildRules.kick(false, 1, 1), "one grudge never gets a player kicked");
        assertFalse(GuildRules.kick(false, 2, 2), "two bots are not the guild: at least three must know them");
        assertTrue(GuildRules.kick(false, 3, 4));
    }

    @Test
    void botGuildsStartWithLittleGpAndStagedClearsGiveLittle() {
        assertEquals(300, GuildRules.seedGp(6));
        assertEquals(1000, GuildRules.seedGp(20));
        assertEquals(1900, GuildRules.seedGp(38));
        assertTrue(GuildRules.seedGp(1000) <= 2000);
        assertEquals(30, GuildRules.bossGp());
        // an rc guild: old seed (24 * 120) plus two old clears -> the new seed plus two new clears
        assertEquals(GuildRules.seedGp(24) + 60, GuildRules.rescaledGp(24 * 120 + 300, 24));
        assertEquals(GuildRules.seedGp(24), GuildRules.rescaledGp(24 * 120, 24));
        assertEquals(-1, GuildRules.rescaledGp(24 * 120 + 700, 24), "players earned some: left alone");
        assertEquals(-1, GuildRules.rescaledGp(GuildRules.seedGp(24), 24), "already the new GP");
        assertEquals(-1, GuildRules.rescaledGp(GuildRules.rescaledGp(24 * 120 + 300, 24), 24), "never twice");
    }

    @Test
    void aBotGuildBelowItsSizeRefills() {
        assertTrue(GuildRules.refillDue(20, 24));
        assertFalse(GuildRules.refillDue(24, 24));
        assertFalse(GuildRules.refillDue(30, 24));
        assertFalse(GuildRules.refillDue(0, 0));
    }

    @Test
    void theGuildNamesFollowTheClientsRule() {
        for (String name : List.of("Legendz", "MoonLiteX", "MesoMafia", "SlumLords", "ZakumSlayerz", "KoreanBBQ",
                "SnailGang", "Pwnage", "NoobTeam", "HenesysKidz")) {
            assertTrue(GuildPlan.SPECS.stream().anyMatch(s -> s.name().equals(name)), name);
        }
        assertEquals("ZakumSlayerz", GuildPlan.SPECS.get(0).rival());
        assertEquals("SlumLords", GuildPlan.SPECS.get(1).rival());
    }
}

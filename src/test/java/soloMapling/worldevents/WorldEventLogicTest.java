package soloMapling.worldevents;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotPersonality;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldEventLogicTest {

    @AfterEach
    void clean() {
        WorldEventSteer.clearForTest();
    }

    @Test
    void levelBandsFollowTheMobs() {
        assertArrayEquals(new int[]{17, 70}, WorldEvents.bandFor(EventTemplate.Kind.INVASION, 22, 60));
        assertArrayEquals(new int[]{35, 75}, WorldEvents.bandFor(EventTemplate.Kind.BOSS_SIGHTING, 50, 50));
        assertArrayEquals(new int[]{1, 35}, WorldEvents.bandFor(EventTemplate.Kind.BOSS_SIGHTING, 10, 10));
        assertArrayEquals(new int[]{17, 40}, WorldEvents.bandFor(EventTemplate.Kind.HOT_SPOT, 25, 0));
        assertArrayEquals(new int[]{1, 255}, WorldEvents.bandFor(EventTemplate.Kind.HOT_SPOT, -1, 0), "no mobs known");
        assertArrayEquals(new int[]{1, 255}, WorldEvents.bandFor(EventTemplate.Kind.WANDERING_NPC, 30, 0));
    }

    @Test
    void templateLevelsWin() {
        EventTemplate t = new EventTemplate("t", EventTemplate.Kind.TREASURE_HUNT, "t", 1, 20, 0, 10, 200, 4,
                List.of(EventTemplate.Spot.map(1)), List.of(), 0, 5, 1, List.of(), true);
        assertArrayEquals(new int[]{10, 200}, WorldEvents.band(t, t.spots().get(0)));
    }

    @Test
    void rewardRolls() {
        List<EventTemplate.Reward> table = List.of(new EventTemplate.Reward(2000004, 5, 1.0),
                new EventTemplate.Reward(2000005, 1, 0.25));
        int bonus = 0;
        Random rng = new Random(3);
        for (int i = 0; i < 4000; i++) {
            List<EventTemplate.Reward> got = WorldEvents.roll(table, rng);
            assertEquals(2000004, got.get(0).itemId(), "a sure reward always comes");
            bonus += got.size() - 1;
        }
        assertEquals(0.25, bonus / 4000.0, 0.03);
    }

    @Test
    void steeringGivesUpAfterAFewTries() {
        WorldEventSteer.steer(1_000_000_001, 7, 104040001);
        assertEquals(104040001, WorldEventSteer.mapFor(1_000_000_001));
        for (int i = 0; i < WorldEventSteer.MAX_DECISIONS; i++) {
            assertEquals(104040001, WorldEventSteer.decide(1_000_000_001));
        }
        assertEquals(-1, WorldEventSteer.decide(1_000_000_001), "a bot that cannot get there stops trying");
        assertFalse(WorldEventSteer.isSteered(1_000_000_001));
    }

    @Test
    void releasingAnEventFreesOnlyItsBots() {
        WorldEventSteer.steer(1, 7, 100);
        WorldEventSteer.steer(2, 7, 100);
        WorldEventSteer.steer(3, 8, 200);
        assertEquals(2, WorldEventSteer.count(7));
        WorldEventSteer.release(7);
        assertEquals(-1, WorldEventSteer.mapFor(1));
        assertEquals(200, WorldEventSteer.mapFor(3));
    }

    @Test
    void grindersAnswerMoreOftenThanLoiterers() {
        BotPersonality.Traits grinder = new BotPersonality.Traits(1, 0.8, 0.1, 0.1, 0.5, 0, 60, 60);
        BotPersonality.Traits loiterer = new BotPersonality.Traits(1, 0.05, 0.9, 0.05, 0.5, 0, 60, 60);
        BotPersonality.Traits social = new BotPersonality.Traits(1, 0.1, 0.1, 0.8, 0.5, 0, 60, 60);
        double g = WorldEventReactions.joinChance(grinder);
        double l = WorldEventReactions.joinChance(loiterer);
        double s = WorldEventReactions.joinChance(social);
        assertTrue(g > s && s > l, g + " " + s + " " + l);
        assertTrue(l >= 0.1 && g <= 0.9, "nobody is certain either way");
        assertEquals(0.5, WorldEventReactions.joinChance(null));
        assertTrue(WorldEventReactions.levelFits(30, 20, 40));
        assertFalse(WorldEventReactions.levelFits(41, 20, 40));
    }

    @Test
    void noticesFillTokensAndNeverShowRawOnes() {
        String text = WorldEventText.fill(WorldEventText.start(EventTemplate.Kind.BOSS_SIGHTING),
                Map.of("BOSS", "Faust", "MAP", "The Forest of Evil I", "CH", "1"));
        assertEquals("[Sighting] Faust has been seen at The Forest of Evil I! (Ch 1)", text);
        assertFalse(WorldEventText.fill("{NOPE} hi {MAP}", Map.of("MAP", "x")).contains("{"));
        assertEquals("F", WorldEventText.initial("The Forest of Evil I"));
        assertEquals("H", WorldEventText.initial("Henesys Hunting Ground II"));
        for (EventTemplate.Kind k : EventTemplate.Kind.values()) {
            assertNotNull(WorldEventText.start(k));
            assertNotNull(WorldEventText.won(k));
            assertNotNull(WorldEventText.timedOut(k));
        }
    }

    @Test
    void botLinesSkipWhatTheyCannotFill() {
        assertNull(WorldEventReactions.fillAll("gg {PLAYER}", Map.of("MAP", "x")));
        assertEquals("gg Bob", WorldEventReactions.fillAll("gg {PLAYER}", Map.of("PLAYER", "Bob")));
    }

    @Test
    void everyKindHasChatterAndAMegaphone() {
        Map<String, String> tokens = Map.of("MAP", "Ant Tunnel I", "CH", "1", "REGION", "Victoria Island",
                "EVENT", "Mushroom Invasion", "BOSS", "Mushmom", "ITEM", "Orange Mushroom Cap",
                "NPC", "Taeng the Explorer", "PLAYER", "Bob");
        Random rng = new Random(1);
        for (EventTemplate.Kind k : EventTemplate.Kind.values()) {
            String prefix = WorldEventReactions.nodePrefix(k);
            for (String node : new String[]{prefix + "During", prefix + "After"}) {
                String line = WorldEventReactions.pickLine(node, tokens, rng);
                assertNotNull(line, node);
                assertFalse(line.contains("{"), line);
            }
            BotDialogueHandler.DialogueConstructor mega = BotDialogueHandler.getDialogueCon("MegaphoneDialogue.yaml",
                    "MegaphoneBroadcast", "WorldEvent" + prefix);
            assertNotNull(mega, "megaphone node WorldEvent" + prefix);
            assertFalse(mega.getDialogue().isEmpty());
        }
        assertNotNull(BotDialogueHandler.getDialogueCon("MegaphoneDialogue.yaml", "MegaphoneBroadcast",
                "WorldEventWon"));
    }

    @Test
    void deathGrumblesWarningsAndAfterLinesFill() {
        Map<String, String> tokens = Map.of("MAP", "Henesys", "CH", "1", "EVENT", "Giant Cake", "BOSS", "Mushmom",
                "N", "6", "MIN", "10", "REGION", "Victoria Island");
        Random rng = new Random(2);
        for (String node : new String[]{"Died", "DiedAgain", "DeathsAfter", "Soon", "CakeDuring"}) {
            for (int i = 0; i < 10; i++) {
                String line = WorldEventReactions.pickLine(node, tokens, rng);
                assertNotNull(line, node);
                assertFalse(line.contains("{"), line);
                assertFalse(line.toLowerCase().contains(" bot"), "bots never call anyone a bot: " + line);
            }
        }
        assertNotNull(BotDialogueHandler.getDialogueCon("MegaphoneDialogue.yaml", "MegaphoneBroadcast",
                "WorldEventSoon"));
        assertEquals("[World Event] A Giant Cake will appear in Henesys in 10 minutes! (Ch 1)",
                WorldEventText.fill(WorldEventText.warning(EventTemplate.Kind.GIANT_CAKE, 10), tokens));
        assertEquals("[World Event] A Giant Cake will appear in Henesys in 1 minute! (Ch 1)",
                WorldEventText.fill(WorldEventText.warning(EventTemplate.Kind.GIANT_CAKE, 1), tokens));
        for (EventTemplate.Kind k : EventTemplate.Kind.values()) {
            assertFalse(WorldEventText.fill(WorldEventText.warning(k, 5), tokens).contains("{"), k.toString());
        }
    }

    @Test
    void anEventBecomesWorldNewsBotsBringUp() {
        long now = System.currentTimeMillis();
        soloMapling.ArtificialPlayer.BotWorldGossip.onWorldEvent("the giant cake in henesys", "Bob", 5, 3,
                List.of(1_000_000_001), now);
        var news = soloMapling.ArtificialPlayer.BotWorldGossip.recent(now).stream()
                .filter(n -> n.kind == soloMapling.ArtificialPlayer.BotWorldGossip.Kind.EVENT).findFirst().orElseThrow();
        assertTrue(news.witnessedBy(1_000_000_001));
        assertEquals("Bob won the giant cake in henesys", soloMapling.ArtificialPlayer.BotWorldGossip.about(news));
        Random rng = new Random(1);
        for (int i = 0; i < 20; i++) {
            for (boolean witness : new boolean[]{true, false}) {
                String line = soloMapling.ArtificialPlayer.BotWorldGossip.line(news, witness, "", rng);
                assertNotNull(line);
                assertFalse(line.contains("{"), line);
                assertFalse(line.contains(" bots "), line);
            }
        }
        soloMapling.ArtificialPlayer.BotWorldGossip.onWorldEvent("the invasion at henesys", null, 0, 0, List.of(), now);
        var noWinner = soloMapling.ArtificialPlayer.BotWorldGossip.recent(now).get(0);
        assertEquals("the invasion at henesys", soloMapling.ArtificialPlayer.BotWorldGossip.about(noWinner));
        for (int i = 0; i < 20; i++) {
            String line = soloMapling.ArtificialPlayer.BotWorldGossip.line(noWinner, i % 2 == 0, "", rng);
            assertFalse(line.contains("{W}") || line.contains("  "), line);
        }
    }
}

package soloMapling.ArtificialPlayer.BotConvo;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 tune (TEST social trace 2026-10-26): short lines, a light accent, real training spots, tips followed. */
class BotThreadsTuneTest {

    private final java.util.function.IntPredicate realFollows = BotThreads.followsTips;

    @AfterEach
    void reset() {
        BotThreads.resetForTest();
        BotThreads.followsTips = realFollows;
        TrainingTips.clearForTest();
        System.clearProperty("solomapling.convo.tipFollow");
    }

    private static Character chr(int slot, String name, int level) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(BotStore.BOT_ID_BASE + slot);
        c.setName(name);
        c.setLevel(level);
        return c;
    }

    @Test
    void longLinesAndSpanishSentencesAreNotSaid() {
        String reply = String.join("\n",
                "Clock4: bro q caro, 300 y trato hecho porfa",
                "clock656: I have 400k mesos. That is all, my friend. Take it or leave it.",
                "Clock4: jaja ok 350",
                "clock656: deal");
        List<String[]> lines = BotThreads.parse(reply, "Clock4", "clock656");
        assertEquals(List.of("jaja ok 350", "deal"), lines.stream().map(l -> l[1]).toList());
    }

    @Test
    void theTrainingPromptGivesRealSpotsAndAsksForShortCasualLines() {
        Character a = chr(1, "Drake1", 25);
        Character b = chr(2, "picklemaple", 51);
        String prompt = BotThreads.threadPrompt(a, b, BotThreads.Topic.TRAINING, "Drake1 is level 25, aiming for 30",
                "Henesys", null);
        assertTrue(prompt.contains("real training spots for level 25: Ant Tunnel"), prompt);
        assertTrue(prompt.contains("Drake1 asks where to train; picklemaple suggests one"), prompt);
        assertTrue(prompt.contains("never make up a place"), prompt);
        assertTrue(prompt.contains("under 45 characters") && prompt.contains("never formal"), prompt);
        String lfg = BotThreads.threadPrompt(a, b, BotThreads.Topic.LFG, "Kerning PQ", "Henesys", null);
        assertFalse(lfg.contains("training spots"), lfg);
    }

    @Test
    void theBankTrainingThreadSuggestsARealSpot() {
        Character a = chr(1, "Drake1", 25);
        Character b = chr(2, "picklemaple", 51);
        List<TrainingTips.Spot> spots = BotThreads.spotsFor(BotThreads.Topic.TRAINING, a);
        for (int seed = 0; seed < 30; seed++) {
            List<String[]> lines = BotThreads.fallback(BotThreads.Topic.TRAINING, a, b, null, spots, new Random(seed));
            assertEquals(2, lines.size());
            assertEquals("Drake1", lines.get(0)[0]);
            assertTrue(lines.get(0)[1].contains("25"), lines.get(0)[1]);
            assertNotNull(TrainingTips.suggested(lines, "Drake1", spots), lines.get(1)[1]);
            assertTrue(lines.get(1)[1].length() <= BotThreads.LINE_MAX);
            assertTrue(TrainingTips.wrongPlaces(lines.get(1)[1], 25).isEmpty(), lines.get(1)[1]);
        }
    }

    private static BotThreads.Live live(Character a, Character b) {
        BotThreads.Live l = new BotThreads.Live(1, 100000000, List.of(a.getId(), b.getId()), List.of(a.getName(),
                b.getName()), BotThreads.Topic.TRAINING, null, null, System.currentTimeMillis() + 60_000, a.getId());
        l.spots = BotThreads.spotsFor(BotThreads.Topic.TRAINING, a);
        return l;
    }

    @Test
    void aboutOneAskerInThreeFollowsARealTip() {
        Character a = chr(1, "Drake1", 25);
        Character b = chr(2, "picklemaple", 51);
        BotThreads.Live l = live(a, b);
        List<String[]> lines = List.of(new String[]{"Drake1", "where u training at 25?"},
                new String[]{"picklemaple", "try ant tunnel tbh"});
        BotThreads.followsTips = id -> true;
        Random rng = new Random(5);
        int followed = 0;
        for (int i = 0; i < 3000; i++) {
            TrainingTips.Spot s = BotThreads.decideTip(l, a, b, lines, rng, 1000);
            if (s != null) {
                followed++;
                assertEquals("Ant Tunnel", s.name());
                assertEquals(s.mapId(), TrainingTips.take(a.getId(), 2000), "the bot takes it on its next decision");
            }
        }
        assertTrue(followed > 850 && followed < 1150, "followed " + followed);
    }

    @Test
    void noTipIsFollowedToAFakeSpotByANonTrainerOrAtTheWrongLevel() {
        System.setProperty("solomapling.convo.tipFollow", "1");
        Character a = chr(1, "Drake1", 25);
        Character b = chr(2, "picklemaple", 51);
        BotThreads.Live l = live(a, b);
        BotThreads.followsTips = id -> true;
        assertNull(BotThreads.decideTip(l, a, b, List.<String[]>of(new String[]{"picklemaple", "go kerning subway lol"}),
                new Random(1), 1000), "not one of the real spots for 25");
        BotThreads.followsTips = id -> false;
        assertNull(BotThreads.decideTip(l, a, b, List.<String[]>of(new String[]{"picklemaple", "try ant tunnel tbh"}),
                new Random(1), 1000), "a town bot has no grind path");
        BotThreads.followsTips = id -> true;
        Character high = chr(3, "M0onWraith", 67);
        l.spots = List.of(new TrainingTips.Spot(105050200, "Ant Tunnel", null));
        assertNull(BotThreads.decideTip(l, high, b, List.<String[]>of(new String[]{"picklemaple", "try ant tunnel"}),
                new Random(1), 1000), "Ant Tunnel is no grind map at 67");
        assertEquals(-1, TrainingTips.pending(high.getId()));
        l.spots = BotThreads.spotsFor(BotThreads.Topic.TRAINING, a);
        assertNotNull(BotThreads.decideTip(l, a, b, List.<String[]>of(new String[]{"picklemaple", "try ant tunnel"}),
                new Random(1), 1000));
        assertEquals(TrainingTips.spotsFor(25, 0, 3).get(0).name().toLowerCase(Locale.ROOT), "ant tunnel");
    }
}

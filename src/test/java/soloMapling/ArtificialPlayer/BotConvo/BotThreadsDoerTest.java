package soloMapling.ArtificialPlayer.BotConvo;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.0 (TEST social trace 2026-10-27, DECIDE 0 of 15): every thread was two town bots - a training bot was never
 * "available" - so no LFG party, tip, or trade could follow, and the gossip was small talk. Threads now take training
 * bots resting in town, prefer them, give the LFG ask and the TRAINING question to one, and a real story beats small
 * talk.
 */
class BotThreadsDoerTest {

    @AfterEach
    void reset() {
        BotThreads.resetForTest();
        TrainingTips.clearForTest();
    }

    private static Character chr(int slot, String name, int level) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(BotStore.BOT_ID_BASE + slot);
        c.setName(name);
        c.setLevel(level);
        return c;
    }

    @Test
    void aTrainingBotRestingInTownMayTalk() {
        TrainingBot resting = mock(TrainingBot.class);
        when(resting.freeForThread()).thenReturn(true);
        assertTrue(BotThreads.talker(resting));
        TrainingBot grinding = mock(TrainingBot.class);
        when(grinding.freeForThread()).thenReturn(false);
        assertFalse(BotThreads.talker(grinding));
        SocialBot social = mock(SocialBot.class);
        when(social.isAvailableForAmbientActions()).thenReturn(true);
        assertTrue(BotThreads.talker(social), "town bots still talk");
        assertFalse(BotThreads.talker(null));
    }

    @Test
    void trainingBotsGetTheThreadThreeTimesInFourTwoInOnePqBandFirst() {
        // the Henesys of the trace: town bots of all levels, and three training bots (two in Kerning PQ's band)
        List<Character> idle = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            idle.add(chr(10 + i, "town" + i, 20 + 5 * i));
        }
        Character k1 = chr(1, "kpqA", 24);
        Character k2 = chr(2, "kpqB", 28);
        Character high = chr(3, "high", 55);
        idle.addAll(List.of(high, k1, k2));
        Set<Integer> trainers = Set.of(k1.getId(), k2.getId(), high.getId());
        IntPredicate doer = trainers::contains;
        Random rng = new Random(3);
        int both = 0;
        int band = 0;
        for (int i = 0; i < 2000; i++) {
            List<Character> shuffled = new ArrayList<>(idle);
            java.util.Collections.shuffle(shuffled, rng);
            List<Character> pair = BotThreads.choosePair(shuffled, doer, rng);
            assertEquals(2, pair.size());
            assertFalse(pair.get(0) == pair.get(1));
            if (doer.test(pair.get(0).getId()) && doer.test(pair.get(1).getId())) {
                both++;
                if (Set.of(pair.get(0), pair.get(1)).equals(Set.of(k1, k2))) {
                    band++;
                }
            }
        }
        assertTrue(both > 1400 && both < 1650, "two training bots " + both);
        assertTrue(band > 1400, "two in one PQ band go first: " + band + " of " + both);
        List<Character> town = idle.subList(0, 8);
        assertEquals(List.of(town.get(0), town.get(1)), BotThreads.choosePair(town, doer, rng), "no trainer: as before");
        List<Character> one = List.of(town.get(0), high, town.get(1));
        for (int i = 0; i < 50; i++) {
            List<Character> pair = BotThreads.choosePair(one, doer, rng);
            assertTrue(pair.get(0) == high || pair.equals(List.of(town.get(0), high)), "the trainer opens");
        }
    }

    @Test
    void theLfgAskAndTheTrainingQuestionComeFromTheTrainingBot() {
        Character town = chr(10, "MapleOG", 23);
        Character trainer = chr(1, "ownagetauro", 25);
        IntPredicate doer = id -> id == trainer.getId();
        List<Character> pair = List.of(town, trainer);
        assertEquals(List.of(trainer, town), BotThreads.doerFirst(BotThreads.Topic.LFG, pair, doer));
        assertEquals(List.of(trainer, town), BotThreads.doerFirst(BotThreads.Topic.TRAINING, pair, doer));
        assertEquals(pair, BotThreads.doerFirst(BotThreads.Topic.GOSSIP, pair, doer));
        assertEquals(List.of(trainer, town), BotThreads.doerFirst(BotThreads.Topic.LFG, List.of(trainer, town), doer));
        Character old = chr(2, "old", 90);
        assertEquals(List.of(town, old), BotThreads.doerFirst(BotThreads.Topic.LFG, List.of(town, old),
                id -> id == old.getId()), "no PQ at 90: the ask stays with the first");
    }

    @Test
    void theRecruitersOwnReasonIsTraced() {
        // TEST trace: "no party: MapleOG the recruiter is not free" - the other bot's name, and not which reason
        LfgParty.State town = new LfgParty.State(23, false, false, false, false, false, false);
        LfgParty.Plan p = LfgParty.decide(1, "ownagetauro", new LfgParty.State(55, false, false, false, false, false,
                false), 2, "MapleOG", town, 1_793_000_000_000L);
        assertEquals(LfgParty.Why.RECRUITER_BUSY, p.why());
        assertEquals("ownagetauro (the recruiter) not a bot that takes life trips", p.decline());
        LfgParty.Plan low = LfgParty.decide(1, "picklemaple", LfgPartyTest.free(51), 2, "EvanKiller",
                LfgPartyTest.free(20), 1_793_000_000_000L);
        assertEquals("EvanKiller too low for the PQ", low.decline());
    }

    @Test
    void aRealStoryBeatsSmallTalk() {
        Character town = chr(10, "ownagetauro", 55);
        Character trainer = chr(1, "StormLover", 40);
        soloMapling.ArtificialPlayer.BotGossip.Fact fm = new soloMapling.ArtificialPlayer.BotGossip.Fact("talk",
                "prices in the FM", null, List.of(), 0, false);
        soloMapling.ArtificialPlayer.BotGossip.Fact ks = new soloMapling.ArtificialPlayer.BotGossip.Fact("ks",
                "Gm ksed StormLover", null, List.of("Gm"), 77, true);
        BotThreads.Told told = BotThreads.storyFirst(List.of(town, trainer), fm,
                (teller, listener) -> teller == trainer ? ks : fm);
        assertEquals(List.of(trainer, town), told.pair(), "the one who knows a story tells it");
        assertEquals(ks, told.fact());
        told = BotThreads.storyFirst(List.of(town, trainer), fm, (teller, listener) -> fm);
        assertEquals(List.of(town, trainer), told.pair());
        assertEquals(fm, told.fact(), "neither knows one: small talk");
        soloMapling.ArtificialPlayer.BotGossip.Fact own = new soloMapling.ArtificialPlayer.BotGossip.Fact("news",
                "Bob killed Zakum", null, List.of("Bob"), 5, false);
        told = BotThreads.storyFirst(List.of(town, trainer), own, (teller, listener) -> ks);
        assertEquals(own, told.fact(), "the opener's own story stays");
    }

    @Test
    void whatBotsCallASpotNamesIt() {
        // TEST trace lines and what players type: "kerning subway", "golems", "dead trees", "mu lung", "clocktower"
        String[][] said = {{"go kerning subway lol", "Kerning subway"}, {"subway is ok", "Kerning subway"},
                {"golems r easy xp", "forest of Golem"}, {"forest of dead trees in el nath tbh", "Forest of Dead Trees"},
                {"dead trees is decent", "Forest of Dead Trees"}, {"mu lung mobs hit hard", "Practice Field : Beginner"},
                {"clocktower is packed", "Lost Time"}, {"try the ant tunnel", "Ant Tunnel"},
                {"wild boars r good", "Land of Wild Boar"}, {"ghost ship = $$", "Ghost ship"}};
        for (String[] s : said) {
            TrainingTips.Spot spot = new TrainingTips.Spot(1, s[1], null);
            assertNotNull(TrainingTips.suggested(List.<String[]>of(new String[]{"Bob", s[0]}), "Kimchi", List.of(spot)),
                    s[0] + " -> " + s[1]);
        }
        assertEquals(null, TrainingTips.suggested(List.<String[]>of(new String[]{"Bob", "drake1 said hi"}), "Kimchi",
                List.of(new TrainingTips.Spot(1, "Drake Hunting Ground", null))), "a name is no place");
    }
}

package soloMapling.ArtificialPlayer.BotScrollSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.Appetite;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.Choice;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.ScrollFacts;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.Target;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScrollRulesTest {

    static final int SWORD = 1302000, CLAW = 1472000, GLOVES = 1082002;
    static final ScrollFacts S100 = new ScrollFacts(2043000, 100, 0, 10);
    static final ScrollFacts S60 = new ScrollFacts(2043001, 60, 0, 23);
    static final ScrollFacts S10 = new ScrollFacts(2043002, 10, 0, 60);
    static final ScrollFacts DARK70 = new ScrollFacts(2043004, 70, 50, 23);
    static final ScrollFacts DARK30 = new ScrollFacts(2043005, 30, 50, 60);

    @Test
    void scrollsFitTheirEquipCategoryOnlyAndNeedASlot() {
        assertTrue(ScrollRules.fits(2043001, SWORD, 7, List.of()));
        assertFalse(ScrollRules.fits(2043001, CLAW, 7, List.of()), "a sword scroll does not go on a claw");
        assertTrue(ScrollRules.fits(2044701, CLAW, 7, List.of()));
        assertTrue(ScrollRules.fits(2040804, GLOVES, 5, List.of()));
        assertFalse(ScrollRules.fits(2043001, SWORD, 0, List.of()), "no slot left");
        assertFalse(ScrollRules.fits(2043001, SWORD, 7, List.of(1302001)), "the scroll's req list says another item");
        assertTrue(ScrollRules.fits(2043001, SWORD, 7, List.of(SWORD)));
    }

    @Test
    void botsUseOnlyPlainStatScrolls() {
        assertTrue(ScrollRules.isPlainScroll(2043001));
        assertFalse(ScrollRules.isPlainScroll(2049100), "chaos");
        assertFalse(ScrollRules.isPlainScroll(2049000), "clean slate");
        assertFalse(ScrollRules.isPlainScroll(2340000), "white scroll is not the scroll itself");
        assertFalse(ScrollRules.isPlainScroll(2000000), "a potion");
        assertFalse(ScrollRules.scrollableEquip(constants.id.ItemId.WEDDING_RING_STAR), "wedding ring");
        assertTrue(ScrollRules.scrollableEquip(SWORD));
    }

    @Test
    void attackWeighsMostInTheGain() {
        assertTrue(ScrollRules.gainWeight(Map.of("PAD", 5, "STR", 3)) > ScrollRules.gainWeight(Map.of("PAD", 2, "STR", 1)));
        assertTrue(ScrollRules.gainWeight(Map.of("PAD", 1)) > ScrollRules.gainWeight(Map.of("PDD", 5)));
    }

    @Test
    void aCarefulBotTakesTheSurestScrollAndNeverADarkOne() {
        Target worn = new Target(SWORD, 7, true);
        Random rng = new Random(1);
        assertEquals(new Choice(2043000, false),
                ScrollRules.choose(Appetite.CAREFUL, worn, List.of(S10, S60, S100, DARK70), true, rng));
        for (int i = 0; i < 200; i++) {
            Choice c = ScrollRules.choose(Appetite.CAREFUL, new Target(SWORD, 7, false), List.of(DARK70, DARK30), true, rng);
            assertNull(c, "a careful bot never uses a dark scroll");
        }
        assertNull(ScrollRules.choose(Appetite.CAREFUL, worn, List.of(S10), false, rng),
                "a careful bot does not 10% without a white scroll");
        assertNull(ScrollRules.choose(Appetite.CAREFUL, worn, List.of(S10), true, rng),
                "nor on gear it wears");
        assertEquals(new Choice(2043002, true),
                ScrollRules.choose(Appetite.CAREFUL, new Target(SWORD, 7, false), List.of(S10), true, rng),
                "a 10% under a white scroll, on gear it does not wear");
    }

    @Test
    void aNormalBotMostlySixtiesAndNeverDarkScrollsWornGear() {
        Random rng = new Random(7);
        Map<Integer, Integer> picks = new java.util.HashMap<>();
        for (int i = 0; i < 1000; i++) {
            Choice c = ScrollRules.choose(Appetite.NORMAL, new Target(SWORD, 7, true), List.of(S60, S10, DARK70), false, rng);
            picks.merge(c.scrollId(), 1, Integer::sum);
        }
        assertTrue(picks.getOrDefault(S60.scrollId(), 0) > 600, "mostly 60%: " + picks);
        assertTrue(picks.getOrDefault(S10.scrollId(), 0) > 100, "now and then a 10%: " + picks);
        assertEquals(0, picks.getOrDefault(DARK70.scrollId(), 0), "never dark on worn gear");
        int dark = 0;
        for (int i = 0; i < 2000; i++) {
            Choice c = ScrollRules.choose(Appetite.NORMAL, new Target(SWORD, 7, false), List.of(S60, DARK70), false, rng);
            if (c.scrollId() == DARK70.scrollId()) {
                dark++;
            }
        }
        assertTrue(dark > 10 && dark < 150, "dark scrolls rarely: " + dark);
    }

    @Test
    void aGamblerGoesForTheBigStatsWithoutAWhiteScroll() {
        Random rng = new Random(3);
        Map<Integer, Integer> picks = new java.util.HashMap<>();
        for (int i = 0; i < 1000; i++) {
            Choice c = ScrollRules.choose(Appetite.GAMBLER, new Target(SWORD, 7, true), List.of(S60, S10, DARK30), true, rng);
            assertFalse(c.whiteScroll(), "gamblers do not spend white scrolls");
            picks.merge(c.scrollId(), 1, Integer::sum);
        }
        assertTrue(picks.get(S10.scrollId()) > 800, picks.toString());
        assertTrue(picks.getOrDefault(DARK30.scrollId(), 0) > 40, "dark scrolls now and then: " + picks);
        assertNull(picks.get(S60.scrollId()));
    }

    @Test
    void nothingWhenTheSlotsRunOutOrNothingFits() {
        Random rng = new Random();
        for (Appetite a : Appetite.values()) {
            assertNull(ScrollRules.choose(a, new Target(SWORD, 0, true), List.of(S60, S100), true, rng));
            assertNull(ScrollRules.choose(a, new Target(SWORD, 3, true), List.of(), true, rng));
        }
    }

    @Test
    void megaphonesOnlyForRealNotableOutcomes() {
        assertEquals("ScrollPerfect", ScrollRules.megaphoneFor(BotScrolling.Outcome.SUCCESS, 7, 7, 0));
        assertNull(ScrollRules.megaphoneFor(BotScrolling.Outcome.SUCCESS, 6, 7, 0), "6/7 is not perfect");
        assertNull(ScrollRules.megaphoneFor(BotScrolling.Outcome.SUCCESS, 5, 7, 2), "not finished yet");
        assertNull(ScrollRules.megaphoneFor(BotScrolling.Outcome.SUCCESS, 3, 3, 0), "a 3-slot item is no news");
        assertEquals("ScrollBoom", ScrollRules.megaphoneFor(BotScrolling.Outcome.CURSE, 5, 7, 0));
        assertNull(ScrollRules.megaphoneFor(BotScrolling.Outcome.CURSE, 1, 7, 4), "a clean boom is no news");
        assertNull(ScrollRules.megaphoneFor(BotScrolling.Outcome.FAIL, 7, 7, 0));
    }

    @Test
    void appetitesSpreadOverBotsAndStayPutPerBot() {
        Map<Appetite, Integer> n = new EnumMap<>(Appetite.class);
        for (int id = 1_000_000; id < 1_010_000; id++) {
            n.merge(Appetite.of(id), 1, Integer::sum);
        }
        assertTrue(n.get(Appetite.CAREFUL) > 3000 && n.get(Appetite.NORMAL) > 3500 && n.get(Appetite.GAMBLER) > 1000,
                n.toString());
        assertEquals(Appetite.of(1_000_123), Appetite.of(1_000_123));
        assertNotEquals(null, Appetite.parse("Gambler", null));
        assertEquals(Appetite.NORMAL, Appetite.parse("??", Appetite.NORMAL));
    }
}

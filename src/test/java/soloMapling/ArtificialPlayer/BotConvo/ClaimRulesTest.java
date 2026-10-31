package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static soloMapling.ArtificialPlayer.BotConvo.ClaimRules.Kind.*;

/** rc6 say-do: the claim rules, on lines from the bank, the dialogue packs and the test server's transcripts. */
class ClaimRulesTest {

    private static List<ClaimRules.Kind> strong(String line) {
        return ClaimRules.classify(line).stream().filter(c -> !c.weak()).map(ClaimRules.Claim::kind).toList();
    }

    private static void claims(String line, ClaimRules.Kind... kinds) {
        assertEquals(List.of(kinds), strong(line), line);
    }

    @Test
    void theOwnersExamplesAreClaims() {
        claims("brb buy pots", RESUPPLY);
        claims("brb pots", RESUPPLY);
        claims("gotta restock on pots before I head out", RESUPPLY);
        claims("omw", COME);
        claims("be there in a sec", COME);
        claims("going to orbis anyone?", GO_TO);
        claims("heading to the fm", GO_TO);
        claims("follow me", FOLLOW);
        claims("ill invite u", PARTY_OFFER);
        claims("inv me", PARTY_ASK);
        claims("LFG kpq", PARTY_ASK);
        claims("healing u", BUFF);
        claims("S> Ilbi 4.5m per set cheap!!! pm me", SELL);
        claims("B> 60% scroll for claw att paying 15m ea @@@", BUY);
        claims("trade me", TRADE_ASK);
        claims("ill trade u for it", TRADE);
        claims("brb dinner", AWAY);
        claims("my mom's calling me hold on", AWAY);
        claims("gtg", LEAVING);
        claims("night all", LEAVING);
        claims("training at ant tunnel", TRAIN_AT);
        claims("here take this", GIFT);
        claims("leaving pt, ty all", LEAVE_GROUP);
    }

    @Test
    void notClaims() {
        claims("gl selling tho");
        claims("someone in henesys is selling ilbis for half the going rate");
        claims("the dudes spamming wts in all caps every 5 seconds");
        claims("I AM GOING TO LOSE MY MIND");
        claims("no buff for u sry");
        claims("not giving you my dagger, nice try");
        claims("she quit the guild after the breakup, total mess");
        claims("quit ksing me you already died twice");
        claims("has anyone else noticed how many people are afk in this map");
        claims("yeah sorry was afk");
        claims("he said \"brb 5 min\" 30 minutes ago in my buddy chat lol");
        claims("the worst part is I knew it was coming");
        claims("honestly been stalking this shop waiting for you to restock");
        claims("Can't buff you right now, I'm out of it");
    }

    /**
     * The rc6 auto-QA run of 21095df97 (data/autoqa/saydo/2026-10-23.jsonl): 426 "trade me" claims, 0 backed - the ad
     * asks the reader to open the trade, it promises no window of its own. A first-person "ill trade u" still does.
     */
    @Test
    void tradeMeAsksItDoesNotPromise() {
        claims("Selling> Ilbi Throwing-Stars just trade me! @@@@@@@@", SELL, TRADE_ASK);
        claims("9,520 for the wokr gloves, just open a trade with me lmao", TRADE_ASK);
        claims("got nx codes, 10k for 50m, trade me fast", TRADE_ASK);
        claims("giving away 50m to the first person who trades me their weapon ;)", TRADE_ASK);
        claims("ok ill send a trade", TRADE);
        claims("sending u a trade now", TRADE);
    }

    /** Item words the ledger checks the bag for: the run's "does not hold" came from these reads (176 claims). */
    @Test
    void adItemsAreReadWithoutTheirCountsAndTails() {
        assertEquals("nx cash code", item("Selling 10k nx cash code, 50m TRADE ME!"));
        assertEquals("nx code", item("S> 10k NX code 50m, no lowballs"));
        assertEquals("nx code", item("S>> 10,000 NX code, 50m, serious offers only"));
        assertEquals("nx", item("SELLING NX 10K CODE!! 50m!! no scammers"));
        assertEquals("elixir", item("Selling> Elixir hotties only @@@@@@@@ @@@@@@@@"));
        assertEquals("heartstopper", item("S> Heartstopper dont waste my time @@@@@@@@"));
        assertEquals("ilbi throwing stars", item("S>> Ilbi Throwing-Stars just trade me!"));
        assertEquals("30% helm def", item("S> 30% helm def 500k ea, clearing out my inventory"));
        assertEquals("dark scroll", item("S> DARK SCROLL FOR BOTTOMWEAR FOR DEX 30% NO NOOBS"));
    }

    private static String item(String line) {
        return ClaimRules.classify(line).stream().filter(c -> c.kind() == SELL).findFirst().orElseThrow().item();
    }

    @Test
    void sellingTalkIsNotAnAd() {
        claims("my fingers hurt from typing S> all day");
        claims("the FM is my home now. i sleep here. i dream in S>");
        claims("lf party, s> ilbis 4m", PARTY_ASK, SELL);
    }

    /** "lv 40 sin still training at henesys? lol sit down" (a roast at the player) was judged as the bot's plan. */
    @Test
    void trainingAtIsAboutTheSpeaker() {
        claims("lv 40 sin still training at henesys? lol sit down");
        claims("u still training at kerning?");
        claims("im still training at ant tunnel", TRAIN_AT);
        claims("still training at ant tunnel", TRAIN_AT);
    }

    @Test
    void aRestockForLaterIsWeak() {
        assertTrue(ClaimRules.classify("i need to go buy some pots after that").get(0).weak());
        claims("brb pots", RESUPPLY);
    }

    @Test
    void weakOnesAreMarked() {
        assertTrue(ClaimRules.classify("im out").get(0).weak());
        assertTrue(ClaimRules.classify("this way, dont get lost").get(0).weak());
        assertTrue(ClaimRules.classify("thinking about heading to ellinia after this").get(0).weak());
        assertFalse(ClaimRules.classify("k coming").get(0).weak());
    }

    @Test
    void placesAndItemsAreRead() {
        ClaimRules.Claim go = ClaimRules.classify("heading to ellinia, need to grab some stuff").get(0);
        assertEquals(101000000, go.mapId());
        ClaimRules.Claim sell = ClaimRules.classify("S> steelys 1.8m/set @@ henesys ch1").get(0);
        assertEquals("steelys", sell.item());
        assertTrue(ClaimRules.sameArea(910000003, 910000000), "an FM room is the FM");
        assertFalse(ClaimRules.sameArea(100000000, 101000000));
    }
    /** t-cv-1 2026-10-31: "lvling at Henesys Hunting Ground I" was judged as a claim to be in the town Henesys. */
    @Test
    void aFieldNamedAfterATownIsNotTheTown() {
        ConvoEntities.Names saved = ConvoEntities.names();
        try {
            ConvoEntities.useForTest(null);
            ClaimRules.Claim c = ClaimRules.classify("lvling at Henesys Hunting Ground I today, comfy spot").stream()
                    .filter(x -> x.kind() == TRAIN_AT).findFirst().orElse(null);
            assertTrue(c == null || c.mapId() != 100000000, "no name table: never the town, got " + c);
            assertEquals(-1, ClaimRules.place("kerning city subway"));
            assertEquals(100000000, ClaimRules.place("henesys to buy pots"));

            ConvoEntities.useForTest(new ConvoEntities.Names() {
                public java.util.Map<String, Integer> maps() {
                    return java.util.Map.of("henesys hunting ground i", 104040000,
                            "kerning city construction site", 103010000, "henesys", 100000000);
                }

                public java.util.Map<String, Integer> mobs() {
                    return java.util.Map.of();
                }

                public java.util.Map<String, Integer> items() {
                    return java.util.Map.of();
                }
            });
            List<ClaimRules.Claim> got = ClaimRules.classify("lvling at Henesys Hunting Ground I today, comfy spot");
            assertEquals(1, got.size(), got.toString());
            assertEquals(TRAIN_AT, got.get(0).kind());
            assertEquals(104040000, got.get(0).mapId());
            assertEquals(103010000, ClaimRules.classify("lvling at Kerning City Construction Site today, comfy spot")
                    .get(0).mapId());
            assertEquals(100000000, ClaimRules.place("henesys today"));
        } finally {
            ConvoEntities.useForTest(saved);
        }
    }
}

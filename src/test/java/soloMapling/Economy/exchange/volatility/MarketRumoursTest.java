package soloMapling.Economy.exchange.volatility;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.Economy.exchange.volatility.MarketRumours.Claim;
import soloMapling.Economy.exchange.volatility.MarketRumours.Facts;
import soloMapling.Economy.exchange.volatility.MarketRumours.Rumour;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.1 volatility: rumours are rate-limited, true ones come from the facts, the lines are clean, pressure fades. */
class MarketRumoursTest {

    static final long NOW = 1_800_000_000_000L, GAP = 20 * 60_000L;

    static MarketRumours rumours() {
        Map<Claim, List<String>> m = new java.util.EnumMap<>(Claim.class);
        for (Claim c : Claim.values()) {
            m.put(c, List.of(c.node + " {ITEM}"));
        }
        return new MarketRumours(MarketRumours.fixed(m));
    }

    static Facts flat(String ticker) {
        return new Facts(ticker, 0, false, false, List.of());
    }

    @Test
    void oneRumourPerGapWorldWide() {
        MarketRumours r = rumours();
        Random rng = new Random(1);
        Rumour a = r.make(flat("LEAF"), NOW, GAP, 35, rng);
        assertNotNull(a);
        r.said(a);
        assertNull(r.make(flat("LEAF"), NOW + GAP - 1, GAP, 35, rng), "inside the gap");
        assertNull(r.make(flat("CHAOS"), NOW + 60_000, GAP, 35, rng), "any ticker");
        assertNotNull(r.make(flat("LEAF"), NOW + GAP, GAP, 35, rng));
        // a day of ticks at most one per 20 min: 72
        MarketRumours s = rumours();
        int said = 0;
        for (long t = NOW; t < NOW + 86_400_000L; t += 30_000) {
            Rumour x = s.make(flat("LEAF"), t, GAP, 35, rng);
            if (x != null) {
                s.said(x);
                said++;
            }
        }
        assertEquals(72, said);
    }

    @Test
    void trueRumoursComeFromTheFactsAndSecretsLeak() {
        MarketRumours r = rumours();
        Facts f = new Facts("LEAF", 0.15, true, false, List.of(MarketEvents.Kind.LEAF_BLIGHT));
        int fake = 0;
        for (int s = 0; s < 400; s++) {
            Rumour x = r.make(f, NOW, 0, 35, new Random(s));
            if (x.fake()) {
                fake++;
                assertFalse(List.of(Claim.RISE, Claim.WHALE_BUY, Claim.DROUGHT).contains(x.claim()), "a made-up "
                        + "rumour is not one of the true claims");
            } else {
                assertTrue(List.of(Claim.RISE, Claim.WHALE_BUY, Claim.DROUGHT).contains(x.claim()), x.toString());
            }
            assertEquals(x.claim().node + " leaves", x.line());
        }
        assertTrue(fake > 100 && fake < 180, "about 35% made up: " + fake);
        // nothing true to say: made up; leaf-only claims never about chaos
        for (int s = 0; s < 100; s++) {
            Rumour x = r.make(flat("CHAOS"), NOW, 0, 0, new Random(s));
            assertTrue(x.fake());
            assertFalse(List.of(Claim.DROUGHT, Claim.FLOOD, Claim.ROOTY).contains(x.claim()));
            assertTrue(x.line().endsWith("chaos scrolls"));
        }
    }

    @Test
    void heardRumoursPushTradersAndFadeInTwoHours() {
        MarketRumours r = rumours();
        r.said(new Rumour("LEAF", Claim.CRASH, true, NOW, "x"));
        assertEquals(-1.0, r.pressure("LEAF", NOW, 100, 7), 1e-9);
        assertEquals(-0.5, r.pressure("LEAF", NOW + 3_600_000L, 100, 7), 1e-9);
        assertEquals(0, r.pressure("LEAF", NOW + 2 * 3_600_000L, 100, 7));
        assertEquals(0, r.pressure("CHAOS", NOW, 100, 7));
        assertEquals(0, r.pressure("LEAF", NOW, 0, 7), "actPct 0: nobody acts on it");
        r.said(new Rumour("LEAF", Claim.RISE, false, NOW + 1, "y"));
        int acted = 0;
        for (long seed = 0; seed < 1000; seed++) {
            acted += r.pressure("LEAF", NOW + 1, 40, seed) != 0 ? 1 : 0;
        }
        assertTrue(acted > 330 && acted < 470, "about 40% of traders act: " + acted);
    }

    @Test
    void theLineBankIsCleanAndFillsEveryClaim() {
        MarketRumours.Lines yaml = MarketRumours.yaml();
        for (Claim c : Claim.values()) {
            List<String> lines = yaml.of(c);
            assertFalse(lines.isEmpty(), c + " has lines");
            for (String l : lines) {
                String filled = l.replace("{ITEM}", "leaves");
                assertFalse(filled.contains("{"), "unfilled token: " + l);
                assertNull(ContentGuard.problem(filled), filled);
                assertFalse(ContentGuard.aiTalk(filled), filled);
                String low = filled.toLowerCase(Locale.ROOT);
                for (String banned : List.of("bot", " ai ", "maplestory", "nexon", "discord")) {
                    assertFalse(low.contains(banned), banned + " in " + filled);
                }
                assertTrue(filled.length() <= 70, "short enough for chat: " + filled);
            }
        }
    }
}

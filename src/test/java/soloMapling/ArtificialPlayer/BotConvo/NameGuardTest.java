package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 S4: no made-up people in bot threads. Lines are real TEST thread lines (2026-10-23..25) unless noted. */
class NameGuardTest {

    /** Who exists in these tests: two bots and a player. */
    private static final NameGuard.Known WORLD = name -> Set.of("nayrb", "kimchi", "joshh")
            .contains(name.toLowerCase(Locale.ROOT));

    private static NameGuard.Verdict check(String line) {
        return NameGuard.check(line, List.of("Kimchi", "Bob"), WORLD);
    }

    @Test
    void madeUpNamesFromTestTranscriptsAreCaught() {
        String[][] cases = {
                {"did u see xxslayerxx try to ks me", "xxslayerxx"},
                {"Did you hear? xXBrawlerXx got scammed out of his Bamboo Sword.", "xXBrawlerXx"},
                {"dude did u c XXDLUFFYXX spammin hnessy?? total n00b xd", "XXDLUFFYXX"},
                {"I heard Stabbyman lost his Zakum Helmet during a trade.", "Stabbyman"},
                {"Did you see xSniperGodx flexing his new Maple Claw?", "xSniperGodx"},
                {"yo did u see xandyr spam cc smega last nite lol", "xandyr"},
                {"did u see sojubandit-nim hit lvl 90? kekeke ^^", "sojubandit"},
                {"did u see kittykat24 ditch us for that lvl 90 guild lol", "kittykat24"},
                {"Did you see LightPetal's new Zhelm? She's only level 52.", "LightPetal"},
        };
        for (String[] c : cases) {
            NameGuard.Verdict v = check(c[0]);
            assertEquals(List.of(c[1]), v.unknown(), c[0]);
            assertFalse(v.line() != null && v.line().contains(c[1]), "the name is gone: " + v.line());
        }
    }

    @Test
    void oneStrangerIsRewrittenTwoDropTheLine() {
        NameGuard.Verdict one = check("did u see xxslayerxx try to ks me");
        assertTrue(one.rewritten());
        assertEquals("did u see some guy try to ks me", one.line());
        assertEquals("Did you see some guy's new Zhelm? She's only level 52.",
                check("Did you see LightPetal's new Zhelm? She's only level 52.").line());
        NameGuard.Verdict two = check("Did you see PartyKing married RibbonQueen in Amoria?");
        assertTrue(two.dropped());
        assertEquals(List.of("PartyKing", "RibbonQueen"), two.unknown());
    }

    @Test
    void realPeopleTheSpeakersAndPlainChatPass() {
        for (String line : List.of("did u hear Joshh ksed Nayrb lol", "kimchi u coming to kpq?", "lf2m orbis pq need a cleric tbh",
                "pig beach xd lots of pgs to slay lolz!!!", "u will get owned tbh maybe stick to snails r ez",
                "Training at Ant Tunnel is slow. Want to try Coolie Zombies?", "did u see that nub spamming @go henesys in the fm lol",
                "OMG did u c XxNAYRBxX lol", "lvl30 sin lf party 1.2m each", "my fame dropped idk who did it")) {
            NameGuard.Verdict v = check(line);
            assertTrue(v.clean(), line + " -> " + v.unknown());
            assertEquals(line, v.line());
        }
    }

    @Test
    void theNamesTheCallerAllowsPass() {
        assertTrue(NameGuard.check("watch out for xXDarkLordZz, ksed Bob", List.of("xXDarkLordZz", "Bob"), n -> false).clean());
        assertFalse(NameGuard.check("watch out for xXDarkLordZz, ksed Bob", List.of("Bob"), n -> false).clean());
    }

    @Test
    void candidatesAreNameShapedTokensOnly() {
        assertEquals(List.of("xxslayerxx"), NameGuard.candidates("did u see xxslayerxx at pig beach"));
        assertEquals(List.of(), NameGuard.candidates("anyone selling a zhelm for 30m in ch1? lv70 fp lf party"));
    }

    /**
     * The measurement the slice asked for: the guard over every bot-thread line TEST said on 2026-10-23..25 (965
     * lines, 293 gossip). Who exists offline: the bot name pool (a superset of the names bots hold) and every bot that
     * spoke. 41 lines (39 of them gossip, 13%) named someone who does not exist; the guard rewrites 39 and drops 2.
     */
    @Test
    void testTranscriptsBeforeTheFixNamedPeopleWhoDoNotExist() throws Exception {
        List<NameGuardReport.Row> rows = NameGuardReport.read(Path.of("src/test/resources/chat/thread-lines-2026-10-23-25.jsonl"));
        NameGuardReport.Result r = NameGuardReport.measure(rows, NameGuardReport.poolNames());
        assertEquals(965, r.threadLines());
        assertEquals(293, r.gossipLines());
        assertTrue(r.flagged() >= 35 && r.flagged() <= 50, "flagged " + r.flagged() + ": " + r.examples());
        assertTrue(r.gossipFlagged() >= 33, "gossip flagged " + r.gossipFlagged());
        for (String madeUp : List.of("xSniperGodx", "xXBrawlerXx", "PartyKing", "Stabbyman", "xandyr", "xtremeer")) {
            assertTrue(r.strangers().containsKey(madeUp), madeUp + " in " + r.strangers());
        }
    }

    @Test
    void anEmptyLineIsLeftAlone() {
        assertNull(check(null).line());
        assertTrue(check("").clean());
    }
}

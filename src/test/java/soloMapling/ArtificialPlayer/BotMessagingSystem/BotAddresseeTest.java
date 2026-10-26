package soloMapling.ArtificialPlayer.BotMessagingSystem;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotAddresseeTest {

    private static final List<BotAddressee.Candidate> NEARBY = List.of(
            new BotAddressee.Candidate(1, "Ressurectme"), new BotAddressee.Candidate(2, "xXSkyBladeXx"),
            new BotAddressee.Candidate(3, "Kimchiz"), new BotAddressee.Candidate(4, "Jun1567"),
            new BotAddressee.Candidate(5, "Lolipop"), new BotAddressee.Candidate(6, "Nayrb"));

    private static BotAddressee.Result resolve(String line) {
        return BotAddressee.resolve(line, NEARBY);
    }

    @Test
    void shortenedAndMisspelledNamesReachTheirBot() {
        assertEquals(1, resolve("hey you Resme").best().id(), "letters in order");
        assertEquals(1, resolve("ressurect u there").best().id(), "a prefix");
        assertEquals(2, resolve("yo skyblade nice bow").best().id(), "the name without xX..Xx");
        assertEquals(2, resolve("sky can u buff").best().id(), "a short prefix of the core");
        assertTrue(resolve("sky can u buff").worthAsking(), "three letters is a guess");
        assertEquals(3, resolve("kimchi wanna party").best().id(), "a prefix");
        assertEquals(3, resolve("kimchix lol").best().id(), "a typo");
        assertEquals(4, resolve("jun where u training").best().id(), "the name without its digits");
        assertTrue(resolve("nayrb ur so bad").sure(), "an exact name, any case");
    }

    @Test
    void aTownInTheLineIsNotABotNamedAfterIt() {
        // TEST 2026-10-26 (t-cv-5): "Kerning PQ? i can come too" went to Kerning952 by name, not to the PQ thread
        List<BotAddressee.Candidate> town = List.of(new BotAddressee.Candidate(7, "Kerning952"),
                new BotAddressee.Candidate(8, "Ludi653"), new BotAddressee.Candidate(9, "HenesysBot"));
        assertNull(BotAddressee.resolve("Kerning PQ? i can come too", town).best());
        assertNull(BotAddressee.resolve("anyone for ludi pq", town).best());
        assertNull(BotAddressee.resolve("meet me in henesys", town).best());
        assertTrue(BotAddressee.resolve("kerning952 u there", town).sure(), "the whole name still works");
    }

    @Test
    void howSureItIsDecidesWhetherTheBotAsksFirst() {
        assertTrue(resolve("kimchiz u there").sure());
        BotAddressee.Result fuzzy = resolve("hey resme");
        assertFalse(fuzzy.sure());
        assertTrue(fuzzy.worthAsking(), "letters in order is a guess: the bot asks 'u talking to me?'");
    }

    @Test
    void ordinaryChatReachesNobody() {
        assertNull(resolve("lol anyone selling claws").best(), "'lol' is not Lolipop");
        assertNull(resolve("selling 60% glove att 3m").best());
        assertNull(resolve("brb mom says dinner").best());
        assertNull(resolve("ok").best());
    }

    @Test
    void twoGoodFitsAreAmbiguous() {
        List<BotAddressee.Candidate> twins = List.of(new BotAddressee.Candidate(1, "Kimchiz"),
                new BotAddressee.Candidate(2, "Kimchis"));
        BotAddressee.Result result = BotAddressee.resolve("kimchi u there", twins);
        assertTrue(result.ambiguous());
        assertFalse(result.sure());
    }
}

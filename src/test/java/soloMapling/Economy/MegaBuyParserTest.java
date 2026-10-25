package soloMapling.Economy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MegaBuyParserTest {

    private static MegaBuyParser.Request p(String s) {
        return MegaBuyParser.parse(s);
    }

    @Test
    void readsTheCommonBuyShapes() {
        MegaBuyParser.Request r = p("B> red whip 5m");
        assertEquals(MegaBuyParser.Kind.BUY, r.kind());
        assertEquals("red whip", r.query());
        assertEquals(5_000_000, r.price());
        assertEquals(0, r.qty());

        r = p("buying clean claw");
        assertEquals(MegaBuyParser.Kind.BUY, r.kind());
        assertEquals("claw", r.query(), "'clean' is a quality word, not part of the name");
        assertEquals(-1, r.price());

        r = p("lf ilbis");
        assertEquals(MegaBuyParser.Kind.BUY, r.kind());
        assertEquals(List.of("ilbis"), r.words());

        r = p("wtb 60% glove dex");
        assertEquals(MegaBuyParser.Kind.BUY, r.kind());
        assertEquals(List.of("60%", "glove", "dex"), r.words());
    }

    @Test
    void readsPricesAndQuantities() {
        assertEquals(900_000, p("wtb ilbis 900k").price());
        assertEquals(1_200_000, p("wtb ilbis 1.2m").price());
        assertEquals(1_200_000, p("wtb ilbis 1,200,000").price());
        assertEquals(300_000, p("B> ilbis 300000 pls").price());
        assertEquals(2_000_000_000L, p("wtb ilbis 2b").price());
        MegaBuyParser.Request r = p("wtb brown work glove x3 200k");
        assertEquals(3, r.qty());
        assertEquals("brown work glove", r.query());
        assertEquals(200_000, r.price());
        assertEquals(2, p("wtb 2 red whip").qty());
        assertEquals(-1, p("wtb lv 30 claw").price(), "a small bare number is no price");
    }

    @Test
    void readsSellsButTheyAreNotBuys() {
        MegaBuyParser.Request r = p("S> red whip 6m");
        assertEquals(MegaBuyParser.Kind.SELL, r.kind());
        assertEquals("red whip", r.query());
        assertEquals(MegaBuyParser.Kind.SELL, p("wts ilbis 1m pm me").kind());
        assertEquals(MegaBuyParser.Kind.SELL, p("selling clean claw").kind());
    }

    @Test
    void cutsTheTailPlayersAdd() {
        MegaBuyParser.Request r = p("B> red whip 5m | pm me");
        assertEquals("red whip", r.query());
        assertEquals("red whip", p("wtb red whip pm me pls").query());
    }

    @Test
    void ignoresWhatIsNotAShoppingList() {
        assertEquals(MegaBuyParser.Kind.NONE, p("lf pt for ludi pq").kind());
        assertEquals(MegaBuyParser.Kind.NONE, p("lf party").kind());
        assertEquals(MegaBuyParser.Kind.NONE, p("need help with the boss").kind());
        assertEquals(MegaBuyParser.Kind.NONE, p("hello everyone").kind());
        assertEquals(MegaBuyParser.Kind.NONE, p("").kind());
        assertEquals(MegaBuyParser.Kind.NONE, p(null).kind());
        assertEquals(MegaBuyParser.Kind.NONE, p("wtb").kind(), "no item words");
        assertEquals(MegaBuyParser.Kind.NONE, p("wtb 5m").kind(), "a price but no item");
        assertEquals(MegaBuyParser.Kind.NONE, p("wtb red whip or ilbis").kind(), "two items are not guessed");
        assertEquals(MegaBuyParser.Kind.NONE, p("wtb red whip, ilbis 5m").kind());
        assertEquals(MegaBuyParser.Kind.NONE, p("wtb red whip / ilbis").kind());
        assertEquals(MegaBuyParser.Kind.NONE, p("wtb " + "word ".repeat(40)).kind(), "a long rant is not an item");
    }
}

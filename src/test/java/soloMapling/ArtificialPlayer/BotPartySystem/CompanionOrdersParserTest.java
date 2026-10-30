package soloMapling.ArtificialPlayer.BotPartySystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Order;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Target;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Verb;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2 ticket 99: the companion command set (nutnnut/Cosmic BotChatManager/BotCommandParser, formations 8fd725404)
 * in the shared parser - read only when allowed, so with the switches off every line reads as before.
 */
class CompanionOrdersParserTest {

    private static final Target CLEO = new Target(1, "Cleo");
    private static final Target MAXX = new Target(2, "Maxx");
    private static final List<Target> PARTY = List.of(CLEO, MAXX);
    private static final Set<Verb> ALL = EnumSet.allOf(Verb.class);

    private static Order parse(String line) {
        return BotCommandParser.parse(line, PARTY, ALL).orElseThrow(() -> new AssertionError("not an order: " + line));
    }

    private static void chat(String line) {
        Optional<Order> o = BotCommandParser.parse(line, PARTY, ALL);
        assertTrue(o.isEmpty(), "read as an order: " + line + " -> " + o);
    }

    @Test
    void movementOrdersWithTargetsByNamePrefixAndSlot() {
        assertEquals(Verb.PATROL, parse("patrol").verb());
        assertEquals(Verb.PATROL, parse("roam around here pls").verb());
        assertEquals(Verb.SENTRY, parse("cleo sentry").verb());
        assertEquals(List.of(CLEO), parse("cleo sentry").targets());
        assertEquals(List.of(MAXX), parse("2 guard this spot").targets());
        assertEquals(List.of(MAXX), parse("ma camp").targets());
        Order move = parse("move here");
        assertEquals(Verb.MOVE_HERE, move.verb());
        assertTrue(move.everyone());
        assertEquals(Verb.MOVE_HERE, parse("go here").verb());
        assertEquals(List.of(CLEO), parse("move here cleo").targets());
    }

    @Test
    void formations() {
        assertEquals("SPREAD:60", parse("spread out").arg());
        assertEquals(Verb.FORMATION, parse("spread").verb());
        assertEquals("STACK:0", parse("stack up").arg());
        assertEquals("SPLIT:30", parse("tight").arg());
        assertEquals("SPLIT:120", parse("loose").arg());
        assertEquals("SPLIT:60", parse("split").arg());
        assertEquals("LEFT:80", parse("formation left 80").arg());
        assertEquals("RIGHT:30", parse("formation right tight").arg());
        assertEquals("RANDOM:60", parse("formation random").arg());
        assertEquals(Formation.SNAP, parse("snap").arg());
        assertEquals("RIGHT:150", parse("formation right 900").arg(), "the spacing is capped");
        chat("left"); // left/right/random need the word formation
        chat("i turned left");
        chat("formation");
    }

    @Test
    void tradeByCategoryMesosItemAndSlot() {
        assertEquals("pots", parse("trade pots").arg());
        assertEquals("pots", parse("trade me your pots").arg());
        assertEquals("pots:hp", parse("trade hp pots").arg());
        assertEquals("pots:mp", parse("trade me mana potions").arg());
        assertEquals("scrolls", parse("trade scrolls").arg());
        assertEquals("use", parse("trade use").arg());
        assertEquals("etc", parse("trade etc").arg());
        assertEquals("equips", parse("trade equips").arg());
        assertEquals("ammo", parse("trade arrows").arg());
        assertEquals("rocks", parse("trade rocks").arg());
        assertEquals("mesos:100000", parse("trade 100k").arg());
        assertEquals("mesos:1500000", parse("trade me 1.5m").arg());
        assertEquals("mesos:100000", parse("trade 100,000 mesos").arg());
        assertEquals("mesos:250000", parse("trade mesos 250k").arg());
        assertEquals("slot:hat", parse("trade hat").arg());
        assertEquals("name:red potion", parse("trade red potion").arg());
        Order targeted = parse("cleo trade me pots");
        assertEquals(List.of(CLEO), targeted.targets());
        assertEquals(Verb.TRADE, targeted.verb());
        assertEquals(List.of(MAXX), parse("trade elixir maxx").targets());
        assertEquals(Verb.TRADE, parse("give me pots").verb());
        chat("trade me");
        chat("trade");
        chat("trade later");
        chat("give me a sec");
        chat("trade mesos");
        chat("we should trade sometime");
    }

    @Test
    void showDropUpgradesRequestAndNeed() {
        Order show = parse("show me your hat");
        assertEquals(Verb.SHOW, show.verb());
        assertEquals("slot:hat", show.arg());
        assertEquals("slot:weapon", parse("let me see ur wep").arg());
        assertEquals("slot:shoes", parse("cleo show boots").arg());
        assertEquals(Verb.DROP, parse("drop etc").verb());
        assertEquals("pots", parse("drop your pots").arg());
        chat("drop 100k"); // mesos are traded, never dropped
        assertEquals(Verb.UPGRADES, parse("any upgrades?").verb());
        assertEquals(Verb.UPGRADES, parse("upgrades").verb());
        assertEquals(Verb.UPGRADES, parse("any better gear").verb());
        assertEquals(Verb.REQUEST, parse("request?").verb());
        assertEquals(Verb.REQUEST, parse("need anything?").verb());
        assertEquals(Verb.REQUEST, parse("what do you need").verb());
        assertEquals(Verb.TRADE_UPGRADES, parse("trade upgrades").verb());
        assertEquals(Verb.TRADE_UPGRADES, parse("maxx trade me upgrades").verb());
        assertEquals("pots", parse("need pots").arg());
        assertEquals("hp", parse("need hp pots").arg());
        assertEquals("mp", parse("low on mana").arg());
        assertEquals("ammo", parse("out of arrows").arg());
        assertEquals("rocks", parse("need rocks").arg());
        assertEquals("pots", parse("running low on pots").arg());
        chat("need a break");
        chat("need to go");
    }

    @Test
    void withTheSwitchesOffTheNewOrdersStayChat() {
        for (String line : List.of("patrol", "spread out", "trade pots", "show me your hat", "any upgrades?",
                "need pots", "cleo sentry", "move here")) {
            assertTrue(BotCommandParser.parse(line, PARTY).isEmpty(), line);
        }
        // and each switch opens only its own set
        assertTrue(BotCommandParser.parse("trade pots", PARTY, EnumSet.copyOf(BotCommandParser.MOVEMENT)).isEmpty());
        assertTrue(BotCommandParser.parse("patrol", PARTY, BotCommandParser.ITEMS).isEmpty());
        assertTrue(BotCommandParser.parse("need pots", PARTY, BotCommandParser.UPGRADE).isEmpty());
        assertEquals(Verb.NEED, BotCommandParser.parse("need pots", PARTY, BotCommandParser.SUPPLY).get().verb());
    }

    @Test
    void theTicket14OrdersReadTheSameWithEverythingAllowed() {
        assertEquals(Verb.FOLLOW, parse("cleo follow").verb());
        assertEquals(Verb.STAY, parse("1 stop").verb());
        assertEquals(Verb.GRIND, parse("grind").verb());
        chat("cleo follow the money lol");
        chat("wait what");
    }

    @Test
    void mesoAmounts() {
        assertEquals(100_000, BotCommandParser.mesoAmount("100k"));
        assertEquals(1_500_000, BotCommandParser.mesoAmount("1_5m"));
        assertEquals(100_000, BotCommandParser.mesoAmount("100_000"));
        assertEquals(2_000_000_000L, BotCommandParser.mesoAmount("2b"));
        assertEquals(0, BotCommandParser.mesoAmount("3b"), "past the meso cap");
        assertEquals(0, BotCommandParser.mesoAmount("pots"));
    }
}

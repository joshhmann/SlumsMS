package soloMapling.ArtificialPlayer.BotPartySystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Order;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Target;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Verb;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotCommandParserTest {

    private static final Target JASON = new Target(1, "Jason");
    private static final Target FIRE = new Target(2, "xFirex");
    private static final Target JANE = new Target(3, "Jane");
    private static final List<Target> PARTY = List.of(JASON, FIRE);

    private static Order parse(String line, List<Target> slots) {
        return BotCommandParser.parse(line, slots).orElseThrow(() -> new AssertionError("not an order: " + line));
    }

    private static void notAnOrder(String line, List<Target> slots) {
        Optional<Order> order = BotCommandParser.parse(line, slots);
        assertTrue(order.isEmpty(), "read as an order: " + line + " -> " + order);
    }

    @Test
    void theTicketsThreeOrders() {
        Order follow = parse("jason follow", PARTY);
        assertEquals(Verb.FOLLOW, follow.verb());
        assertEquals(List.of(JASON), follow.targets());

        Order stop = parse("1 stop", PARTY);
        assertEquals(Verb.STAY, stop.verb());
        assertEquals(List.of(JASON), stop.targets());

        Order grind = parse("grind", PARTY);
        assertEquals(Verb.GRIND, grind.verb());
        assertEquals(PARTY, grind.targets());
        assertTrue(grind.everyone());
    }

    @Test
    void targetsByFullNamePrefixPieceAndSlot() {
        assertEquals(List.of(FIRE), parse("xFirex, grind here", PARTY).targets());
        assertEquals(List.of(FIRE), parse("xf come", PARTY).targets());
        assertEquals(List.of(FIRE), parse("fire grind", PARTY).targets());
        assertEquals(List.of(FIRE), parse("2 wait here", PARTY).targets());
        assertEquals(List.of(JASON), parse("JASON FOLLOW ME", PARTY).targets());
    }

    @Test
    void theTargetMayComeLast() {
        Order order = parse("stop 2", PARTY);
        assertEquals(Verb.STAY, order.verb());
        assertEquals(List.of(FIRE), order.targets());
        assertEquals(List.of(JASON), parse("follow me jason", PARTY).targets());
    }

    @Test
    void wordsThatMeanEveryone() {
        assertTrue(parse("all stop", PARTY).everyone());
        assertTrue(parse("guys follow me", PARTY).everyone());
        assertTrue(parse("hey everyone, come here pls", PARTY).everyone());
        assertTrue(parse("lets grind", PARTY).everyone());
        assertTrue(parse("train here", PARTY).everyone());
    }

    @Test
    void anAmbiguousNameAsksWhichOne() {
        List<Target> slots = List.of(JASON, FIRE, JANE);
        Order order = parse("ja follow", slots);
        assertTrue(order.isAmbiguous());
        assertEquals(List.of(JASON, JANE), order.ambiguous());
        assertEquals("which one? 1 Jason / 3 Jane", BotCommandParser.whichOne(order.ambiguous(), slots));
        assertEquals(List.of(JANE), parse("jane follow", slots).targets()); // the full name settles it
        assertEquals(List.of(JANE), parse("3 follow", slots).targets());
    }

    @Test
    void naturalChatStaysNaturalChat() {
        notAnOrder("jason follow the money lol", PARTY);
        notAnOrder("wait what", PARTY);
        notAnOrder("where do u train", PARTY);
        notAnOrder("jason party", PARTY);
        notAnOrder("jason trade", PARTY);
        notAnOrder("hey jason", PARTY);
        notAnOrder("we grind", List.of(new Target(5, "Wendy")));
        notAnOrder("bob follow", PARTY); // not in the party
        notAnOrder("3 stop", PARTY); // no third bot
        notAnOrder("12 stop", PARTY);
        notAnOrder("grind", List.of()); // no party bots, no orders
    }

    @Test
    void aBotNamedLikeAWordIsStillABot() {
        Target hey = new Target(9, "Hey");
        assertEquals(List.of(hey), parse("hey stop", List.of(JASON, hey)).targets());
        Target all = new Target(10, "All");
        Order order = parse("all stop", List.of(JASON, all));
        assertFalse(order.everyone());
        assertEquals(List.of(all), order.targets());
    }
}

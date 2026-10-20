package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Order;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Target;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.checkIfInvisibleBot;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById;

/**
 * Ticket 14: a party member's chat order to the party's bots ({@link BotCommandParser}) carried out - party bots
 * only; a bot outside the player's party never takes an order, so "jason follow" to a stranger stays natural chat.
 * Bots answer in chat bubbles; nothing here opens a window.
 */
public final class PartyBotOrders {

    private PartyBotOrders() {
    }

    /** The bots in this player's party, in party order: slot 1 is the first bot (real players take no slot). */
    public static List<Target> slots(Character player) {
        Party party = player == null ? null : player.getParty();
        if (party == null) {
            return List.of();
        }
        List<Target> slots = new ArrayList<>();
        for (PartyCharacter pc : party.getPartyMembers()) {
            if (pc == null || pc.getId() == player.getId() || checkIfInvisibleBot(pc.getId())) {
                continue;
            }
            BotSM bot = getBotById(pc.getId());
            if (bot != null && bot.getChr() != null) {
                slots.add(new Target(pc.getId(), bot.getChr().getName()));
            }
        }
        return slots;
    }

    /** The order in a party member's line, if it is one. */
    public static Optional<Order> read(Character player, String line) {
        List<Target> slots = slots(player);
        return BotCommandParser.parse(line, slots);
    }

    /** Carries an order out: each target bot that can, does it and says so; an ambiguous name gets "which one?". */
    public static void carryOut(Character player, Order order) {
        if (order.isAmbiguous()) {
            BotSM asker = getBotById(order.ambiguous().get(0).id());
            if (asker != null && asker.getChr() != null) {
                BotSpeak(asker.getChr(), BotCommandParser.whichOne(order.ambiguous(), slots(player)));
            }
            return;
        }
        for (Target target : order.targets()) {
            BotSM bot = getBotById(target.id());
            if (bot instanceof FollowerBot follower) {
                follower.order(order.verb(), player, order.everyone());
            } else if (bot instanceof TrainingBot trainer) {
                trainer.order(order.verb(), player, order.everyone());
            }
        }
    }
}

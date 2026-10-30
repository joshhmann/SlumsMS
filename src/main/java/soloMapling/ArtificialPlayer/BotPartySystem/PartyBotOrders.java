package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Order;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Target;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Verb;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.companions.OwnedAltOrders;
import soloMapling.companions.OwnedAltService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.checkIfInvisibleBot;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById;

/**
 * Ticket 14: a party member's chat order to the party's bots ({@link BotCommandParser}) carried out - party bots
 * only; a bot outside the player's party never takes an order, so "jason follow" to a stranger stays natural chat.
 * Bots answer in chat bubbles; nothing here opens a window.
 *
 * <p>1.6.2 ticket 99: the player's own owned alts in the party take orders too (when a companion switch is on), in
 * party order with the bot friends, and every companion takes the same orders: movement and formations
 * ({@link CompanionStance}), and - for companions that can hand things over, which today are owned alts - item
 * orders ({@link CompanionItems}). One implementation; only who obeys and how it talks differ (FollowerBot /
 * TrainingBot for bot friends, {@link OwnedAltOrders} for alts). Item orders may open a trade window, never
 * anything else.
 */
public final class PartyBotOrders {

    private PartyBotOrders() {
    }

    /**
     * The bots in this player's party, in party order: slot 1 is the first bot (real players take no slot). With a
     * companion switch on, the player's own owned alts count as party bots too.
     */
    public static List<Target> slots(Character player) {
        return slots(player, ownAlts(player));
    }

    private static List<Target> slots(Character player, Map<Integer, Character> alts) {
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
            } else if (alts.containsKey(pc.getId())) {
                slots.add(new Target(pc.getId(), alts.get(pc.getId()).getName()));
            }
        }
        return slots;
    }

    /** The player's own owned alts that are out (id -> alt), when a companion switch is on; else none. */
    static Map<Integer, Character> ownAlts(Character player) {
        Map<Integer, Character> out = new HashMap<>();
        if (player == null || !CompanionSwitches.any()) {
            return out;
        }
        try {
            for (OwnedAltService.Active a : OwnedAltService.getInstance().active()) {
                if (a.owner().getId() == player.getId()) {
                    out.put(a.alt().getId(), a.alt());
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return out;
        }
        return out;
    }

    /** The order in a party member's line, if it is one. */
    public static Optional<Order> read(Character player, String line) {
        Map<Integer, Character> alts = ownAlts(player);
        List<Target> slots = slots(player, alts);
        return BotCommandParser.parse(line, slots, CompanionSwitches.allowed(!alts.isEmpty()));
    }

    /**
     * 1.6.2 ticket 99: an order said in party chat (where a player talks to their party) - read and carried out as
     * the same order said aloud. Only with a companion switch on; true when the line was an order.
     */
    public static boolean fromPartyChat(Character player, String line) {
        if (!CompanionSwitches.any()) {
            return false;
        }
        Optional<Order> order = read(player, line);
        if (order.isEmpty()) {
            return false;
        }
        soloMapling.server.ExecutorServiceManager.runAsync(() -> carryOut(player, order.get()));
        return true;
    }

    /** Carries an order out: each target bot that can, does it and says so; an ambiguous name gets "which one?". */
    public static void carryOut(Character player, Order order) {
        Map<Integer, Character> alts = ownAlts(player);
        if (order.isAmbiguous()) {
            say(order.ambiguous().get(0).id(), alts, BotCommandParser.whichOne(order.ambiguous(), slots(player, alts)));
            return;
        }
        Verb verb = order.verb();
        if (verb == Verb.FORMATION) {
            formation(player, order, alts);
            return;
        }
        if (BotCommandParser.ITEMS.contains(verb) || BotCommandParser.UPGRADE.contains(verb)
                || BotCommandParser.SUPPLY.contains(verb)) {
            List<CompanionItems.Companion> companions = new ArrayList<>();
            List<Target> who = order.everyone() ? slots(player, alts) : order.targets();
            for (Target t : who) {
                Character alt = alts.get(t.id());
                if (alt != null) {
                    companions.add(OwnedAltOrders.companion(alt));
                }
            }
            CompanionItems.carryOut(player, order, companions);
            return;
        }
        for (Target target : order.targets()) {
            Character alt = alts.get(target.id());
            if (alt != null) {
                OwnedAltOrders.order(alt, player, verb, order.everyone());
                continue;
            }
            BotSM bot = getBotById(target.id());
            if (bot instanceof FollowerBot follower) {
                follower.order(verb, player, order.everyone());
            } else if (bot instanceof TrainingBot trainer) {
                trainer.order(verb, player, order.everyone());
            }
        }
    }

    /**
     * A formation (nutnnut/Cosmic 8fd725404): the leader's formation is set and each companion takes its place, in
     * slot order; "snap" sends every companion back to following at its place now. The first one says so.
     */
    private static void formation(Character player, Order order, Map<Integer, Character> alts) {
        boolean snap = Formation.SNAP.equals(order.arg());
        Formation f = snap ? CompanionStance.formation(player.getId()) : Formation.fromArg(order.arg());
        if (f == null) {
            return;
        }
        CompanionStance.setFormation(player.getId(), f);
        List<Target> slots = slots(player, alts);
        for (int i = 0; i < slots.size(); i++) {
            int id = slots.get(i).id();
            Character c = alts.get(id);
            if (c == null) {
                BotSM bot = getBotById(id);
                c = bot == null ? null : bot.getChr();
                if (snap && bot instanceof FollowerBot follower) {
                    follower.order(Verb.FOLLOW, player, true);
                }
            } else if (snap) {
                CompanionStance.set(id, CompanionStance.Mode.FOLLOW, null, null, -1);
            }
            if (c != null) {
                GCMovement.setFollowOffset(c, f.offsetFor(i));
            }
        }
        if (!slots.isEmpty()) {
            say(slots.get(0).id(), alts, snap ? "k, back in formation" : "k, " + f.said());
        }
    }

    /** A party bot or an alt says a line (a bubble for a bot friend, party chat for an alt). */
    private static void say(int id, Map<Integer, Character> alts, String line) {
        Character alt = alts.get(id);
        if (alt != null) {
            OwnedAltOrders.say(alt, line);
            return;
        }
        BotSM bot = getBotById(id);
        if (bot != null && bot.getChr() != null) {
            BotSpeak(bot.getChr(), line);
        }
    }
}

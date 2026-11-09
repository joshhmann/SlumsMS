package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Order;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Target;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Verb;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotMessagingSystem.BotAddressee;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.companions.CompanionLootMode;
import soloMapling.companions.CompanionSellJunk;
import soloMapling.companions.CompanionSettings;
import soloMapling.companions.CompanionSettingsStore;
import soloMapling.companions.OwnedAltOrders;
import soloMapling.companions.OwnedAltService;
import soloMapling.menus.CompanionCardMenu;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
        Set<Verb> allowed = EnumSet.copyOf(CompanionSwitches.allowed(!alts.isEmpty()));
        if (reportsReadable(alts)) {
            allowed.addAll(BotCommandParser.REPORTS); // 1.6.7: "pots", "stats", "slots"
        }
        if (settingsReadable(alts)) {
            allowed.addAll(BotCommandParser.SETTINGS); // 1.6.7 alt-settings: "heals off", "pots at 60", "card"
        }
        if (lootReadable(alts)) {
            allowed.addAll(BotCommandParser.LOOT); // 1.6.7 group D: "loot everything", "sell junk", "offers off"
        }
        return BotCommandParser.parse(line, slots, allowed);
    }

    /**
     * 1.6.7 alt-settings: read only when a companion would take the switch - when the switch is on for one of the
     * player's alts, or for party regulars (-Dslums.companion.settings.regulars=true). With the defaults and no alts,
     * "heals off" and "pots at 60" stay ordinary chat.
     */
    private static boolean settingsReadable(Map<Integer, Character> alts) {
        for (Character alt : alts.values()) {
            if (PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.SETTINGS, alt)) {
                return true;
            }
        }
        return Boolean.getBoolean(PartyCompanionPolicy.Feature.SETTINGS.property(false));
    }

    /**
     * 1.6.7 alt-loot: read only when a companion would take it - when the loot switch is on for one of the player's
     * alts, or for party regulars (-Dslums.companion.loot.regulars=true). With the defaults and no alts, "loot
     * everything" stays ordinary chat.
     */
    private static boolean lootReadable(Map<Integer, Character> alts) {
        for (Character alt : alts.values()) {
            if (PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.LOOT, alt)) {
                return true;
            }
        }
        return Boolean.getBoolean(PartyCompanionPolicy.Feature.LOOT.property(false));
    }

    /**
     * 1.6.7 reports: read only when a companion would answer - when the switch is on for one of the player's alts,
     * or for party regulars (-Dslums.companion.reports.regulars=true). With the defaults and no alts, "pots" stays
     * ordinary chat.
     */
    private static boolean reportsReadable(Map<Integer, Character> alts) {
        for (Character alt : alts.values()) {
            if (PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.CHAT_REPORTS, alt)) {
                return true;
            }
        }
        return Boolean.getBoolean(PartyCompanionPolicy.Feature.CHAT_REPORTS.property(false));
    }

    /**
     * 1.6.7 reports: "did you mean 'grind'?" for a near-miss of an order, only when the line is clearly addressed to
     * a companion - {@code addressed} for an "@alts" line, else the line has to open with a companion's name (the
     * same name rules chat routing uses). True when a companion said it, so ordinary party chat stays chat.
     */
    public static boolean typoHint(Character player, String line, boolean addressed) {
        if (player == null || line == null || line.isBlank()) {
            return false;
        }
        Map<Integer, Character> alts = ownAlts(player);
        List<Target> slots = slots(player, alts);
        if (slots.isEmpty()) {
            return false;
        }
        Character speaker;
        if (addressed) {
            speaker = companion(slots.get(0).id(), alts);
        } else {
            List<BotAddressee.Candidate> names = new ArrayList<>();
            for (Target t : slots) {
                names.add(new BotAddressee.Candidate(t.id(), t.name()));
            }
            BotAddressee.Match match = CompanionReports.addressedTo(line, names);
            speaker = match == null ? null : companion(match.id(), alts);
        }
        if (speaker == null
                || !PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.CHAT_REPORTS, speaker)) {
            return false;
        }
        List<String> ignore = new ArrayList<>();
        for (Target t : slots) {
            ignore.add(t.name().toLowerCase(Locale.ROOT));
        }
        Optional<String> hint = CompanionReports.typoSuggestion(line, ignore);
        if (hint.isEmpty()) {
            return false;
        }
        say(speaker.getId(), alts, "did you mean '" + hint.get() + "'?");
        return true;
    }

    /**
     * 1.6.2 ticket 99: an order said in party chat (where a player talks to their party) - read and carried out as
     * the same order said aloud. Only with a companion switch on; true when the line was an order.
     */
    public static boolean fromPartyChat(Character player, String line) {
        if (!CompanionSwitches.any()) {
            return false;
        }
        if (soloMapling.companions.CompanionUpgradeOffer.answer(player, line)) {
            return true; // 1.6.7 group D: the owner's yes to a companion's "found a <item>, want it?"
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
        if (verb == Verb.LANES) {
            lanes(player, order, alts);
            return;
        }
        if (verb == Verb.FORMATION) {
            formation(player, order, alts);
            return;
        }
        if (BotCommandParser.REPORTS.contains(verb)) {
            // 1.6.7: an answer to the player who asked, from the companions that can answer it
            List<Target> who = order.everyone() ? slots(player, alts) : order.targets();
            for (Target t : who) {
                Character c = companion(t.id(), alts);
                if (c == null) {
                    continue;
                }
                if (CompanionReports.answer(c, verb, line -> say(t.id(), alts, line))) {
                    if (order.everyone()) {
                        break; // an untargeted report: the first companion that can answer speaks
                    }
                }
            }
            return;
        }
        if (BotCommandParser.SETTINGS.contains(verb)) {
            settings(player, order, alts); // 1.6.7 alt-settings
            return;
        }
        if (BotCommandParser.LOOT.contains(verb)) {
            loot(player, order, alts); // 1.6.7 group D: the loot mode, "sell junk", "offers on|off"
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
            } else {
                continue;
            }
            // 1.6.7 reports: a regular says its own acknowledgement inside its bot class, so the low-pots note is a
            // short line of its own here - off by default for regulars, so nobody hears a new line unless asked
            if ((verb == Verb.GRIND || verb == Verb.FOLLOW) && bot.getChr() != null
                    && PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.CHAT_REPORTS, bot.getChr())) {
                String note = CompanionReports.lowLine(bot.getChr());
                if (!note.isEmpty()) {
                    say(target.id(), alts, note);
                }
            }
        }
    }

    /**
     * 1.6.7 alt-settings: "heals off", "support on", "buff pots off", "pots at 60", "mp pots at 40" save the switch
     * for the companion (or companions) the line names and one of them answers with the state now in force; "card"
     * opens that companion's card for the player. A companion the switch is off for takes nothing and says nothing.
     */
    private static void settings(Character player, Order order, Map<Integer, Character> alts) {
        if (player == null) {
            return;
        }
        List<Target> who = order.everyone() ? slots(player, alts) : order.targets();
        if (order.verb() == Verb.CARD) {
            for (Target t : who) {
                Character c = companion(t.id(), alts);
                if (c != null && PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.SETTINGS, c)) {
                    CompanionCardMenu.open(player, c);
                    return; // one card at a time; the window is the player's
                }
            }
            return;
        }
        int speaker = -1;
        String said = null;
        for (Target t : who) {
            Character c = companion(t.id(), alts);
            if (c == null || !PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.SETTINGS, c)) {
                continue;
            }
            String reply = apply(player, c, order.verb(), order.arg());
            if (reply != null && speaker < 0) {
                speaker = t.id();
                said = reply;
            }
        }
        if (speaker >= 0) {
            say(speaker, alts, said);
        }
    }

    /**
     * 1.6.7 alt-loot: "loot everything|my kills|quest|nothing|like me" saves the mode for the companion(s) the line
     * names and one of them answers; "sell junk" (and "sell junk equips too") is carried out for each named companion
     * by {@link CompanionSellJunk}, which never runs by itself; "offers on|off" saves the upgrade-offer switch. A
     * companion the loot switch is off for takes nothing and says nothing.
     */
    private static void loot(Character player, Order order, Map<Integer, Character> alts) {
        if (player == null) {
            return;
        }
        List<Target> who = order.everyone() ? slots(player, alts) : order.targets();
        int speaker = -1;
        String said = null;
        for (Target t : who) {
            Character c = companion(t.id(), alts);
            if (c == null || !PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.LOOT, c)) {
                continue;
            }
            if ((order.verb() == Verb.SELL_JUNK || order.verb() == Verb.OFFERS) && !ownAlt(player, c)) {
                continue; // wave 4 audit L5: an owned alt's order, from its own owner only (regulars: 1.7.1)
            }
            if (order.verb() == Verb.SELL_JUNK) {
                CompanionSellJunk.order(c, player, "equips".equals(order.arg()), line -> say(t.id(), alts, line));
                continue;
            }
            String reply = apply(player, c, order.verb(), order.arg());
            if (reply != null && speaker < 0) {
                speaker = t.id();
                said = reply;
            }
        }
        if (speaker >= 0) {
            say(speaker, alts, said);
        }
    }

    /** Whether {@code c} is an owned alt and {@code player} is its owner. */
    static boolean ownAlt(Character player, Character c) {
        if (player == null || c == null || !c.isOwnedAlt()) {
            return false;
        }
        Character owner = c.getOwnedAltOwner();
        return owner != null && owner.getId() == player.getId();
    }

    /**
     * Saves one switch or potion line for one companion and answers the line it says. The number is clamped by the
     * store (10..90), and the answer carries what was stored, so "pots at 5" says what it became.
     */
    static String apply(Character owner, Character companion, Verb verb, String arg) {
        CompanionSettings current = CompanionSettingsStore.get(owner.getId(), companion.getId());
        CompanionSettings next;
        switch (verb) {
            case HEALS -> next = current.withHeals(!"off".equals(arg));
            case SUPPORT -> next = current.withSupport(!"off".equals(arg));
            case BUFF_POTS -> next = current.withBuffPots(!"off".equals(arg));
            case HP_POTS -> next = current.withHpPercent(number(arg, current.hpPercent()));
            case MP_POTS -> next = current.withMpPercent(number(arg, current.mpPercent()));
            case LOOT -> next = current.withLootMode(arg);
            case OFFERS -> next = current.withOffers(!"off".equals(arg));
            default -> {
                return null;
            }
        }
        CompanionSettings saved = CompanionSettingsStore.save(owner, companion, next);
        return said(verb, saved);
    }

    private static int number(String arg, int fallback) {
        try {
            return arg == null ? fallback : Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** What a companion answers to a switch: the state now in force. */
    private static String said(Verb verb, CompanionSettings s) {
        return switch (verb) {
            case HEALS -> s.heals() ? "heals on" : "heals off";
            case SUPPORT -> s.support() ? "party buffs on" : "party buffs off";
            case BUFF_POTS -> s.buffPots() ? "buff pots on" : "buff pots off";
            case HP_POTS -> "k, i'll pot at " + s.hpPercent() + "%";
            case MP_POTS -> "k, i'll drink mp at " + s.mpPercent() + "%";
            case LOOT -> {
                CompanionLootMode mode = CompanionLootMode.parse(s.lootMode());
                yield mode == null ? "k" : "k, " + mode.said();
            }
            case OFFERS -> s.offers() ? "offers on" : "offers off";
            default -> null;
        };
    }

    /**
     * 1.6.3 s-lanes (-Dslums.alts.lanes=true): the companions spread out over lanes of their own instead of stacking
     * on one sentry spot. {@link CompanionLanes} hands the lanes out from the map's mob spawn points for the whole
     * roster on this map, left to right in party order, so a later targeted "lanes" takes a lane nobody else has;
     * each ordered companion then holds its lane like a sentry. A lane belongs to the map it was given on - another
     * map and the companion follows again - and a narrow map gives fewer lanes than companions (the rest follow).
     * The reach is the order's own word ("lanes map" / "lanes screen"), or else the switch's default: the automatic
     * rule ({@link CompanionLanes.Reach#AUTO}), a screen for everybody with only the companions a screen cannot seat
     * going over the whole map - each lane carries its own reach, so only those get the wider gate.
     */
    private static void lanes(Character player, Order order, Map<Integer, Character> alts) {
        if (player == null || !CompanionSwitches.lanes()) {
            return;
        }
        CompanionLanes.Reach reach = CompanionLanes.Reach.parse(order.arg());
        if (reach == null) {
            reach = CompanionLanes.Reach.configured();
        }
        // the plan covers every companion standing on this map, not only the ones this order names: the spots stay
        // each companion's own whichever way the order was given (party order decides who stands where)
        List<Target> roster = new ArrayList<>();
        Map<Integer, Character> companions = new HashMap<>();
        for (Target t : slots(player, alts)) {
            Character c = companion(t.id(), alts);
            if (c != null && c.getMap() != null && c.getMap() == player.getMap()) {
                roster.add(t);
                companions.put(t.id(), c);
            }
        }
        List<Target> who = order.everyone() ? roster : order.targets();
        if (roster.isEmpty()) {
            say(order.targets().isEmpty() ? -1 : order.targets().get(0).id(), alts, "no one of yours is on this map");
            return;
        }
        List<CompanionLanes.Lane> plan = CompanionLanes.lanes(player.getMap(), player.getPosition(), reach,
                roster.size());
        if (plan.isEmpty()) {
            say(roster.get(0).id(), alts, "no room for lanes here");
            return;
        }
        int here = 0;
        int across = 0;
        int speaker = -1;
        for (Target t : who) {
            Character c = companions.get(t.id());
            int idx = roster.indexOf(t);
            if (c == null || idx < 0 || idx >= plan.size()) {
                continue; // not on this map, or no lane left for it: it keeps following
            }
            CompanionLanes.Lane lane = plan.get(idx);
            CompanionStance.setAt(c.getId(), CompanionStance.Mode.LANES, lane.spot(), player.getMapId(),
                    lane.reach());
            BotSM bot = getBotById(c.getId());
            if (bot instanceof FollowerBot follower) {
                follower.order(Verb.LANES, player, order.everyone());
            }
            if (lane.reach() == CompanionLanes.Reach.MAP) {
                across++;
            } else {
                here++;
            }
            if (speaker < 0) {
                speaker = c.getId();
            }
        }
        if (speaker < 0) {
            return;
        }
        say(speaker, alts, lanesSaid(here, across, here + across < who.size()));
    }

    /**
     * What the companions answer to a lanes order: how many hold a lane on this screen and how many across the map,
     * so a player can tell that a party too big for one screen was split ("2 lanes here, 1 across the map").
     */
    static String lanesSaid(int here, int across, boolean noRoomForMore) {
        String said;
        if (across == 0) {
            said = laneCount(here) + " spread out";
        } else if (here == 0) {
            said = laneCount(across) + " across the map";
        } else {
            said = laneCount(here) + " here, " + across + " across the map";
        }
        return "k, " + said + (noRoomForMore ? " (no room for more)" : "");
    }

    private static String laneCount(int lanes) {
        return lanes == 1 ? "1 lane" : lanes + " lanes";
    }

    /** The Character behind a party slot: an owned alt, or a party bot friend. */
    private static Character companion(int id, Map<Integer, Character> alts) {
        Character alt = alts.get(id);
        if (alt != null) {
            return alt;
        }
        BotSM bot = getBotById(id);
        return bot == null ? null : bot.getChr();
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

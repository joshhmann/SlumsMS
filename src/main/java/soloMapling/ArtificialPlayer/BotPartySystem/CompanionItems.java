package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import soloMapling.ArtificialPlayer.BotGearSystem.GearUpgrades;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Order;
import soloMapling.ArtificialPlayer.BotSupplySystem.SupplyShare;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade.Give;
import soloMapling.ArtificialPlayer.BotTradeSystem.TradeCategories;
import soloMapling.ArtificialPlayer.BotTradeSystem.TradePacing;
import soloMapling.server.BotTiming;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 1.6.2 ticket 99: the item orders a companion carries out for the player it follows - after nutnnut/Cosmic
 * {@code BotChatManager} / {@code BotInventoryManager} / {@code BotOfferManager} / {@code BotPotionManager}: trade a
 * category, show a worn item, drop a category, "any upgrades?", "request?", "trade upgrades", "need pot". One
 * implementation for every companion that can hand things over ({@link BotGiveTrade#canGive}); the caller says who
 * the companions are and how each one talks ({@link Companion}). Everything changes hands through a normal trade
 * window ({@link BotGiveTrade}) or the ground (a normal drop); nothing is teleported.
 *
 * <p>Wording: a companion talks like the player's friend, never about being anything else.
 */
public final class CompanionItems {

    /** One companion that can be asked: its character and its voice (party chat for an alt, a bubble for a bot). */
    public record Companion(Character self, Consumer<String> say) {
        void tell(String line) {
            if (say != null && line != null) {
                say.accept(line);
            }
        }
    }

    static final int MAX_UPGRADE_LINES = 3;

    private static final List<String> OPEN_LINES = List.of("k, inv u", "sure, one sec", "ok opening trade",
            "sec, inv u");
    private static final List<String> DONE_LINES = List.of("there u go", "all yours", "that's it", "here");

    private CompanionItems() {
    }

    /** Ticket 99: carry out an item order of {@code leader}'s for these companions (slot order). */
    public static void carryOut(Character leader, Order order, List<Companion> companions) {
        if (leader == null || order == null || companions.isEmpty()) {
            return;
        }
        switch (order.verb()) {
            case TRADE, SHOW, DROP, TRADE_UPGRADES -> firstWithSomething(leader, order, companions);
            case UPGRADES -> upgrades(leader, companions, order.everyone());
            case REQUEST -> request(leader, companions, order.everyone());
            case NEED -> need(leader, order.arg(), companions);
            default -> {
            }
        }
    }

    // ── trade / show / drop / trade upgrades: one companion acts ──

    private static void firstWithSomething(Character leader, Order order, List<Companion> companions) {
        Companion quiet = null;
        for (Companion c : companions) {
            if (!BotGiveTrade.canGive(c.self())) {
                continue;
            }
            if (quiet == null) {
                quiet = c;
            }
            if (has(c.self(), leader, order)) {
                act(c, leader, order, companions);
                return;
            }
        }
        if (quiet != null) {
            quiet.tell(nothingLine(order));
        }
    }

    static boolean has(Character self, Character leader, Order order) {
        String arg = order.arg();
        return switch (order.verb()) {
            case TRADE -> TradeCategories.mesos(arg) > 0 ? self.getMeso() > 0 : !TradeCategories.select(self, arg).isEmpty();
            case DROP -> !TradeCategories.select(self, arg).isEmpty();
            case SHOW -> worn(self, arg) != null;
            case TRADE_UPGRADES -> !GearUpgrades.offers(self, List.of(leader)).isEmpty();
            default -> false;
        };
    }

    static String nothingLine(Order order) {
        return switch (order.verb()) {
            case SHOW -> "i'm not wearing a " + TradeCategories.said(order.arg());
            case TRADE_UPGRADES -> "nothing that'd help you rn";
            default -> TradeCategories.mesos(order.arg()) > 0 ? "i'm broke lol"
                    : "i don't have any " + TradeCategories.said(order.arg());
        };
    }

    private static void act(Companion c, Character leader, Order order, List<Companion> all) {
        Character self = c.self();
        switch (order.verb()) {
            case TRADE -> {
                int mesos = TradeCategories.mesos(order.arg());
                if (mesos > 0) {
                    int have = self.getMeso();
                    if (have < mesos) {
                        c.tell("i only have " + have + ", here");
                    }
                    give(c, leader, List.of(), Math.min(mesos, have), null);
                    return;
                }
                List<Give> items = TradeCategories.select(self, order.arg());
                if (items.size() > BotGiveTrade.WINDOW_SLOTS) {
                    c.tell("that's more than fits, here's the first " + BotGiveTrade.WINDOW_SLOTS
                            + " - ask again for the rest");
                }
                give(c, leader, items, 0, null);
            }
            case SHOW -> show(c, leader, order.arg());
            case DROP -> drop(c, order.arg());
            case TRADE_UPGRADES -> tradeUpgrades(c, leader, all);
            default -> {
            }
        }
    }

    private static boolean give(Companion c, Character to, List<Give> items, int mesos,
                                Consumer<BotGiveTrade.End> after) {
        boolean started = BotGiveTrade.start(new BotGiveTrade.Plan(c.self(), to, items, mesos, pick(DONE_LINES),
                c.say(), after));
        c.tell(started ? pick(OPEN_LINES) : "can't trade right now, try again in a bit");
        return started;
    }

    // ── show me your hat ──

    static Item worn(Character self, String slotArg) {
        if (slotArg == null || !slotArg.startsWith("slot:")) {
            return null;
        }
        Short slot = TradeCategories.SLOTS.get(slotArg.substring(5));
        return slot == null ? null : self.getInventory(InventoryType.EQUIPPED).getItem(slot);
    }

    /**
     * Takes the worn piece off into its bag (a normal unequip), shows it in a trade window, and puts it back on when
     * the window closes without the trade (it is the player's to take if they press Trade: it is their alt's).
     */
    private static void show(Companion c, Character leader, String slotArg) {
        Character self = c.self();
        Item on = worn(self, slotArg);
        if (on == null) {
            c.tell("i'm not wearing a " + TradeCategories.said(slotArg));
            return;
        }
        Inventory bag = self.getInventory(InventoryType.EQUIP);
        short free = bag.getNextFreeSlot();
        if (free < 0) {
            c.tell("my bag's full, can't take it off to show u");
            return;
        }
        short bodySlot = on.getPosition();
        InventoryManipulator.unequip(self.getClient(), bodySlot, free);
        Item off = bag.getItem(free);
        if (off == null) {
            c.tell("hm, can't get it off rn");
            return;
        }
        Give g = new Give(InventoryType.EQUIP, off, (short) 1);
        boolean started = BotGiveTrade.start(new BotGiveTrade.Plan(self, leader, List.of(g), 0,
                "here's my " + TradeCategories.said(slotArg), c.say(), how -> putBack(self, off, bodySlot)));
        if (started) {
            c.tell("sure, inv u");
        } else {
            putBack(self, off, bodySlot);
            c.tell("can't trade right now, try again in a bit");
        }
    }

    /** The shown piece is still in the bag (the window closed without the trade): it goes back on. */
    static void putBack(Character self, Item piece, short bodySlot) {
        Inventory bag = self.getInventory(InventoryType.EQUIP);
        if (bag.getItem(piece.getPosition()) == piece && self.getInventory(InventoryType.EQUIPPED).getItem(bodySlot) == null) {
            InventoryManipulator.equip(self.getClient(), piece.getPosition(), bodySlot);
        }
    }

    // ── drop ──

    /** Drops a category on the ground at a person's pace (one stack a beat), at most a window's worth. */
    private static void drop(Companion c, String category) {
        Character self = c.self();
        List<Give> items = TradeCategories.select(self, category);
        List<Give> batch = items.subList(0, Math.min(BotGiveTrade.WINDOW_SLOTS, items.size()));
        c.tell("k, dropping my " + TradeCategories.said(category));
        Random rng = new Random(ThreadLocalRandom.current().nextLong());
        long at = 0;
        for (Give g : List.copyOf(batch)) {
            at += TradePacing.stepMs(rng);
            BotTiming.after(at, () -> {
                Item now = self.getInventory(g.type()).getItem(g.item().getPosition());
                if (now == g.item() && self.getMap() != null) {
                    InventoryManipulator.drop(self.getClient(), g.type(), g.item().getPosition(), g.quantity());
                }
            });
        }
    }

    // ── upgrades ──

    /** The other companions (siblings) of a companion that could take gear from it. */
    private static List<Character> siblings(Companion c, List<Companion> all) {
        List<Character> out = new ArrayList<>();
        for (Companion other : all) {
            if (other.self() != c.self() && BotGiveTrade.canGive(other.self())) {
                out.add(other.self());
            }
        }
        return out;
    }

    private static void upgrades(Character leader, List<Companion> companions, boolean everyone) {
        boolean anyone = false;
        Companion first = null;
        for (Companion c : companions) {
            if (!BotGiveTrade.canGive(c.self())) {
                continue;
            }
            first = first == null ? c : first;
            List<Character> to = new ArrayList<>();
            to.add(leader);
            to.addAll(siblings(c, companions));
            List<GearUpgrades.Offer> offers = GearUpgrades.offers(c.self(), to);
            if (offers.isEmpty()) {
                if (!everyone) {
                    c.tell("nothing better than what you've got");
                }
                continue;
            }
            anyone = true;
            c.tell(offerLine(offers, leader));
        }
        if (everyone && !anyone && first != null) {
            first.tell("nothing better than what you've got");
        }
    }

    static String offerLine(List<GearUpgrades.Offer> offers, Character leader) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_UPGRADE_LINES, offers.size()); i++) {
            GearUpgrades.Offer o = offers.get(i);
            String item = TradeCategories.facts().name(o.item().getItemId());
            parts.add(o.to() == leader ? "my " + item + " would help you" : o.to().getName() + " could use my " + item);
        }
        String line = String.join(", ", parts);
        if (offers.size() > MAX_UPGRADE_LINES) {
            line += " (+" + (offers.size() - MAX_UPGRADE_LINES) + " more)";
        }
        return line + " - say trade upgrades";
    }

    private static void request(Character leader, List<Companion> companions, boolean everyone) {
        List<Equip> spares = GearUpgrades.spares(leader);
        boolean anyone = false;
        Companion first = null;
        for (Companion c : companions) {
            if (!BotGiveTrade.canGive(c.self())) {
                continue;
            }
            first = first == null ? c : first;
            List<GearUpgrades.Offer> wants = new ArrayList<>();
            for (Equip e : spares) {
                double gain = GearUpgrades.scorer().gain(c.self(), e);
                if (gain > 0) {
                    wants.add(new GearUpgrades.Offer(e, c.self(), gain));
                }
            }
            if (wants.isEmpty()) {
                if (!everyone) {
                    c.tell("i'm good for now");
                }
                continue;
            }
            anyone = true;
            wants.sort((a, b) -> Double.compare(b.gain(), a.gain()));
            List<String> names = new ArrayList<>();
            for (int i = 0; i < Math.min(MAX_UPGRADE_LINES, wants.size()); i++) {
                names.add(TradeCategories.facts().name(wants.get(i).item().getItemId()));
            }
            c.tell("your " + String.join(", ", names) + (names.size() == 1 ? " would" : " would all") + " help me");
        }
        if (everyone && !anyone && first != null) {
            first.tell("we're good for now");
        }
    }

    /**
     * Trade upgrades: the pieces that help the player go to the player in one window; then, one window each, the
     * pieces that help a sibling go to that sibling.
     */
    private static void tradeUpgrades(Companion c, Character leader, List<Companion> all) {
        List<Character> to = new ArrayList<>();
        to.add(leader);
        to.addAll(siblings(c, all));
        List<GearUpgrades.Offer> offers = GearUpgrades.offers(c.self(), to);
        List<Character> order = new ArrayList<>();
        for (GearUpgrades.Offer o : offers) {
            if (!order.contains(o.to())) {
                order.add(o.to());
            }
        }
        order.remove(leader);
        order.add(0, leader);
        handOverInTurn(c, order, offers, 0);
    }

    private static void handOverInTurn(Companion c, List<Character> recipients, List<GearUpgrades.Offer> offers, int i) {
        if (i >= recipients.size()) {
            return;
        }
        Character to = recipients.get(i);
        List<Give> items = new ArrayList<>();
        for (GearUpgrades.Offer o : offers) {
            if (o.to() == to && items.size() < BotGiveTrade.WINDOW_SLOTS) {
                items.add(new Give(InventoryType.EQUIP, o.item(), (short) 1));
            }
        }
        if (items.isEmpty()) {
            handOverInTurn(c, recipients, offers, i + 1);
            return;
        }
        Companion voice = i == 0 ? c : new Companion(c.self(), null); // siblings' handovers go quietly
        give(voice, to, items, 0, how -> handOverInTurn(c, recipients, offers, i + 1));
    }

    // ── need pot ──

    private static void need(Character leader, String what, List<Companion> companions) {
        List<Character> donors = new ArrayList<>();
        for (Companion c : companions) {
            if (BotGiveTrade.canGive(c.self()) && !BotGiveTrade.isBusy(c.self())) {
                donors.add(c.self());
            }
        }
        for (SupplyShare.Kind kind : SupplyShare.kindsOf(what)) {
            Character donor = SupplyShare.bestDonor(donors, kind, leader);
            if (donor == null) {
                continue;
            }
            Companion c = companionOf(donor, companions);
            List<Give> items = SupplyShare.handover(donor, kind, leader);
            if (c != null && !items.isEmpty()) {
                boolean started = BotGiveTrade.start(new BotGiveTrade.Plan(donor, leader, items, 0,
                        pick(DONE_LINES), c.say(), null));
                c.tell(started ? "got some " + SupplyShare.said(kind) + ", inv u" : "can't trade right now, sec");
                return;
            }
        }
        if (!donors.isEmpty()) {
            Companion c = companionOf(donors.get(0), companions);
            List<SupplyShare.Kind> kinds = SupplyShare.kindsOf(what);
            String said = kinds.size() == 1 ? SupplyShare.said(kinds.get(0)) : "pots";
            if (c != null) {
                c.tell("we're low on " + said + " too, might need a shop run");
            }
        }
    }

    private static Companion companionOf(Character self, List<Companion> companions) {
        for (Companion c : companions) {
            if (c.self() == self) {
                return c;
            }
        }
        return null;
    }

    /**
     * Sibling sharing: a companion running low on a supply it uses gets some from the sibling with the most to spare
     * (both are the same player's companions, so neither has a person at the window). True when a handover started.
     */
    public static boolean shareWithSibling(Character needy, SupplyShare.Kind kind, List<Character> siblings,
                                           java.util.function.BiConsumer<Character, String> donorSays) {
        List<Character> donors = new ArrayList<>();
        for (Character s : siblings) {
            if (BotGiveTrade.canGive(s) && !BotGiveTrade.isBusy(s) && s.getMap() == needy.getMap()) {
                donors.add(s);
            }
        }
        Character donor = SupplyShare.bestDonor(donors, kind, needy);
        if (donor == null || BotGiveTrade.isBusy(needy)) {
            return false;
        }
        List<Give> items = SupplyShare.handover(donor, kind, needy);
        boolean started = !items.isEmpty() && BotGiveTrade.start(new BotGiveTrade.Plan(donor, needy, items, 0,
                null, null, null));
        if (started && donorSays != null) {
            donorSays.accept(donor, needy.getName() + ", have some of my " + SupplyShare.said(kind));
        }
        return started;
    }

    private static String pick(List<String> lines) {
        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }
}

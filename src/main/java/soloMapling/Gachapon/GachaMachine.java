package soloMapling.Gachapon;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.qa.QaLog;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventFactory;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 1.5.0: one ticket at a town's gachapon, for a player (the machine NPCs, a remote ticket, a GM's forced roll) or a
 * bot ({@link BotGacha}). Rolls the town's pool ({@link GachaPools}), hands the prize over, writes the
 * {@code logs/gachapon.log} line and, for a tier the pool file announces, the server-wide notice
 * ({@link GachaAnnouncer}).
 * <p>
 * A player's ticket is taken only once the prize is known to fit: a full inventory costs nothing.
 * <p>
 * 1.5.0 rc6 bug hunt: a prize that doesn't fit used to be thrown away, so filling a tab and pulling again was a free
 * reroll until a rare came up. A ticket roll is now committed: the prize waits for that player at that town and the
 * next ticket there is offered the same prize until it fits ({@link #pending}). This was picked over "need a free slot
 * in every tab before rolling" because it asks nothing extra of a player whose prize already fits, and a player
 * whose prize doesn't fit still keeps their ticket and only has to clear the one tab the prize needs. Pending prizes
 * live in memory: a restart forgets them, which costs the player nothing (the ticket was never taken).
 */
public final class GachaMachine {

    private static final Logger log = LoggerFactory.getLogger("server.gachapon.Gachapon");

    public static final int TICKET = 5220000;
    public static final int REMOTE_TICKET = 5451000;

    /** A ticket roll that didn't fit yet, by (character id, town key); claimed by that player's next ticket there. */
    private static final Map<String, GachaPools.Prize> pending = new ConcurrentHashMap<>();

    private GachaMachine() {
    }

    private static String pendingKey(Character chr, GachaPools.Town town) {
        return chr.getId() + ":" + town.key();
    }

    /** The prize waiting for {@code chr} at {@code town}, or null. */
    public static GachaPools.Prize pendingPrize(Character chr, GachaPools.Town town) {
        return chr == null || town == null ? null : pending.get(pendingKey(chr, town));
    }

    static void clearPendingForTest() {
        pending.clear();
    }

    /** What happened: the prize and the item handed over, or why nothing was (then {@code prize} may be null). */
    public record Result(GachaPools.Prize prize, Item item, String refusal, boolean announced) {
        public boolean ok() {
            return refusal == null;
        }

        static Result refused(GachaPools.Prize prize, String why) {
            return new Result(prize, null, why, false);
        }
    }

    /**
     * A player uses one ticket ({@code ticketId}, or none when 0: a GM's forced roll) at {@code town}. The ticket is
     * taken after the prize fits and before it is given.
     */
    public static Result playForPlayer(Character chr, GachaPools.Town town, int ticketId, String source) {
        return playForPlayer(chr, town, ticketId, source, ThreadLocalRandom.current());
    }

    static Result playForPlayer(Character chr, GachaPools.Town town, int ticketId, String source, Random rng) {
        return playForPlayer(chr, town, ticketId, source, rng, null);
    }

    /** As above; {@code tier} non-null forces the tier (QA: a rare on demand to see the notice), no ticket. */
    public static Result playForPlayer(Character chr, GachaPools.Town town, int ticketId, String source, Random rng,
                                       GachaPools.Tier tier) {
        if (chr == null || town == null) {
            return Result.refused(null, "That gachapon is not working.");
        }
        if (ticketId > 0 && chr.getItemQuantity(ticketId, false) < 1) {
            return Result.refused(null, "You need a ticket.");
        }
        // A ticket's roll is committed (rc6 bug hunt): a prize that didn't fit is offered again, not rerolled. A GM's
        // forced roll (no ticket, or a forced tier) is free anyway, so it neither claims nor leaves a pending prize.
        boolean committed = ticketId > 0 && tier == null;
        String key = pendingKey(chr, town);
        GachaPools.Prize prize = committed ? pending.get(key) : null;
        if (prize == null) {
            prize = tier == null ? GachaPools.get().roll(town, rng) : GachaPools.get().rollTier(town, tier, rng);
        }
        if (prize == null) {
            return Result.refused(null, "That gachapon is empty.");
        }
        if (!InventoryManipulator.checkSpace(chr.getClient(), prize.itemId(), prize.quantity(), "")) {
            if (committed) {
                pending.put(key, prize);
                return Result.refused(prize, "Your prize, " + nameOf(prize.itemId()) + ", is waiting for you here. Make room"
                        + " in your " + ItemConstants.getInventoryType(prize.itemId()).name() + " inventory and use"
                        + " a ticket again to claim it. Your ticket was not used.");
            }
            return Result.refused(prize, "Please make room in your " + ItemConstants.getInventoryType(prize.itemId())
                    .name() + " inventory first. Your ticket was not used.");
        }
        if (ticketId > 0) {
            InventoryManipulator.removeById(chr.getClient(), ItemConstants.getInventoryType(ticketId), ticketId, 1,
                    true, false);
        }
        Item given = chr.getClient().getAbstractPlayerInteraction().gainItem(prize.itemId(), (short) prize.quantity(),
                true, true);
        if (given == null) {
            // checkSpace said yes a moment ago; give the ticket back rather than eat it
            if (ticketId > 0) {
                chr.getClient().getAbstractPlayerInteraction().gainItem(ticketId, (short) 1, false, false);
            }
            if (committed) {
                pending.put(key, prize);
            }
            return Result.refused(prize, "Your inventory is full. Your ticket was given back.");
        }
        if (committed) {
            pending.remove(key, prize);
        }
        return after(chr, town, prize, given, source);
    }

    /** A bot plays one ticket it already paid for (or a GM's forced roll). Nothing is taken from it here. */
    public static Result playForBot(Character bot, GachaPools.Town town, String source, Random rng) {
        if (bot == null || town == null) {
            return Result.refused(null, "no machine");
        }
        GachaPools.Prize prize = GachaPools.get().roll(town, rng);
        if (prize == null) {
            return Result.refused(null, "the machine is empty");
        }
        Item item = makeItem(prize);
        if (item == null) {
            return Result.refused(prize, "no item data for " + prize.itemId());
        }
        if (!BotHoldings.grantItem(bot, item)) {
            return Result.refused(prize, "its " + item.getInventoryType().name() + " tab is full");
        }
        return after(bot, town, prize, item, source);
    }

    /** True when the bot has a free slot in every tab a prize can land in (so a roll it pays for always lands). */
    public static boolean botHasRoom(Character bot) {
        for (InventoryType t : new InventoryType[]{InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP,
                InventoryType.ETC}) {
            if (bot.getInventory(t).getNextFreeSlot() < 0) {
                return false;
            }
        }
        return true;
    }

    static Item makeItem(GachaPools.Prize prize) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (ItemConstants.getInventoryType(prize.itemId()) == InventoryType.EQUIP) {
            Item e = ii.getEquipById(prize.itemId());
            return e instanceof Equip eq ? ii.randomizeStats(eq) : null;
        }
        return new Item(prize.itemId(), (short) 0, (short) prize.quantity());
    }

    private static Result after(Character chr, GachaPools.Town town, GachaPools.Prize prize, Item given, String source) {
        String name = nameOf(prize.itemId());
        log.info("{} got {} x{} ({}, {}) from the {} gachapon [{}]", chr.getName(), name, prize.quantity(),
                prize.itemId(), prize.tier().key(), town.name(), source);
        if (QaLog.on(QaLog.Topic.GACHA)) {
            QaLog.any(QaLog.Topic.GACHA, chr.getName() + " " + source + " at " + town.name() + ": "
                    + prize.tier().key() + " " + name + " (" + prize.itemId() + ") x" + prize.quantity());
        }
        GachaStats.won(soloMapling.ArtificialPlayer.BotHelpers.isBot(chr), prize.tier());
        try {
            if (chr.getMap() != null) {
                EventBus.getInstance().publish(EventFactory.createGachaponEvent(chr, given));
            }
        } catch (RuntimeException e) {
            log.debug("gachapon event not published", e);
        }
        boolean announced = prize.announce() && GachaAnnouncer.get().announce(chr, given, town.name());
        return new Result(prize, given, null, announced);
    }

    static String nameOf(int itemId) {
        try {
            String n = ItemInformationProvider.getInstance().getName(itemId);
            return n == null ? String.valueOf(itemId) : n;
        } catch (LinkageError | RuntimeException e) {
            return String.valueOf(itemId);
        }
    }
}

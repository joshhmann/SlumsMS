package server.cashshop;

import client.BotClient;
import client.Character;
import client.Client;
import client.Ring;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.manipulator.CashIdGenerator;
import client.inventory.manipulator.InventoryManipulator;
import scripting.npc.NPCScriptManager;
import server.CashShop;
import server.ItemInformationProvider;
import tools.DatabaseConnection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Couple and friendship rings from the Cash Shop window (1.5.0).
 *
 * <p>A partner ring is only a ring when BOTH characters hold a copy whose {@code ringid} points at a
 * {@code rings} row naming the other one: the client draws the ring's Effect.wz/ItemEff look only from those
 * records (the couple / friendship records in the character data and in the remote-user spawn). Sold as a plain
 * item it is just a stat-less ring, which is why the catalogue tool left every ItemEff ring out until now.
 *
 * <p>The stock flow is {@code CashOperationHandler} 0x1D / 0x23 inside the Cash Shop stage, with the partner's
 * copy sent as a cash-locker gift. Neither half works here: the window replaces the stage (that handler is gated on
 * {@code CashShop.isOpened()}), and nobody ever opens the locker, so a gift would never arrive. So the window does
 * it in the field:
 * <ol>
 *   <li>Buy on a partner ring ({@code ACTION_BUY}) does NOT buy. It parks the item ({@link #begin}) and opens a
 *       text prompt (NPC script {@value #SCRIPT}) for the partner's name. The window keeps "Buying..." until the
 *       result comes.</li>
 *   <li>The prompt's answer ({@link #complete}) takes the parked item ONCE, then {@link #purchase}: the partner
 *       must be another real player online in the world, both need a free Equip slot, the buyer the NX. Both ring
 *       rows are written in one transaction, a copy goes straight into each inventory, and only then is the NX
 *       taken. Any failure deletes the rows and puts both inventories back; nothing is charged.</li>
 *   <li>Closing the prompt ({@link #dialogClosed}) answers the window with {@code BUY_PARTNER_REFUSED}.</li>
 * </ol>
 * Cart buys never take a partner ring ({@link CashShopWindowPurchase} refuses it): a cart has no partner.
 *
 * <p>No gender check, on purpose (owner decision for 1.5.0): any two players can share a couple ring.
 */
public final class CashShopPartnerRings {
    private static final Logger log = LoggerFactory.getLogger(CashShopPartnerRings.class);

    /** The NPC script with the name prompt (scripts/npc/cashshop_partner_ring.js). */
    public static final String SCRIPT = "cashshop_partner_ring";
    /** Who asks: the Maple Administrator, present in every v83 client. */
    public static final int PROMPT_NPC = 9010000;
    /** A parked purchase older than this is forgotten (the player walked away from the prompt). */
    static final long PENDING_MS = 5 * 60_000L;

    /**
     * Couple rings the window sells: the v83 crush rings on sale in the stock Commodity.img. They go in the
     * character's crush list ({@code Character.addPlayerRing}: item id up to 1112012).
     */
    public static final Set<Integer> COUPLE_RINGS = Set.of(
            1112001, 1112002, 1112003, 1112005, 1112006, 1112007, 1112012);
    /**
     * Friendship rings the window sells: the v83 ones on sale in the stock Commodity.img (friendship list). Wedding
     * and engagement rings (1112803-1112809) belong to the marriage system and are never sold here.
     */
    public static final Set<Integer> FRIENDSHIP_RINGS = Set.of(
            1112800, 1112801, 1112802, 1112810, 1112811, 1112812);

    public static boolean isPartnerRing(int itemId) {
        return COUPLE_RINGS.contains(itemId) || FRIENDSHIP_RINGS.contains(itemId);
    }

    public static boolean isCoupleRing(int itemId) {
        return COUPLE_RINGS.contains(itemId);
    }

    // ---------------------------------------------------------------------------------------------
    // Seams (the tests swap these in; the game uses the defaults)
    // ---------------------------------------------------------------------------------------------

    /** Finds the partner. */
    interface Directory {
        /** The partner online anywhere in the buyer's world, or null. */
        Character online(Character buyer, String name);

        /** Whether a character with this name exists at all (to tell "offline" from "no such character"). */
        boolean exists(String name);
    }

    /** The {@code rings} table. */
    interface RingStore {
        /** Writes both rows in one transaction; returns {buyer's ring id, partner's ring id}. */
        int[] createPair(int itemId, Character buyer, Character partner) throws SQLException;

        /** Deletes both rows again (a purchase that failed after {@link #createPair}). */
        void deletePair(int[] ids);
    }

    /** Opens the name prompt. */
    interface Prompt {
        /** Closes a conversation the player still has open (a second Buy while the first prompt is up). */
        void closeOpen(Client c);

        boolean ask(Client c, Character chr, int itemId);
    }

    static volatile Directory directory = new GameDirectory();
    static volatile RingStore store = new DbRingStore();
    static volatile Prompt prompt = new ScriptPrompt();

    private record Pending(int itemId, long since) {
    }

    /** Character id -> the ring waiting for a partner name. */
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();

    private CashShopPartnerRings() {
    }

    // ---------------------------------------------------------------------------------------------
    // The window flow
    // ---------------------------------------------------------------------------------------------

    /**
     * The window's Buy on a partner ring: the cheap refusals now (unknown, gates, NX), then the name prompt. Sends
     * the window's result itself when it refuses; otherwise the result comes from {@link #complete} or
     * {@link #dialogClosed}.
     */
    public static void begin(Client c, Character chr, int itemId) {
        final CashShopCatalog.Row row = CashShopCatalog.byItemId(itemId);
        if (row == null || !isPartnerRing(itemId)) {
            c.sendPacket(CashShopWindowPackets.buyResult(CashShopWindowPackets.BUY_UNKNOWN_ITEM, itemId));
            return;
        }
        final CashShopServices.Denial denial = CashShopServices.check(c, chr, itemId);
        if (denial != null) {
            chr.dropMessage(1, denial.message());
            c.sendPacket(CashShopWindowPackets.buyResult(denial.code(), itemId));
            return;
        }
        if (row.price() > chr.getCashShop().getCash(CashShopWindowPackets.NX_CREDIT)) {
            c.sendPacket(CashShopWindowPackets.buyResult(CashShopWindowPackets.BUY_NO_NX, itemId));
            return;
        }
        prompt.closeOpen(c);                 // cancels an older parked ring first (dialogClosed)
        PENDING.put(chr.getId(), new Pending(itemId, System.currentTimeMillis()));
        qa("PROMPT " + chr.getName() + " pressed Buy on " + row.name() + " (" + itemId + ", " + row.price()
                + " NX): asking for the partner's name");
        if (!prompt.ask(c, chr, itemId)) {
            qa("BUSY " + chr.getName() + ": the name prompt could not open");
            PENDING.remove(chr.getId());
            chr.dropMessage(1, "Close the open NPC chat first, then press Buy again. You were not charged.");
            c.sendPacket(CashShopWindowPackets.buyResult(CashShopWindowPackets.BUY_BUSY, itemId));
        }
    }

    /** The ring this character is naming a partner for, or 0. For the prompt's text. */
    public static int pendingItem(Character chr) {
        final Pending p = PENDING.get(chr.getId());
        return p == null || expired(p) ? 0 : p.itemId();
    }

    /**
     * The prompt's answer. Takes the parked ring exactly once -- a repeated answer, or one with nothing parked, is
     * refused without touching anything -- runs {@link #purchase} and answers the window.
     *
     * @return the line the prompt shows to close the conversation
     */
    public static String complete(Client c, Character chr, String partnerName) {
        final Pending p = PENDING.remove(chr.getId());
        if (p == null || expired(p)) {
            qa("NOTHING WAITING " + chr.getName() + " answered '" + partnerName + "' with no ring parked: nothing done");
            return "There is no ring purchase waiting for a partner name. Press Buy in the Cash Shop again.";
        }
        final CashShopWindowPurchase.Result r = purchase(c, chr, p.itemId(), partnerName);
        c.sendPacket(CashShopWindowPackets.buyResult(r.code(), p.itemId()));
        if (r.ok()) {
            c.sendPacket(CashShopWindowPackets.cash(chr));
            return "Done! Equip the ring; your partner has theirs. Change channel once so your own client loads the"
                    + " ring, then stand near each other to see its effect.";
        }
        return "No ring was bought and you were not charged.";
    }

    /** The prompt closed (Esc, or the end of the conversation). A ring still parked is cancelled. */
    public static void dialogClosed(Client c, Character chr) {
        if (chr == null) {
            return;
        }
        final Pending p = PENDING.remove(chr.getId());
        if (p != null) {
            qa("CANCELLED " + chr.getName() + " closed the name prompt for " + p.itemId() + ": nothing charged");
            c.sendPacket(CashShopWindowPackets.buyResult(CashShopWindowPackets.BUY_PARTNER_REFUSED, p.itemId()));
        }
    }

    /** !qa pr-1 / pr-2: one RING line (test server only; free when no run listens). */
    private static void qa(String message) {
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.RING)) {
            soloMapling.qa.QaLog.any(soloMapling.qa.QaLog.Topic.RING, message);
        }
    }

    private static boolean expired(Pending p) {
        return System.currentTimeMillis() - p.since() > PENDING_MS;
    }

    /** Test hook: forget every parked purchase. */
    static void clearPending() {
        PENDING.clear();
    }

    // ---------------------------------------------------------------------------------------------
    // The purchase
    // ---------------------------------------------------------------------------------------------

    private static CashShopWindowPurchase.Result refuse(Character chr, int code, String why) {
        qa("REFUSED " + chr.getName() + " code " + code + (why == null ? "" : ": " + why));
        if (why != null) {
            chr.dropMessage(1, why);
        }
        return new CashShopWindowPurchase.Result(code, 0, 0, 0);
    }

    private static CashShopWindowPurchase.Result refuse(Character chr, String why) {
        return refuse(chr, CashShopWindowPackets.BUY_PARTNER_REFUSED, why + " You were not charged.");
    }

    /**
     * Buys {@code itemId} for {@code chr} with {@code partnerName} as the partner: both ring rows, a copy in each
     * inventory, then the NX. All or nothing.
     */
    public static CashShopWindowPurchase.Result purchase(Client c, Character chr, int itemId, String partnerName) {
        final CashShopCatalog.Row row = CashShopCatalog.byItemId(itemId);
        if (row == null || !isPartnerRing(itemId)) {
            return refuse(chr, CashShopWindowPackets.BUY_UNKNOWN_ITEM, null);
        }
        final String name = partnerName == null ? "" : partnerName.strip();
        if (name.isEmpty()) {
            return refuse(chr, "Type your partner's character name to buy a ring.");
        }
        if (name.equalsIgnoreCase(chr.getName())) {
            return refuse(chr, "You can't share a ring with yourself.");
        }
        if (!c.tryacquireClient()) {
            return refuse(chr, CashShopWindowPackets.BUY_BUSY, null);
        }
        try {
            final CashShopServices.Denial denial = CashShopServices.check(c, chr, itemId);
            if (denial != null) {
                return refuse(chr, denial.code(), denial.message());
            }
            final Character partner = directory.online(chr, name);
            if (partner == null || partner.getWorld() != chr.getWorld()) {
                return refuse(chr, directory.exists(name)
                        ? name + " is not online. Both of you need to be online to share a ring."
                        : "There is no character named " + name + ".");
            }
            if (partner.getId() == chr.getId()) {
                return refuse(chr, "You can't share a ring with yourself.");
            }
            if (partner.getClient() == null || partner.getClient() instanceof BotClient) {
                return refuse(chr, "Rings can only be shared with another player.");
            }
            if (sharesRing(chr, partner.getId(), itemId)) {
                return refuse(chr, "You already share a " + row.name() + " with " + partner.getName() + ".");
            }
            final CashShop cs = chr.getCashShop();
            if (row.price() > cs.getCash(CashShopWindowPackets.NX_CREDIT)) {
                return refuse(chr, CashShopWindowPackets.BUY_NO_NX, null);
            }

            // Both characters' monitors and inventories, always lower id first: two players buying each other a
            // ring at the same moment must not take them in opposite orders.
            final Character first = chr.getId() < partner.getId() ? chr : partner;
            final Character second = first == chr ? partner : chr;
            synchronized (first) {
                synchronized (second) {
                    return deliver(c, chr, partner, row, first);
                }
            }
        } catch (Exception e) {
            log.error("Cash Shop window: partner ring {} for {} failed", itemId, chr.getName(), e);
            return refuse(chr, CashShopWindowPackets.BUY_UNKNOWN_ITEM,
                    "That purchase could not be completed. You were not charged.");
        } finally {
            c.releaseClient();
        }
    }

    private static CashShopWindowPurchase.Result deliver(Client c, Character chr, Character partner,
                                                         CashShopCatalog.Row row, Character first) throws Exception {
        final int itemId = row.itemId();
        final Client pc = partner.getClient();
        final boolean buyerFirst = first == chr;
        int[] ids = null;
        boolean done = false;
        try (CashShopCartDelivery a = new CashShopCartDelivery(buyerFirst ? c : pc, first);
             CashShopCartDelivery b = new CashShopCartDelivery(buyerFirst ? pc : c, buyerFirst ? partner : chr)) {
            final CashShopCartDelivery mine = buyerFirst ? a : b;
            final CashShopCartDelivery theirs = buyerFirst ? b : a;
            if (chr.getInventory(InventoryType.EQUIP).isFull()) {
                return refuse(chr, CashShopWindowPackets.BUY_INVENTORY_FULL, null);
            }
            if (partner.getInventory(InventoryType.EQUIP).isFull()) {
                return refuse(chr, partner.getName() + " has no free slot in their Equip inventory.");
            }

            final ItemInformationProvider ii = ItemInformationProvider.getInstance();
            final Equip myRing = (Equip) ii.getEquipById(itemId);
            final Equip theirRing = (Equip) ii.getEquipById(itemId);
            if (myRing == null || theirRing == null) {
                return refuse(chr, CashShopWindowPackets.BUY_UNKNOWN_ITEM, null);
            }

            ids = store.createPair(itemId, chr, partner);
            myRing.setRingId(ids[0]);
            theirRing.setRingId(ids[1]);
            myRing.setSN(itemId);
            theirRing.setSN(itemId);

            mine.deliveryStarted();
            theirs.deliveryStarted();
            if (!InventoryManipulator.addFromDrop(c, myRing, false)) {
                return refuse(chr, CashShopWindowPackets.BUY_INVENTORY_FULL, null);
            }
            if (!InventoryManipulator.addFromDrop(pc, theirRing, false)) {
                return refuse(chr, partner.getName() + " has no free slot in their Equip inventory.");
            }

            chr.getCashShop().gainCash(CashShopWindowPackets.NX_CREDIT, -row.price());
            mine.commit();
            theirs.commit();
            done = true;
        } finally {
            if (!done && ids != null) {
                store.deletePair(ids);
            }
        }

        // The character-side records the ring look and the equip handler use (getRingById must find them).
        chr.addPlayerRing(new Ring(ids[0], ids[1], partner.getId(), itemId, partner.getName()));
        partner.addPlayerRing(new Ring(ids[1], ids[0], chr.getId(), itemId, chr.getName()));

        final String kind = isCoupleRing(itemId) ? "couple" : "friendship";
        chr.dropMessage(1, "You bought a " + row.name() + " for you and " + partner.getName() + ". "
                + partner.getName() + " already has theirs. Equip it, change channel once so your client loads the"
                + " ring, and stand near each other to see the effect.");
        partner.dropMessage(1, chr.getName() + " bought you a " + row.name() + " (a " + kind + " ring for the two of"
                + " you). It is in your Equip inventory. Equip it, change channel once so your client loads the ring,"
                + " and stand near " + chr.getName() + " to see the effect.");
        qa("BOUGHT " + chr.getName() + " + " + partner.getName() + ": " + kind + " ring " + row.name() + " ("
                + itemId + ") for " + row.price() + " NX; ring rows #" + ids[0] + " (" + chr.getName() + ", partner "
                + partner.getId() + ") and #" + ids[1] + " (" + partner.getName() + ", partner " + chr.getId() + ")");
        log.info("{} bought a {} ring {} for {} NX with partner {} (rings {} / {})", chr.getName(), kind, itemId,
                row.price(), partner.getName(), ids[0], ids[1]);
        return new CashShopWindowPurchase.Result(CashShopWindowPackets.BUY_OK, 0, 2, row.price());
    }

    /** Whether {@code chr} already holds this ring with this partner. */
    static boolean sharesRing(Character chr, int partnerId, int itemId) {
        for (List<Ring> rings : List.of(chr.getCrushRings(), chr.getFriendshipRings())) {
            for (Ring r : rings) {
                if (r != null && r.getPartnerChrId() == partnerId && r.getItemId() == itemId) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Game implementations
    // ---------------------------------------------------------------------------------------------

    static final class GameDirectory implements Directory {
        @Override
        public Character online(Character buyer, String name) {
            return buyer.getWorldServer().getPlayerStorage().getCharacterByName(name);
        }

        @Override
        public boolean exists(String name) {
            return Character.getIdByName(name) > 0;
        }
    }

    static final class DbRingStore implements RingStore {
        private static final String INSERT =
                "INSERT INTO rings (id, itemid, partnerRingId, partnerChrId, partnername) VALUES (?, ?, ?, ?, ?)";

        @Override
        public int[] createPair(int itemId, Character buyer, Character partner) throws SQLException {
            final int[] ids = {CashIdGenerator.generateCashId(), CashIdGenerator.generateCashId()};
            try (Connection con = DatabaseConnection.getConnection()) {
                con.setAutoCommit(false);
                try (PreparedStatement ps = con.prepareStatement(INSERT)) {
                    ps.setInt(1, ids[0]);
                    ps.setInt(2, itemId);
                    ps.setInt(3, ids[1]);
                    ps.setInt(4, partner.getId());
                    ps.setString(5, partner.getName());
                    ps.addBatch();
                    ps.setInt(1, ids[1]);
                    ps.setInt(2, itemId);
                    ps.setInt(3, ids[0]);
                    ps.setInt(4, buyer.getId());
                    ps.setString(5, buyer.getName());
                    ps.addBatch();
                    ps.executeBatch();
                    con.commit();
                } catch (SQLException e) {
                    con.rollback();
                    throw e;
                } finally {
                    con.setAutoCommit(true);
                }
            } catch (SQLException e) {
                CashIdGenerator.freeCashId(ids[0]);
                CashIdGenerator.freeCashId(ids[1]);
                throw e;
            }
            return ids;
        }

        @Override
        public void deletePair(int[] ids) {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("DELETE FROM rings WHERE id = ?")) {
                for (int id : ids) {
                    ps.setInt(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                log.error("Could not delete ring rows {} after a failed partner ring purchase", ids, e);
            }
            for (int id : ids) {
                CashIdGenerator.freeCashId(id);
            }
        }
    }

    static final class ScriptPrompt implements Prompt {
        @Override
        public void closeOpen(Client c) {
            if (NPCScriptManager.getInstance().getCM(c) != null) {
                NPCScriptManager.getInstance().dispose(c);
            }
        }

        @Override
        public boolean ask(Client c, Character chr, int itemId) {
            if (!c.canClickNPC()) {
                return false;
            }
            final NPCScriptManager npc = NPCScriptManager.getInstance();
            return npc.start(c, PROMPT_NPC, SCRIPT, chr) && npc.getCM(c) != null;
        }
    }
}

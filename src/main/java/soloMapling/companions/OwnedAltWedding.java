package soloMapling.companions;

import client.Character;
import client.Ring;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;
import constants.id.MapId;
import server.ItemInformationProvider;
import tools.Pair;

/**
 * Ticket 189 stage 2 (option B2): a player marries their own owned alt at High Priest John with no ceremony. An alt has
 * no client, so it cannot press Yes; this stands in for the whole vows step and leaves the stock end state (a
 * {@code marriages} row, a ring in each bag, both marriage item ids), so the marriage is divorceable at Amos as usual.
 * Everything is behind {@code -Dslums.ownedalts.marry} (default OFF). Another player's alt and every population bot stay
 * refused exactly as before. The owner pays one cathedral wedding ticket as a fee. No bonus of any kind.
 */
public final class OwnedAltWedding {
    public static final String SWITCH = "slums.ownedalts.marry";
    /** The fee: one cathedral wedding ticket (the common one John's sister Nicole sells). */
    public static final int FEE_TICKET = ItemId.NORMAL_WEDDING_TICKET_CATHEDRAL;
    private static final short[] RING_SLOTS = {-12, -13, -15, -16};

    private OwnedAltWedding() {
    }

    public static boolean enabled() {
        try {
            return Boolean.getBoolean(SWITCH);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * True when {@code alt} is one of {@code owner}'s own alts that is out with the owner on the same map, both level
     * 50 or more, and the switch is on - the only case where a headless character takes part in an engagement.
     */
    public static boolean accepts(Character owner, Character alt) {
        if (!enabled() || owner == null || alt == null || owner.isOwnedAlt() || owner.isBot()) {
            return false;
        }
        if (!alt.isOwnedAlt() || alt.isOwnedAltClosing()) {
            return false;
        }
        Character altOwner = alt.getOwnedAltOwner();
        if (altOwner == null || altOwner.getId() != owner.getId()) {
            return false;
        }
        return owner.getMap() != null && owner.getMap().equals(alt.getMap())
                && owner.getLevel() >= 50 && alt.getLevel() >= 50;
    }

    static int ringItemFor(int token) {
        int base = token % 2 == 1 ? token : token - 1;
        return switch (base) {
            case 4031357 -> 1112803;
            case 4031359 -> 1112806;
            case 4031361 -> 1112807;
            case 4031363 -> 1112809;
            default -> -1;
        };
    }

    /** Marries {@code owner} to the alt they are engaged to. The reason it cannot be done, or null on success. */
    public static String marry(Character owner) {
        return marry(owner, new Ops());
    }

    static String marry(Character owner, Ops ops) {
        if (!enabled()) {
            return "that is not open yet";
        }
        Character alt = ops.partner(owner);
        if (alt == null || !alt.isOwnedAlt() || alt.isOwnedAltClosing() || alt.getOwnedAltOwner() == null
                || alt.getOwnedAltOwner().getId() != owner.getId() || owner.getPartnerId() != alt.getId()) {
            return "you are not engaged to an alt of yours that is out with you";
        }
        if (owner.getMarriageRing() != null || alt.getMarriageRing() != null) {
            return "you are already married";
        }
        if (owner.getMapId() != MapId.AMORIA || alt.getMapId() != MapId.AMORIA) {
            return "you and your alt must both be in Amoria";
        }
        if (!owner.isAlive() || !alt.isAlive()) {
            return "you and your alt must both be on your feet";
        }
        int token = owner.getMarriageItemId();
        int ringItem = ringItemFor(token);
        int altToken = token % 2 == 1 ? token + 1 : token - 1;
        if (ringItem <= 0 || !owner.haveItem(token) || !alt.haveItem(altToken)) {
            return "the engagement tokens are missing";
        }
        if (!ops.hasRoom(owner, ringItem)) {
            return "make room for the ring in your equip inventory";
        }
        if (!ops.hasRoom(alt, ringItem)) {
            return "make room for the ring in your alt's equip inventory";
        }
        if (!owner.haveItem(FEE_TICKET)) {
            return "you need a wedding ticket for the fee";
        }
        Equip ownerRing = ops.newRing(ringItem);
        Equip altRing = ops.newRing(ringItem);
        if (ownerRing == null || altRing == null) {
            return "the ring has no item data";
        }
        Pair<Integer, Integer> rings = ops.createRings(ringItem, owner, alt);
        if (rings.getLeft() <= 0 || rings.getRight() <= 0) {
            return "the rings could not be written";
        }
        ownerRing.setRingId(rings.getLeft());
        altRing.setRingId(rings.getRight());
        if (!ops.deliver(owner, ownerRing)) {
            ops.deleteRings(rings, alt, ringItem);
            return "the ring could not be put in your inventory";
        }
        if (!ops.deliver(alt, altRing)) {
            ops.take(owner, ringItem);
            ops.deleteRings(rings, alt, ringItem);
            return "the ring could not be put in your alt's inventory";
        }
        ops.removeToken(owner, token);
        ops.removeToken(alt, altToken);
        ops.removeFee(owner);
        ops.bond(owner, alt, ringItem, rings);
        return null;
    }

    /** The steps that touch the DB, the item data and inventories; tests replace them. */
    static class Ops {
        Character partner(Character owner) {
            return owner.getClient().getChannelServer().getPlayerStorage().getCharacterById(owner.getPartnerId());
        }

        boolean hasRoom(Character chr, int ringItem) {
            return InventoryManipulator.checkSpace(chr, ringItem, 1, "");
        }

        Equip newRing(int ringItem) {
            return ItemInformationProvider.getInstance().getEquipById(ringItem) instanceof Equip e ? e : null;
        }

        Pair<Integer, Integer> createRings(int ringItem, Character owner, Character alt) {
            return Ring.createRing(ringItem, owner, alt);
        }

        boolean deliver(Character chr, Equip ring) {
            return InventoryManipulator.addFromDrop(chr, ring, false, -1);
        }

        void take(Character chr, int itemId) {
            InventoryManipulator.removeById(chr, InventoryType.EQUIP, itemId, 1, false, false);
        }

        void deleteRings(Pair<Integer, Integer> rings, Character alt, int ringItem) {
            Ring.removeRing(new Ring(rings.getLeft(), rings.getRight(), alt.getId(), ringItem, alt.getName()));
        }

        void removeToken(Character chr, int itemId) {
            InventoryManipulator.removeById(chr, InventoryType.ETC, itemId, 1, false, false);
        }

        void removeFee(Character chr) {
            InventoryManipulator.removeById(chr, InventoryType.CASH, FEE_TICKET, 1, false, false);
        }

        void bond(Character owner, Character alt, int ringItem, Pair<Integer, Integer> rings) {
            owner.addMarriageRing(Ring.loadFromDb(rings.getLeft()));
            owner.setMarriageItemId(ringItem);
            Ring altRing = Ring.loadFromDb(rings.getRight());
            alt.addMarriageRing(altRing);
            alt.setMarriageItemId(ringItem);
            wear(alt, ringItem, altRing);
            owner.broadcastMarriageMessage();
            alt.broadcastMarriageMessage();
            if (soloMapling.site.SiteFeed.isPublic(owner) && soloMapling.site.SiteFeed.isPublic(alt)) {
                soloMapling.site.SiteFeed.wedding(owner.getName(), alt.getName());
            }
            soloMapling.achievements.Achievements.onWedding(owner);
            soloMapling.achievements.Achievements.onWedding(alt);
        }

        /** Puts the alt's ring on so the couple look shows; a full ring row leaves it in the bag. */
        void wear(Character alt, int ringItem, Ring altRing) {
            Item piece = alt.getInventory(InventoryType.EQUIP).findById(ringItem);
            Inventory worn = alt.getInventory(InventoryType.EQUIPPED);
            if (piece == null) {
                return;
            }
            for (short slot : RING_SLOTS) {
                if (worn.getItem(slot) == null) {
                    InventoryManipulator.equip(alt.getClient(), piece.getPosition(), slot);
                    if (altRing != null) {
                        altRing.equip();
                    }
                    return;
                }
            }
        }
    }
}

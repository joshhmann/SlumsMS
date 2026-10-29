package soloMapling.companions;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;

import java.awt.Point;
import java.util.List;
import java.util.Set;

/**
 * 1.6.1 ticket 13: what an owned alt picks up. Its own drops and its party's, by the game's loot rules, into its own
 * bag through the player pickup path ({@code Character.pickupItem}: party meso split, quest items it needs, the drop
 * lock) - it is its player's character, so nothing goes through the bots' holdings. It never takes:
 * <ul>
 *   <li>a drop owned by someone outside the party (even after the ownership window ends: no sweeping strangers' loot),</li>
 *   <li>an item a player dropped on purpose,</li>
 *   <li>a drop still settling ({@link #SETTLE_MS}), or one off its ledge or farther than {@link #REACH_X_PX} px,</li>
 *   <li>anything its bag has no room for: it leaves it and says so once (until it has room again).</li>
 * </ul>
 */
public final class OwnedAltLoot {

    private OwnedAltLoot() {
    }

    static final long SETTLE_MS = 1_000;
    /** How far across the alt walks for a drop. */
    static final int REACH_X_PX = 350;
    static final int SAME_LEDGE_DY = 120;
    /** Close enough to pick up. */
    static final int PICKUP_PX = 50;
    static final int PICKUP_DY = 60;

    /** Room in the alt's bag for this drop (mesos: the meso cap). Replaced in tests (item data needs WZ). */
    interface Room {
        boolean fits(Character alt, MapItem drop);
    }

    static final Room REAL_ROOM = OwnedAltLoot::realRoom;
    static volatile Room room = REAL_ROOM;

    private static boolean realRoom(Character alt, MapItem drop) {
        if (drop.getMeso() > 0) {
            return alt.canHoldMeso(drop.getMeso());
        }
        Item item = drop.getItem();
        if (item == null) {
            return false;
        }
        if (constants.id.ItemId.isNxCard(item.getItemId())) {
            return true;
        }
        try {
            if (server.ItemInformationProvider.getInstance().isConsumeOnPickup(item.getItemId())) {
                return true;
            }
        } catch (RuntimeException | LinkageError noItemData) {
            // no item data: fall through to the space check
        }
        return client.inventory.manipulator.InventoryManipulator.checkSpace(alt, item.getItemId(), item.getQuantity(),
                item.getOwner());
    }

    /** The bag a drop goes to (for the "bag is full" line), null for mesos. */
    static InventoryType bagOf(MapItem drop) {
        if (drop.getMeso() > 0 || drop.getItem() == null) {
            return null;
        }
        return ItemConstants.getInventoryType(drop.getItem().getItemId());
    }

    /** The loot rules, before room: the party's, not a player's own drop, settled, still there. */
    static boolean ours(Character alt, MapItem drop, Set<Integer> party, long now) {
        if (drop == null || drop.isPickedUp() || drop.getPosition() == null || drop.isPlayerDrop() || drop.isConjured()) {
            return false;
        }
        if (now - drop.getDropTime() < SETTLE_MS) {
            return false;
        }
        int ownerId = drop.getOwnerId();
        if (ownerId != alt.getId() && !party.contains(ownerId)) {
            return false; // a stranger's (or a drop nobody owns: a reactor's or an event's) - not the alt's to take
        }
        if (drop.getMeso() <= 0 && drop.getItem() != null && drop.getQuest() > 0
                && !alt.needQuestItem(drop.getQuest(), drop.getItem().getItemId())) {
            return false;
        }
        return true;
    }

    /** What the alt goes for now. {@code full} collects the bags it found no room in (for the once-only line). */
    static MapItem pick(Character alt, Character owner, Set<Integer> party, long now, java.util.Set<InventoryType> full,
                        boolean[] mesoFull) {
        Point at = alt.getPosition();
        Point op = owner.getPosition();
        if (alt.getMap() == null || at == null || op == null) {
            return null;
        }
        double range = (double) REACH_X_PX * REACH_X_PX + (double) SAME_LEDGE_DY * SAME_LEDGE_DY;
        MapItem best = null;
        double bestSq = Double.MAX_VALUE;
        for (MapObject mo : alt.getMap().getMapObjectsInRange(at, range, List.of(MapObjectType.ITEM))) {
            if (!(mo instanceof MapItem drop) || !ours(alt, drop, party, now)) {
                continue;
            }
            Point dp = drop.getPosition();
            if (Math.abs(dp.x - at.x) > REACH_X_PX || Math.abs(dp.y - at.y) > SAME_LEDGE_DY
                    || Math.abs(dp.x - op.x) > OwnedAltCombat.LEASH_X_PX) {
                continue;
            }
            if (!room.fits(alt, drop)) {
                InventoryType bag = bagOf(drop);
                if (bag != null) {
                    full.add(bag);
                } else if (mesoFull != null && mesoFull.length > 0) {
                    mesoFull[0] = true;
                }
                continue;
            }
            double d = at.distanceSq(dp);
            if (d < bestSq) {
                bestSq = d;
                best = drop;
            }
        }
        return best;
    }

    /** Close enough to pick it up from where it stands. */
    static boolean inReach(Character alt, MapItem drop) {
        Point at = alt.getPosition();
        Point dp = drop.getPosition();
        return at != null && dp != null && Math.abs(dp.x - at.x) <= PICKUP_PX && Math.abs(dp.y - at.y) <= PICKUP_DY;
    }

    /** The line the alt says when a bag is full (once until it has room again). */
    static String fullLine(InventoryType bag) {
        String name = switch (bag) {
            case EQUIP -> "Equip";
            case USE -> "Use";
            case SETUP -> "Set-up";
            case ETC -> "Etc";
            case CASH -> "Cash";
            default -> bag.name();
        };
        return "My " + name + " inventory is full, I'm leaving those drops on the ground.";
    }
}

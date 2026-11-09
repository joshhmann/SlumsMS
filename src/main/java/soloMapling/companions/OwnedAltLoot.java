package soloMapling.companions;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.maps.MapItem;
import soloMapling.ArtificialPlayer.BotGrindSystem.LootPicker;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionReports;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy;

import java.awt.Point;
import java.util.Map;
import java.util.Set;

/**
 * 1.6.1 ticket 13: what an owned alt picks up. Its own drops and its party's, by the game's loot rules, into its own
 * bag through the player pickup path ({@code Character.pickupItem}: party meso split, quest items it needs, the drop
 * lock) - it is its player's character, so nothing goes through the bots' holdings. It never takes:
 * <ul>
 *   <li>a drop owned by someone outside the party (even after the ownership window ends: no sweeping strangers' loot),</li>
 *   <li>an item a player dropped on purpose,</li>
 *   <li>a drop still settling ({@link #SETTLE_MS}), or one off its ledge or farther than {@link #REACH_X_PX} px (the
 *       drop selection is the bots' shared LootPicker),</li>
 *   <li>anything its bag has no room for: it leaves it and says so once (until it has room again).</li>
 * </ul>
 */
public final class OwnedAltLoot {

    private OwnedAltLoot() {
    }

    /** The settle time and same-ledge band are the bots' (LootPicker); before 1.6.2 an alt waited 1,000 ms. */
    static final long SETTLE_MS = LootPicker.SETTLE_MS;
    /** How far across the alt walks for a drop (its own rule: it stays with its owner, OwnedAltCombat's leash). */
    static final int REACH_X_PX = 350;
    static final int SAME_LEDGE_DY = LootPicker.SAME_LEDGE_DY;

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

    /**
     * The loot rules, before room: the party's (the shared ownership rule with no ownership window: never a stranger's,
     * however old), not a player's own drop, not a prop, a quest item only when it needs it; on the floor and settled.
     */
    static boolean ours(Character alt, MapItem drop, Set<Integer> party, long now) {
        if (!LootPicker.onTheFloor(drop) || !LootPicker.settled(drop, SETTLE_MS, now)) {
            return false;
        }
        return mayTake(alt, drop, party, now);
    }

    // the alt's own rules on top of the shared picker
    private static boolean mayTake(Character alt, MapItem drop, Set<Integer> party, long now) {
        if (drop.isPlayerDrop() || drop.isConjured()) {
            return false;
        }
        if (!LootPicker.claimable(drop, id -> id == alt.getId() || party.contains(id), -1, now)) {
            return false; // a stranger's (or a drop nobody owns: a reactor's or an event's) - not the alt's to take
        }
        if (!(drop.getMeso() > 0 || drop.getItem() == null || drop.getQuest() <= 0
                || alt.needQuestItem(drop.getQuest(), drop.getItem().getItemId()))) {
            return false; // a quest item none of its own quests needs: the player pickup rule refuses it too
        }
        // SlumsMS: 1.6.7 group D - the owner's loot mode for this alt, asked last so it only ever narrows. An alt with
        // no saved choice picks up only its own kills (CompanionLootMode.of); with the switch off, everything.
        return CompanionLootMode.allows(alt, drop);
    }

    /**
     * What the alt goes for now: the shared picker (LootPicker) within {@link #REACH_X_PX} of it and its owner's leash,
     * on its ledge. {@code full} collects the bags it found no room in (for the once-only line).
     */
    static MapItem pick(Character alt, Character owner, Set<Integer> party, long now, java.util.Set<InventoryType> full,
                        boolean[] mesoFull) {
        return pick(alt, owner, party, now, full, mesoFull, d -> false);
    }

    /**
     * SlumsMS: 1.6.7 group D - the equip this alt went for last, so the tick after it has it can offer it to its owner
     * ({@link CompanionUpgradeOffer}). Kept here because this is the one place the alt's looting is seen (the
     * companion's own tick picks through {@link #pick}).
     */
    private static final Map<Integer, MapItem> lastPick = new java.util.concurrent.ConcurrentHashMap<>();

    /** Tests only: forget the equips each alt went for. */
    static void forgetLastPicks() {
        lastPick.clear();
    }

    /**
     * The equip the alt went for last tick: when it has it now, the owner is told once that it is better than what
     * they wear. A drop it never got (a full bag, an unreachable one) is forgotten without a word.
     */
    private static void settleLastPick(Character alt, Character owner, MapItem next) {
        MapItem before = lastPick.get(alt.getId());
        if (before == null || before == next) {
            return;
        }
        lastPick.remove(alt.getId());
        if (!before.isPickedUp() || !(before.getItem() instanceof Equip piece)) {
            return;
        }
        try {
            CompanionUpgradeOffer.consider(alt, owner, piece, line -> OwnedAltOrders.say(alt, line));
        } catch (RuntimeException | LinkageError e) {
            // an offer is a nicety: it never stops the looting
        }
    }

    /** As pick, leaving the drops {@code skip} names (ones it could not reach lately). */
    static MapItem pick(Character alt, Character owner, Set<Integer> party, long now, java.util.Set<InventoryType> full,
                        boolean[] mesoFull, java.util.function.Predicate<MapItem> skip) {
        Point at = alt.getPosition();
        Point op = owner.getPosition();
        if (alt.getMap() == null || at == null || op == null) {
            return null;
        }
        int x0 = Math.max(at.x - REACH_X_PX, op.x - OwnedAltCombat.LEASH_X_PX);
        int x1 = Math.min(at.x + REACH_X_PX, op.x + OwnedAltCombat.LEASH_X_PX);
        int range = (int) Math.ceil(Math.hypot(REACH_X_PX, SAME_LEDGE_DY));
        MapItem chosen = LootPicker.nearest(alt, new LootPicker.Reach(range, x0, x1, SAME_LEDGE_DY, SETTLE_MS), now,
                drop -> {
            if (skip.test(drop) || !mayTake(alt, drop, party, now)) {
                return false;
            }
            if (room.fits(alt, drop)) {
                return true;
            }
            InventoryType bag = bagOf(drop);
            if (bag != null) {
                if (maySayFull(alt, bag, now)) {
                    full.add(bag);
                }
            } else if (mesoFull != null && mesoFull.length > 0) {
                mesoFull[0] = true;
            }
            return false;
        });
        settleLastPick(alt, owner, chosen);
        if (chosen != null && chosen.getItem() instanceof Equip) {
            lastPick.put(alt.getId(), chosen); // the next tick knows whether it got it
        }
        return chosen;
    }

    /** Close enough to pick it up from where it stands (the bots' at-feet reach). */
    static boolean inReach(Character alt, MapItem drop) {
        return LootPicker.atFeet(alt.getPosition(), drop.getPosition());
    }

    /** When each alt was last allowed to name a full bag (alt id and tab), 1.6.7's once-per-window rule. */
    private static final Map<Long, Long> fullSaidAt = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 1.6.7 reports: a full tab is named once per {@link CompanionReports#FULL_SAY_MS} on a companion with the
     * reports switch on; a regular (defaults) keeps today's behaviour and is told every tick.
     */
    private static boolean maySayFull(Character alt, InventoryType bag, long now) {
        if (alt == null
                || !PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.CHAT_REPORTS, alt)) {
            return true;
        }
        long key = ((long) alt.getId() << 8) | bag.ordinal();
        Long last = fullSaidAt.get(key);
        if (last != null && now - last < CompanionReports.FULL_SAY_MS) {
            return false;
        }
        fullSaidAt.put(key, now);
        return true;
    }

    /** Tests only: forget when each tab was last named. */
    static void forgetFullSays() {
        fullSaidAt.clear();
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

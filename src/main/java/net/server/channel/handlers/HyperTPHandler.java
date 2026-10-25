package net.server.channel.handlers;

import client.Client;
import client.inventory.InventoryType;
import constants.id.MapId;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.maps.FieldLimit;
import server.maps.MapleMap;
import client.Character;
import client.inventory.Item;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Shahar
 */
public class HyperTPHandler extends AbstractPacketHandler {
    private static final int REQUIRED_ITEM_ID = 5590001; // Item ID for Hyper Teleport Rock
    private static final long COOLDOWN_TIME = 300000; // 5 minute in milliseconds

    // Store the last teleport time for each player
    private static final Map<Integer, Long> lastTeleportTime = new ConcurrentHashMap<>();

    @Override
    public void handlePacket(InPacket p, Client c) {
        if (p.available() > 0) {
            Character player = c.getPlayer();
            int map_id = p.readInt();

            // Check if player has the required item in inventory
            if (!hasRequiredItem(player)) {
                long currentTime = System.currentTimeMillis();
                long lastTimeUsed = lastTeleportTime.getOrDefault(player.getId(), 0L);
                long timeSinceLastUse = currentTime - lastTimeUsed;

                if (timeSinceLastUse < COOLDOWN_TIME) {
                    long timeLeft = (COOLDOWN_TIME - timeSinceLastUse) / 1000; // in seconds
                    player.dropMessage(1, "You need to wait " + timeLeft + " seconds before teleporting again.");
                    return;
                }
            }

            try {
                MapleMap target = c.getChannelServer().getMapFactory().getMap(map_id);
                if (target == null) {
                    player.yellowMessage("Map is invalid.");
                    return;
                }

                String refusal = refusal(player.getMapId(), player.getEventInstance() != null, player.isAlive(),
                        map_id, target.getFieldLimit(), target.getForcedReturnId(), player.getReborns());
                if (refusal != null) {
                    player.dropMessage(1, refusal);
                    return;
                }

                // Only update the cooldown time if all checks pass and the teleportation is successful
                player.saveLocationOnWarp();
                player.changeMap(target, target.getRandomPlayerSpawnpoint());

                // Now that the teleportation was successful, update the last teleport time
                lastTeleportTime.put(player.getId(), System.currentTimeMillis());

            } catch (Exception ex) {
                player.yellowMessage("Map is invalid.");
            }
        }
    }


    /**
     * Why this teleport is refused, or null when it may go ahead. MapleRoot's world-map teleport is free every
     * five minutes, so it used to be a way out of the jail and of a running party quest or boss fight, and a way
     * into instance maps (party quest stages, the GM map) that are only meant to be entered through their NPC.
     * A map whose forced return points somewhere else is an instance or a stage; a town returns to itself.
     */
    static String refusal(int fromMapId, boolean inEvent, boolean alive, int toMapId, int toFieldLimit,
                          int toForcedReturn, int reborns) {
        if (!alive) {
            return "You can't teleport while dead.";
        }
        if (fromMapId == MapId.JAIL || toMapId == MapId.JAIL) {
            return "You can't teleport out of or into the jail.";
        }
        if (inEvent) {
            return "Leave your party quest or expedition first.";
        }
        if (isRestrictedMap(toMapId)) {
            return "You are not allowed to teleport to this map.";
        }
        if (reborns < 1 && isLionKingMap(toMapId)) {
            return "You need to be Rebirth level 1 or higher.";
        }
        if (reborns < 2 && isTwilightPerion(toMapId)) {
            return "You need to be Rebirth level 2 or higher.";
        }
        if (reborns < 3 && isGrandisandArcane(toMapId)) {
            return "You need to be Rebirth level 3 or higher.";
        }
        if (FieldLimit.CANNOTVIPROCK.check(toFieldLimit)
                || (toForcedReturn != MapId.NONE && toForcedReturn != toMapId)) {
            return "Unable to approach due to the force of the ground.";
        }
        return null;
    }

    // Helper method to check if the player has the required item in their inventory
    private boolean hasRequiredItem(Character player) {
        Item item = player.getInventory(InventoryType.CASH).findById(REQUIRED_ITEM_ID);
        return item != null;
    }

    public static boolean isLionKingMap(int mapid) {
        return mapid >= 211060000 && mapid <= 211070550;
    }

    public static boolean isTwilightPerion(int mapid) {
        return mapid >= 271000000 && mapid <= 273060200;
    }

    public static boolean isGrandisandArcane(int mapid) {
        return mapid >= 400000000 && mapid <= 460000000;
    }

    public static boolean isRestrictedMap(int mapid) {
        return mapid == 401053002 || (mapid >= 271030000 && mapid <= 271040300);
    }
}

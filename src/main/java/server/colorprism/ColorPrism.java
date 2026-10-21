package server.colorprism;

import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.YamlConfig;
import net.packet.Packet;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.server.SoloMaplingConstants;

import java.util.List;

/**
 * Coloring Prism (item 5782000, slums.dll {@code MAPLEROOT_COLOR_PRISM}): dyes a Cash equip's body or effect layer,
 * or the character's hair or eyes. The handler is {@code WeaponTintHandler}, the wire format
 * {@link ColorPrismPackets}, the tint rules {@link Tint}, storage {@link ColorPrismStore} (migration 010).
 * <p>
 * Off unless {@code server.COLOR_PRISM_ENABLED} is true AND migration 010's tables exist; when off, nothing is
 * loaded, saved or sent and the handler ignores every request. Bots never get packets and never appear in a table.
 * <p>
 * The client only asks for a snapshot when the window opens, so the server pushes:
 * <ul>
 *   <li>on every map entry: the entrant's own snapshot, and the map's table (to the whole map when the entrant
 *       shows a tint, otherwise only to the entrant);</li>
 *   <li>after an apply / restore: the result, the snapshot and the table to the whole map;</li>
 *   <li>on an equip change by a player who owns a tinted equip: the snapshot and the table to the whole map.</li>
 * </ul>
 */
public final class ColorPrism {

    private ColorPrism() {
    }

    public static boolean enabled() {
        return YamlConfig.config.server.COLOR_PRISM_ENABLED && ColorPrismStore.schemaPresent();
    }

    /** A real player: never a bot (BotClient or an id at or above the bot base) nor the console character. */
    public static boolean isPlayer(Character chr) {
        return chr != null && !BotHelpers.isBot(chr)
                && chr.getId() < SoloMaplingConstants.GameConstants.BOT_BASE_ID
                && chr.getId() != SoloMaplingConstants.GameConstants.CONSOLE_BOT_ID;
    }

    /** Called by MapleMap.addPlayer once the player is in the map. */
    public static void onMapEnter(Character chr, MapleMap map) {
        if (map == null || !isPlayer(chr) || !enabled()) {
            return;
        }
        chr.sendPacket(ColorPrismPackets.snapshot(chr));
        if (!ColorPrismPackets.visibleEntries(chr).isEmpty()) {
            broadcastMapTable(map);
        } else {
            chr.sendPacket(ColorPrismPackets.mapTable(map.getAllPlayers()));
        }
    }

    /** Called by Character.equipChanged. */
    public static void onEquipChanged(Character chr) {
        if (!isPlayer(chr) || !enabled() || !ownsTintedEquip(chr)) {
            return;
        }
        chr.sendPacket(ColorPrismPackets.snapshot(chr));
        broadcastMapTable(chr.getMap());
    }

    /** The full table to every real player in the map (the client replaces its table with each one). */
    public static void broadcastMapTable(MapleMap map) {
        if (map == null) {
            return;
        }
        List<Character> players = map.getAllPlayers();
        Packet table = ColorPrismPackets.mapTable(players);
        for (Character other : players) {
            if (isPlayer(other)) {
                other.sendPacket(table);
            }
        }
    }

    static boolean ownsTintedEquip(Character chr) {
        return ownsTinted(chr.getInventory(InventoryType.EQUIPPED)) || ownsTinted(chr.getInventory(InventoryType.EQUIP));
    }

    private static boolean ownsTinted(Inventory inv) {
        if (inv == null) {
            return false;
        }
        inv.lockInventory();
        try {
            for (Item item : inv.list()) {
                if (item instanceof Equip eq && (eq.isTinted() || eq.isFxTinted())) {
                    return true;
                }
            }
        } finally {
            inv.unlockInventory();
        }
        return false;
    }
}

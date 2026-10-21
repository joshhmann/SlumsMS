package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.ItemInformationProvider;
import server.colorprism.ColorPrism;
import server.colorprism.ColorPrismPackets;
import server.colorprism.Tint;

/**
 * Handles {@code RecvOpcode.WEAPON_TINT_ACTION} (0x372E), sent by slums.dll ({@code weapontint.cpp}) for the
 * Coloring Prism window. From the ColorPrism TrueColor package's handler; changes here: the feature gate and bot
 * exclusion ({@link ColorPrism}), a per-player lock so two Confirms cannot spend one prism twice, and seams for tests.
 * <ul>
 *   <li>{@code 0} request - resend every tint this character owns</li>
 *   <li>{@code 1} apply       - invType(1) invPos(2) itemId(4) hue(2) chroma(1) bright(1) prismPos(2) layer(1)</li>
 *   <li>{@code 2} restore     - invType(1) invPos(2) itemId(4) prismPos(2) layer(1)</li>
 *   <li>{@code 3} applyLook   - kind(1) hue(2) chroma(1) bright(1) prismPos(2)</li>
 *   <li>{@code 4} restoreLook - kind(1) prismPos(2)</li>
 * </ul>
 * ONE item, {@link ItemId#COLORING_PRISM}, pays for all four of the window's tabs. Actions 1 and 2 dye an EQUIP --
 * {@code layer} picks its body or its effect sprites -- and name their target explicitly. Actions 3 and 4 dye the
 * character's own HAIR or FACE, which have no inventory address, so they carry a kind instead. Everything the client
 * sends is a HINT and is re-derived here: the item at that position must still exist, must still be the id the
 * client thinks it is, and must be a Cash equip. The prism is found by ownership, preferring the slot it was used
 * from. A prism is consumed only when a tint actually changes.
 */
public final class WeaponTintHandler extends AbstractPacketHandler {

    /** What the handler does to the world, separated so tests can run without WZ data or a database. */
    interface Effects {
        boolean isCash(int itemId);

        void consumePrism(Client c, short slot);

        void save(Character player);

        void broadcastMapTable(Character player);
    }

    private static final Effects LIVE = new Effects() {
        @Override
        public boolean isCash(int itemId) {
            return ItemInformationProvider.getInstance().isCash(itemId);
        }

        @Override
        public void consumePrism(Client c, short slot) {
            InventoryManipulator.removeFromSlot(c, InventoryType.CASH, slot, (short) 1, false);
        }

        @Override
        public void save(Character player) {
            // The equip object is the live one in the inventory, so the normal save persists it. Force one now so a
            // crash before the next autosave cannot lose a consumed prism's worth of colour.
            player.saveCharToDB();
        }

        @Override
        public void broadcastMapTable(Character player) {
            ColorPrism.broadcastMapTable(player.getMap());
        }
    };

    private final Effects effects;

    public WeaponTintHandler() {
        this(LIVE);
    }

    WeaponTintHandler(Effects effects) {
        this.effects = effects;
    }

    @Override
    public void handlePacket(InPacket p, Client c) {
        Character player = c.getPlayer();
        if (player == null || !ColorPrism.isPlayer(player) || !ColorPrism.enabled()) {
            return;
        }
        if (p.available() < Byte.BYTES) {
            return;
        }

        byte action = p.readByte();
        switch (action) {
            case ColorPrismPackets.ACTION_REQUEST_SNAPSHOT -> player.sendPacket(ColorPrismPackets.snapshot(player));
            case ColorPrismPackets.ACTION_APPLY -> {
                if (p.available() < 1 + 2 + 4 + 2 + 1 + 1 + 2 + 1) {
                    return;
                }
                Target target = readTarget(p);
                int hue = p.readShort();   // signed: -1..-360 encode absolute Tone 0..359
                int chroma = p.readByte();
                int bright = p.readByte();
                short prismPos = p.readShort();
                int layer = p.readByte();
                synchronized (player) {
                    handleApply(c, player, target, hue, chroma, bright, prismPos, layer);
                }
            }
            case ColorPrismPackets.ACTION_RESTORE -> {
                if (p.available() < 1 + 2 + 4 + 2 + 1) {
                    return;
                }
                Target target = readTarget(p);
                short prismPos = p.readShort();
                int layer = p.readByte();
                synchronized (player) {
                    handleRestore(c, player, target, prismPos, layer);
                }
            }
            case ColorPrismPackets.ACTION_APPLY_LOOK -> {
                if (p.available() < 1 + 2 + 1 + 1 + 2) {
                    return;
                }
                int kind = p.readByte();
                int hue = p.readShort();
                int chroma = p.readByte();
                int bright = p.readByte();
                short prismPos = p.readShort();
                synchronized (player) {
                    handleApplyLook(c, player, kind, hue, chroma, bright, prismPos);
                }
            }
            case ColorPrismPackets.ACTION_RESTORE_LOOK -> {
                if (p.available() < 1 + 2) {
                    return;
                }
                int kind = p.readByte();
                short prismPos = p.readShort();
                synchronized (player) {
                    handleRestoreLook(c, player, kind, prismPos);
                }
            }
            default -> {
            }
        }
    }

    private static boolean isLookKind(int kind) {
        return kind == ColorPrismPackets.TINT_KEY_HAIR || kind == ColorPrismPackets.TINT_KEY_FACE;
    }

    private void handleApplyLook(Client c, Character player, int kind, int hue, int chroma, int bright,
                                 short prismPos) {
        if (!isLookKind(kind)) {
            fail(player, ColorPrismPackets.RESULT_FAILED, "That can't be dyed with a Coloring Prism.");
            return;
        }
        if (!Tint.isValid(hue, chroma, bright)) {
            fail(player, ColorPrismPackets.RESULT_FAILED, "Those color values are invalid.");
            return;
        }
        short slot = findItem(player, ItemId.COLORING_PRISM, prismPos);
        if (slot == 0) {
            fail(player, ColorPrismPackets.RESULT_NO_ITEM, "You don't have a Coloring Prism.");
            return;
        }
        if (kind == ColorPrismPackets.TINT_KEY_HAIR) {
            player.setHairTint(hue, chroma, bright);
        } else {
            player.setFaceTint(hue, chroma, bright);
        }
        effects.consumePrism(c, slot);
        effects.save(player);
        succeed(player);
    }

    private void handleRestoreLook(Client c, Character player, int kind, short prismPos) {
        if (!isLookKind(kind)) {
            fail(player, ColorPrismPackets.RESULT_FAILED, "That can't be dyed with a Coloring Prism.");
            return;
        }
        final boolean tinted = (kind == ColorPrismPackets.TINT_KEY_HAIR)
                ? player.isHairTinted() : player.isFaceTinted();
        if (!tinted) {
            // Nothing to undo: refuse WITHOUT consuming the prism (Reset then Confirm on a vanilla colour).
            fail(player, ColorPrismPackets.RESULT_NOT_TINTED,
                    kind == ColorPrismPackets.TINT_KEY_HAIR
                            ? "Your hair is already its original color."
                            : "Your eyes are already their original color.");
            return;
        }
        short slot = findItem(player, ItemId.COLORING_PRISM, prismPos);
        if (slot == 0) {
            fail(player, ColorPrismPackets.RESULT_NO_ITEM, "You don't have a Coloring Prism.");
            return;
        }
        if (kind == ColorPrismPackets.TINT_KEY_HAIR) {
            player.clearHairTint();
        } else {
            player.clearFaceTint();
        }
        effects.consumePrism(c, slot);
        effects.save(player);
        succeed(player);
    }

    private record Target(byte invType, short invPos, int itemId) {
    }

    private static Target readTarget(InPacket p) {
        return new Target(p.readByte(), p.readShort(), p.readInt());
    }

    private void handleApply(Client c, Character player, Target target, int hue, int chroma, int bright,
                             short prismPos, int layer) {
        if (!Tint.isValid(hue, chroma, bright)) {
            fail(player, ColorPrismPackets.RESULT_FAILED, "Those color values are invalid.");
            return;
        }
        Equip equip = resolve(player, target);
        if (equip == null) {
            fail(player, ColorPrismPackets.RESULT_NO_CASH_WEAPON, "That item can't be dyed with a Coloring Prism.");
            return;
        }
        short slot = findItem(player, ItemId.COLORING_PRISM, prismPos);
        if (slot == 0) {
            fail(player, ColorPrismPackets.RESULT_NO_ITEM, "You don't have a Coloring Prism.");
            return;
        }
        // The window's Equip and Effects tabs dye the same item under different keys.
        if (layer == ColorPrismPackets.LAYER_EFFECTS) {
            equip.setFxTint(hue, chroma, bright);
        } else {
            equip.setTint(hue, chroma, bright);
        }
        effects.consumePrism(c, slot);
        effects.save(player);
        succeed(player);
    }

    private void handleRestore(Client c, Character player, Target target, short prismPos, int layer) {
        Equip equip = resolve(player, target);
        if (equip == null) {
            fail(player, ColorPrismPackets.RESULT_NO_CASH_WEAPON, "That item can't be dyed with a Coloring Prism.");
            return;
        }
        final boolean fx = layer == ColorPrismPackets.LAYER_EFFECTS;
        if (fx ? !equip.isFxTinted() : !equip.isTinted()) {
            fail(player, ColorPrismPackets.RESULT_NOT_TINTED, "That item is already its original color.");
            return;
        }
        short slot = findItem(player, ItemId.COLORING_PRISM, prismPos);
        if (slot == 0) {
            fail(player, ColorPrismPackets.RESULT_NO_ITEM, "You don't have a Coloring Prism.");
            return;
        }
        if (fx) {
            equip.clearFxTint();
        } else {
            equip.clearTint();
        }
        effects.consumePrism(c, slot);
        effects.save(player);
        succeed(player);
    }

    /**
     * The Cash equip the client is pointing at, or null if it is not one, is not there, or is not what the client
     * said it was. The SIGN of the position picks the inventory: the v83 client calls a worn item's inventory EQUIP(1)
     * and addresses it at -bodypart (or -(100 + bodypart) for a Cash overlay); server-side both live in EQUIPPED.
     */
    private Equip resolve(Character player, Target target) {
        InventoryType type = target.invPos() < 0
                ? InventoryType.EQUIPPED
                : InventoryType.getByType(target.invType());
        if (type != InventoryType.EQUIPPED && type != InventoryType.EQUIP) {
            return null;
        }
        Inventory inv = player.getInventory(type);
        if (inv == null) {
            return null;
        }
        inv.lockInventory();
        try {
            Item item = inv.getItem(target.invPos());
            if (!(item instanceof Equip equip)) {
                return null;
            }
            if (equip.getItemId() != target.itemId()) {
                return null;
            }
            if (!effects.isCash(equip.getItemId())) {
                return null;
            }
            return equip;
        } finally {
            inv.unlockInventory();
        }
    }

    private void succeed(Character player) {
        player.sendPacket(ColorPrismPackets.result(ColorPrismPackets.RESULT_OK));
        player.sendPacket(ColorPrismPackets.snapshot(player));
        effects.broadcastMapTable(player);
    }

    /**
     * The result code is what the DLL reads; the popup is what the player reads (the window closes on Confirm). The
     * snapshot that follows undoes the client's optimistic recolour ({@code WeaponTint_AdoptOptimistic}).
     */
    private static void fail(Character player, byte code, String message) {
        player.sendPacket(ColorPrismPackets.result(code));
        player.sendPacket(ColorPrismPackets.snapshot(player));
        player.dropMessage(1, message);
    }

    /** The CASH slot holding {@code itemId}, preferring {@code hintPos}; 0 (not a valid position) when none. */
    private static short findItem(Character player, int itemId, short hintPos) {
        Inventory cash = player.getInventory(InventoryType.CASH);
        if (cash == null) {
            return 0;
        }
        cash.lockInventory();
        try {
            if (hintPos > 0) {
                Item at = cash.getItem(hintPos);
                if (at != null && at.getItemId() == itemId && at.getQuantity() > 0) {
                    return hintPos;
                }
            }
            for (Item item : cash.list()) {
                if (item.getItemId() == itemId && item.getQuantity() > 0) {
                    return item.getPosition();
                }
            }
        } finally {
            cash.unlockInventory();
        }
        return 0;
    }
}

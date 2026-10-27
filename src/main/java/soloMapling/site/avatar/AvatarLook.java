package soloMapling.site.avatar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a character looks like for its ranking picture: skin, hair, face and the equipped items by their (negative)
 * equip slot, cash-shop covers included ({@code -101} covers {@code -1}, and so on). Pure.
 */
public record AvatarLook(int skin, int hair, int face, Map<Integer, Integer> equipped) {

    /** Bumped when the drawing changes, so every cached picture is drawn again. */
    public static final int RENDER_VERSION = 1;
    /** The cash-shop cover slots sit 100 below the slot they cover. */
    public static final int CASH_OFFSET = 100;
    /** The slots drawn on the avatar: cap, face, eye, earring, top/overall, bottom, shoes, gloves, cape, shield, weapon. */
    public static final int FIRST_SLOT = 1;
    public static final int LAST_SLOT = 11;
    public static final int WEAPON_SLOT = 11;

    public AvatarLook {
        equipped = Map.copyOf(equipped);
    }

    /** One item the picture draws, and for a cash weapon the weapon it is drawn over (its art is per weapon type). */
    public record Shown(int slot, int itemId, int underWeapon) {
    }

    /**
     * The items as the game draws them: a cash cover replaces the normal item of its slot; a cash weapon is drawn in
     * the shape of the real weapon under it and is not drawn without one. Ordered by slot.
     */
    public List<Shown> shown() {
        List<Shown> out = new ArrayList<>();
        for (int slot = FIRST_SLOT; slot <= LAST_SLOT; slot++) {
            Integer normal = equipped.get(-slot);
            Integer cash = equipped.get(-(slot + CASH_OFFSET));
            if (slot == WEAPON_SLOT) {
                if (normal == null) {
                    continue;
                }
                out.add(cash != null ? new Shown(slot, cash, normal) : new Shown(slot, normal, 0));
            } else if (cash != null) {
                out.add(new Shown(slot, cash, 0));
            } else if (normal != null) {
                out.add(new Shown(slot, normal, 0));
            }
        }
        return out;
    }

    /** The real weapon (two-handed weapons stand differently), 0 for none. */
    public int weapon() {
        return equipped.getOrDefault(-WEAPON_SLOT, 0);
    }

    /**
     * The picture's cache key: a hash of the render version, skin, hair, face and every drawn slot's item (cash and
     * normal), so a ring or pendant change does not draw the picture again but a new cap does. 16 hex chars.
     */
    public String cacheKey() {
        StringBuilder sb = new StringBuilder("v").append(RENDER_VERSION).append('|').append(skin).append('|')
                .append(hair).append('|').append(face);
        Map<Integer, Integer> drawn = new TreeMap<>();
        for (Map.Entry<Integer, Integer> e : equipped.entrySet()) {
            int slot = -e.getKey();
            int base = slot > CASH_OFFSET ? slot - CASH_OFFSET : slot;
            if (base >= FIRST_SLOT && base <= LAST_SLOT && e.getValue() != null && e.getValue() > 0) {
                drawn.put(slot, e.getValue());
            }
        }
        drawn.forEach((slot, id) -> sb.append('|').append(slot).append(':').append(id));
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

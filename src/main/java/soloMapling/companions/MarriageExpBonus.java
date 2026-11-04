package soloMapling.companions;

import client.Character;
import soloMapling.ArtificialPlayer.BotHelpers;

/**
 * Ticket 189 stage 3: married players earn extra EXP while their spouse is on the same map. +10% with a playerbot
 * spouse, +5% with an owned-alt spouse that is out. EXP only. Added on top of the multiplier chain like the Pendant
 * of the Spirit (never inside it), so it does not compound with coupons or the family buff. Behind
 * {@code -Dslums.marriage.expbonus} (default OFF).
 */
public final class MarriageExpBonus {
    public static final String SWITCH = "slums.marriage.expbonus";
    public static final double MARRIAGE_EXP_BONUS_PLAYERBOT = 0.10;
    public static final double MARRIAGE_EXP_BONUS_ALTBOT = 0.05;

    private MarriageExpBonus() {
    }

    public static boolean enabled() {
        try {
            return Boolean.getBoolean(SWITCH);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Floor of amount x rate; never negative, 0 stays 0, no overflow. */
    static int share(long amount, double rate) {
        if (amount <= 0 || rate <= 0) return 0;
        return (int) Math.min((long) Math.floor(amount * rate), Integer.MAX_VALUE);
    }

    /** The extra EXP for {@code chr} on a gain of {@code base}; 0 when no condition holds. */
    public static int bonusFor(Character chr, long base) {
        if (base <= 0 || !enabled() || chr == null || !chr.isMarried() || chr.getMap() == null) return 0;
        Character spouse = chr.getMap().getCharacterById(chr.getPartnerId());
        if (spouse == null || spouse == chr) return 0;
        if (spouse.isOwnedAlt()) {
            return spouse.isOwnedAltClosing() ? 0 : share(base, MARRIAGE_EXP_BONUS_ALTBOT);
        }
        return BotHelpers.isRosterBot(spouse) ? share(base, MARRIAGE_EXP_BONUS_PLAYERBOT) : 0;
    }
}

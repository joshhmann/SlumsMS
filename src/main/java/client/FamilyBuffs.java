package client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Ticket 53 (1.6.1): the timed EXP and drop multipliers the Family entitlements give. Stock Cosmic left these
 * "not implemented". The character's rate fields are whole numbers (coupons are 2x / 3x), so a 1.5x buff lives here
 * as its own multiplier, applied where a mob's EXP is given ({@code Monster.giveExpToCharacter}) and where its drop
 * rate is taken ({@code MapleMap.dropFromMonster}). It stacks with coupons and world rates the way a coupon does.
 *
 * <p>Family buffs never stack with each other: a second one of the same kind keeps the higher rate, and the later
 * end when the rates are equal. Every multiplier is capped at {@link #MAX_RATE}. Kept by character id, so a buff
 * survives a relog or channel change (not a restart). Bots are never given one.
 */
public final class FamilyBuffs {

    /** The highest multiplier a Family buff gives (2x, the tooltips' best). */
    public static final double MAX_RATE = 2.0;

    /** What one entitlement gives: EXP and/or drop, the rate, the minutes, and who gets it. */
    public record Spec(boolean exp, boolean drop, double rate, int minutes, Target target) {
    }

    public enum Target { SELF, PARTY, BONDING }

    /** One character's active Family buffs (0 = none of that kind). */
    record Active(double exp, long expEnds, double drop, long dropEnds) {
    }

    private static final Map<Integer, Active> active = new ConcurrentHashMap<>();

    private FamilyBuffs() {
    }

    /** The buff an entitlement gives, or null for Reunion and Summon (no buff). Tooltips: FamilyEntitlement. */
    public static Spec specOf(FamilyEntitlement e) {
        return switch (e) {
            case SELF_DROP_1_5 -> new Spec(false, true, 1.5, 15, Target.SELF);
            case SELF_EXP_1_5 -> new Spec(true, false, 1.5, 15, Target.SELF);
            case SELF_DROP_2 -> new Spec(false, true, 2.0, 15, Target.SELF);
            case SELF_EXP_2 -> new Spec(true, false, 2.0, 15, Target.SELF);
            case SELF_DROP_2_30MIN -> new Spec(false, true, 2.0, 30, Target.SELF);
            case SELF_EXP_2_30MIN -> new Spec(true, false, 2.0, 30, Target.SELF);
            case PARTY_DROP_2_30MIN -> new Spec(false, true, 2.0, 30, Target.PARTY);
            case PARTY_EXP_2_30MIN -> new Spec(true, false, 2.0, 30, Target.PARTY);
            case FAMILY_BONDING -> new Spec(true, true, 2.0, 30, Target.BONDING);
            default -> null;
        };
    }

    /** Gives character {@code chrId} the buff of {@code spec} from {@code now}. */
    public static void grant(int chrId, Spec spec, long now) {
        sweep(now);
        long ends = now + TimeUnit.MINUTES.toMillis(spec.minutes());
        double rate = Math.min(MAX_RATE, spec.rate());
        active.compute(chrId, (id, old) -> {
            Active a = old == null ? new Active(0, 0, 0, 0) : live(old, now);
            double exp = a.exp(), drop = a.drop();
            long expEnds = a.expEnds(), dropEnds = a.dropEnds();
            if (spec.exp()) {
                if (rate > exp) {
                    exp = rate;
                    expEnds = ends;
                } else if (rate == exp) {
                    expEnds = Math.max(expEnds, ends);
                }
            }
            if (spec.drop()) {
                if (rate > drop) {
                    drop = rate;
                    dropEnds = ends;
                } else if (rate == drop) {
                    dropEnds = Math.max(dropEnds, ends);
                }
            }
            return new Active(exp, expEnds, drop, dropEnds);
        });
    }

    private static Active live(Active a, long now) {
        boolean expOn = a.exp() > 0 && a.expEnds() > now;
        boolean dropOn = a.drop() > 0 && a.dropEnds() > now;
        return new Active(expOn ? a.exp() : 0, expOn ? a.expEnds() : 0, dropOn ? a.drop() : 0, dropOn ? a.dropEnds() : 0);
    }

    /** The EXP multiplier of {@code chrId}'s Family buff now (1 when none). */
    public static double expMultiplier(int chrId, long now) {
        Active a = active.get(chrId);
        return a == null || a.exp() <= 0 || a.expEnds() <= now ? 1.0 : a.exp();
    }

    /** The drop-rate multiplier of {@code chrId}'s Family buff now (1 when none). */
    public static double dropMultiplier(int chrId, long now) {
        Active a = active.get(chrId);
        return a == null || a.drop() <= 0 || a.dropEnds() <= now ? 1.0 : a.drop();
    }

    public static double expMultiplier(Character chr) {
        return chr == null || active.isEmpty() ? 1.0 : expMultiplier(chr.getId(), System.currentTimeMillis());
    }

    public static double dropMultiplier(Character chr) {
        return chr == null || active.isEmpty() ? 1.0 : dropMultiplier(chr.getId(), System.currentTimeMillis());
    }

    /** Minutes left on the EXP / drop buff (0 when none), for messages and QA. */
    public static long[] minutesLeft(int chrId, long now) {
        Active a = active.get(chrId);
        if (a == null) {
            return new long[]{0, 0};
        }
        return new long[]{a.expEnds() > now ? TimeUnit.MILLISECONDS.toMinutes(a.expEnds() - now + 59_999) : 0,
                a.dropEnds() > now ? TimeUnit.MILLISECONDS.toMinutes(a.dropEnds() - now + 59_999) : 0};
    }

    /** Takes {@code chrId}'s Family buffs away (QA teardown). */
    public static void clear(int chrId) {
        active.remove(chrId);
    }

    /** Drops ended buffs (called now and then so the map does not keep logged-out players forever). */
    public static void sweep(long now) {
        active.replaceAll((id, a) -> live(a, now));
        active.values().removeIf(a -> a.exp() == 0 && a.drop() == 0);
    }

    /** The player-facing line for a buff just given. */
    public static String describe(Spec spec) {
        String what = spec.exp() && spec.drop() ? "EXP and drop rate" : spec.exp() ? "EXP" : "Drop rate";
        String rate = spec.rate() == Math.floor(spec.rate()) ? Integer.toString((int) spec.rate()) : Double.toString(spec.rate());
        return "[Family] " + what + " x" + rate + " for " + spec.minutes() + " minutes.";
    }
}

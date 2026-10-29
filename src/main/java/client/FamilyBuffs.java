package client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Ticket 53 (1.6.1): the timed EXP and drop multipliers the Family entitlements give. Stock Cosmic left these
 * "not implemented". The character's rate fields are whole numbers (coupons are 2x / 3x), so a 1.5x buff lives here
 * as its own multiplier, applied where a mob's EXP is given ({@code Monster.giveExpToCharacter}) and where its drop
 * rate is taken ({@code MapleMap.dropFromMonster}). It stacks with coupons and world rates the way a coupon does.
 *
 * <p>Family buffs never stack with each other: a second one of the same kind keeps the higher rate, and the later
 * end when the rates are equal. Every multiplier is capped at {@link #MAX_RATE}. Kept by character id, so a buff
 * survives a relog or channel change, and saved to {@code family_buffs} (migration 053) with its end time, so it
 * survives a restart too (the clock runs while the server is down, as a coupon's does). A bot gets one only from
 * Family Bonding (b035); its off-screen grind honours it in TrainingBot.
 *
 * <p>1.6.1 promise audit: the tooltips (sent by this server, {@link FamilyEntitlement}) say a perk "will be nullified"
 * while an EXP (drop) event is in progress, and that is what the original game did, so it is enforced: while an EXP
 * event applies to the character (a world-event hot spot on its map, or a GM EXP rate above the world's configured
 * rate) its Family EXP buff gives 1x; the same for the drop buff and a GM drop-rate event. The buff's time still runs.
 * Coupons are not events and still stack.
 */
public final class FamilyBuffs {

    private static final Logger log = LoggerFactory.getLogger(FamilyBuffs.class);

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

    /** One character's saved buffs (a {@code family_buffs} row). */
    public record Saved(int chrId, double exp, long expEnds, double drop, long dropEnds) {
    }

    /** Where active buffs are kept across a restart. */
    public interface Store {
        void save(Saved row);

        void delete(int chrId);

        /** Every row still running at {@code now} (and forgets the ended ones). */
        List<Saved> loadAll(long now);
    }

    private static volatile Store store = new DbStore();

    /** Whether an EXP event applies to the character now (the tooltips' "nullified"). Tests replace it. */
    static volatile Predicate<Character> expEvent = FamilyBuffs::expEventOn;
    /** Whether a drop event applies to the character now. Tests replace it. */
    static volatile Predicate<Character> dropEvent = FamilyBuffs::dropEventOn;

    private FamilyBuffs() {
    }

    /** Tests: keep the buffs somewhere else (null: the database again). */
    public static void useStore(Store s) {
        store = s == null ? new DbStore() : s;
    }

    /** Tests: decide what counts as an EXP / drop event (null: the real rules again). */
    public static void useEventRules(Predicate<Character> exp, Predicate<Character> drop) {
        expEvent = exp == null ? FamilyBuffs::expEventOn : exp;
        dropEvent = drop == null ? FamilyBuffs::dropEventOn : drop;
    }

    /** Boot (Server, with the families): reads the buffs still running back. Returns how many characters have one. */
    public static int load(long now) {
        int n = 0;
        for (Saved row : store.loadAll(now)) {
            Active a = live(new Active(row.exp(), row.expEnds(), row.drop(), row.dropEnds()), now);
            if (a.exp() > 0 || a.drop() > 0) {
                active.put(row.chrId(), a);
                n++;
            }
        }
        return n;
    }

    private static void persist(int chrId) {
        Active a = active.get(chrId);
        try {
            if (a == null || (a.exp() == 0 && a.drop() == 0)) {
                store.delete(chrId);
            } else {
                store.save(new Saved(chrId, a.exp(), a.expEnds(), a.drop(), a.dropEnds()));
            }
        } catch (RuntimeException e) {
            log.warn("Could not save the Family buff of character {} (it still runs until a restart)", chrId, e);
        }
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
        persist(chrId);
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

    /** What a mob kill multiplies EXP by: the buff, or 1 while an EXP event applies to {@code chr} ("nullified"). */
    public static double expMultiplier(Character chr) {
        if (chr == null || active.isEmpty()) {
            return 1.0;
        }
        double m = expMultiplier(chr.getId(), System.currentTimeMillis());
        return m > 1.0 && expEvent.test(chr) ? 1.0 : m;
    }

    /** What the drop rate is multiplied by: the buff, or 1 while a drop event applies to {@code chr}. */
    public static double dropMultiplier(Character chr) {
        if (chr == null || active.isEmpty()) {
            return 1.0;
        }
        double m = dropMultiplier(chr.getId(), System.currentTimeMillis());
        return m > 1.0 && dropEvent.test(chr) ? 1.0 : m;
    }

    /** An EXP event for this character: a world-event hot spot on its map, or a GM EXP rate event on its world. */
    static boolean expEventOn(Character chr) {
        try {
            if (soloMapling.worldevents.WorldEvents.expMultiplier(chr) > 1f) {
                return true;
            }
            net.server.world.World w = chr.getWorldServer();
            return w != null && w.isExpRateEvent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A drop event for this character: a GM drop rate event on its world. */
    static boolean dropEventOn(Character chr) {
        try {
            net.server.world.World w = chr.getWorldServer();
            return w != null && w.isDropRateEvent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The line a player gets when a perk is used while an event nullifies it (null when none does). */
    public static String eventNote(Character chr, Spec spec) {
        if (chr == null || spec == null) {
            return null;
        }
        boolean exp = spec.exp() && expEvent.test(chr), drop = spec.drop() && dropEvent.test(chr);
        if (!exp && !drop) {
            return null;
        }
        return "[Family] " + (exp && drop ? "An EXP and drop event is" : exp ? "An EXP event is" : "A drop event is")
                + " on: this perk gives nothing extra while it runs (its time still counts down).";
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
        if (active.remove(chrId) != null) {
            persist(chrId);
        }
    }

    /** Tests: forget every buff in memory (the store is not touched), as a restart does. */
    static void forgetAllForTest() {
        active.clear();
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

    /** {@code family_buffs} (migration 053-family-buffs). Without the table the buffs still work, until a restart. */
    public static final class DbStore implements Store {
        /** The upsert of one character's row (public for the migration test). */
        public static final String SAVE_SQL = "INSERT INTO family_buffs (charid, exp_rate, exp_ends, drop_rate,"
                + " drop_ends) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE exp_rate = VALUES(exp_rate), exp_ends ="
                + " VALUES(exp_ends), drop_rate = VALUES(drop_rate), drop_ends = VALUES(drop_ends)";
        @Override
        public void save(Saved r) {
            try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(SAVE_SQL)) {
                ps.setInt(1, r.chrId());
                ps.setDouble(2, r.exp());
                ps.setLong(3, r.expEnds());
                ps.setDouble(4, r.drop());
                ps.setLong(5, r.dropEnds());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void delete(int chrId) {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("DELETE FROM family_buffs WHERE charid = ?")) {
                ps.setInt(1, chrId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public List<Saved> loadAll(long now) {
            List<Saved> out = new ArrayList<>();
            try (Connection con = DatabaseConnection.getConnection()) {
                try (PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM family_buffs WHERE exp_ends <= ? AND drop_ends <= ?")) {
                    ps.setLong(1, now);
                    ps.setLong(2, now);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT charid, exp_rate, exp_ends, drop_rate, drop_ends FROM family_buffs");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Saved(rs.getInt(1), rs.getDouble(2), rs.getLong(3), rs.getDouble(4), rs.getLong(5)));
                    }
                }
            } catch (SQLException | RuntimeException e) {
                log.warn("Could not read the saved Family buffs (migration 053 missing?); none restored", e);
            }
            return out;
        }
    }
}

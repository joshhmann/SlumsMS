package soloMapling.qa;

import java.util.List;
import java.util.Set;

/** 1.5.1 co-1: the two all-day EXP cards (owner) and {@code @coupons}, with an older timed card for contrast. */
final class CouponScenarios {
    static final int EXP_2X_ALL_DAY = 5211048;      // sold: "2x EXP Card 7 days"
    static final int EXP_WEEKDAYS_7_11 = 5211004;   // off sale since 1.5.1, still works when owned: Mon-Fri 7-11 AM

    private CouponScenarios() { }

    static List<QaScenario> all() { return List.of(new Co1Hours()); }

    /** co-1: an all-day card and an old timed card, then @coupons and the Cash Shop's two EXP cards. */
    static final class Co1Hours implements QaScenario {
        public String id() { return "co-1"; }
        public String title() { return "EXP cards: the two all-day Cash Shop cards and @coupons (server/Pacific)"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 5; }

        public List<String> stage(QaContext ctx) {
            if (ctx.giveBack(EXP_2X_ALL_DAY, 1) < 1 || ctx.giveBack(EXP_WEEKDAYS_7_11, 1) < 1) {
                throw new QaRefusal("Could not give the coupons (cash inventory full?).");
            }
            return List.of("Type @coupons: server time (UTC-6) and Pacific; the 2x card works any time, the old weekday"
                            + " card shows Mon-Fri 7-11 AM server and its Pacific hours, each with working now or not.",
                    "Open the Cash Shop (Setup tab, Game category): only \"2x EXP Card 7 days\" (6,000 NX) and \"3x EXP"
                            + " Card 1 day\" (3,000 NX), no drop cards. Buy the 3x: the item expires in 24 hours.");
        }
    }
}

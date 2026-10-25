package soloMapling.qa;

import java.util.List;
import java.util.Set;

/** 1.5.1 co-1: rate coupon hours are readable (Cash Shop names and {@code @coupons}). */
final class CouponScenarios {
    static final int EXP_WEEKDAYS_7_11 = 5211004;   // "2x EXP M-F 7-11am 30d"
    static final int EXP_DAILY_1_5 = 5211016;       // "2x EXP 1-5pm 10d"

    private CouponScenarios() { }

    static List<QaScenario> all() { return List.of(new Co1Hours()); }

    /** co-1: two coupons with different hours, then @coupons and the Cash Shop's coupon names. */
    static final class Co1Hours implements QaScenario {
        public String id() { return "co-1"; }
        public String title() { return "Coupon hours: Cash Shop names and @coupons (server and Pacific time)"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 5; }

        public List<String> stage(QaContext ctx) {
            if (ctx.giveBack(EXP_WEEKDAYS_7_11, 1) < 1 || ctx.giveBack(EXP_DAILY_1_5, 1) < 1) {
                throw new QaRefusal("Could not give the coupons (cash inventory full?).");
            }
            return List.of("Type @coupons: server time (UTC-6) and Pacific time, then each coupon's hours on both clocks"
                            + " and whether it is working now.",
                    "Open the Cash Shop (Setup tab, Game category): names read like \"2x EXP M-F 7-11am 30d\" and fit"
                            + " the cell on two lines. Buy one: a chat line says when it works.");
        }
    }
}

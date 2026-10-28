package soloMapling.qa;

import client.Character;
import soloMapling.mechanics.KpqStaging;
import soloMapling.mechanics.Mechanics;
import soloMapling.mechanics.MechanicsRuns;
import soloMapling.mechanics.RunState;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static soloMapling.qa.QaLog.Topic.PQ;

/**
 * 1.7.0 ticket 130 (bots play the mechanics), {@code mx-1}: four fresh bots form a party on Kerning City's PQ map,
 * start the Kerning PQ as Lakelis starts it, and clear stage 1 on their own: the answerers ask Cloto (our
 * {@code scripts/npc/9020001.js}), farm exactly their answer in Ligator coupons (with a persona's 0-2 visible wrong
 * hand-ins first), trade them for passes and drop the passes by the leader, who hands them in. Pass = the stage's
 * clear property set within {@link #LIMIT_S} seconds. The bots leave the party and the world afterwards (the PQ ends).
 * Auto-QA: t-mx-1. Needs {@code -Dslums.mechanics=true}; refuses otherwise.
 */
final class MechanicsScenarios {

    /** Stage 1 must clear within this (the KPQ event gives 30 minutes for all five stages). */
    static final int LIMIT_S = 15 * 60;

    private MechanicsScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Mx1KpqStage1());
    }

    /** The line mx-1 logs when the run ends: PASS when the stage cleared in time, else FAIL with the reason. */
    static String verdict(boolean cleared, long clearedAfterMs, String endedBecause, int wrongTries, int bots) {
        long secs = clearedAfterMs / 1000;
        if (cleared && secs <= LIMIT_S) {
            return "MX1 PASS cleared stage 1 in " + secs + " s (limit " + LIMIT_S + " s) by " + bots + " bots, "
                    + wrongTries + " wrong tries shown";
        }
        if (cleared) {
            return "MX1 FAIL cleared stage 1 only after " + secs + " s (limit " + LIMIT_S + " s)";
        }
        return "MX1 FAIL " + endedBecause;
    }

    static final class Mx1KpqStage1 implements QaScenario {

        public String id() {
            return "mx-1";
        }

        public String title() {
            return "Bots play the mechanics: four bots clear Kerning PQ stage 1 on their own (1.7.0)";
        }

        public String usage() {
            return "[perfect]";
        }

        public Set<QaLog.Topic> topics() {
            return EnumSet.of(PQ);
        }

        public Set<Integer> watchedMaps(Character gm, String[] args) {
            return Set.of(KpqStaging.RECRUIT_MAP, 103000800);
        }

        public int minutes(String[] args) {
            return 18;
        }

        public List<String> stage(QaContext ctx) {
            if (!Mechanics.enabled()) {
                throw new QaRefusal("Mechanics is off: the server needs -D" + Mechanics.FLAG + "=true.");
            }
            boolean perfect = ctx.args() != null && ctx.args().length > 0
                    && "perfect".equals(ctx.args()[0].toLowerCase(Locale.ROOT));
            KpqStaging.Staged s = KpqStaging.start(ctx.gm(), false, 4, perfect, LIMIT_S * 1000L,
                    line -> ctx.run().note("MX1", line), done -> {
                    });
            if (!s.ok()) {
                String why = s.error() == null ? "the run ended at once" : s.error();
                if (why.contains("another party is in KPQ")) {
                    why += " (wait for it)";
                }
                throw new QaRefusal("mx-1 could not stage: " + why);
            }
            MechanicsRuns.Active a = s.active();
            ctx.onStop("stop the KPQ run and take the " + s.spawned().size() + " bots away", () -> {
                MechanicsRuns.get().stop(a, "QA stop");
                KpqStaging.cleanup(s.spawned());
            });
            ctx.run().note("MX1", "MX1 START kpq stage 1 with " + a.bots().size() + " bots in " + a.instanceName()
                    + (perfect ? " (perfect)" : ""));
            // the verdict line once the run ends (the tick thread calls the reporter with "RUN end ...")
            Thread watcher = new Thread(() -> {
                RunState r = a.run();
                long deadline = System.currentTimeMillis() + (LIMIT_S + 120) * 1000L;
                while (!r.ended() && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                ctx.run().note("MX1", verdict(r.cleared(), r.clearedAfterMs(),
                        r.ended() ? r.endedBecause() : "no end within the limit", r.wrongShownTotal(),
                        a.bots().size()));
            }, "qa-mx-1");
            watcher.setDaemon(true);
            watcher.start();
            return List.of(
                    a.bots().size() + " bots are in Kerning PQ stage 1 (" + a.instanceName() + "). They ask Cloto,"
                            + " farm Ligator coupons, hand them in for passes and give the passes to their leader.",
                    "The log has a RUN line per step (answers, hand-ins with wrong tries, drops) and a verdict line at"
                            + " the end; the bots leave 20 s after."); // names no verdict word: a waitFor must not match it
        }
    }
}

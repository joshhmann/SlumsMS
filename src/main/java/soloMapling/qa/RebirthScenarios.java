package soloMapling.qa;

import client.Character;
import soloMapling.ArtificialPlayer.BotRebirthQa;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 1.6.1, ticket 30: rb-1, a regular earns a rebirth. Stages an online bot at level 200 (4th job on its own build),
 * forces the pace and walks its earned path: two boss runs, the key crafted, the trip to Wyatt granted, the rebirth
 * at rebirth.js's rules. Prints what changed and what must not have (gear, mesos, cash items, past skills).
 * {@code !qa do check} prints the bot again; stop puts the bot back exactly as it was. Auto-QA t-rb-1 runs the same.
 */
final class RebirthScenarios {
    private RebirthScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Rebirth());
    }

    static final class Rebirth implements QaScenario {
        public String id() { return "rb-1"; }
        public String title() { return "Bot rebirth: a level 200 regular earns its key and is reborn (pace forced)"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 20; }

        private volatile BotRebirthQa.Before before;

        public List<String> stage(QaContext ctx) {
            Character gm = ctx.gm();
            BotRebirthQa.Before b;
            try {
                b = BotRebirthQa.pick(gm);
            } catch (IllegalStateException e) {
                throw new QaRefusal(e.getMessage());
            }
            before = b;
            ctx.onStop("put the QA bot back as it was", () -> ctx.run().note("CLEANUP", BotRebirthQa.restore(b)));
            if (!BotRebirthQa.raise(b)) {
                throw new QaRefusal(b.bot().getName() + " did not reach a 4th job at level 200 on its build");
            }
            ctx.log("RB1 STAGED " + BotRebirthQa.describe(b) + " (was lv " + b.level() + " job " + b.job() + ")");
            List<String> walk = BotRebirthQa.walk(b);
            walk.forEach(line -> ctx.log("RB1 " + line));
            List<String> bad = BotRebirthQa.check(b);
            ctx.log("RB1 " + (bad.isEmpty() ? "OK" : "BAD " + String.join("; ", bad)));
            List<String> out = new ArrayList<>();
            out.add(walk.getFirst());
            out.add(bad.isEmpty() ? "Checks: level 1 beginner, key and boss drops gone, rebirth EXP rate, same gear,"
                    + " same mesos, no cash items, past skills kept." : "PROBLEMS: " + String.join("; ", bad));
            out.add("Now: " + BotRebirthQa.describe(b) + ". Look at it (!warpto " + b.bot().getName()
                    + "): same gear on, level 1.");
            out.add("!qa do check prints it again. !qa stop puts " + b.bot().getName() + " back at lv " + b.level()
                    + " job " + b.job() + ".");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"check".equals(name) || before == null) {
                throw new QaRefusal("rb-1 steps: check");
            }
            String line = "RB1 CHECK " + BotRebirthQa.describe(before);
            ctx.run().note("RB1", line);
            return List.of(line);
        }
    }
}

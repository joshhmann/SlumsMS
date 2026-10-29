package soloMapling.qa;

import client.Character;
import soloMapling.ArtificialPlayer.BotRebirthQa;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 1.6.1, ticket 30: rb-1, a regular earns a rebirth. Stages an online bot at level 200 (4th job on its own build),
 * forces the pace and walks its earned path: Zakum and Horntail run off screen on the players' rules, the key
 * crafted, the trip to Wyatt granted, the rebirth at rebirth.js's rules. Prints what changed and what must not have
 * (only level-1 gear worn and the rest kept, mesos, cash items, past skills). {@code !qa do check} prints the bot
 * again; stop puts the bot back exactly as it was. Auto-QA t-rb-1 runs the same.
 *
 * <p>rb-2 (promise audit): the same bot staged at rebirth 1 goes into a real Von Leon run (Von Leon summoned at 1% of
 * his HP so it ends in minutes); watch it ({@code !warpto}) and
 * {@code !qa do check} until it is over, which then crafts the key and makes the rebirth. Auto-QA t-rb-2.
 */
final class RebirthScenarios {
    private RebirthScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Rebirth(), new RebirthBossKill());
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
            out.add(bad.isEmpty() ? "Checks: level 1 beginner, key and boss items gone, rebirth EXP rate, only"
                    + " level-1 gear on and the rest in its bag, same mesos, no cash items, past skills kept."
                    : "PROBLEMS: " + String.join("; ", bad));
            out.add("Now: " + BotRebirthQa.describe(b) + ". Look at it (!warpto " + b.bot().getName()
                    + "): level 1, in what a level 1 can wear.");
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

    /** rb-2: a staged level 200 regular forms a real Von Leon run; check until it is over, then it is reborn. */
    static final class RebirthBossKill implements QaScenario {
        public String id() { return "rb-2"; }
        public String title() { return "Bot rebirth: a regular earns its key item from a real Von Leon kill"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 40; }

        private volatile BotRebirthQa.Before before;
        private volatile soloMapling.ArtificialPlayer.BotProgressionSystem.RebirthBossRuns.Run run;
        private volatile boolean finished;

        public List<String> stage(QaContext ctx) {
            BotRebirthQa.Before b;
            try {
                b = BotRebirthQa.pick(ctx.gm());
            } catch (IllegalStateException e) {
                throw new QaRefusal(e.getMessage());
            }
            before = b;
            ctx.onStop("put the QA bot back as it was", () -> ctx.run().note("CLEANUP", BotRebirthQa.restore(b)));
            if (!BotRebirthQa.raise(b)) {
                throw new QaRefusal(b.bot().getName() + " did not reach a 4th job at level 200 on its build");
            }
            ctx.log("RB2 STAGED " + BotRebirthQa.stageVonLeon(b));
            soloMapling.ArtificialPlayer.BotProgressionSystem.BotRebirth.certainFor(40 * 60_000L);
            ctx.onStop("bothering back to normal", () ->
                    soloMapling.ArtificialPlayer.BotProgressionSystem.BotRebirth.certainFor(0));
            String[] why = {"?"};
            run = BotRebirthQa.startVonLeon(b, why);
            if (run == null) {
                throw new QaRefusal("no Von Leon run formed: " + why[0]);
            }
            ctx.log("RB2 RUN " + run.crewNames());
            return List.of(b.bot().getName() + " formed a Von Leon run with " + run.crewNames() + ".",
                    "Watch it: !warpto " + b.bot().getName() + " (the room is an instance: you see the recruit map"
                            + " 211070000 before they go in).",
                    "!qa do check until it says DONE; then it crafts its key and is reborn. !qa stop puts it back.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!"check".equals(name) || before == null || run == null) {
                throw new QaRefusal("rb-2 steps: check");
            }
            List<String> out = new ArrayList<>();
            out.add("RB2 RUN " + run.phase() + " cleared " + run.cleared() + " item by " + run.gotBy(before.bot().getId())
                    + (run.result() == null ? "" : " (" + run.result() + ")"));
            if (run.phase() == soloMapling.ArtificialPlayer.BotProgressionSystem.RebirthBossRuns.Phase.DONE && !finished) {
                finished = true;
                BotRebirthQa.RunResult r = BotRebirthQa.result(before, run);
                r.notes().forEach(line -> ctx.log("RB2 " + line));
                if (r.cleared() && r.gotBy() != null) {
                    BotRebirthQa.finishRebirth(before).forEach(line -> out.add("RB2 " + line));
                    List<String> bad = BotRebirthQa.check(before, 1);
                    out.add("RB2 " + (bad.isEmpty() ? "OK" : "BAD " + String.join("; ", bad)));
                } else {
                    out.add("RB2 BAD the run did not earn the item");
                }
            }
            out.forEach(line -> ctx.run().note("RB2", line));
            return out;
        }
    }
}

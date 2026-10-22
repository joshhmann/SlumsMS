package soloMapling.qa;

import client.Character;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.qa.QaLog.Topic.ATTACK;
import static soloMapling.qa.QaLog.Topic.BOT_CHAT;
import static soloMapling.qa.QaLog.Topic.COMBAT;
import static soloMapling.qa.QaLog.Topic.HABITS;
import static soloMapling.qa.QaLog.Topic.MANNERS;
import static soloMapling.qa.QaLog.Topic.SPREAD;

/**
 * 1.5.0 rc3, "bots fight like people" and "spread out" (cb-1 .. cb-4, sp-1). Every scenario stages fresh on-demand
 * training bots (memory only) on the GM's map, a map with mobs, and takes them away at stop. A map without mobs
 * falls back to the Henesys Hunting Ground.
 */
final class CombatScenarios {

    static final int HHG = QaScenarios.HENESYS_HUNTING_GROUND;
    // 3rd-job jobs with an AoE, a single-target attack and buffs, one per class (cb-1 / cb-2 default).
    static final List<String> DEFAULT_JOBS = List.of("CRUSADER", "FP_MAGE", "RANGER", "HERMIT");

    private CombatScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Cb1Skills(), new Cb2Positioning(), new Cb3Habits(), new Cb4Manners(), new Sp1Spread());
    }

    /** Shared: the staged grinders per GM, a mob map, the undo, a read-out tick. */
    abstract static class GrinderScenario implements QaScenario {
        final Map<Integer, List<Integer>> bots = new ConcurrentHashMap<>();

        public Set<Integer> watchedMaps(Character gm, String[] args) {
            return new java.util.HashSet<>(List.of(gm.getMapId(), HHG));
        }

        public int tickSeconds() {
            return 15;
        }

        public int minutes(String[] args) {
            return 15;
        }

        void onMobMap(QaContext ctx) {
            if (ctx.world().liveMobCount(ctx.gm()) == 0) {
                if (!ctx.world().warp(ctx.gm(), HHG)) {
                    throw new QaRefusal("Stand on a map with mobs.");
                }
                ctx.log("no mobs here: warped to " + HHG);
            }
            ctx.log(ctx.world().combatTuning());
        }

        List<Integer> stage(QaContext ctx, List<String> jobs, int count, int levelOffset, boolean hold, boolean atGm) {
            Character gm = ctx.gmNow() != null ? ctx.gmNow() : ctx.gm();
            List<Integer> ids = ctx.world().stageGrinders(gm, jobs, count, levelOffset, hold, atGm);
            if (ids.isEmpty()) {
                throw new QaRefusal("No bot could be staged here.");
            }
            bots.put(ctx.run().gmId(), ids);
            ctx.log("staged " + ids.size() + " grinder(s): " + ids);
            ctx.onStop("unstage " + ids.size() + " grinder(s)", () -> {
                ctx.world().unstageGrinders(ids);
                bots.remove(ctx.run().gmId());
            });
            return ids;
        }

        List<Integer> staged(QaContext ctx) {
            return bots.getOrDefault(ctx.run().gmId(), List.of());
        }

        void report(QaContext ctx, String kind, String what) {
            for (String line : ctx.world().grinderReport(staged(ctx), what)) {
                ctx.run().note(kind, line);
            }
        }

        static List<String> jobArgs(String[] args) {
            List<String> jobs = new ArrayList<>();
            for (String a : args) {
                if (!a.isBlank() && !a.matches("\\d{1,2}")) {
                    jobs.add(a);
                }
            }
            return jobs;
        }
    }

    // ---- cb-1 ----

    /** cb-1: skill choice by mob count, single target on bosses and lone mobs, class buffs kept up. */
    static final class Cb1Skills extends GrinderScenario {
        public String id() { return "cb-1"; }
        public String title() { return "Bot skill choice: AoE on 3+ mobs, single on lone mobs and bosses, buffs kept up"; }
        public String usage() { return "[job ...], default crusader fp_mage ranger hermit"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(COMBAT, ATTACK); }

        public List<String> stage(QaContext ctx) {
            onMobMap(ctx);
            List<String> jobs = jobArgs(ctx.args());
            stage(ctx, jobs.isEmpty() ? DEFAULT_JOBS : jobs, 0, 0, true, false);
            return List.of("Grinders of each class fight the densest pack here and stay on this map.",
                    "The log has a CHOICE line per swing (mobs in AoE reach -> AOE or SINGLE, and why: PACK, FEW, BOSS,"
                            + " SLOPPY_*), a BUFF line per cast (next cast after it runs out), and every attack (ATTACK).",
                    "Watch: AoE into packs of 3+, single-target on a lone mob, buffs back up a little after they run"
                            + " out. A boss map (or !spawn a boss) must show BOSS -> SINGLE. Buff timers every 15 s.");
        }

        public void tick(QaContext ctx) {
            report(ctx, "BUFFS", "buffs");
        }
    }

    // ---- cb-2 ----

    /** cb-2: positioning: range-correct distance, facing before swings, out of mob bodies, platform pacing. */
    static final class Cb2Positioning extends GrinderScenario {
        public String id() { return "cb-2"; }
        public String title() { return "Bot positioning: archers/mages keep distance, face before hitting, no standing in mobs, walk the platform"; }
        public String usage() { return "[job ...], default crusader fp_mage ranger hermit"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(COMBAT); }

        public List<String> stage(QaContext ctx) {
            onMobMap(ctx);
            List<String> jobs = jobArgs(ctx.args());
            stage(ctx, jobs.isEmpty() ? DEFAULT_JOBS : jobs, 0, 0, true, false);
            return List.of("Grinders of each class fight here. Every 15 s the log lists each bot's position, facing and"
                            + " its nearest mob (INSIDE its body / FACING AWAY are the faults to look for).",
                    "FACE (turned before a swing), TOUCH (stepped out of a mob), KNOCKBACK (stepped back after a hit),"
                            + " KITE (backed off), PACE (walked the platform in a lull) lines show the moves.",
                    "Watch: the warrior closes in, the archer and the mage stand off, the thief hops in and out; no"
                            + " swing goes out facing away; nobody stands on one pixel through a lull.");
        }

        public void tick(QaContext ctx) {
            report(ctx, "POSITION", "position");
        }
    }

    // ---- cb-3 ----

    /** cb-3: per-bot habits: loot styles, potion lines, restock trips, a rare death and the walk back. */
    static final class Cb3Habits extends GrinderScenario {
        public String id() { return "cb-3"; }
        public String title() { return "Bot habits: loot style, potion line, restock trip, a death and the walk back"; }
        public String usage() { return "[bots 1-6, default 4] (a few levels under the map: a harder map)"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(HABITS, BOT_CHAT); }

        public Set<Integer> watchedMaps(Character gm, String[] args) {
            return Set.of(); // the respawn and the walk back happen in town
        }

        public List<String> stage(QaContext ctx) {
            onMobMap(ctx);
            int n = 4;
            for (String a : ctx.args()) {
                if (a.matches("\\d")) {
                    n = Math.max(1, Math.min(6, Integer.parseInt(a)));
                }
            }
            stage(ctx, List.of(), n, -5, false, false);
            report(ctx, "HABITS", "habits");
            return List.of(n + " grinders 5 levels under this map fight here. Each has its own habits (logged above):"
                            + " loot style, potion line, restock mark.",
                    "LOOT, POT, RESTOCK, DEATH, RESPAWN and WALK BACK lines show them. !qa do hurt hits each to ~45%"
                            + " (watch who drinks), !qa do lowpots drains their HP pots to their restock mark (they head"
                            + " to town), !qa do die kills one now (it lies down, respawns in town, comes back).",
                    "Their state every 15 s.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            List<String> lines = ctx.world().grinderAction(staged(ctx), name.toLowerCase(java.util.Locale.ROOT));
            lines.forEach(l -> ctx.run().note("DO " + name, l));
            return lines;
        }

        public void tick(QaContext ctx) {
            report(ctx, "STATE", "habits");
        }
    }

    // ---- cb-4 ----

    /** cb-4: KS manners: neutral grinders leave the GM's platform and mobs, move on from a crowded map, "cc?". */
    static final class Cb4Manners extends GrinderScenario {
        public String id() { return "cb-4"; }
        public String title() { return "KS manners: neutral bots leave your platform and your mobs, move on when crowded, sometimes 'cc?'"; }
        public String usage() { return "[bots 1-6, default 3]"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(MANNERS, BOT_CHAT); }

        public List<String> stage(QaContext ctx) {
            onMobMap(ctx);
            int n = 3;
            for (String a : ctx.args()) {
                if (a.matches("\\d")) {
                    n = Math.max(1, Math.min(6, Integer.parseInt(a)));
                }
            }
            stage(ctx, List.of(), n, 0, false, true);
            return List.of(n + " neutral grinders start right next to you, on your platform.",
                    "They must leave your platform (YIELD), never take a mob you are fighting or standing by (LEAVE),"
                            + " and when only your platform is left here they move on (CROWDED, MOVE ON, sometimes"
                            + " 'cc?' in chat).",
                    "Walk to another platform: they may come back to the one you left. A rival still KSes you"
                            + " (af-2), that is on purpose.");
        }
    }

    // ---- sp-1 ----

    /** sp-1: spread: a clump of grinders spreads over the map's spots, bands, ropes. */
    static final class Sp1Spread extends GrinderScenario {
        public String id() { return "sp-1"; }
        public String title() { return "Spread: a clump of grinders spreads over the platforms, no pixel stacking, one bot per rope"; }
        public String usage() { return "[bots 2-12, default 8]"; }
        public Set<QaLog.Topic> topics() { return EnumSet.of(SPREAD); }

        public List<String> stage(QaContext ctx) {
            onMobMap(ctx);
            int n = 8;
            for (String a : ctx.args()) {
                if (a.matches("\\d{1,2}")) {
                    n = Math.max(2, Math.min(12, Integer.parseInt(a)));
                }
            }
            stage(ctx, List.of(), n, 0, false, true);
            tally(ctx, "START");
            return List.of(n + " grinders start in one clump next to you.",
                    "Every 15 s a SPREAD tally: bots, how many 100 px spots they cover, pairs closer than 34 px, bots"
                            + " per rope. SPACE (stepped off another bot) and ROPE (waited for a rope) lines show the moves.",
                    "Watch: within a minute they cover several platforms, no two stand on one pixel, no two hang on one"
                            + " rope. Extra bots may move on to another map (that is spread too).");
        }

        public void tick(QaContext ctx) {
            tally(ctx, "TALLY");
        }

        private void tally(QaContext ctx, String kind) {
            Character gm = ctx.gmNow();
            if (gm != null) {
                ctx.world().spreadTally(gm).forEach(l -> ctx.run().note(kind, l));
            }
        }
    }

    static String join(String[] args) {
        return String.join(" ", Arrays.asList(args));
    }
}

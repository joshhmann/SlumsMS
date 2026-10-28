package soloMapling.qa;

import client.Character;
import soloMapling.achievements.AchievementService;
import soloMapling.achievements.Achievements;
import soloMapling.achievements.AchievementsQa;
import soloMapling.achievements.Goal;

import java.util.List;
import java.util.Set;

/**
 * 1.6.1, ticket 93: ac-1, achievements and medals. Clears the GM's Mano and Fishing Hobby goals, then lets you earn them
 * through the real event path: {@code !qa do mano} (a credited kill of Mano), {@code !qa do fish} (ten kept catches),
 * {@code !qa do list} (what @achievements shows). Each hands over one cosmetic medal with a yellow notice; doing it
 * again hands over nothing. Stop clears both goals and takes the medals back. Auto-QA: t-ac-1.
 */
final class AchievementScenarios {
    private AchievementScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Achievements1());
    }

    static final class Achievements1 implements QaScenario {
        public String id() { return "ac-1"; }
        public String title() { return "Achievements: earn two goals and their medals, once"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 20; }

        private static AchievementService service() {
            AchievementService s = Achievements.service();
            if (s == null) {
                throw new QaRefusal("Achievements are off on this server (-Dslums.achievements=true) or the yaml is invalid.");
            }
            return s;
        }

        public List<String> stage(QaContext ctx) {
            AchievementService s = service();
            Character gm = ctx.gm();
            try {
                AchievementsQa.clear(s, gm);
            } catch (java.sql.SQLException e) {
                throw new QaRefusal("The achievement store failed: " + e.getMessage());
            }
            ctx.onStop("clear the QA goals and medals", () -> {
                try {
                    AchievementsQa.clear(s, ctx.gmNow());
                } catch (java.sql.SQLException e) {
                    ctx.run().note("CLEANUP", "failed: " + e.getMessage());
                }
            });
            ctx.log("AC1 START goals=" + AchievementsQa.KILL_GOAL + "," + AchievementsQa.FISH_GOAL);
            return List.of(
                    "Your Mano and Fishing Hobby goals are cleared (and any such medal taken out of your bags).",
                    "1) !qa do mano: a credited Mano kill -> the Mano Vanquisher Medal, a yellow notice. Again: nothing.",
                    "2) !qa do fish: ten kept catches -> the Fishing Master Medal. Check the Equip tab: the medals have no stats.",
                    "3) !qa do list: what @achievements prints. Fill your Equip tab first to see the 'make room' notice,"
                            + " then free a slot and type @achievements.",
                    "!qa stop clears both goals again.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            AchievementService s = service();
            Character gm = ctx.gmNow();
            switch (name) {
                case "mano" -> {
                    List<Goal> got = AchievementsQa.kill(s, gm);
                    String line = "AC1 MANO completed " + got.stream().map(Goal::id).toList();
                    ctx.run().note("AC1", line);
                    return List.of(line);
                }
                case "fish" -> {
                    List<Goal> got = AchievementsQa.fish(s, gm, 10);
                    String line = "AC1 FISH x10 completed " + got.stream().map(Goal::id).toList();
                    ctx.run().note("AC1", line);
                    return List.of(line);
                }
                case "list" -> {
                    try {
                        return client.command.commands.gm0.AchievementsCommand.lines(s, gm.getId(), false);
                    } catch (java.sql.SQLException e) {
                        throw new QaRefusal("The achievement store failed: " + e.getMessage());
                    }
                }
                default -> throw new QaRefusal("ac-1 steps: mano, fish, list");
            }
        }
    }
}

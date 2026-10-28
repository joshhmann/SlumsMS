package soloMapling.qa;

import client.Character;
import soloMapling.menus.TutorialIslandMenu;

import java.util.List;
import java.util.Set;

/**
 * 1.6.1, ticket 29: ti-1, the "Start on Maple Island?" question. Opens the question for the GM (who is not a new
 * Beginner, so the real first-login rule would not ask). Yes warps to Mushroom Town (map 10000); {@code !qa do back}
 * returns to the Base of Roots hub; {@code !qa do ask} asks again. The auto-QA check t-ti-1 runs the same picks.
 */
final class TutorialIslandScenarios {
    private TutorialIslandScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Island());
    }

    static final class Island implements QaScenario {
        public String id() { return "ti-1"; }
        public String title() { return "Maple Island opt-in: the first-login question, Yes to the island, No to stay"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 15; }

        public List<String> stage(QaContext ctx) {
            Character gm = ctx.gm();
            boolean opened = TutorialIslandMenu.openForQa(gm);
            ctx.log("TI1 START question " + (opened ? "opened" : "not opened (another NPC window is open)"));
            return List.of(
                    opened ? "The question is open: pick No first (you stay), then !qa do ask and pick Yes."
                            : "Close your NPC window, then !qa do ask.",
                    "Yes lands you in Mushroom Town (map 10000): talk to Heena / Sera, check the quest list, walk the portals"
                            + " east to Southperry (Shanks takes you to Lith Harbor at level 7; a GM may skip the level).",
                    "!qa do back returns to Base of Roots. The real question only shows to a brand-new Explorer: make one"
                            + " on a test account to see it at first login (Cygnus / Aran are never asked).");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            Character gm = ctx.gmNow();
            if (gm == null) {
                throw new QaRefusal("You are offline.");
            }
            switch (name) {
                case "ask" -> {
                    return List.of(TutorialIslandMenu.openForQa(gm) ? "Asked." : "Close your NPC window first.");
                }
                case "back" -> {
                    gm.changeMap(constants.id.MapId.MAPLEROOT_HUB);
                    return List.of("Back at Base of Roots.");
                }
                default -> throw new QaRefusal("ti-1 steps: ask, back");
            }
        }
    }
}

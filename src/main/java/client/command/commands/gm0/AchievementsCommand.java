package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.achievements.AchievementService;
import soloMapling.achievements.AchievementStore;
import soloMapling.achievements.Achievements;
import soloMapling.achievements.Goal;
import soloMapling.achievements.Subject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @achievements (1.6.1, ticket 93): your achievements and medals. {@code @achievements} lists what you earned and
 * what is under way; {@code @achievements all} lists every goal with what it asks. Also hands over a medal that was
 * waiting for room in your Equip inventory.
 */
public class AchievementsCommand extends Command {
    {
        setDescription("List your achievements and medals (@achievements all for every goal).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character me = c.getPlayer();
        AchievementService svc = Achievements.service();
        if (svc == null) {
            me.yellowMessage("Achievements are not running on this server.");
            return;
        }
        try {
            svc.settle(Subject.of(me));
            for (String line : lines(svc, me.getId(), params.length > 0 && params[0].equalsIgnoreCase("all"))) {
                me.yellowMessage(line);
            }
        } catch (java.sql.SQLException e) {
            me.yellowMessage("Your achievements could not be read just now. Try again in a minute.");
        }
    }

    /** The lines @achievements prints (also what the QA scenario shows). */
    public static List<String> lines(AchievementService svc, int charId, boolean all) throws java.sql.SQLException {
        Map<String, AchievementStore.Row> rows = svc.snapshot(charId);
        List<Goal> goals = svc.catalog().goals();
        List<String> out = new ArrayList<>();
        int earned = 0;
        StringBuilder done = new StringBuilder();
        List<String> open = new ArrayList<>();
        for (Goal g : goals) {
            AchievementStore.Row r = rows.get(g.id());
            boolean got = r != null && r.state() >= AchievementStore.EARNED;
            if (got) {
                earned++;
                done.append(done.length() == 0 ? "" : ", ").append(g.title())
                        .append(r.state() == AchievementStore.EARNED ? " (medal waits for room)" : "");
            }
            int progress = r == null ? 0 : r.progress();
            if (all) {
                open.add((got ? "[done] " : "[" + g.progressText(progress) + "] ") + g.title() + ": " + g.text());
            } else if (!got && progress > 0 && g.keepsProgress()) {
                open.add("[" + g.progressText(progress) + "] " + g.title() + ": " + g.text());
            }
        }
        out.add("Achievements: " + earned + " of " + goals.size() + " earned." + (all ? "" : " (@achievements all lists every goal)"));
        if (!all && done.length() > 0) {
            out.addAll(wrap("Earned: " + done));
        }
        out.addAll(open);
        return out;
    }

    private static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        String rest = text;
        while (rest.length() > 100) {
            int cut = rest.lastIndexOf(", ", 100);
            if (cut < 20) {
                cut = 100;
            }
            out.add(rest.substring(0, cut + 1));
            rest = rest.substring(Math.min(rest.length(), cut + 2));
        }
        out.add(rest);
        return out;
    }
}

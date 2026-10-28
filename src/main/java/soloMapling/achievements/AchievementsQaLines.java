package soloMapling.achievements;

/** What the player's list would show for a goal (shared by the QA check and @achievements). */
final class AchievementsQaLines {
    private AchievementsQaLines() {
    }

    static boolean listsGoal(AchievementService s, int charId, Goal g) {
        try {
            return client.command.commands.gm0.AchievementsCommand.lines(s, charId, false).stream()
                    .anyMatch(l -> l.startsWith("Earned:") && l.contains(g.title()));
        } catch (java.sql.SQLException e) {
            return false;
        }
    }
}

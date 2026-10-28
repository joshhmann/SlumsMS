package soloMapling.achievements;

import java.sql.SQLException;
import java.util.Map;

/**
 * Durable progress, one row per (character, goal). A bot has no characters row, so there is no foreign key: a bot's
 * progress is stored exactly like a player's.
 */
public interface AchievementStore {
    int PROGRESS = 0;
    /** The goal is done and its medal is owed (not yet confirmed in the bag). */
    int EARNED = 1;
    /** The medal is in the character's bag (or, for a bot, was handed over / recorded). */
    int GRANTED = 2;

    record Row(int progress, int state, long earnedAt, long grantedAt) {
    }

    Map<String, Row> load(int charId) throws SQLException;

    /** Saves a count or bitmask while the goal is still open; never touches a goal that is already earned. */
    void saveProgress(int charId, String goal, int progress, long now) throws SQLException;

    /** Marks the goal earned (PROGRESS or no row -> EARNED). False when it was already earned or granted. */
    boolean markEarned(int charId, String goal, int progress, long now) throws SQLException;

    /** EARNED -> GRANTED. False when the row was not EARNED. */
    boolean markGranted(int charId, String goal, long now) throws SQLException;

    /** Forgets the goal for the character (GM reset). */
    void delete(int charId, String goal) throws SQLException;
}

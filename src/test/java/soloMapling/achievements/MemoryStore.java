package soloMapling.achievements;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/** An in-memory {@link AchievementStore} with the same rules as the SQL one, and switches to make it fail. */
class MemoryStore implements AchievementStore {
    final Map<Integer, Map<String, Row>> data = new HashMap<>();
    boolean failMarkGranted;
    int markEarnedCalls;
    int loads;

    Map<String, Row> of(int id) {
        return data.computeIfAbsent(id, k -> new HashMap<>());
    }

    @Override
    public Map<String, Row> load(int charId) {
        loads++;
        return new HashMap<>(of(charId));
    }

    @Override
    public void saveProgress(int charId, String goal, int progress, long now) {
        Row r = of(charId).get(goal);
        if (r == null || r.state() == PROGRESS) {
            of(charId).put(goal, new Row(progress, PROGRESS, 0, 0));
        }
    }

    @Override
    public boolean markEarned(int charId, String goal, int progress, long now) {
        markEarnedCalls++;
        Row r = of(charId).get(goal);
        if (r != null && r.state() != PROGRESS) {
            return false;
        }
        of(charId).put(goal, new Row(progress, EARNED, now, 0));
        return true;
    }

    @Override
    public boolean markGranted(int charId, String goal, long now) throws SQLException {
        if (failMarkGranted) {
            throw new SQLException("simulated crash after the item was handed over");
        }
        Row r = of(charId).get(goal);
        if (r == null || r.state() != EARNED) {
            return false;
        }
        of(charId).put(goal, new Row(r.progress(), GRANTED, r.earnedAt(), now));
        return true;
    }

    @Override
    public void delete(int charId, String goal) {
        of(charId).remove(goal);
    }
}

package soloMapling.ArtificialPlayer.BotQuestSystem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ticket 158 item 2: one bot's quests, kept in its life profile ({@code botlifeprofile.Profile}, one row per roster
 * slot that already exists) instead of {@code queststatus}. Roster bots are never saved through
 * {@code Character.saveCharToDB}, so they have no quest rows at all; this is their only quest store.
 *
 * <p>What is kept, and nothing else (the ticket's "active and completed only, capped per bot"):
 * <ul>
 *   <li>at most one active quest, with its NPCs, maps, the kills it needs and the kills made so far;</li>
 *   <li>the ids of the last {@link #keepDone()} completed quests (oldest dropped first);</li>
 *   <li>today's completion count (the daily cap) and two lifetime counters.</li>
 * </ul>
 * Not-started placeholders, forfeits and progress of other quests are never stored. Size per bot: about 120 bytes of
 * JSON for the active quest plus about 6 per completed id, so at most about 0.35 KB at the default cap of 32.
 *
 * <p>A dropped completed id could in principle be picked again; picks only look at quests whose start level is within
 * {@link BotQuests#LEVEL_WINDOW} of the bot's level, and the oldest ids go first, so a dropped one is long out of reach.
 *
 * <p>Not thread-safe on its own: {@code LifeMemory} guards it with its own monitor.
 */
public final class BotQuestLog {

    /** How the active quest is being run. REAL: started at the NPC through {@code Quest.start}. MODELLED: taken while
     *  nobody watched, so only this log knows about it (no quest status, no rewards). */
    public enum Mode { REAL, MODELLED }

    /** The active quest. Mutable kill counts; everything else fixed at pick time. */
    public static final class Active {
        public final int questId, startNpc, endNpc, startMap, endMap, huntMap;
        public final Map<Integer, Integer> need;
        final Map<Integer, Double> kills = new LinkedHashMap<>();
        boolean taken;
        Mode mode = Mode.MODELLED;
        public final long pickedAt;
        int deferrals;

        public Active(int questId, int startNpc, int endNpc, int startMap, int endMap, int huntMap,
                      Map<Integer, Integer> need, long pickedAt) {
            this.questId = questId;
            this.startNpc = startNpc;
            this.endNpc = endNpc;
            this.startMap = startMap;
            this.endMap = endMap;
            this.huntMap = huntMap;
            this.need = Collections.unmodifiableMap(new LinkedHashMap<>(need));
            this.pickedAt = pickedAt;
        }

        public boolean taken() { return taken; }
        public Mode mode() { return mode; }
        public int deferrals() { return deferrals; }

        /** Kills counted for this mob (real progress or modelled), whole. */
        public int killed(int mobId) {
            return (int) Math.floor(kills.getOrDefault(mobId, 0.0));
        }

        /** Whether every required kill is counted. */
        public boolean hunted() {
            for (Map.Entry<Integer, Integer> e : need.entrySet()) if (killed(e.getKey()) < e.getValue()) return false;
            return true;
        }

        /** Adds kills of one mob (capped at what the quest needs). */
        void addKills(int mobId, double n) {
            Integer want = need.get(mobId);
            if (want == null || n <= 0) return;
            kills.put(mobId, Math.min(want, kills.getOrDefault(mobId, 0.0) + n));
        }

        /** Sets a mob's kill count from the real quest progress (never lowers a count). */
        void syncKills(int mobId, int n) {
            Integer want = need.get(mobId);
            if (want == null) return;
            kills.put(mobId, Math.min(want, Math.max(kills.getOrDefault(mobId, 0.0), n)));
        }
    }

    /** Default cap on stored completed ids per bot ({@code -Dslums.botquests.keepDone}). */
    public static final int DEFAULT_KEEP_DONE = 32;

    private Active active;
    private final ArrayDeque<Integer> done = new ArrayDeque<>();
    private long day = -1;
    private int doneToday;
    private int realDone, modelledDone;
    /** Bumped by every change that is saved (LifeMemory marks itself dirty on a change). */
    private int version;

    public int version() { return version; }
    /** Quests that failed for this bot this run (NPC missing, refused): never stored, bounded. */
    private final Set<Integer> skip = new LinkedHashSet<>();
    static final int KEEP_SKIP = 16;

    public static int keepDone() {
        return Math.max(1, Integer.getInteger("slums.botquests.keepDone", DEFAULT_KEEP_DONE));
    }

    public Active active() { return active; }

    public boolean isEmpty() {
        return active == null && done.isEmpty() && realDone == 0 && modelledDone == 0;
    }

    public boolean completed(int questId) { return done.contains(questId); }

    /** Completed ids, oldest first. */
    public List<Integer> done() { return List.copyOf(done); }

    public int realDone() { return realDone; }

    public int modelledDone() { return modelledDone; }

    public boolean skipped(int questId) { return skip.contains(questId); }

    /** Completions on this UTC day. */
    public int doneToday(long now) {
        rollDay(now);
        return doneToday;
    }

    private void rollDay(long now) {
        long today = Math.floorDiv(now, 86_400_000L); // the UTC day
        if (today != day) {
            day = today;
            doneToday = 0;
        }
    }

    /** Starts tracking a picked quest (replaces nothing: callers check {@link #active()} first). */
    public void pick(Active quest) {
        if (active == null && quest != null) {
            active = quest;
            version++;
        }
    }

    /** The NPC took the bot's "yes": the quest is on. */
    public void taken(Mode mode) {
        if (active == null) return;
        active.taken = true;
        active.mode = mode;
        version++;
    }

    public void addKills(int mobId, double n) {
        if (active == null || !active.taken) return;
        int before = active.killed(mobId);
        active.addKills(mobId, n);
        if (active.killed(mobId) != before) version++; // whole kills only: fractions are not worth a save
    }

    public void syncKills(int mobId, int n) {
        if (active == null || !active.taken) return;
        int before = active.killed(mobId);
        active.syncKills(mobId, n);
        if (active.killed(mobId) != before) version++;
    }

    /** The turn-in waited (rewards over the yield caps, bag full): counted so a stuck quest is dropped in time. */
    public int defer() {
        if (active == null) return 0;
        version++;
        return ++active.deferrals;
    }

    /** The active quest is done: stored as completed (the cap drops the oldest), counted for today. */
    public void complete(boolean real, long now) {
        if (active == null) return;
        int id = active.questId;
        active = null;
        done.remove(id);
        done.addLast(id);
        while (done.size() > keepDone()) done.removeFirst();
        rollDay(now);
        doneToday++;
        if (real) realDone++;
        else modelledDone++;
        version++;
    }

    /** The active quest is dropped and not tried again this run. */
    public void drop() {
        if (active == null) return;
        skip(active.questId);
        active = null;
        version++;
    }

    public void skip(int questId) {
        skip.add(questId);
        while (skip.size() > KEEP_SKIP) skip.remove(skip.iterator().next());
    }

    // ── saving: plain maps for LifeMemory's JSON ──

    public Map<String, Object> save() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (active != null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", active.questId);
            a.put("npc", List.of(active.startNpc, active.endNpc));
            a.put("maps", List.of(active.startMap, active.endMap, active.huntMap));
            Map<String, Integer> need = new LinkedHashMap<>();
            active.need.forEach((k, v) -> need.put(String.valueOf(k), v));
            a.put("need", need);
            Map<String, Integer> kills = new LinkedHashMap<>();
            active.kills.forEach((k, v) -> kills.put(String.valueOf(k), (int) Math.floor(v)));
            a.put("kills", kills);
            a.put("taken", active.taken);
            a.put("mode", active.mode.name());
            a.put("at", active.pickedAt);
            out.put("active", a);
        }
        out.put("done", new ArrayList<>(done));
        out.put("day", day);
        out.put("today", doneToday);
        out.put("real", realDone);
        out.put("modelled", modelledDone);
        return out;
    }

    /** Reads a saved log; anything unreadable is left out (never fatal). */
    public static BotQuestLog read(Object saved) {
        BotQuestLog log = new BotQuestLog();
        if (!(saved instanceof Map<?, ?> map)) return log;
        try {
            if (map.get("done") instanceof List<?> ids) {
                for (Object id : ids) if (id instanceof Number n) log.done.addLast(n.intValue());
                while (log.done.size() > keepDone()) log.done.removeFirst();
            }
            log.day = map.get("day") instanceof Number n ? n.longValue() : -1;
            log.doneToday = map.get("today") instanceof Number n ? Math.max(0, n.intValue()) : 0;
            log.realDone = map.get("real") instanceof Number n ? Math.max(0, n.intValue()) : 0;
            log.modelledDone = map.get("modelled") instanceof Number n ? Math.max(0, n.intValue()) : 0;
            if (map.get("active") instanceof Map<?, ?> a && a.get("id") instanceof Number id
                    && a.get("npc") instanceof List<?> npc && npc.size() == 2
                    && a.get("maps") instanceof List<?> maps && maps.size() == 3
                    && a.get("need") instanceof Map<?, ?> need) {
                Map<Integer, Integer> needs = new LinkedHashMap<>();
                need.forEach((k, v) -> needs.put(Integer.parseInt(String.valueOf(k)), ((Number) v).intValue()));
                Active act = new Active(id.intValue(), ((Number) npc.get(0)).intValue(), ((Number) npc.get(1)).intValue(),
                        ((Number) maps.get(0)).intValue(), ((Number) maps.get(1)).intValue(),
                        ((Number) maps.get(2)).intValue(), needs,
                        a.get("at") instanceof Number at ? at.longValue() : 0);
                act.taken = Boolean.TRUE.equals(a.get("taken"));
                act.mode = "REAL".equals(a.get("mode")) ? Mode.REAL : Mode.MODELLED;
                if (a.get("kills") instanceof Map<?, ?> kills) {
                    kills.forEach((k, v) -> act.syncKills(Integer.parseInt(String.valueOf(k)), ((Number) v).intValue()));
                }
                if (!needs.isEmpty()) log.active = act;
            }
        } catch (RuntimeException unreadable) {
            log.active = null; // the completed ids read so far stay
        }
        return log;
    }
}

package soloMapling.ArtificialPlayer.BotQuestSystem;

import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;

/**
 * Ticket 158 item 4: "what quests r u doing" and "recommend a quest", answered from the bot's own quest log (ticket
 * 156: speak only from the bot's real state). Nothing here invents a quest: every name comes from the log and
 * Quest.wz, and with nothing to tell the bot says so.
 *
 * <p>The answer is an {@link Answer}: a fact for the chat model's prompt (what the line must say) and a scripted line
 * for when no model answers, the same shape as {@code BotChatActions.Result}. {@link #promptFact} adds the quest to
 * the life brain's prompt facts, and {@link #describe} is the {@code !botwhy} line.
 */
public final class BotQuestChat {

    private BotQuestChat() {
    }

    /** What the bot's line must say ({@code fact}), a scripted line, and a word the line must contain (or null). */
    public record Answer(boolean known, String fact, String scripted, String mustMention) {}

    // ── names (seams: tests have no WZ) ──

    private static final Map<Integer, String> QUEST_NAMES = new ConcurrentHashMap<>();
    static volatile IntFunction<String> questNames = id -> {
        try {
            String n = server.quest.Quest.getInstance(id).getName();
            return n == null || n.isBlank() ? null : n.trim();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    };
    static volatile IntFunction<String> mobNames = id -> {
        try {
            String n = server.life.MonsterInformationProvider.getInstance().getMobNameFromId(id);
            return n == null || n.isBlank() ? null : n.trim();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    };
    static volatile IntFunction<String> npcNames = id -> {
        try {
            String n = server.life.LifeFactory.getNPC(id).getName();
            return n == null || n.isBlank() ? null : n.trim();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    };

    /** The quest's name, or null when Quest.wz has none. */
    public static String questName(int id) {
        String cached = QUEST_NAMES.get(id);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String name = questNames.apply(id);
        QUEST_NAMES.put(id, name == null ? "" : name);
        return name;
    }

    static void clearNames() {
        QUEST_NAMES.clear();
    }

    // ── "what quests r u doing" ──

    static final List<String> DOING = List.of("doing %s rn, %s", "%s atm, %s", "on %s, %s", "%s lol, %s");
    static final List<String> GRABBING = List.of("gonna grab %s from %s", "about to pick up %s from %s",
            "headin to %2$s for %1$s");
    static final List<String> JUST_DID = List.of("just finished %s", "did %s earlier", "turned in %s not long ago");
    static final List<String> NONE = List.of("no quests rn, just grinding", "not doing any quests atm",
            "nah just grinding", "none rn tbh");

    /** What the bot is doing, from its log; {@code known} false when it has no quest to talk about. */
    public static Answer doing(BotQuestLog log) {
        BotQuestLog.Active a = log == null ? null : log.active();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (a != null) {
            String quest = questName(a.questId);
            if (quest != null) {
                if (!a.taken()) {
                    String npc = npcNames.apply(a.startNpc);
                    if (npc != null) {
                        return new Answer(true, "Tell them you are about to take the quest \"" + quest + "\" from " + npc
                                + ". Name no other quest.", String.format(pick(GRABBING, rng), lower(quest), npc), keyWord(quest));
                    }
                } else {
                    String progress = progress(a);
                    return new Answer(true, "Tell them you are doing the quest \"" + quest + "\" (" + progress
                            + "). Name no other quest and no other monster.",
                            String.format(pick(DOING, rng), lower(quest), progress), keyWord(quest));
                }
            }
        }
        List<Integer> done = log == null ? List.of() : log.done();
        for (int i = done.size() - 1; i >= 0; i--) {
            String quest = questName(done.get(i));
            if (quest != null) {
                return new Answer(true, "Tell them you are not on a quest right now; you finished \"" + quest
                        + "\" earlier. Name no other quest.", String.format(pick(JUST_DID, rng), lower(quest)), keyWord(quest));
            }
        }
        return new Answer(false, "Tell them you are not doing any quests right now. Do not name any quest.",
                pick(NONE, rng), null);
    }

    /** "12/20 green snails" for each required mob, joined. */
    static String progress(BotQuestLog.Active a) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : a.need.entrySet()) {
            String mob = mobNames.apply(e.getKey());
            parts.add(a.killed(e.getKey()) + "/" + e.getValue() + (mob == null ? "" : " " + lower(mob)));
        }
        return String.join(", ", parts);
    }

    // ── "recommend a quest" ──

    static final List<String> RECOMMEND = List.of("try %s, %s", "%s is decent, %s", "%s was ok, %s",
            "do %s, %s");
    static final List<String> NO_IDEA = List.of("idk any good ones for ur lvl tbh", "no idea for ur lvl sry",
            "havent done any that fit u", "idk, i mostly just grind");

    /**
     * A quest the bot knows (it is doing it or did it) that fits a player of {@code playerLevel}: its start level is
     * reached and its level cap not passed. Newest first, the active one before finished ones.
     */
    public static Answer recommend(BotQuestLog log, int playerLevel) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<Integer> known = new ArrayList<>();
        if (log != null) {
            if (log.active() != null) known.add(log.active().questId);
            List<Integer> done = log.done();
            for (int i = done.size() - 1; i >= 0; i--) known.add(done.get(i));
        }
        Map<Integer, BotQuestIndex.QuestMeta> index = known.isEmpty() ? Map.of() : BotQuestIndex.get().byId();
        for (int id : known) {
            BotQuestIndex.QuestMeta q = index.get(id);
            String quest = questName(id);
            if (q == null || quest == null) continue;
            if (q.lvmin() > playerLevel || q.lvmax() > 0 && playerLevel > q.lvmax()) continue;
            String npc = npcNames.apply(q.startNpc());
            String how = howTo(q, npc);
            return new Answer(true, "Recommend the quest \"" + quest + "\" (" + how + "). It is one you know; name no "
                    + "other quest.", String.format(pick(RECOMMEND, rng), lower(quest), how), keyWord(quest));
        }
        return new Answer(false, "Say you do not know a good quest for their level. Do not name any quest.",
                pick(NO_IDEA, rng), null);
    }

    static String howTo(BotQuestIndex.QuestMeta q, String npc) {
        List<String> kills = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : q.mobs().entrySet()) {
            String mob = mobNames.apply(e.getKey());
            kills.add(e.getValue() + (mob == null ? " mobs" : " " + lower(mob)));
        }
        return "kill " + String.join(" and ", kills) + (npc == null ? "" : " for " + npc);
    }

    // ── the brain's prompt and !botwhy ──

    /** One prompt line with the bot's quest, or "" (switch off, no quest). */
    public static String promptFact(LifeMemory memory) {
        if (memory == null || !BotQuests.enabled()) return "";
        return memory.quests(log -> {
            BotQuestLog.Active a = log.active();
            if (a == null) return "";
            String quest = questName(a.questId);
            if (quest == null) return "";
            return a.taken() ? "Quest facts: you are doing the quest \"" + quest + "\" (" + progress(a)
                    + "). It is the only quest you are on.\n"
                    : "Quest facts: you plan to take the quest \"" + quest + "\" next; you have not taken it yet.\n";
        });
    }

    /** The {@code !botwhy} line, or null when the bot has no quest history. */
    public static String describe(LifeMemory memory) {
        if (memory == null) return null;
        return memory.quests(log -> {
            if (log.isEmpty()) return null;
            BotQuestLog.Active a = log.active();
            String now = a == null ? "no active quest"
                    : "quest #" + a.questId + " " + (questName(a.questId) == null ? "" : "\"" + questName(a.questId) + "\" ")
                    + (a.taken() ? "taken (" + a.mode().name().toLowerCase(Locale.ROOT) + "), " + progress(a)
                    : "picked, not taken") + ", NPC maps " + a.startMap + "/" + a.endMap + ", hunt map " + a.huntMap
                    + (a.deferrals() > 0 ? ", turn-in waited " + a.deferrals() + "x" : "");
            return "Quests: " + now + "; done " + log.realDone() + " at the NPC, " + log.modelledDone()
                    + " recorded offscreen; " + log.done().size() + " ids kept (cap " + BotQuestLog.keepDone() + ")";
        });
    }

    // ── helpers ──

    private static String pick(List<String> lines, ThreadLocalRandom rng) {
        return lines.get(rng.nextInt(lines.size()));
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** The word a model's reply must contain: the quest name's longest word, lower case. */
    static String keyWord(String name) {
        String best = null;
        for (String w : lower(name).replaceAll("[^a-z0-9' ]", " ").split("\\s+")) {
            if (best == null || w.length() > best.length()) best = w;
        }
        return best == null || best.isBlank() ? null : best;
    }
}

package soloMapling.ArtificialPlayer.BotQuestSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ticket 158 item 1: which quests a bot can run on its own, read from the server's {@code Quest.wz}. Ported from
 * nutnnut/Cosmic 77e0c5bc9 ({@code server.bots.BotQuestIndex}, AGPL-3.0, like ours), cut down to the one shape the
 * ticket asks for: the "kill mobs, turn in" quest that piggybacks on grinding.
 *
 * <p>A quest qualifies ({@link #qualifies}) when it is not scripted (no {@code scripts/quest/<id>.js}, no
 * startscript/endscript marker), has a start NPC and an end NPC, needs at least one monster kill to complete, and
 * every other complete requirement is a gate the real {@code Quest.canComplete} checks again at turn-in (level, job,
 * an earlier quest, the NPC). Anything a bot would have to fetch (items, mesos, a pet, fame) disqualifies it. Quests
 * with a time limit are left out too (a bot that wanders off would just fail them).
 *
 * <p>Level, job and prerequisite checks are not done here: the real {@code Quest.canStart} decides them at pick time
 * ({@link BotQuests}). The index only narrows the field to quests whose shape a bot can finish legally.
 *
 * <p>Parse and filter are split: {@link #parse} reads WZ into a {@link QuestMeta}, the pure {@link #qualifies} decides.
 * Unit tests feed synthetic metas; the WZ test skips without {@code wz/Quest.wz}. Built once, lazily, the first time a
 * bot asks with the switch on (no disk cache: a full Quest.wz scan is a one-off of a second or two).
 */
public final class BotQuestIndex {

    private static final Logger log = LoggerFactory.getLogger(BotQuestIndex.class);
    private static final Path SCRIPT_DIR = Path.of("scripts", "quest");

    /**
     * One runnable quest. {@code mobs}: required mob id to kill count. {@code rewardExp} / {@code rewardMesos}: the
     * complete action's EXP and mesos (before rates). {@code rewardItems}: item id to count of the guaranteed item
     * rewards meant for everyone; {@code randomReward} when some item rewards depend on job or gender (one unit is
     * reserved for them). {@code lvmin}/{@code lvmax}: the start window (0 when absent). {@code actsOk}: the start and
     * complete actions are ones a bot can take through the real path (see {@link #ALLOWED_ACT_KEYS}).
     */
    public record QuestMeta(int id, int startNpc, int endNpc, int lvmin, int lvmax, Map<Integer, Integer> mobs,
                            int rewardExp, int rewardMesos, Map<Integer, Integer> rewardItems, boolean randomReward,
                            boolean scripted, boolean timed, List<String> completeReqKeys, boolean actsOk) {
        public QuestMeta {
            mobs = Collections.unmodifiableMap(new LinkedHashMap<>(mobs));
            rewardItems = Collections.unmodifiableMap(new LinkedHashMap<>(rewardItems));
            completeReqKeys = List.copyOf(completeReqKeys);
        }

        /** Total kills the quest needs. */
        public int killsNeeded() {
            int n = 0;
            for (int c : mobs.values()) n += c;
            return n;
        }
    }

    /** The index: runnable quests by id, and for each mob the quests that need it killed. */
    public record Index(Map<Integer, QuestMeta> byId, Map<Integer, List<Integer>> byMob) {
        public static final Index EMPTY = new Index(Map.of(), Map.of());

        public List<QuestMeta> forMob(int mobId) {
            List<Integer> ids = byMob.get(mobId);
            if (ids == null) return List.of();
            List<QuestMeta> out = new ArrayList<>(ids.size());
            for (int id : ids) out.add(byId.get(id));
            return out;
        }
    }

    /**
     * Complete-requirement keys that do not stop a mob quest: the kill targets plus the gates the real
     * {@code canComplete} re-checks. Anything else (item, money, pop, pet, ...) is something a bot cannot get by
     * grinding.
     */
    static final Set<String> ALLOWED_COMPLETE_KEYS = Set.of(
            "mob", "npc", "lvmin", "lvmax", "job", "quest", "interval", "normalAutoStart", "infoNumber");

    /**
     * Start/complete action keys a bot runs safely through {@code Quest.start}/{@code complete}: EXP, mesos, fame,
     * fixed item rewards. Left out: random or chosen items (the item action's space check reads the shared bot
     * client's player, which is null, and a choice needs a selection), items taken back, other quests started or
     * changed (chains), info strings, pets, skills and buffs.
     */
    static final Set<String> ALLOWED_ACT_KEYS = Set.of("exp", "money", "item", "pop", "npc", "lvmin", "lvmax", "job");

    private static volatile Index index;

    private BotQuestIndex() {
    }

    /** The index, built on first use (empty when Quest.wz cannot be read). */
    public static Index get() {
        Index cached = index;
        if (cached != null) return cached;
        synchronized (BotQuestIndex.class) {
            if (index == null) {
                long started = System.currentTimeMillis();
                index = build();
                log.info("Bot quest index: {} runnable mob quests in {} ms", index.byId().size(),
                        System.currentTimeMillis() - started);
            }
            return index;
        }
    }

    /** Test seam: replace the index (null rebuilds from WZ on the next {@link #get}). */
    static void set(Index replacement) {
        index = replacement;
    }

    // ── the pure filter ──

    /** True when this quest is the kill-and-turn-in shape a bot can run legally. */
    public static boolean qualifies(QuestMeta q) {
        if (q == null || q.scripted() || q.timed() || !q.actsOk()) return false;
        if (q.startNpc() <= 0 || q.endNpc() <= 0) return false;
        if (q.mobs().isEmpty()) return false;
        for (String key : q.completeReqKeys()) {
            if (!ALLOWED_COMPLETE_KEYS.contains(key)) return false;
        }
        return true;
    }

    /** Builds the index from runnable metas (also used by tests). */
    public static Index of(List<QuestMeta> metas) {
        Map<Integer, QuestMeta> byId = new LinkedHashMap<>();
        Map<Integer, List<Integer>> byMob = new LinkedHashMap<>();
        for (QuestMeta q : metas) {
            if (!qualifies(q)) continue;
            byId.put(q.id(), q);
            for (int mob : q.mobs().keySet()) byMob.computeIfAbsent(mob, k -> new ArrayList<>()).add(q.id());
        }
        byMob.replaceAll((k, v) -> List.copyOf(v));
        return new Index(Collections.unmodifiableMap(byId), Collections.unmodifiableMap(byMob));
    }

    // ── WZ ──

    static Index build() {
        try {
            DataProvider quest = DataProviderFactory.getDataProvider(WZFiles.QUEST);
            Data checkRoot = quest.getData("Check.img");
            Data actRoot = quest.getData("Act.img");
            Data infoRoot = quest.getData("QuestInfo.img");
            if (checkRoot == null) return Index.EMPTY;
            List<QuestMeta> metas = new ArrayList<>();
            for (Data node : checkRoot.getChildren()) {
                int id;
                try {
                    id = Integer.parseInt(node.getName());
                } catch (NumberFormatException e) {
                    continue;
                }
                metas.add(parse(id, node, actRoot == null ? null : actRoot.getChildByPath(node.getName()),
                        infoRoot == null ? null : infoRoot.getChildByPath(node.getName())));
            }
            return of(metas);
        } catch (RuntimeException e) {
            log.warn("Bot quest index build failed", e);
            return Index.EMPTY;
        }
    }

    /** One quest's Check.img node (with its Act.img and QuestInfo.img nodes) as a {@link QuestMeta}. */
    static QuestMeta parse(int id, Data check, Data act, Data info) {
        Data start = check.getChildByPath("0");
        Data complete = check.getChildByPath("1");
        int startNpc = start == null ? 0 : DataTool.getInt("npc", start, 0);
        int endNpc = complete == null ? 0 : DataTool.getInt("npc", complete, 0);
        int lvmin = start == null ? 0 : DataTool.getInt("lvmin", start, 0);
        int lvmax = start == null ? 0 : DataTool.getInt("lvmax", start, 0);
        Map<Integer, Integer> mobs = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        if (complete != null) {
            for (Data req : complete.getChildren()) keys.add(req.getName());
            Data mobNode = complete.getChildByPath("mob");
            if (mobNode != null) {
                for (Data m : mobNode.getChildren()) {
                    int mob = DataTool.getInt("id", m, 0);
                    int count = DataTool.getInt("count", m, 0);
                    if (mob > 0 && count > 0) mobs.merge(mob, count, Integer::sum);
                }
            }
        }
        boolean scripted = marker(start) || marker(complete) || Files.exists(SCRIPT_DIR.resolve(id + ".js"));
        boolean timed = info != null && (DataTool.getInt("timeLimit", info, 0) > 0
                || DataTool.getInt("timeLimit2", info, 0) > 0);
        int exp = 0, mesos = 0;
        boolean random = false;
        Map<Integer, Integer> items = new LinkedHashMap<>();
        boolean actsOk = actsOk(act == null ? null : act.getChildByPath("0"), false)
                && actsOk(act == null ? null : act.getChildByPath("1"), true);
        Data done = act == null ? null : act.getChildByPath("1");
        if (done != null) {
            exp = DataTool.getInt("exp", done, 0);
            mesos = DataTool.getInt("money", done, 0);
            Data itemNode = done.getChildByPath("item");
            if (itemNode != null) {
                for (Data it : itemNode.getChildren()) {
                    int item = DataTool.getInt("id", it, 0);
                    int count = DataTool.getInt("count", it, 0);
                    if (item <= 0 || count <= 0) continue; // a negative count takes an item; zero is nothing
                    if (it.getChildByPath("job") != null || it.getChildByPath("gender") != null) {
                        random = true; // one meant for a job/gender: reserve one unit for it
                    } else {
                        items.merge(item, count, Integer::sum);
                    }
                }
            }
        }
        return new QuestMeta(id, startNpc, endNpc, lvmin, lvmax, mobs, exp, mesos, items, random, scripted, timed, keys,
                actsOk);
    }

    /** Whether an Act.img node only does what a bot can take (no items at start; fixed, given items at the end). */
    static boolean actsOk(Data node, boolean itemsAllowed) {
        if (node == null) return true;
        for (Data a : node.getChildren()) {
            String key = a.getName();
            if (!ALLOWED_ACT_KEYS.contains(key)) return false;
            if (!"item".equals(key)) continue;
            if (!itemsAllowed) return false;
            for (Data it : a.getChildren()) {
                if (DataTool.getInt("count", it, 0) <= 0 || DataTool.getInt("prop", it, 0) != 0
                        || it.getChildByPath("period") != null) return false;
            }
        }
        return true;
    }

    private static boolean marker(Data node) {
        return node != null && (node.getChildByPath("startscript") != null || node.getChildByPath("endscript") != null);
    }
}

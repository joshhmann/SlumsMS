package soloMapling.ArtificialPlayer.BotMakerSystem;

import client.Character;
import client.Client;
import client.Skill;
import client.SkillFactory;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.MakerService;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.Economy.EconomyConfig;
import soloMapling.Economy.EconomyMeters;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/*
 * Ticket 112 (owner 2026-10-30, "bots: plan, with crafting capped"): population bots and owned alts batch-make
 * monster crystals from their leftovers and disassemble junk equips, through the real Maker path
 * (MakerService.makeLeftoverCrystal / disassemble: the recipe, the checks, the rolls and the result are the Maker
 * window's). One implementation for alts and population bots (owner rule); only the hands differ (a population bot's
 * items go through its ledger, BotMakerHands; an alt has its own client).
 *
 * Capped so crafted goods don't flood the market (economy.yaml maker.*): each operation (one crystal, one
 * disassembly) counts toward the character's own daily cap (maker.perBotDaily, in memory: a restart gives a bot its
 * day back) and the world's (maker.worldDaily, the persisted economy meter "makerbatch"). The whole thing is behind
 * maker.botBatch, off by default; the read-only plan (MakerPlan) is not.
 *
 * Population bots never walk Moren's quests, so with the switch on a population bot learns Maker as if it had: level
 * 45, 75 and 105 give Maker 1, 2 and 3 (the chain's own gates). Owned alts learn it the player's way, through the
 * quests.
 *
 * Inspired by nutnnut/Cosmic 6fd0512a0 (branch dev) src/main/java/server/bots/BotMakerManager.java (AGPL-3.0): "make monster
 * crystals" and "disassemble trash" through the player's MakerProcessor path. Upstream paces one operation per 5 s
 * and has no caps; here a batch is a handful of operations at a town stop (or on the owner's word), so it runs at once.
 */
public final class BotMaker {

    private static final Logger log = LoggerFactory.getLogger(BotMaker.class);

    /* The persisted world meter: Maker operations bots and alts made today. */
    public static final String WORLD_METER = "makerbatch";
    /* Monster leftovers: the 4000xxx-4009xxx etc drops (ores and other etc items are never turned into crystals). */
    static final int LEFTOVER_PREFIX = 400;

    public record Result(int made, String stoppedBy) {
        public static final Result NONE = new Result(0, "nothing to do");
    }

    /* One Maker operation; its MakerService status (0 = made). */
    @FunctionalInterface
    interface Op {
        short run();
    }

    private static final Map<Integer, Integer> todayByChar = new ConcurrentHashMap<>();
    private static volatile LocalDate countedDay;
    private static volatile Supplier<LocalDate> clock = LocalDate::now;

    private BotMaker() {
    }

    // ---- switches and caps (economy.yaml maker.*) ----

    public static boolean enabled() {
        return EconomyConfig.getBool("maker.botBatch", false);
    }

    static int perBotDaily() {
        return Math.max(0, EconomyConfig.getInt("maker.perBotDaily", 10));
    }

    static long worldDaily() {
        return Math.max(0, EconomyConfig.getLong("maker.worldDaily", 300));
    }

    /* Operations this character made today. */
    static int doneToday(int charId) {
        roll();
        return todayByChar.getOrDefault(charId, 0);
    }

    /* Operations this character made today, for the auto-QA check (t-mk-2). */
    public static int opsToday(int charId) {
        return doneToday(charId);
    }

    /* Operations the world's cap still has today (for the auto-QA check). */
    public static long worldCapLeft() {
        return worldDaily() - EconomyMeters.get(WORLD_METER);
    }

    /* Gives a character its day back (the auto-QA check's throwaway bot: ids are reused between runs). */
    public static void forget(int charId) {
        todayByChar.remove(charId);
    }

    private static void roll() {
        LocalDate now = clock.get();
        if (!now.equals(countedDay)) {
            synchronized (BotMaker.class) {
                if (!now.equals(countedDay)) {
                    todayByChar.clear();
                    countedDay = now;
                }
            }
        }
    }

    static void useForTest(Supplier<LocalDate> testClock) {
        clock = testClock == null ? LocalDate::now : testClock;
        todayByChar.clear();
        countedDay = null;
    }

    // ---- the batches ----

    /* Turns the character's monster leftovers into crystals, 100 per crystal, at most {@code max} (and the caps). */
    public static Result makeCrystals(Character chr, int max) {
        MakerService.Hands h = hands(chr);
        if (h == null) {
            return new Result(0, "no hands");
        }
        List<Op> ops = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : leftovers(chr).entrySet()) {
            int leftover = e.getKey();
            for (int i = 0; i < e.getValue(); i++) {
                ops.add(() -> MakerService.makeLeftoverCrystal(h, leftover));
            }
        }
        return run(chr, ops, max);
    }

    /* Breaks the character's junk equips (Maker-made, not worn, not an upgrade) into crystals, at most {@code max}. */
    public static Result disassembleJunk(Character chr, int max) {
        MakerService.Hands h = hands(chr);
        if (h == null) {
            return new Result(0, "no hands");
        }
        List<Op> ops = new ArrayList<>();
        for (Equip e : junk(chr, MakerGearScore.live())) {
            short pos = e.getPosition();
            int id = e.getItemId();
            ops.add(() -> MakerService.disassemble(h, pos, id));
        }
        return run(chr, ops, max);
    }

    /*
     * A population bot's town stop (TrainingBot, before it sells its junk): crystals first, then junk, within its
     * caps. Nothing unless the switch is on. Never throws.
     */
    public static Result townBatch(Character bot) {
        if (!enabled() || bot == null || !BotHelpers.isRosterBot(bot)) {
            return Result.NONE;
        }
        try {
            learnByLevel(bot);
            if (MakerService.getMakerSkillLevel(bot) < 1) {
                return Result.NONE;
            }
            Result crystals = makeCrystals(bot, Integer.MAX_VALUE);
            Result junk = disassembleJunk(bot, Integer.MAX_VALUE);
            int made = crystals.made() + junk.made();
            if (made > 0) {
                log.info("[Maker] {} (lv {}) made {} crystal(s) and disassembled {} equip(s) on map {}", bot.getName(),
                        bot.getLevel(), crystals.made(), junk.made(), bot.getMapId());
            }
            return new Result(made, junk.stoppedBy());
        } catch (RuntimeException | LinkageError e) {
            log.warn("Maker batch failed for {}", bot.getName(), e);
            return Result.NONE;
        }
    }

    /*
     * Runs operations in order until the list ends or a cap is reached. Each operation first takes one unit of the
     * world's daily cap (given back if the operation does not happen, or throws) and must fit the character's own.
     * An operation that fails (this leftover makes no crystal, that equip cannot be disassembled, no room for its
     * crystals, short of its fee) is skipped and the batch goes on: the list is built in the same order at every town
     * stop, so stopping at the first failure dead-ended the batch on one item for good (audit 163 §2). The result
     * names the first failure when nothing stopped it sooner.
     *
     * The caps count operations, not crystals: one crystal made from 100 leftovers is one, and one equip disassembled
     * is one however many crystals it gives back (half its recipe's).
     */
    static Result run(Character chr, List<Op> ops, int max) {
        if (!enabled()) {
            return new Result(0, "off");
        }
        if (ops.isEmpty()) {
            return Result.NONE;
        }
        Client c = chr.getClient();
        boolean locked = !BotHelpers.isRosterBot(chr) && c != null;   // a player or an alt: its own client, as the window
        if (locked && !c.tryacquireClient()) {
            return new Result(0, "busy");
        }
        int made = 0;
        String stop = "done";
        String failed = null;
        try {
            for (Op op : ops) {
                if (made >= max) {
                    stop = "asked for " + max;
                    break;
                }
                if (doneToday(chr.getId()) >= perBotDaily()) {
                    stop = "daily limit (" + perBotDaily() + ")";
                    break;
                }
                if (!EconomyMeters.tryAdd(WORLD_METER, 1, worldDaily())) {
                    stop = "world daily limit (" + worldDaily() + ")";
                    break;
                }
                short status;
                try {
                    status = op.run();
                } catch (RuntimeException | LinkageError e) {
                    EconomyMeters.add(WORLD_METER, -1);   // it did not happen: the world's unit goes back
                    throw e;
                }
                if (status != 0) {
                    EconomyMeters.add(WORLD_METER, -1);
                    if (failed == null) {
                        failed = reason(status);
                    }
                    continue;
                }
                todayByChar.merge(chr.getId(), 1, Integer::sum);
                made++;
            }
            if ("done".equals(stop) && failed != null) {
                stop = failed;
            }
        } finally {
            if (locked) {
                c.releaseClient();
            }
        }
        return new Result(made, stop);
    }

    static String reason(short status) {
        return switch (status) {
            case -1 -> "not a Maker item";
            case 1 -> "out of materials";
            case 2 -> "out of mesos";
            case 3 -> "level too low";
            case 4 -> "Maker level too low";
            case 5 -> "inventory full";
            default -> "status " + status;
        };
    }

    /*
     * The Maker path's hands for this character: a population bot's ledger, or the client of a player or an owned
     * alt (its own client with the alt attached). Null when neither holds (a bot whose client is someone else's).
     */
    static MakerService.Hands hands(Character chr) {
        if (chr == null) {
            return null;
        }
        if (BotHelpers.isRosterBot(chr)) {
            return new BotMakerHands(chr);
        }
        Client c = chr.getClient();
        return c != null && c.getPlayer() == chr ? MakerService.of(c) : null;
    }

    // ---- what goes in ----

    /* Monster leftovers held in 100s that make a crystal: item id to how many crystals. */
    static Map<Integer, Integer> leftovers(Character chr) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        Inventory etc = chr.getInventory(InventoryType.ETC);
        etc.lockInventory();
        try {
            for (Item it : etc.list()) {
                if (it.getItemId() / 10000 == LEFTOVER_PREFIX) {
                    counts.merge(it.getItemId(), (int) it.getQuantity(), Integer::sum);
                }
            }
        } finally {
            etc.unlockInventory();
        }
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            int crystals = e.getValue() / MakerService.LEFTOVERS_PER_CRYSTAL;
            if (crystals > 0 && !ii.isQuestItem(e.getKey()) && ii.getMakerCrystalFromLeftover(e.getKey()) != -1) {
                out.put(e.getKey(), crystals);
            }
        }
        return out;
    }

    /*
     * Junk to disassemble: carried (not worn) equips with a Maker disassembly recipe that would not be worn (no
     * upgrade by the gear score), never cash, quest, locked, ring or rebirth items.
     */
    static List<Equip> junk(Character chr, MakerGearScore score) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<Equip> carried = new ArrayList<>();
        Inventory inv = chr.getInventory(InventoryType.EQUIP);
        inv.lockInventory();
        try {
            for (Item it : inv.list()) {
                if (it instanceof Equip e && e.getPosition() > 0) {
                    carried.add(e);
                }
            }
        } finally {
            inv.unlockInventory();
        }
        List<Equip> out = new ArrayList<>();
        for (Equip e : carried) {
            int id = e.getItemId();
            if ((e.getFlag() & ItemConstants.LOCK) != 0 || e.getRingId() > 0 || ii.isCash(id) || ii.isQuestItem(id)
                    || soloMapling.ArtificialPlayer.BotProgressionSystem.BotRebirth.keeps(id)
                    || !MakerService.canDisassemble(id) || score.upgradeValue(chr, e) > 0) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    // ---- population bots learn Maker by level ----

    /* The Maker level Moren's chain gives at this character level: 1 at 45, 2 at 75, 3 at 105. */
    static int makerLevelFor(int level) {
        return level >= 105 ? 3 : level >= 75 ? 2 : level >= 45 ? 1 : 0;
    }

    /* A population bot past a first job learns the Maker level its level would have earned at Moren's. */
    static void learnByLevel(Character bot) {
        int job = bot.getJob().getId();
        if (job % 1000 == 0 || job == 2001) {
            return;   // a beginner (Moren's chain needs a first job)
        }
        int want = makerLevelFor(bot.getLevel());
        if (want <= MakerService.getMakerSkillLevel(bot)) {
            return;
        }
        Skill maker = SkillFactory.getSkill(MakerService.makerSkillId(job));
        if (maker != null) {
            bot.changeSkillLevel(maker, (byte) want, 3, -1);
        }
    }
}

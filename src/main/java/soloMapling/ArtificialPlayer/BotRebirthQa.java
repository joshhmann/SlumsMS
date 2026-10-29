package soloMapling.ArtificialPlayer;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import server.expeditions.ExpeditionBossLog;
import server.expeditions.ExpeditionBossLog.BossLogEntry;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotProgression;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotRealStats;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotRebirth;
import soloMapling.ArtificialPlayer.BotProgressionSystem.RebirthBossRuns;
import soloMapling.ArtificialPlayer.BotProgressionSystem.RebirthBosses.Boss;
import soloMapling.itemPool.EquipMetadataCache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.6.1 ticket 30: staging for {@code !qa rb-1}/{@code rb-2} and auto-QA t-rb-1/t-rb-2. An online roster bot without
 * rebirths is raised to level 200 on its own build (4th job), then walks the earned path with the pace forced.
 * <ul>
 *   <li>rb-1 ({@link #walk}): its 1st key, Zakum's and Horntail's items, through off-screen runs on the players' rules
 *   (clear limits, its real damage against the bosses' real HP); the key crafted, the trip, the rebirth.</li>
 *   <li>rb-2 ({@link #stageVonLeon}, {@link #runVonLeon}): staged at 1 rebirth holding Pink Bean's item, it forms a
 *   real Von Leon run on screen; the item must come from that kill; then the key, the rebirth.</li>
 * </ul>
 * {@link #check} judges the rebirth; {@link #restore} puts the bot back exactly as it was (level, job, build, rebirths,
 * its characters_rebirth row, EXP rate, worn gear, the QA's items and boss-log rows gone, the pace slot given back).
 */
public final class BotRebirthQa {

    /** What the bot was before the run (and what the run did that the restore undoes). */
    public record Before(Character bot, int level, long exp, int job, int reborns, BotRealStats build, int expRate,
                         long meso, int cashItems, Map<Short, Integer> worn, Map<Integer, Integer> skills,
                         Map<BossLogEntry, Integer> bossLog, int starterWeapons, Set<String> marks,
                         Map<Integer, Integer> helperLogs, java.util.concurrent.atomic.AtomicLong walletAtWalk) {
    }

    private BotRebirthQa() {
    }

    /** Picks an online bot (a build job, level 30+, no rebirths) near {@code player}'s level; throws if none. */
    public static Before pick(Character player) {
        return pick(player, Set.of());
    }

    /** As {@link #pick(Character)}, never one of {@code skip}. */
    public static Before pick(Character player, Set<Integer> skip) {
        if (!BotRebirth.enabled()) {
            throw new IllegalStateException("bot rebirths are off (BOT_REBIRTH_ENABLED and USE_REBIRTH_SYSTEM)");
        }
        List<Character> bots = new ArrayList<>();
        for (BotSM sm : soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getAllBots().values()) {
            Character c = sm == null ? null : sm.getChr();
            if (c != null && c.isBot() && c.getMap() != null && c.getWorld() == player.getWorld() && c.getLevel() >= 30
                    && c.getLevel() < 200 && c.getParty() == null && BotBuild.isBuildJob(c.getJob().getId())
                    && BotProgression.isReal(c.getId()) && c.getReborns() == 0 && c.getEventInstance() == null
                    && !RebirthBossRuns.holds(c.getId()) && !skip.contains(c.getId()) && sm instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot) {
                bots.add(c);
            }
        }
        if (bots.isEmpty()) throw new IllegalStateException("no online trainer (level 30-199, a build job, no party, no rebirths)");
        bots.sort(Comparator.comparingInt((Character c) -> c.getMapId() == player.getMapId() ? 0 : 1)
                .thenComparingInt(c -> -c.getLevel()));
        Character bot = bots.getFirst();
        synchronized (bot) {
            Map<Short, Integer> worn = new LinkedHashMap<>();
            for (Item i : bot.getInventory(InventoryType.EQUIPPED).list()) worn.put(i.getPosition(), i.getItemId());
            Map<Integer, Integer> skills = new LinkedHashMap<>();
            bot.getSkills().forEach((s, e) -> { if (s != null && e != null && e.skillevel > 0) skills.put(s.getId(),
                    (int) e.skillevel); });
            Map<BossLogEntry, Integer> logs = new EnumMap<>(BossLogEntry.class);
            for (Boss boss : Boss.values()) {
                BossLogEntry e = boss.logEntry();
                if (e != null) logs.put(e, Math.max(0, ExpeditionBossLog.countPlayerEntries(bot.getId(), e)));
            }
            return new Before(bot, bot.getLevel(), bot.getExp(), bot.getJob().getId(), bot.getReborns(),
                    BotProgression.snapshot(bot), bot.getExpRate(), bot.getMeso(),
                    bot.getInventory(InventoryType.CASH).list().size(), worn, skills, logs, starters(bot),
                    ConcurrentHashMap.newKeySet(), new ConcurrentHashMap<>(),
                    new java.util.concurrent.atomic.AtomicLong(bot.getMeso()));
        }
    }

    /** Raises the bot to 200 on its build (it advances to 4th job as any bot does); false if it did not get there. */
    public static boolean raise(Before b) {
        Character bot = b.bot();
        synchronized (bot) {
            bot.setLevel(BotRebirth.REBIRTH_LEVEL);
            BotProgression.afterOffscreenLevels(bot, b.level());
            b.walletAtWalk().set(bot.getMeso()); // the 4th-job package (10m, NPC 2084000) is the raise's, not the rebirth's
            return bot.getLevel() == BotRebirth.REBIRTH_LEVEL && BotRebirth.fullyAdvanced(bot.getJob().getId());
        }
    }

    /**
     * rb-1: walks the earned path with the pace forced: up to twelve decisions (each one off-screen boss run on the
     * players' rules; its outcome is real, so some may not clear), the craft, the trip; then the rebirth there.
     * Returns the evidence lines; the first says what happened.
     */
    public static List<String> walk(Before b) {
        Character bot = b.bot();
        List<String> ev = new ArrayList<>();
        long now = System.currentTimeMillis();
        forcePace(now);
        BotRebirth.certainFor(120_000);
        try {
            int trip = -1;
            for (int i = 0; i < 12 && trip < 0 && bot.getReborns() == b.reborns(); i++) {
                trip = BotRebirth.tripMap(bot, now);
                ev.add("decision " + (i + 1) + ": stage " + BotRebirth.stageOf(bot, now) + ", items "
                        + held(bot, BotRebirth.ingredientsFor(b.reborns())) + ", key "
                        + count(bot, BotRebirth.keyItemFor(b.reborns())) + ", trip " + trip + "; "
                        + BotRebirth.lastOutcome(bot.getId()));
            }
            ev.addFirst(rebirthAt(b, now).text());
        } finally {
            BotRebirth.certainFor(0);
        }
        return ev;
    }

    /** rb-2: one rebirth already (as if earned), Pink Bean's item in the bag (bots cannot run its stages alone). */
    public static String stageVonLeon(Before b) {
        Character bot = b.bot();
        synchronized (bot) {
            bot.setBotReborns(1);
            b.marks().add("staged-reborns");
            takeAll(bot, Boss.VONLEON.clearItem);
            if (count(bot, Boss.PINKBEAN.clearItem) <= 0) {
                BotHoldings.grantItem(bot, new Item(Boss.PINKBEAN.clearItem, (short) 0, (short) 1));
            }
        }
        return "staged at rebirth 1 with Pink Bean's " + Boss.PINKBEAN.clearItem + " (staged: Pink Bean's statue stage"
                + " needs a player); Von Leon's " + Boss.VONLEON.clearItem + " to earn: " + count(bot, Boss.VONLEON.clearItem);
    }

    /** What the Von Leon run did. */
    public record RunResult(boolean started, boolean cleared, String gotBy, String result, List<String> notes) {
    }

    /**
     * rb-2: the bot's own decision forms a real Von Leon run (RebirthBossRuns); waits for it to end, at most
     * {@code waitMs}. The item must be in its bag afterwards, earned in that run.
     */
    public static RunResult runVonLeon(Before b, long waitMs) throws InterruptedException {
        Character bot = b.bot();
        BotRebirth.certainFor(waitMs + 60_000);
        try {
            String[] why = {"?"};
            RebirthBossRuns.Run run = startVonLeon(b, why);
            if (run == null) return new RunResult(false, false, null, why[0], List.of());
            long deadline = System.currentTimeMillis() + waitMs;
            while (run.phase() != RebirthBossRuns.Phase.DONE && System.currentTimeMillis() < deadline) {
                Thread.sleep(2_000);
            }
            if (run.phase() != RebirthBossRuns.Phase.DONE) {
                RebirthBossRuns.endForQa(run, "QA wait over");
            }
            return result(b, run);
        } finally {
            BotRebirth.certainFor(0);
        }
    }

    /** rb-2/t-rb-2: Von Leon starts at this share of his HP so the check fits in minutes; the fight stays real. */
    public static final double QA_BOSS_HP = 0.01;
    /** rb-2/t-rb-2: the staged Von Leon dies in about this long to the bot alone (no helper has a rebirth on TEST). */
    static final double QA_KILL_SECONDS = 120;

    /**
     * The share of Von Leon's HP the staged fight starts at: {@link #QA_BOSS_HP}, or less so that the bot alone kills
     * him in about {@link #QA_KILL_SECONDS} (b034 run 3: a lv 200 Dark Knight at 51,681 a second needed 24 minutes
     * for 1%, past the check's wait).
     */
    public static double qaBossHp(Character bot) {
        return qaBossHp(soloMapling.ArtificialPlayer.BotProgressionSystem.OffscreenBossRun.dpsAgainst(bot, Boss.VONLEON),
                soloMapling.ArtificialPlayer.BotProgressionSystem.OffscreenBossRun.hpOf(Boss.VONLEON));
    }

    /** Tests: {@link #qaBossHp} from a damage a second and an HP. */
    public static double qaBossHpForTest(double dps, long hp) {
        return qaBossHp(dps, hp);
    }

    static double qaBossHp(double dps, long hp) {
        if (!(dps > 0) || hp <= 0) return QA_BOSS_HP;
        return Math.max(1e-6, Math.min(QA_BOSS_HP, dps * QA_KILL_SECONDS / hp));
    }

    /**
     * t-rb-1: a group of six of this bot clears Zakum and Horntail nine runs in ten (inside half their time limit), so
     * its walk can earn the 1st key; with the line that says why (its damage a second and the kill times).
     */
    public static boolean strongEnough(Before b, List<String> ev) {
        Character bot = b.bot();
        boolean ok = true;
        StringBuilder line = new StringBuilder(bot.getName() + " (job " + bot.getJob().getId() + " lv "
                + bot.getLevel() + "):");
        for (Boss boss : new Boss[]{Boss.ZAKUM, Boss.HORNTAIL}) {
            double dps = soloMapling.ArtificialPlayer.BotProgressionSystem.OffscreenBossRun.dpsAgainst(bot, boss);
            double min = soloMapling.ArtificialPlayer.BotProgressionSystem.OffscreenBossRun.groupMinutes(bot, boss);
            line.append(String.format(" %s %,.0f dmg/s, a group of 6 in %.1f min of %d;", boss, dps, min,
                    boss.timeLimitMinutes));
            if (!(min <= boss.timeLimitMinutes / 2.0)) ok = false;
        }
        ev.add((ok ? "strong enough: " : "too weak, next bot: ") + line);
        return ok;
    }

    /**
     * rb-2: the bot's next item must be Von Leon's, a boss bots run themselves (its own decision would form this run);
     * the run is formed for it now with the boss at {@link #QA_BOSS_HP} (null with why[0] when none formed).
     */
    public static RebirthBossRuns.Run startVonLeon(Before b, String[] why) {
        Character bot = b.bot();
        int next = BotRebirth.nextItem(bot);
        Boss boss = soloMapling.ArtificialPlayer.BotProgressionSystem.RebirthBosses.Boss.byItem(next);
        if (boss != Boss.VONLEON || !boss.botsRunIt) {
            why[0] = "its next item is " + next + " (" + boss + "), not Von Leon's";
            return null;
        }
        RebirthBossRuns.Run run = RebirthBossRuns.startForQa(bot, Boss.VONLEON, qaBossHp(bot), why);
        if (run != null) b.marks().add("ran");
        return run;
    }

    /** What this run did for the bot (and the helpers' clears for the restore). */
    public static RunResult result(Before b, RebirthBossRuns.Run run) {
        if (run.cleared()) {
            for (int id : run.crewIds()) {
                if (id != b.bot().getId()) b.helperLogs().putIfAbsent(id, 1);
            }
        }
        return new RunResult(true, run.cleared(), run.gotBy(b.bot().getId()), run.result(), run.notes());
    }

    /** rb-2 after the run: its decisions craft the key and send it to Wyatt; the rebirth there (pace forced). */
    public static List<String> finishRebirth(Before b) {
        Character bot = b.bot();
        long now = System.currentTimeMillis();
        forcePace(now);
        List<String> ev = new ArrayList<>();
        int trip = -1;
        for (int i = 0; i < 3 && trip < 0; i++) {
            trip = BotRebirth.tripMap(bot, now);
            ev.add("decision " + (i + 1) + ": stage " + BotRebirth.stageOf(bot, now) + ", key "
                    + count(bot, BotRebirth.keyItemFor(bot.getReborns())) + ", trip " + trip);
        }
        ev.addFirst(rebirthAt(b, now).text());
        return ev;
    }

    private static BotRebirth.Result rebirthAt(Before b, long now) {
        Character bot = b.bot();
        int before = bot.getReborns();
        BotRebirth.Result r = BotRebirth.rebirthHere(bot, now, false);
        if (!r.done() && bot.getReborns() == before + 1) {
            r = new BotRebirth.Result(true, "its own decision got there first: " + bot.getName() + " rebirth "
                    + bot.getReborns());
        }
        if (bot.getReborns() > before) b.marks().add("reborn");
        return new BotRebirth.Result(r.done(), (r.done() ? "REBORN " : "NOT REBORN ") + r.text());
    }

    private static void forcePace(long now) {
        if (BotRebirth.paceLeft(now) <= 0) BotRebirth.givePaceBack(now); // one slot today for the QA run
    }

    /** What is wrong with the rebirth the QA run just made (empty: it is right). */
    public static List<String> check(Before b, int fromReborns) {
        Character bot = b.bot();
        List<String> bad = new ArrayList<>();
        int r = fromReborns + 1;
        if (bot.getReborns() != r) bad.add("rebirths " + bot.getReborns() + ", expected " + r);
        if (bot.getLevel() != 1 || bot.getExp() != 0) bad.add("level " + bot.getLevel() + " exp " + bot.getExp());
        int job = bot.getJob().getId();
        if (job != 0 && job != 1000 && job != 2000) bad.add("job " + job + " is not a beginner");
        if (count(bot, BotRebirth.keyItemFor(fromReborns)) != 0) bad.add("the key item was not taken");
        if (!held(bot, BotRebirth.ingredientsFor(fromReborns)).equals("0/0")) bad.add("boss items left over");
        int rate = BotRebirth.rateFor(r, 1);
        if (rate > 0 && bot.getExpRate() != rate) bad.add("EXP rate " + bot.getExpRate() + ", expected " + rate);
        // REPORT item 2: it wears only what a level 1 can, and keeps every piece it wore (on, or in its bag)
        List<Integer> owned = new ArrayList<>();
        for (Item i : bot.getInventory(InventoryType.EQUIPPED).list()) {
            owned.add(i.getItemId());
            int req = reqLevel(i.getItemId());
            if (i.getPosition() > -100 && req > bot.getLevel()) {
                bad.add("still wears " + i.getItemId() + " (level " + req + ")");
            }
        }
        for (Item i : bot.getInventory(InventoryType.EQUIP).list()) owned.add(i.getItemId());
        for (int id : b.worn().values()) {
            if (!owned.remove((Integer) id)) bad.add("gear " + id + " was lost");
        }
        if (bot.getMeso() != b.walletAtWalk().get()) {
            bad.add("the rebirth path moved mesos: " + b.walletAtWalk().get() + " -> " + bot.getMeso());
        }
        if (bot.getInventory(InventoryType.CASH).list().size() != b.cashItems()) bad.add("cash items changed");
        long lost = b.skills().keySet().stream().filter(id -> bot.getSkills().keySet().stream()
                .noneMatch(s -> s != null && s.getId() == id)).count();
        if (lost > 0) bad.add(lost + " past skills lost");
        if (BotRebirth.rebirthHere(bot, System.currentTimeMillis(), false).done()) bad.add("reborn again at level 1");
        return bad;
    }

    /** rb-1's check (from no rebirths). */
    public static List<String> check(Before b) {
        return check(b, b.reborns());
    }

    /** One line on the bot now. */
    public static String describe(Before b) {
        Character bot = b.bot();
        int worn = 0;
        for (Item i : bot.getInventory(InventoryType.EQUIPPED).list()) if (i.getPosition() > -100) worn++;
        return bot.getName() + " lv " + bot.getLevel() + " job " + bot.getJob().getId() + " rebirths "
                + bot.getReborns() + " EXP rate " + bot.getExpRate() + " key " + count(bot,
                BotRebirth.keyItemFor(Math.max(0, bot.getReborns() - 1))) + " wears " + worn + " pieces";
    }

    /** Puts the bot back exactly as it was before the run; returns what it did. */
    public static String restore(Before b) {
        Character bot = b.bot();
        RebirthBossRuns.Run run = RebirthBossRuns.runOf(bot.getId());
        if (run != null) RebirthBossRuns.endForQa(run, "QA cleanup");
        boolean reborn = b.marks().contains("reborn");
        boolean recorded = bot.getReborns() > b.reborns();
        List<String> did = new ArrayList<>();
        synchronized (bot) {
            for (int r = b.reborns(); r < BotRebirth.MAX_REBIRTHS; r++) {
                takeAll(bot, BotRebirth.keyItemFor(r));
                for (int id : BotRebirth.ingredientsFor(r)) takeAll(bot, id);
            }
            bot.setBotReborns(b.reborns());
            if (recorded || reborn) BotRebirth.unrecord(bot, b.reborns());
            bot.setJob(Job.getById(b.job()));
            bot.setLevel(b.level());
            bot.setExp(b.exp());
            BotProgression.forget(bot.getId());
            if (b.build() != null) BotProgression.restore(bot, b.build());
            else BotProgression.buildNew(bot);
            bot.setPlayerExpRatesCerezeth(b.expRate());
            did.add(regear(bot, b));
            bot.refreshStatsAfterProgression();
            long back = b.meso() - bot.getMeso();   // the raise's 4th-job package, paid back
            if (back > 0 && back <= Integer.MAX_VALUE && BotHoldings.grantMeso(bot, (int) back)) {
                did.add("paid back " + back + " mesos (the raise's 4th-job package)");
            } else if (back < 0 && -back <= Integer.MAX_VALUE) {
                BotHoldings.spendMeso(bot, (int) -back);
            }
        }
        // the QA's boss clears out of bosslog_daily: its own, and one Von Leon clear per helper of a cleared run
        b.bossLog().forEach((entry, was) -> {
            int extra = ExpeditionBossLog.countPlayerEntries(bot.getId(), entry) - was;
            if (extra > 0) {
                ExpeditionBossLog.removePlayerEntry(bot.getId(), entry, extra);
                did.add("removed " + extra + " " + entry + " clear(s)");
            }
        });
        b.helperLogs().forEach((id, n) -> ExpeditionBossLog.removePlayerEntry(id, BossLogEntry.VONLEON, n));
        if (!b.helperLogs().isEmpty()) did.add("removed the helpers' Von Leon clears " + b.helperLogs());
        if (reborn) BotRebirth.givePaceBack(System.currentTimeMillis());
        return "restored " + describe(b) + "; " + String.join("; ", did);
    }

    /** Its old gear back on the slots it wore it in; a starter weapon the rebirth gave it taken away. */
    private static String regear(Character bot, Before b) {
        Inventory body = bot.getInventory(InventoryType.EQUIPPED);
        Inventory bag = bot.getInventory(InventoryType.EQUIP);
        int moved = 0;
        for (Map.Entry<Short, Integer> e : b.worn().entrySet()) {
            short slot = e.getKey();
            Item on = body.getItem(slot);
            if (on != null && on.getItemId() == e.getValue()) continue;
            Item want = null;
            for (Item i : bag.list()) {
                if (i.getItemId() == e.getValue()) {
                    want = i;
                    break;
                }
            }
            if (!(want instanceof Equip piece)) continue;
            short from = piece.getPosition();
            bag.removeSlot(from);
            if (on instanceof Equip off) {
                body.removeSlot(slot);
                bot.unequippedItem(off);
                off.setPosition(from);
                bag.addItemFromDB(off);
            }
            piece.setPosition(slot);
            piece.wear(true);
            bot.equippedItem(piece);
            body.addItemFromDB(piece);
            moved++;
        }
        int starters = starters(bot) - b.starterWeapons();
        for (Item i : new ArrayList<>(bag.list())) {
            if (starters <= 0) break;
            if (i.getItemId() == soloMapling.ArtificialPlayer.BotGearSystem.BotGear.STARTER_WEAPON) {
                bag.removeSlot(i.getPosition());
                starters--;
            }
        }
        if (moved > 0 && bot.getMap() != null) bot.equipChanged();
        return moved + " piece(s) put back on";
    }

    private static int starters(Character bot) {
        int n = 0;
        for (InventoryType t : new InventoryType[]{InventoryType.EQUIP, InventoryType.EQUIPPED}) {
            for (Item i : bot.getInventory(t).list()) {
                if (i.getItemId() == soloMapling.ArtificialPlayer.BotGearSystem.BotGear.STARTER_WEAPON) n++;
            }
        }
        return n;
    }

    private static int reqLevel(int itemId) {
        try {
            EquipMetadataCache.EquipEntry e = EquipMetadataCache.isInitialized() ? EquipMetadataCache.get().find(itemId)
                    : null;
            return e == null ? 0 : e.reqLevel;
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static void takeAll(Character bot, int itemId) {
        if (itemId < 0) return;
        for (Item held; (held = bot.getInventory(InventoryType.ETC).findById(itemId)) != null; ) {
            if (BotHoldings.consumeHeld(bot, held, held.getQuantity()) <= 0) return;
        }
    }

    private static int count(Character bot, int itemId) {
        return itemId < 0 ? 0 : bot.getInventory(InventoryType.ETC).countById(itemId);
    }

    private static String held(Character bot, int[] ids) {
        return ids.length < 2 ? "-" : count(bot, ids[0]) + "/" + count(bot, ids[1]);
    }
}

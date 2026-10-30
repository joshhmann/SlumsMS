package soloMapling.autoqa;

import client.Character;
import client.Job;
import client.QuestStatus;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import server.ItemInformationProvider;
import server.life.LifeFactory;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotConvo.TalkQa;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGear;
import soloMapling.ArtificialPlayer.BotGearSystem.GearScoring;
import soloMapling.ArtificialPlayer.BotGrindSystem.GearFirstAdvisor;
import soloMapling.ArtificialPlayer.BotScrollSystem.BotScrolling;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollEvQa;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotBagRoom;
import soloMapling.ArtificialPlayer.BotSupplySystem.JunkSort;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMap;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMapChooser;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMapFinder;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeGoals;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotProgressionSystem.ApVariantQa;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotProgression;
import soloMapling.ArtificialPlayer.GCMoveSystem.LodCounts;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePlanner;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotQuestSystem.BotQuestLog;
import soloMapling.ArtificialPlayer.BotQuestSystem.BotQuests;
import soloMapling.ArtificialPlayer.BotQuestSystem.TempleLanes;
import soloMapling.ArtificialPlayer.BotQuestSystem.TempleOfTime;
import soloMapling.ArtificialPlayer.BotQuestSystem.TempleRoute;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 1.6.2 auto-QA probes for the bot switches the 1.6.2 bot audit (ticket 163, section 4) found with no auto-QA proof:
 * bot quests (t-bq-1), the Temple of Time (t-tm-1), the gear and build switches (t-gb-*), the bot support switches
 * (t-su-*), job errands and skill books (t-jb-*) and the two nav ports (t-nn-*).
 * <p>
 * Each probe stages fresh throwaway bots next to the pretend player ({@link TalkQa#spawn}), drives the switch's real
 * public entry point on them, judges, and despawns them in a {@code finally} ({@link TalkQa#teardown}); anything else
 * it changed (map pins, properties) is put back there too. The first line says PASS / FAIL / SKIP (SKIP names the
 * switch that is off), the rest is evidence written so a FAIL says which step went wrong. The verdict rules are
 * static and pure, for the tests. These probes only read and drive; they never change how the game behaves.
 */
final class Probes162 {

    /** Every probe name here, for the catalog test. */
    static final List<String> NAMES = List.of("botQuest", "templeQuest", "paretoGear", "dexlessAp", "gearFirstFarm", "junkSortSale", "scrollEv", "apVariants");

    private Probes162() {
    }

    /** The probe's lines, or null when {@code what} is not one of these. */
    static List<String> probe(String what, SyntheticPlayer p, String... a) {
        try {
            return switch (what) {
                case "botQuest" -> botQuest(p);
                case "templeQuest" -> templeQuest(p);
                case "paretoGear" -> paretoGear(p);
                case "dexlessAp" -> dexlessAp(p);
                case "gearFirstFarm" -> gearFirstFarm(p);
                case "junkSortSale" -> junkSortSale(p);
                case "scrollEv" -> scrollEv(p);
                case "apVariants" -> apVariants();
                default -> null;
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of("SKIP interrupted");
        } catch (Exception e) {
            return List.of("FAIL probe " + what + " threw " + e);
        }
    }

    // ── shared helpers ───────────────────────────────────────────────────────────────────────────

    /** One fresh training bot next to the player at {@code level}, or null. */
    static Character spawnTraining(SyntheticPlayer p, int level) {
        List<Character> bots = TalkQa.spawn(p.character(), 1, true, level);
        return bots.isEmpty() ? null : bots.get(0);
    }

    /** Stops the bot's own tick, so its brain does not move it while the probe drives it (teardown despawns it). */
    static void freeze(Character bot) {
        BotSM sm = CharacterStorage.getBotById(bot.getId());
        if (sm != null) {
            sm.stopScheduledTask();
        }
    }

    static TrainingBot trainingBot(Character bot) {
        return CharacterStorage.getBotById(bot.getId()) instanceof TrainingBot tb ? tb : null;
    }

    /** The bot's map {@code mapId} on its own channel. */
    static MapleMap mapOf(Character bot, int mapId) {
        return bot.getClient().getChannelServer().getMapFactory().getMap(mapId);
    }

    /** Puts the bot on {@code mapId} at {@code at} (null: the map's first spawn point); blocks a few seconds. */
    static void place(Character bot, int mapId, Point at) {
        MapleMap map = mapOf(bot, mapId);
        Point to = at != null ? new Point(at) : map.getPortal(0) != null ? new Point(map.getPortal(0).getPosition())
                : new Point(0, 0);
        BotGeneration.warpBotToLocation(bot, to, map);
    }

    /** Pins maps to full simulation (watched); returns the ones this call pinned, for {@link #unpin}. */
    static List<Integer> pin(List<String> ev, int... maps) {
        List<Integer> mine = new ArrayList<>();
        for (int m : maps) {
            if (GCMovement.lodPins().contains(m) || mine.contains(m)) {
                continue;
            }
            String why = GCMovement.lodPin(m);
            if (why == null) {
                mine.add(m);
            } else {
                ev.add("could not pin map " + m + ": " + why);
            }
        }
        return mine;
    }

    static void unpin(List<Integer> mine) {
        for (int m : mine) {
            GCMovement.lodUnpin(m);
        }
    }

    /** Spawns {@code mobId} at the bot's feet on its map and kills it with the bot's hit (the real kill path). */
    static boolean killFor(Character bot, int mobId) {
        MapleMap map = bot.getMap();
        Monster mob = LifeFactory.getMonster(mobId);
        if (map == null || mob == null) {
            return false;
        }
        map.spawnMonsterOnGroundBelow(mob, new Point(bot.getPosition()));
        if (map.getMonsterByOid(mob.getObjectId()) == null) {
            return false;
        }
        map.damageMonster(bot, mob, mob.getMaxHp());
        if (map.getMonsterByOid(mob.getObjectId()) != null) {
            map.killMonster(mob, bot, false);
            return false;
        }
        return true;
    }

    // ── t-bq-1: a watched bot takes, hunts and turns in one mob quest ────────────────────────────

    /** t-bq-1: the bot's level (most Victoria mob quests are open by then). */
    static final int BQ_LEVEL = 20;
    /** t-bq-1: the most kills a quest may need to be staged (the probe kills them for the bot). */
    static final int BQ_MAX_KILLS = 40;
    /** t-bq-1: Victoria grind maps searched for a runnable mob quest, nearest-town first. */
    static final int[] BQ_GRIND_MAPS = {104040000, 104040001, 100010000, 100020000, 101010100, 101020000, 102010000,
            102020000, 103010000, 104000100, 105040300, 106000002};

    /** t-bq-1: the first candidate a probe can stage (few kills, plain mob quest), or null. */
    static BotQuests.Candidate stageable(List<BotQuests.Candidate> ranked) {
        for (BotQuests.Candidate c : ranked) {
            int kills = c.quest().mobs().values().stream().mapToInt(Integer::intValue).sum();
            if (kills > 0 && kills <= BQ_MAX_KILLS && !c.quest().timed() && !c.quest().scripted()) {
                return c;
            }
        }
        return null;
    }

    /** t-bq-1: what is wrong with the visits and the real quest state, or empty. Pure, for the tests. */
    static List<String> questProblems(BotQuests.Visit take, QuestStatus.Status afterTake, boolean hunted,
                                      BotQuests.Visit turnIn, QuestStatus.Status afterTurnIn) {
        List<String> bad = new ArrayList<>();
        if (take != BotQuests.Visit.STARTED) {
            bad.add("take visit was " + take + ", not STARTED (watched: the real Quest.start must run)");
        }
        if (afterTake != QuestStatus.Status.STARTED && afterTake != QuestStatus.Status.COMPLETED) {
            bad.add("the real quest is " + afterTake + " after the take");
        }
        if (!hunted) {
            bad.add("the kills did not reach the log (syncReal from the real QuestStatus)");
        }
        if (turnIn != BotQuests.Visit.COMPLETED) {
            bad.add("turn-in visit was " + turnIn + ", not COMPLETED"
                    + (turnIn == BotQuests.Visit.DEFERRED ? " (the yield caps refused the rewards)" : "")
                    + (turnIn == BotQuests.Visit.RECORDED ? " (recorded only: not watched, not REAL, or canComplete false)" : ""));
        }
        if (afterTurnIn != QuestStatus.Status.COMPLETED) {
            bad.add("the real quest is " + afterTurnIn + " after the turn-in");
        }
        return bad;
    }

    /**
     * t-bq-1 ({@code slums.botquests.enabled}): a fresh level-20 training bot (its own tick stopped, so the probe drives
     * it) gets the best stageable mob quest on a Victoria grind map from the real ranking ({@code BotQuests.ranked}:
     * level window, real {@code Quest.canStart}). With the start, hunt and end maps pinned as watched, it stands by the
     * start NPC and {@code BotQuests.atNpc(TAKE_QUEST)} must really start the quest; the quest's mobs are spawned at its
     * feet on the hunt map and killed with its own hit (the normal kill credit); {@code syncReal} must see every kill;
     * by the end NPC {@code atNpc(TURN_IN_QUEST)} must complete it through {@code Quest.complete} and the yield caps.
     */
    static List<String> botQuest(SyntheticPlayer p) throws InterruptedException {
        if (!BotQuests.enabled()) {
            return List.of("SKIP bot quests are off (-Dslums.botquests.enabled=true, and -Dslums.botlife.enabled/"
                    + "execute=true for the life brain)");
        }
        List<String> bad = new ArrayList<>();
        List<String> ev = new ArrayList<>();
        Character bot = spawnTraining(p, BQ_LEVEL);
        if (bot == null) {
            return List.of("FAIL could not spawn a training bot");
        }
        List<Integer> pinned = List.of();
        try {
            freeze(bot);
            TrainingBot tb = trainingBot(bot);
            if (tb == null || tb.lifeRuntime() == null) {
                return List.of("FAIL the staged bot has no life runtime (TrainingBot.lifeRuntime() null)");
            }
            LifeMemory mem = tb.lifeRuntime().memory();
            Map<String, Long> m0 = BotQuests.metrics();
            BotQuests.Candidate pick = null;
            for (int map : BQ_GRIND_MAPS) {
                List<BotQuests.Candidate> r = mem.quests(log -> BotQuests.ranked(bot, log, map, 0,
                        Double.NEGATIVE_INFINITY, 20));
                pick = stageable(r);
                if (pick != null) {
                    break;
                }
            }
            if (pick == null) {
                return List.of("FAIL no stageable mob quest (<= " + BQ_MAX_KILLS + " kills) for a level " + BQ_LEVEL
                        + " bot on any of " + BQ_GRIND_MAPS.length + " Victoria grind maps");
            }
            BotQuests.Candidate c = pick;
            int qid = c.quest().id();
            ev.add("quest " + qid + " start npc " + c.quest().startNpc() + " on " + c.startMap() + ", hunt "
                    + c.quest().mobs() + " on " + c.huntMap() + ", end npc " + c.quest().endNpc() + " on " + c.endMap()
                    + String.format(java.util.Locale.ROOT, ", score %.2f", c.score()));
            pinned = pin(ev, c.startMap(), c.huntMap(), c.endMap());
            long now = System.currentTimeMillis();
            mem.quests(log -> {
                log.pick(new BotQuestLog.Active(qid, c.quest().startNpc(), c.quest().endNpc(), c.startMap(),
                        c.endMap(), c.huntMap(), c.quest().mobs(), now));
                return null;
            });
            // take
            place(bot, c.startMap(), null);
            Point npc = mem.quests(log -> BotQuests.npcSpot(bot, LifePlanner.Step.TAKE_QUEST, log));
            if (npc == null) {
                bad.add("start npc " + c.quest().startNpc() + " is not on map " + c.startMap());
                return verdict(bad, ev);
            }
            place(bot, c.startMap(), npc);
            boolean watchedStart = GCMovement.isMapObserved(c.startMap());
            BotQuests.Visit take = mem.quests(log -> BotQuests.atNpc(bot, log, LifePlanner.Step.TAKE_QUEST,
                    watchedStart, System.currentTimeMillis()));
            QuestStatus.Status afterTake = bot.getQuest(qid).getStatus();
            ev.add("take: watched=" + watchedStart + " visit=" + take + " real status " + afterTake);
            if (take != BotQuests.Visit.STARTED) {
                bad.addAll(questProblems(take, afterTake, false, BotQuests.Visit.NONE, afterTake).subList(0, 1));
                return verdict(bad, ev);
            }
            // hunt
            place(bot, c.huntMap(), null);
            int killed = 0, failed = 0;
            for (Map.Entry<Integer, Integer> e : c.quest().mobs().entrySet()) {
                for (int i = 0; i < e.getValue(); i++) {
                    if (killFor(bot, e.getKey())) {
                        killed++;
                    } else {
                        failed++;
                    }
                }
            }
            boolean hunted = mem.quests(log -> {
                BotQuests.syncReal(bot, log);
                return log.active() != null && log.active().hunted();
            });
            ev.add("hunt: " + killed + " kills by the bot (" + failed + " not landed), real progress "
                    + bot.getQuest(qid).getProgress() + ", log hunted=" + hunted);
            // turn in
            place(bot, c.endMap(), null);
            Point end = mem.quests(log -> BotQuests.npcSpot(bot, LifePlanner.Step.TURN_IN_QUEST, log));
            if (end != null) {
                place(bot, c.endMap(), end);
            }
            boolean watchedEnd = GCMovement.isMapObserved(c.endMap());
            BotQuests.Visit turnIn = mem.quests(log -> BotQuests.atNpc(bot, log, LifePlanner.Step.TURN_IN_QUEST,
                    watchedEnd, System.currentTimeMillis()));
            QuestStatus.Status afterTurnIn = bot.getQuest(qid).getStatus();
            ev.add("turn-in: watched=" + watchedEnd + " end npc there=" + (end != null) + " visit=" + turnIn
                    + " real status " + afterTurnIn);
            Map<String, Long> m1 = BotQuests.metrics();
            ev.add("metrics delta: realStarts " + delta(m0, m1, "realStarts") + ", realCompletes "
                    + delta(m0, m1, "realCompletes") + ", deferred " + delta(m0, m1, "deferred") + ", dropped "
                    + delta(m0, m1, "dropped"));
            bad.addAll(questProblems(take, afterTake, hunted, turnIn, afterTurnIn));
            return Probes160.verdict(bad, "a watched bot took quest " + qid + " through the real Quest.start, its own"
                    + " kills counted, and it turned the quest in through Quest.complete", ev);
        } finally {
            unpin(pinned);
            ev.add(TalkQa.teardown(List.of(bot)));
        }
    }

    // ── t-tm-1: the next Temple mainline quest and its lane ───────────────────────────────────────

    /** t-tm-1: the staged bot's level, above every bot's Temple ambition (90..125) and 3501's level. */
    static final int TM_LEVEL = 126;
    /** t-tm-1: a Road of Regrets lane, closed to a bot that has only done 3500 (the corridor gate). */
    static final int TM_CLOSED_LANE = 270020100;

    /**
     * t-tm-1: what is wrong with the two offers. {@code first}/{@code second} are the quest ids picked (0 = none);
     * {@code lane} the lane registered for the second, {@code wantLane} the step's lane; {@code laneOpen} whether the
     * corridor gate lets the bot onto it, {@code laterOpen} whether it lets it onto a later stretch. Pure, for tests.
     */
    static List<String> templeProblems(int first, String took, int second, int lane, int wantLane, boolean laneOpen,
                                       boolean laterOpen) {
        List<String> bad = new ArrayList<>();
        int q0 = TempleRoute.MAINLINE.get(0).questId(), q1 = TempleRoute.MAINLINE.get(1).questId();
        if (first != q0) {
            bad.add("a fresh bot was offered " + (first == 0 ? "nothing" : "quest " + first) + ", not " + q0);
        }
        if (took != null) {
            bad.add("the first quest did not go through: " + took);
        }
        if (second != q1) {
            bad.add("after " + q0 + " the bot was offered " + (second == 0 ? "nothing" : "quest " + second)
                    + ", not " + q1);
        } else if (lane != wantLane) {
            bad.add("no lane entry for " + q1 + ": registered map " + lane + ", want " + wantLane);
        }
        if (!laneOpen) {
            bad.add("the corridor gate keeps the bot off its own lane " + wantLane);
        }
        if (laterOpen) {
            bad.add("the corridor gate lets the bot onto " + TM_CLOSED_LANE + " before its quest");
        }
        return bad;
    }

    /**
     * t-tm-1 ({@code slums.temple.enabled}): a fresh level-126 training bot (tick stopped) is asked by
     * {@code TempleOfTime.consider}: it must be offered the first mainline quest (3500, a talk). With the hub pinned as
     * watched it takes and turns it in by the Temple Keeper through {@code BotQuests.atNpc} (the real quest). Asked
     * again it must be offered 3501 with its lane entry: Memory Lane 1 registered for it in {@code TempleLanes}, the
     * corridor gate open there and still shut on the Road of Regrets.
     */
    static List<String> templeQuest(SyntheticPlayer p) throws InterruptedException {
        if (!TempleOfTime.enabled()) {
            return List.of("SKIP the Temple is off (-Dslums.temple.enabled=true, which needs -Dslums.botquests.enabled)");
        }
        List<String> ev = new ArrayList<>();
        Character bot = spawnTraining(p, TM_LEVEL);
        if (bot == null) {
            return List.of("FAIL could not spawn a training bot");
        }
        List<Integer> pinned = List.of();
        LifeMemory mem = null;
        try {
            freeze(bot);
            TrainingBot tb = trainingBot(bot);
            if (tb == null || tb.lifeRuntime() == null) {
                return List.of("FAIL the staged bot has no life runtime (TrainingBot.lifeRuntime() null)");
            }
            mem = tb.lifeRuntime().memory();
            ev.add("bot " + bot.getName() + " lv " + bot.getLevel() + " ambition " + TempleOfTime.ambition(bot.getId())
                    + " channel " + bot.getClient().getChannel());
            pinned = pin(ev, TempleRoute.HUB);
            place(bot, TempleRoute.HUB, null);
            Boolean got1 = mem.quests(log -> TempleOfTime.consider(bot, log, System.currentTimeMillis()));
            int first = mem.quests(log -> log.active() == null ? 0 : log.active().questId);
            ev.add("offer 1: consider=" + got1 + " active " + first);
            String took = null;
            if (first == TempleRoute.MAINLINE.get(0).questId()) {
                Point keeper = mem.quests(log -> BotQuests.npcSpot(bot, LifePlanner.Step.TAKE_QUEST, log));
                if (keeper != null) {
                    place(bot, TempleRoute.HUB, keeper);
                }
                boolean watched = GCMovement.isMapObserved(TempleRoute.HUB);
                BotQuests.Visit take = mem.quests(log -> BotQuests.atNpc(bot, log, LifePlanner.Step.TAKE_QUEST,
                        watched, System.currentTimeMillis()));
                BotQuests.Visit in = mem.quests(log -> BotQuests.atNpc(bot, log, LifePlanner.Step.TURN_IN_QUEST,
                        watched, System.currentTimeMillis()));
                int done = mem.quests(BotQuestLog::templeDone);
                ev.add("3500 by the Temple Keeper (there=" + (keeper != null) + ", watched=" + watched + "): take "
                        + take + ", turn-in " + in + ", real status " + bot.getQuest(first).getStatus()
                        + ", templeDone " + done);
                if (done < 1) {
                    took = "take " + take + ", turn-in " + in;
                }
            }
            Boolean got2 = mem.quests(log -> TempleOfTime.consider(bot, log, System.currentTimeMillis()));
            int second = mem.quests(log -> log.active() == null ? 0 : log.active().questId);
            int wantLane = TempleRoute.MAINLINE.get(1).lanes().get(0);
            int lane = TempleLanes.registeredMap(bot.getId());
            int hunt = mem.quests(log -> log.active() == null ? -1 : log.active().huntMap);
            ev.add("offer 2: consider=" + got2 + " active " + second + " hunt map " + hunt + ", lane registered " + lane
                    + ", wanted channel " + TempleOfTime.wantedChannel(bot));
            boolean laneOpen = TempleOfTime.mayEnter(wantLane, bot);
            boolean laterOpen = TempleOfTime.mayEnter(TM_CLOSED_LANE, bot);
            ev.add("corridor gate: " + wantLane + " open=" + laneOpen + ", " + TM_CLOSED_LANE + " open=" + laterOpen);
            return Probes160.verdict(templeProblems(first, took, second, lane, wantLane, laneOpen, laterOpen),
                    "a fresh bot was offered 3500, took and turned it in by the Temple Keeper, then was offered 3501"
                            + " with its Memory Lane entry; the later corridor stays shut", ev);
        } finally {
            unpin(pinned);
            TempleLanes.unregister(bot.getId());
            if (mem != null) {
                mem.quests(log -> {
                    log.drop();
                    return null;
                });
            }
            ev.add(TalkQa.teardown(List.of(bot)));
        }
    }

    // ── gear staging (t-gb-*) ────────────────────────────────────────────────────────────────────

    /** Takes every worn and bagged equip off a throwaway bot, so only staged gear is there. */
    static void clearGear(Character bot) {
        for (InventoryType t : new InventoryType[]{InventoryType.EQUIPPED, InventoryType.EQUIP}) {
            List<Short> slots = new ArrayList<>();
            for (Item it : bot.getInventory(t).list()) {
                slots.add(it.getPosition());
            }
            for (short s : slots) {
                bot.getInventory(t).removeSlot(s);
            }
        }
    }

    /** A real equip with its stats replaced by the staged ones (as a scrolled piece would carry). */
    static Equip stagedEquip(int itemId, int watk, int str) {
        Item it = ItemInformationProvider.getInstance().getEquipById(itemId);
        if (!(it instanceof Equip e)) {
            return null;
        }
        e.setStr((short) str);
        e.setDex((short) 0);
        e.setInt((short) 0);
        e.setLuk((short) 0);
        e.setWatk((short) watk);
        e.setMatk((short) 0);
        return e;
    }

    static void wear(Character bot, Equip e, short slot) {
        e.setPosition(slot);
        bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(e);
    }

    static int wornId(Character bot, short slot) {
        Item it = bot.getInventory(InventoryType.EQUIPPED).getItem(slot);
        return it == null ? 0 : it.getItemId();
    }

    /** Sets the four base stats exactly (4 is the floor; nothing left unspent). */
    static void setStats(Character bot, int str, int dex, int int_, int luk) {
        bot.updateStrDexIntLuk(4);
        int ap = (str - 4) + (dex - 4) + (int_ - 4) + (luk - 4);
        bot.changeRemainingAp(ap, true);
        bot.assignStrDexIntLuk(str - 4, dex - 4, int_ - 4, luk - 4);
    }

    /** The first real item in [from, to) with {@code test} on its equip stats (reqLevel, reqSTR, ...), or 0. */
    static int findEquip(int from, int to, java.util.function.Predicate<Map<String, Integer>> test) {
        return findEquip(from, to, (id, st) -> test.test(st));
    }

    /** As above, with the item id handed to the test too. */
    static int findEquip(int from, int to, java.util.function.BiPredicate<Integer, Map<String, Integer>> test) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (int id = from; id < to; id++) {
            if (ii.getName(id) == null || ii.isCash(id)) {
                continue;
            }
            Map<String, Integer> st = ii.getEquipStats(id);
            if (st != null && !st.isEmpty() && test.test(id, st)) {
                return id;
            }
        }
        return 0;
    }

    static int req(Map<String, Integer> st, String k) {
        return st.getOrDefault(k, 0);
    }

    // ── t-gb-1: the Pareto optimizer wears a requirement chain ──────────────────────────────────

    /** t-gb-1: the Fighter's level; the chain's sword needs this much more STR than the bot has (two +5 helpers). */
    static final int GB1_LEVEL = 50, GB1_SHORT = 8, GB1_HELPER_STR = 5;

    /** t-gb-1: what is wrong with the end state (worn ids against the staged chain). Pure, for the tests. */
    static List<String> chainProblems(int sword, int gloves, int shoes, int wantSword, int wantGloves, int wantShoes,
                                      boolean moved) {
        List<String> bad = new ArrayList<>();
        if (!moved) {
            bad.add("the review moved nothing (the climb's answer: one helper is not enough for the sword)");
        }
        if (sword != wantSword) {
            bad.add("weapon is " + sword + ", not the stronger " + wantSword);
        }
        if (gloves != wantGloves || shoes != wantShoes) {
            bad.add("helpers worn " + gloves + "/" + shoes + ", want " + wantGloves + "/" + wantShoes
                    + " (both are needed for the sword's STR)");
        }
        return bad;
    }

    /**
     * t-gb-1 ({@code slums.gear.optimizer=pareto}): a fresh bot made a level-50 Fighter on the player's map (an active
     * map, so the full optimizer runs), all its gear taken off. It wears a real sword (WATK 20), gloves and shoes; its
     * bag holds a real sword needing {@link #GB1_SHORT} more STR than it has (WATK 40) and real gloves and shoes with
     * +5 STR each: only both helpers together let it hold the better sword (the climb leaves this chain alone, see
     * BotGearSwitchTest). {@code BotGear.review} must end with all three bag pieces worn.
     */
    static List<String> paretoGear(SyntheticPlayer p) throws InterruptedException {
        if (!"pareto".equals(BotGear.optimizer())) {
            return List.of("SKIP the gear optimizer is " + BotGear.optimizer() + " (-Dslums.gear.optimizer=pareto)");
        }
        List<String> ev = new ArrayList<>();
        int strongSword = findEquip(1302000, 1303000, st -> req(st, "reqLevel") <= GB1_LEVEL
                && req(st, "reqSTR") >= 4 + GB1_SHORT + 10 && req(st, "reqDEX") <= 25 && req(st, "reqJob") <= 1);
        if (strongSword == 0) {
            return List.of("FAIL no real one-handed sword at level <= " + GB1_LEVEL + " with a STR requirement to chain");
        }
        int needStr = req(ItemInformationProvider.getInstance().getEquipStats(strongSword), "reqSTR");
        int baseStr = needStr - GB1_SHORT;
        int weakSword = findEquip(1302000, 1303000, st -> req(st, "reqLevel") <= GB1_LEVEL
                && req(st, "reqSTR") <= baseStr && req(st, "reqDEX") <= 25 && req(st, "reqJob") <= 1);
        int[] gloves = {findEquip(1082000, 1083000, st -> plain(st)), 0};
        gloves[1] = findEquip(gloves[0] + 1, 1083000, st -> plain(st));
        int[] shoes = {findEquip(1072000, 1073000, st -> plain(st)), 0};
        shoes[1] = findEquip(shoes[0] + 1, 1073000, st -> plain(st));
        if (weakSword == 0 || gloves[1] == 0 || shoes[1] == 0) {
            return List.of("FAIL could not find real staging items: sword " + weakSword + ", gloves "
                    + java.util.Arrays.toString(gloves) + ", shoes " + java.util.Arrays.toString(shoes));
        }
        Character bot = spawnTraining(p, GB1_LEVEL);
        if (bot == null) {
            return List.of("FAIL could not spawn a training bot");
        }
        try {
            freeze(bot);
            bot.setJob(Job.FIGHTER);
            setStats(bot, baseStr, 25, 4, 4);
            clearGear(bot);
            wear(bot, stagedEquip(weakSword, 20, 0), (short) -11);
            wear(bot, stagedEquip(gloves[0], 2, 0), (short) -8);
            wear(bot, stagedEquip(shoes[0], 1, 0), (short) -7);
            Equip bagSword = stagedEquip(strongSword, 40, 0);
            bot.getInventory(InventoryType.EQUIP).addItem(bagSword);
            bot.getInventory(InventoryType.EQUIP).addItem(stagedEquip(gloves[1], 0, GB1_HELPER_STR));
            bot.getInventory(InventoryType.EQUIP).addItem(stagedEquip(shoes[1], 0, GB1_HELPER_STR));
            ev.add("Fighter lv " + bot.getLevel() + " STR " + bot.getStr() + "; worn sword " + weakSword + " (WATK 20),"
                    + " gloves " + gloves[0] + ", shoes " + shoes[0] + "; bag sword " + strongSword + " (WATK 40, needs "
                    + needStr + " STR), gloves " + gloves[1] + " +5 STR, shoes " + shoes[1] + " +5 STR");
            ev.add("map " + bot.getMapId() + " active=" + LodCounts.isMapActive(bot.getMapId()) + " (tracker running="
                    + LodCounts.trackerRunning() + ")");
            Map<Short, Equip> would = GearScoring.live().bestSet(bot, List.of());
            StringBuilder w = new StringBuilder("scoring's best set (changes): ");
            would.forEach((slot, e) -> w.append(slot).append('=').append(e.getItemId()).append(' '));
            ev.add(w.toString().trim());
            boolean moved = BotGear.review(bot);
            int s = wornId(bot, (short) -11), g = wornId(bot, (short) -8), sh = wornId(bot, (short) -7);
            ev.add("after review: moved=" + moved + " worn sword " + s + ", gloves " + g + ", shoes " + sh
                    + ", STR " + bot.getStr() + " total " + bot.getTotalStr());
            return Probes160.verdict(chainProblems(s, g, sh, strongSword, gloves[1], shoes[1], moved),
                    "the Pareto optimizer wore the whole chain: both +5 STR helpers and the sword they unlock", ev);
        } finally {
            ev.add(TalkQa.teardown(List.of(bot)));
        }
    }

    /** An equip any level-50 warrior can wear with no stat requirement. */
    private static boolean plain(Map<String, Integer> st) {
        return req(st, "reqLevel") <= GB1_LEVEL && req(st, "reqSTR") == 0 && req(st, "reqDEX") == 0
                && req(st, "reqINT") == 0 && req(st, "reqLUK") == 0 && (req(st, "reqJob") == 0 || req(st, "reqJob") == 1);
    }

    // ── t-gb-2: gear.dexless stops new AP at the secondary cap ──────────────────────────────────

    /** t-gb-2: the bot's level, the AP handed to it, and how many fresh bots it may try (magicians have no cap). */
    static final int GB2_LEVEL = 50, GB2_AP = 25, GB2_TRIES = 4;

    static int statOf(Character c, BotBuild.Stat s) {
        return switch (s) {
            case STR -> c.getStr();
            case DEX -> c.getDex();
            case INT -> c.getInt();
            case LUK -> c.getLuk();
        };
    }

    /**
     * t-gb-2: the secondary stat a spend of {@code ap} should end at, from {@code before} with the class rule's
     * {@code target}, the class {@code floor} and the gear {@code cap} (-1 none): AP never lowers a stat.
     */
    static int expectedSecondary(int before, int target, int floor, int cap, int ap) {
        int stop = cap < 0 ? target : Math.min(target, Math.max(floor, cap));
        return Math.max(before, Math.min(stop, before + ap));
    }

    /** t-gb-2: what is wrong with the spend. Pure, for the tests. */
    static List<String> dexlessProblems(int cap, int target, int floor, int secBefore, int secAfter, int primGain,
                                        int ap) {
        List<String> bad = new ArrayList<>();
        if (cap < 0) {
            bad.add("no gear cap was computed (dexlessSecondaryTarget -1: no weapon it holds qualifies)");
            return bad;
        }
        if (Math.max(floor, cap) >= target) {
            bad.add("the cap " + cap + " does not bind (class rule " + target + "): nothing to prove");
            return bad;
        }
        int want = expectedSecondary(secBefore, target, floor, cap, ap);
        if (secAfter != want) {
            bad.add("secondary went " + secBefore + " -> " + secAfter + ", want " + want + " (cap " + cap
                    + ", class rule " + target + ")");
        }
        int toSecondary = secAfter - secBefore;
        if (primGain != ap - toSecondary) {
            bad.add("primary gained " + primGain + ", want " + (ap - toSecondary) + " (the rest of the " + ap + " AP)");
        }
        return bad;
    }

    /**
     * t-gb-2 ({@code slums.gear.dexless}): a fresh level-50 bot (not a magician: their secondary has no cap) keeps its
     * job and weapon; its secondary stat is moved down to the class floor (the points into its primary), it is handed
     * {@link #GB2_AP} AP and settled the way a level-up is ({@code BotProgression.afterLevelUp}). The gear cap
     * ({@code BotGear.dexlessSecondaryTarget}) must sit under the class rule, the secondary must stop at it and the
     * rest go to the primary.
     */
    static List<String> dexlessAp(SyntheticPlayer p) throws InterruptedException {
        if (!Boolean.getBoolean("slums.gear.dexless")) {
            return List.of("SKIP dexless builds are off (-Dslums.gear.dexless=true)");
        }
        List<String> ev = new ArrayList<>();
        List<Character> spawned = new ArrayList<>();
        try {
            for (int i = 0; i < GB2_TRIES; i++) {
                Character bot = spawnTraining(p, GB2_LEVEL);
                if (bot == null) {
                    continue;
                }
                spawned.add(bot);
                freeze(bot);
                int job = bot.getJob().getId();
                BotBuild.Choice choice = new BotBuild.Choice(job, bot.getId());
                int floor = BotBuild.secondaryFloor(job, choice);
                if (!BotBuild.isBuildJob(job) || floor == Integer.MAX_VALUE || !BotProgression.isReal(bot.getId())) {
                    ev.add("bot " + bot.getName() + " job " + job + " skipped (magician, not a build job or not real)");
                    continue;
                }
                BotBuild.Stat prim = BotBuild.primaryStat(job, choice), sec = BotBuild.secondaryStat(job, choice);
                int[] st = {bot.getStr(), bot.getDex(), bot.getInt(), bot.getLuk()};
                int moved = st[sec.ordinal()] - floor;
                st[prim.ordinal()] += Math.max(0, moved);
                st[sec.ordinal()] = Math.min(st[sec.ordinal()], floor);
                setStats(bot, st[0], st[1], st[2], st[3]);
                bot.changeRemainingAp(GB2_AP, true);
                int target = BotBuild.secondaryTarget(job, bot.getLevel(), choice);
                int cap = BotGear.dexlessSecondaryTarget(bot, prim, sec, floor, GB2_AP);
                int secBefore = statOf(bot, sec), primBefore = statOf(bot, prim);
                ev.add("bot " + bot.getName() + " job " + job + " lv " + bot.getLevel() + " build "
                        + BotProgression.apBuildName(bot) + ": " + prim + " " + primBefore + ", " + sec + " " + secBefore
                        + " (floor " + floor + "), class rule " + target + ", gear cap " + cap + ", AP " + GB2_AP
                        + ", weapon " + wornId(bot, (short) -11));
                BotProgression.afterLevelUp(bot, bot.getLevel());
                int secAfter = statOf(bot, sec), primAfter = statOf(bot, prim);
                ev.add("after the settle: " + prim + " " + primAfter + ", " + sec + " " + secAfter + ", AP left "
                        + bot.getRemainingAp());
                return Probes160.verdict(dexlessProblems(cap, target, floor, secBefore, secAfter,
                        primAfter - primBefore, GB2_AP), "new AP stopped the " + sec + " at the gear cap " + cap
                        + " (the class rule wanted " + target + ") and the rest went to " + prim, ev);
            }
            ev.add(0, "FAIL none of " + GB2_TRIES + " fresh level-" + GB2_LEVEL + " bots could be staged");
            return ev;
        } finally {
            ev.add(TalkQa.teardown(spawned));
        }
    }

    // ── t-gb-3: grind.gearFirst sends a bot that wants an item where it drops ──────────────────

    /** t-gb-3: the bot's level (under 30: no "chill" trips), the chooser's band and the map search reach. */
    static final int GB3_LEVEL = 22, GB3_BELOW = 25, GB3_ABOVE = 12, GB3_HOPS = 6, GB3_TRIES = 3;

    /** t-gb-3: the equip that drops on the fewest of these maps (at least one), or 0. Pure, for the tests. */
    static int rarestDrop(Map<Integer, java.util.Set<Integer>> dropsByMap) {
        Map<Integer, Integer> maps = new java.util.TreeMap<>();
        dropsByMap.values().forEach(items -> items.forEach(i -> maps.merge(i, 1, Integer::sum)));
        int best = 0, fewest = Integer.MAX_VALUE;
        for (Map.Entry<Integer, Integer> e : maps.entrySet()) {
            if (e.getValue() < fewest) {
                fewest = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /** t-gb-3: what is wrong with the pick. Pure, for the tests. */
    static List<String> farmProblems(TrainingMap chosen, boolean saidFarm, java.util.Set<Integer> dropMaps, int minMob,
                                     int maxMob) {
        List<String> bad = new ArrayList<>();
        if (chosen == null) {
            bad.add("the chooser picked no map");
            return bad;
        }
        if (!saidFarm) {
            bad.add("the chooser did not take the farm path (no 'farming its wanted drop' decision)");
        }
        if (!dropMaps.contains(chosen.mapId())) {
            bad.add("map " + chosen.mapId() + " does not drop the wanted item (it drops on " + dropMaps + ")");
        }
        if (chosen.mobLevel() < minMob || chosen.mobLevel() > maxMob) {
            bad.add("map " + chosen.mapId() + " mob level " + chosen.mobLevel() + " is out of the band " + minMob + ".."
                    + maxMob);
        }
        return bad;
    }

    /**
     * t-gb-3 ({@code slums.grind.gearFirst}): a fresh level-22 training bot (tick stopped). Among the in-band maps the
     * finder offers from its spot, the equip that drops on the fewest maps is picked, and the bot is given a FARM_ITEM
     * goal for it (the {@code !qa bl-11} seam). {@code TrainingMapChooser.choose} must take the farm path to an
     * in-band map that drops it. The reservation is released and the want forgotten after.
     */
    static List<String> gearFirstFarm(SyntheticPlayer p) throws InterruptedException {
        if (!GearFirstAdvisor.enabled()) {
            return List.of("SKIP gear-first grinding is off (-Dslums.grind.gearFirst=true)");
        }
        List<String> ev = new ArrayList<>();
        Character bot = spawnTraining(p, GB3_LEVEL);
        if (bot == null) {
            return List.of("FAIL could not spawn a training bot");
        }
        try {
            freeze(bot);
            int level = bot.getLevel();
            int minMob = Math.max(1, level - GB3_BELOW), maxMob = level + GB3_ABOVE;
            List<TrainingMap> band = TrainingMapFinder.findTrainingMaps(bot.getMapId(), level, minMob, maxMob, GB3_HOPS,
                    new java.util.HashSet<>());
            Map<Integer, java.util.Set<Integer>> drops = new java.util.LinkedHashMap<>();
            for (TrainingMap m : band) {
                if (m.mobLevel() < minMob || m.mobLevel() > maxMob) {
                    continue;
                }
                java.util.Set<Integer> items = new java.util.TreeSet<>();
                for (int mob : new java.util.LinkedHashSet<>(MapMobIndex.mobIds(m.mapId()))) {
                    var list = server.life.MonsterInformationProvider.getInstance().retrieveEffectiveDrop(mob);
                    if (list != null) {
                        for (var d : list) {
                            if (d.itemId / 1000000 == 1 && d.questid <= 0 && d.chance > 0) {
                                items.add(d.itemId);
                            }
                        }
                    }
                }
                drops.put(m.mapId(), items);
            }
            int item = rarestDrop(drops);
            if (item == 0) {
                return List.of("FAIL no in-band map (" + minMob + ".." + maxMob + ", " + drops.size() + " maps from "
                        + bot.getMapId() + ") drops an equip");
            }
            java.util.Set<Integer> dropMaps = new java.util.TreeSet<>();
            drops.forEach((map, items) -> {
                if (items.contains(item)) {
                    dropMaps.add(map);
                }
            });
            ev.add("bot " + bot.getName() + " lv " + level + " on " + bot.getMapId() + "; " + drops.size()
                    + " in-band maps; wants " + item + " (" + ItemInformationProvider.getInstance().getName(item)
                    + "), which drops on " + dropMaps);
            LifeMemoryStore.of(bot.getId()).stageGoal(new LifeGoals.Goal(LifeGoals.Kind.FARM_ITEM, level,
                    "farm " + item, 0.8, item));
            TrainingMap chosen = null;
            boolean saidFarm = false;
            for (int i = 0; i < GB3_TRIES && !saidFarm; i++) {
                List<String> debug = new ArrayList<>();
                TrainingMap m = TrainingMapChooser.choose(bot, bot.getMapId(), new java.util.HashSet<>(), debug::add);
                if (m != null) {
                    TrainingMapChooser.release(m.mapId());
                }
                chosen = m;
                saidFarm = debug.stream().anyMatch(l -> l.contains("farming its wanted drop"));
                ev.add("choose " + (i + 1) + ": " + (m == null ? "none" : "map " + m.mapId() + " mob lv " + m.mobLevel())
                        + "; " + (debug.isEmpty() ? "no decision lines" : String.join(" | ", debug.subList(
                        Math.max(0, debug.size() - 2), debug.size()))));
            }
            return Probes160.verdict(farmProblems(chosen, saidFarm, dropMaps, minMob, maxMob),
                    "a bot wanting " + item + " picked map " + (chosen == null ? 0 : chosen.mapId())
                            + ", in its band, which drops it", ev);
        } finally {
            GearFirstAdvisor.forget(bot.getId());
            ev.add(TalkQa.teardown(List.of(bot)));
        }
    }

    // ── t-gb-4: junk.sort keeps the reserve and shelf out of the town sale ──────────────────────

    /** t-gb-4: the equip tab's size for the stage, the scrolled (shelf) pieces and the plain junk in it. */
    static final int GB4_SLOTS = 24, GB4_SHELF = 14, GB4_JUNK = 2;

    /**
     * t-gb-4: what is wrong with the sale: {@code keptSold} kept pieces gone, {@code shelfNotKept} staged scrolled
     * pieces the sort did not keep, {@code junkLeft} plain junk still held. Pure, for the tests.
     */
    static List<String> junkProblems(int kept, int keptSold, int shelfNotKept, int junkLeft, int sold) {
        List<String> bad = new ArrayList<>();
        if (kept == 0) {
            bad.add("JunkSort.keeps kept nothing (no item data, or the switch did not reach it)");
        }
        if (shelfNotKept > 0) {
            bad.add(shelfNotKept + " scrolled piece(s) were not kept as valuables");
        }
        if (keptSold > 0) {
            bad.add("the sale sold " + keptSold + " kept piece(s)");
        }
        if (junkLeft > 0) {
            bad.add(junkLeft + " plain junk piece(s) were not sold");
        }
        if (sold == 0) {
            bad.add("the sale sold nothing");
        }
        return bad;
    }

    /**
     * t-gb-4 ({@code slums.junk.sort}): a fresh bot's equip bag is emptied and cut to {@link #GB4_SLOTS} slots, then
     * holds {@link #GB4_SHELF} scrolled off-class pieces (valuables: the shelf) and {@link #GB4_JUNK} plain off-class
     * pieces with no stats or slots (junk): more than half full, and the sale wants to free more than the junk, so a
     * sale that ignored the sort would sell shelf pieces. {@code BotBagRoom.sellJunk} (the town stop's call) must sell
     * the junk and keep every piece {@code JunkSort.keeps} names, including every scrolled one.
     */
    static List<String> junkSortSale(SyntheticPlayer p) throws InterruptedException {
        if (!JunkSort.enabled()) {
            return List.of("SKIP junk sorting is off (-Dslums.junk.sort=true)");
        }
        if (!BotBagRoom.saleEnabled()) {
            return List.of("SKIP the bots' town junk sale is off (-Dsolomapling.botJunkSale=false)");
        }
        List<String> ev = new ArrayList<>();
        Character bot = spawnTraining(p, 30);
        if (bot == null) {
            return List.of("FAIL could not spawn a training bot");
        }
        try {
            freeze(bot);
            int niche = bot.getJob().getJobNiche();
            int bit = niche >= 1 && niche <= 5 ? 1 << (niche - 1) : 1;
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            int offClass = findEquip(1040000, 1100000, (id, st) -> req(st, "reqJob") != 0
                    && (req(st, "reqJob") & bit) == 0 && req(st, "reqLevel") <= 30 && ii.getPrice(id, 1) > 0);
            if (offClass == 0 || ii.getPrice(offClass, 1) <= 0) {
                return List.of("FAIL no off-class equip with a price to stage (job niche " + niche + ", item "
                        + offClass + ")");
            }
            client.inventory.Inventory bag = bot.getInventory(InventoryType.EQUIP);
            List<Short> slots = new ArrayList<>();
            for (Item it : bag.list()) {
                slots.add(it.getPosition());
            }
            for (short sl : slots) {
                bag.removeSlot(sl);
            }
            bag.setSlotLimit(GB4_SLOTS);
            List<Item> shelf = new ArrayList<>(), junk = new ArrayList<>();
            for (int i = 0; i < GB4_SHELF + GB4_JUNK; i++) {
                Equip e = stagedEquip(offClass, 0, 0);
                e.setUpgradeSlots((byte) 0);
                if (i < GB4_SHELF) {
                    e.setLevel((byte) 1);
                    shelf.add(e);
                } else {
                    junk.add(e);
                }
                bag.addItem(e);
            }
            java.util.Set<Item> keeps = JunkSort.keeps(bot);
            int shelfNotKept = (int) shelf.stream().filter(e -> !keeps.contains(e)).count();
            ev.add("bot " + bot.getName() + " lv " + bot.getLevel() + " job " + bot.getJob().getId() + "; bag "
                    + bag.list().size() + "/" + GB4_SLOTS + ": " + shelf.size() + " scrolled + " + junk.size()
                    + " plain " + offClass + " (" + ii.getName(offClass) + ", " + ii.getPrice(offClass, 1)
                    + " mesos); the sort keeps " + keeps.size());
            BotBagRoom.Sale sale = BotBagRoom.sellJunk(bot);
            java.util.Set<Item> held = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            held.addAll(bot.getInventory(InventoryType.EQUIP).list());
            int keptSold = (int) keeps.stream().filter(k -> !held.contains(k)).count();
            int junkLeft = (int) junk.stream().filter(held::contains).count();
            ev.add("sale: " + sale.items() + " item(s) for " + sale.mesosOffered() + " mesos (" + sale.mesosCredited()
                    + " credited); equip bag now " + held.size() + ", kept pieces sold " + keptSold + ", junk left "
                    + junkLeft);
            return Probes160.verdict(junkProblems(keeps.size(), keptSold, shelfNotKept, junkLeft, sale.items()),
                    "the town sale sold the junk and kept all " + keeps.size() + " sorted pieces, though it wanted"
                            + " to free more", ev);
        } finally {
            ev.add(TalkQa.teardown(List.of(bot)));
        }
    }

    // ── t-gb-5: scroll.ev declines an EV-negative scroll; a gambler still gambles ───────────────

    /** t-gb-5: the worn weapon (a one-handed sword, 7 slots) and the scroll ids searched for its ATT scrolls. */
    static final int GB5_SWORD = 1302000, GB5_SCROLL_FROM = 2043000, GB5_SCROLL_TO = 2043020;

    /** The scroll id a plan picked, or 0 for none. */
    static int pickOf(BotScrolling.Plan plan) {
        return plan == null ? 0 : plan.choice().scrollId();
    }

    /**
     * t-gb-5: what is wrong. Part A holds only the plain 100% scroll ({@code plain}): the EV planner must decline it
     * for a careful bot ({@code carefulA} 0); part B only the dark scroll ({@code dark}, 0 when not judged): a careful
     * bot never takes it ({@code carefulB} 0), a gambler does ({@code gamblerB} = dark). Pure, for the tests.
     */
    static List<String> scrollEvProblems(int plain, int carefulA, int dark, int carefulB, int gamblerB) {
        List<String> bad = new ArrayList<>();
        if (carefulA != 0) {
            bad.add("a careful bot under the EV planner took the plain " + plain + " (100% scrolls are break-even at"
                    + " best under reproduction pricing, below the 20% margin)");
        }
        if (dark != 0) {
            if (carefulB != 0) {
                bad.add("a careful bot took the dark scroll " + carefulB);
            }
            if (gamblerB != dark) {
                bad.add("a gambler declined the dark scroll " + dark + " on its worn sword (the EV planner's pick is"
                        + " appetite-blind past the dark-scroll filter: it gambles only when the scroll is EV-positive)");
            }
        }
        return bad;
    }

    /**
     * t-gb-5 ({@code slums.scroll.ev}): a fresh level-50 bot (tick stopped), gear and scrolls taken away, wears a
     * one-handed sword with 7 slots. Part A: it holds only a plain 100% ATT scroll for it; the appetite rule (the
     * switch off) would use it for a careful bot, the EV planner must not. Part B (only while long shots are allowed
     * today): it holds only a dark ATT scroll; a careful bot must decline it, a gambler must take it. Read-only
     * ({@code ScrollEvQa}): nothing is rolled.
     */
    static List<String> scrollEv(SyntheticPlayer p) throws InterruptedException {
        if (!BotScrolling.evEnabled()) {
            return List.of("SKIP EV scrolling is off (-Dslums.scroll.ev=true)");
        }
        List<String> ev = new ArrayList<>();
        Character bot = spawnTraining(p, 50);
        if (bot == null) {
            return List.of("FAIL could not spawn a training bot");
        }
        try {
            freeze(bot);
            clearGear(bot);
            Equip sword = stagedEquip(GB5_SWORD, 17, 0);
            sword.setUpgradeSlots((byte) 7);
            wear(bot, sword, (short) -11);
            clearScrolls(bot);
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            for (int id = GB5_SCROLL_FROM; id < GB5_SCROLL_TO; id++) {
                if (ii.getName(id) != null) {
                    bot.getInventory(InventoryType.USE).addItem(new Item(id, (short) 0, (short) 1));
                }
            }
            List<ScrollRules.ScrollFacts> fit = ScrollEvQa.fitting(bot, sword);
            clearScrolls(bot);
            int plain = 0, dark = 0;
            for (ScrollRules.ScrollFacts f : fit) {
                if (!f.dark() && f.success() == 100 && plain == 0) {
                    plain = f.scrollId();
                } else if (f.dark() && dark == 0) {
                    dark = f.scrollId();
                }
            }
            ev.add("bot " + bot.getName() + " (own appetite " + ScrollRules.Appetite.of(bot.getId()) + ") wears "
                    + GB5_SWORD + " with 7 slots; fitting scrolls " + fit.stream().map(f -> f.scrollId() + "(" + f.success()
                    + "%" + (f.dark() ? " dark" : "") + ")").toList() + "; plain 100% " + plain + ", dark " + dark);
            if (plain == 0) {
                return List.of("FAIL no plain 100% ATT scroll fits " + GB5_SWORD + " in " + GB5_SCROLL_FROM + ".."
                        + GB5_SCROLL_TO, ev.get(0));
            }
            bot.getInventory(InventoryType.USE).addItem(new Item(plain, (short) 0, (short) 1));
            ScrollRules.Choice ruleA = ScrollEvQa.rule(bot, sword, ScrollRules.Appetite.CAREFUL);
            int carefulA = pickOf(ScrollEvQa.ev(bot, ScrollRules.Appetite.CAREFUL));
            int gamblerA = pickOf(ScrollEvQa.ev(bot, ScrollRules.Appetite.GAMBLER));
            ev.add("A, plain " + plain + " held: the appetite rule (switch off) for a careful bot picks "
                    + (ruleA == null ? "nothing" : ruleA.scrollId()) + "; EV careful " + carefulA + ", EV gambler "
                    + gamblerA);
            clearScrolls(bot);
            boolean longShots = ScrollEvQa.longShotsAllowed();
            int carefulB = 0, gamblerB = 0, judgedDark = longShots ? dark : 0;
            if (judgedDark != 0) {
                bot.getInventory(InventoryType.USE).addItem(new Item(dark, (short) 0, (short) 1));
                carefulB = pickOf(ScrollEvQa.ev(bot, ScrollRules.Appetite.CAREFUL));
                gamblerB = pickOf(ScrollEvQa.ev(bot, ScrollRules.Appetite.GAMBLER));
                ScrollRules.Choice ruleB = ScrollEvQa.rule(bot, sword, ScrollRules.Appetite.GAMBLER);
                ev.add("B, dark " + dark + " held: EV careful " + carefulB + ", EV gambler " + gamblerB
                        + "; the appetite rule for a gambler picks " + (ruleB == null ? "nothing" : ruleB.scrollId()));
            } else {
                ev.add("B not judged: " + (dark == 0 ? "no dark scroll fits" : "long shots are off today (the world's"
                        + " godly cap is reached)"));
            }
            List<String> bad = scrollEvProblems(plain, carefulA, judgedDark, carefulB, gamblerB);
            if (bad.isEmpty() && judgedDark == 0) {
                List<String> out = new ArrayList<>();
                out.add("SKIP part A passed (a careful bot declined the plain 100% scroll); part B needs a dark scroll"
                        + " and long shots allowed today");
                out.addAll(ev);
                return out;
            }
            return Probes160.verdict(bad, "a careful bot declined the EV-negative plain scroll and the dark one; a"
                    + " gambler took the dark scroll on its worn sword", ev);
        } finally {
            ev.add(TalkQa.teardown(List.of(bot)));
        }
    }

    /** Takes every scroll (204xxxx, white scrolls included) out of the bot's use tab. */
    static void clearScrolls(Character bot) {
        client.inventory.Inventory use = bot.getInventory(InventoryType.USE);
        List<Short> slots = new ArrayList<>();
        for (Item it : use.list()) {
            if (it.getItemId() / 10000 == 204 || it.getItemId() / 10000 == 234) {
                slots.add(it.getPosition());
            }
        }
        for (short sl : slots) {
            use.removeSlot(sl);
        }
    }

    // ── t-gb-6: bots.apvariants with fromSlot=auto rolls variants for new roster slots ──────────

    /** t-gb-6: new slots sampled past the boot slot, and the job classes sampled (one 2nd job each). */
    static final int GB6_SLOTS = 200;
    static final int[] GB6_JOBS = {110, 210, 310, 410, 510};

    /**
     * t-gb-6: what is wrong. {@code raw} is the fromSlot property as set, {@code fromSlot} what the build reads from it
     * ({@link Integer#MAX_VALUE} = none); {@code bootRolls}/{@code newRolls} whether the slot just before and the
     * first slot from it may roll; {@code variants} non-standard rolls among {@code sampled}. Pure, for the tests.
     */
    static List<String> apVariantProblems(String raw, int fromSlot, boolean bootRolls, boolean newRolls, int variants,
                                          int sampled) {
        List<String> bad = new ArrayList<>();
        if (fromSlot == Integer.MAX_VALUE) {
            bad.add(raw == null ? "slums.bots.apvariants.fromSlot is unset: only reborn bots roll a variant (audit B29)"
                    : "this build reads fromSlot=" + raw + " as unset (Integer.getInteger), so no new bot rolls a"
                    + " variant; 'auto' is understood from 162-auditfix b526a8132");
            return bad;
        }
        if (bootRolls) {
            bad.add("slot " + (fromSlot - 1) + " (before the first new slot) may roll: existing bots would change"
                    + " build");
        }
        if (!newRolls) {
            bad.add("slot " + fromSlot + " (the first new slot) may not roll");
        }
        if (variants == 0) {
            bad.add("none of " + sampled + " new slots rolled a variant");
        }
        return bad;
    }

    /**
     * t-gb-6 ({@code slums.bots.apvariants}, {@code fromSlot=auto}): the slot the build reads from the property must be
     * a real number (auto = the first roster slot created after this boot); the slot before it must not roll (every
     * bot that existed keeps its build), the slot itself must, and of the first {@link #GB6_SLOTS} new slots for each
     * of five classes some must roll a non-standard build. Online roster bots at or past the slot are listed with their
     * build name. Reads only; no bot is created.
     */
    static List<String> apVariants() {
        if (!soloMapling.ArtificialPlayer.BotSupportSwitches.apVariants()) {
            return List.of("SKIP AP variants are off (-Dslums.bots.apvariants=true)");
        }
        List<String> ev = new ArrayList<>();
        String raw = System.getProperty("slums.bots.apvariants.fromSlot");
        int fromSlot = soloMapling.ArtificialPlayer.BotSupportSwitches.apVariantFromSlot();
        int base = soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE;
        ev.add("fromSlot property " + raw + " -> slot " + (fromSlot == Integer.MAX_VALUE ? "none" : fromSlot));
        boolean bootRolls = false, newRolls = false;
        int variants = 0, sampled = 0;
        if (fromSlot != Integer.MAX_VALUE) {
            bootRolls = ApVariantQa.rolls(base + fromSlot - 1, 0);
            newRolls = ApVariantQa.rolls(base + fromSlot, 0);
            Map<String, Integer> kinds = new java.util.TreeMap<>();
            for (int job : GB6_JOBS) {
                for (int i = 0; i < GB6_SLOTS; i++) {
                    BotBuild.Variant v = ApVariantQa.variant(job, base + fromSlot + i);
                    sampled++;
                    kinds.merge(job + ":" + v, 1, Integer::sum);
                    if (v != BotBuild.Variant.STANDARD) {
                        variants++;
                    }
                }
            }
            ev.add("slot " + (fromSlot - 1) + " rolls=" + bootRolls + ", slot " + fromSlot + " rolls=" + newRolls
                    + "; first " + GB6_SLOTS + " new slots per class: " + kinds);
            int highest = -1, past = 0;
            Map<String, Integer> online = new java.util.TreeMap<>();
            for (soloMapling.ArtificialPlayer.BotRoster.Member m : soloMapling.ArtificialPlayer.BotRoster.members()) {
                int slot = m.botId() - base;
                highest = Math.max(highest, slot);
                if (slot >= fromSlot) {
                    past++;
                    Character c = soloMapling.ArtificialPlayer.BotHelpers.getCharFromChannelStorage(m.botId());
                    if (c != null) {
                        online.merge(BotProgression.apBuildName(c), 1, Integer::sum);
                    }
                }
            }
            ev.add("roster: highest slot " + highest + ", " + past + " member(s) at or past slot " + fromSlot
                    + (online.isEmpty() ? "" : "; online builds " + online));
        }
        return Probes160.verdict(apVariantProblems(raw, fromSlot, bootRolls, newRolls, variants, sampled),
                "new roster slots from " + fromSlot + " roll AP variants (" + variants + " of " + sampled
                        + " sampled), the slots before keep their build", ev);
    }

    static long delta(Map<String, Long> a, Map<String, Long> b, String k) {
        return b.getOrDefault(k, 0L) - a.getOrDefault(k, 0L);
    }

    /** A FAIL with what went wrong (the PASS text is never used: callers only reach here with problems). */
    static List<String> verdict(List<String> bad, List<String> ev) {
        return Probes160.verdict(bad, "", ev);
    }
}

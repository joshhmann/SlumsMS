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
import net.server.world.Party;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotPartySupport;
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
import soloMapling.ArtificialPlayer.GCMoveSystem.NavPortQa;
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
    static final List<String> NAMES = List.of("botQuest", "templeQuest", "paretoGear", "dexlessAp", "gearFirstFarm", "junkSortSale", "scrollEv", "apVariants", "partyHeal", "fameRequest", "magicGuard", "jobErrand", "skillBooks", "swimWallJump",
            "blockedPosWatchdog");

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
                case "partyHeal" -> partyHeal(p);
                case "fameRequest" -> fameRequest(p);
                case "magicGuard" -> magicGuard(p);
                case "jobErrand" -> jobErrand(p);
                case "skillBooks" -> skillBooks(p);
                case "swimWallJump" -> swimWallJump();
                case "blockedPosWatchdog" -> blockedPosWatchdog();
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

    // ── party staging (t-su-*) ───────────────────────────────────────────────────────────────────

    /** Puts the player in a fresh party with the bot (the player leads); false when the party did not form. */
    static boolean partyUp(Character me, Character bot) {
        if (me.getParty() != null) {
            Party.leaveParty(me.getParty(), me);
        }
        if (bot.getParty() != null) {
            Party.leaveParty(bot.getParty(), bot);
        }
        Party.createParty(me, true);
        if (me.getParty() == null) {
            return false;
        }
        Party.joinParty(bot, me.getPartyId(), true);
        return bot.getParty() != null && bot.getPartyId() == me.getPartyId();
    }

    static void partyDown(Character me, Character bot) {
        try {
            if (bot.getParty() != null) {
                Party.leaveParty(bot.getParty(), bot);
            }
        } finally {
            if (me.getParty() != null) {
                Party.leaveParty(me.getParty(), me);
            }
        }
    }

    // ── t-su-1: a cleric bot heals a partied player under 60% HP ─────────────────────────────────

    /** t-su-1: the cleric's level and Heal level, and the HP share the player is dropped to. */
    static final int SU1_LEVEL = 40, SU1_HEAL = 10, SU1_HP_PERCENT = 40;

    /** t-su-1: what is wrong. Pure, for the tests. */
    static List<String> healProblems(boolean partied, boolean acted, int hpStaged, int hpAfter, int maxHp) {
        List<String> bad = new ArrayList<>();
        if (!partied) {
            bad.add("the party did not form");
            return bad;
        }
        if (!acted) {
            bad.add("BotPartySupport.tick did nothing for a partied player at " + hpStaged + "/" + maxHp + " HP");
        }
        if (hpAfter <= hpStaged) {
            bad.add("the player's HP did not rise (" + hpStaged + " -> " + hpAfter + ")");
        }
        return bad;
    }

    /**
     * t-su-1 ({@code slums.bots.partysupport}): a fresh level-40 bot (tick stopped) is made a Cleric with Heal 10 and
     * full MP, next to the pretend player; the two form a party (the player leads) and the player is dropped to
     * {@link #SU1_HP_PERCENT}% HP. One {@code BotPartySupport.tick} must cast Heal on the player and raise its HP.
     * Server side only: the cast pose still needs a real client (audit B24). The party is left and the player's HP
     * put back.
     */
    static List<String> partyHeal(SyntheticPlayer p) throws InterruptedException {
        if (!soloMapling.ArtificialPlayer.BotSupportSwitches.partySupport()) {
            return List.of("SKIP party support is off (-Dslums.bots.partysupport=true)");
        }
        Character me = p.character();
        List<String> ev = new ArrayList<>();
        Character bot = spawnTraining(p, SU1_LEVEL);
        if (bot == null) {
            return List.of("FAIL could not spawn a training bot");
        }
        int hp0 = me.getHp();
        try {
            freeze(bot);
            bot.setJob(Job.CLERIC);
            bot.changeSkillLevel(client.SkillFactory.getSkill(constants.skills.Cleric.HEAL), (byte) SU1_HEAL, 30, -1);
            bot.updateHpMp(bot.getCurrentMaxHp(), bot.getCurrentMaxMp());
            boolean partied = partyUp(me, bot);
            int max = me.getCurrentMaxHp();
            int staged = Math.max(1, max * SU1_HP_PERCENT / 100);
            me.updateHp(staged);
            ev.add("cleric " + bot.getName() + " lv " + bot.getLevel() + " Heal " + bot.getSkillLevel(constants.skills.Cleric.HEAL)
                    + " MP " + bot.getMp() + "/" + bot.getCurrentMaxMp() + " at " + bot.getPosition() + "; player "
                    + me.getName() + " at " + me.getPosition() + " HP " + me.getHp() + "/" + max + "; partied=" + partied
                    + " same map=" + (bot.getMapId() == me.getMapId()));
            boolean acted = partied && BotPartySupport.tick(bot);
            int after = me.getHp();
            ev.add("tick acted=" + acted + "; player HP " + staged + " -> " + after + ", cleric MP now " + bot.getMp());
            return Probes160.verdict(healProblems(partied, acted, staged, after, max), "the cleric bot healed its"
                    + " partied player from " + staged + " to " + after + " HP (server side; the pose needs a real"
                    + " client)", ev);
        } finally {
            BotPartySupport.clearBot(bot.getId());
            partyDown(me, bot);
            me.updateHp(Math.max(hp0, 1));
            ev.add(TalkQa.teardown(List.of(bot)));
        }
    }

    // ── t-su-2: a friend fames on request once; a second ask is refused ─────────────────────────

    /** t-su-2: the line the pretend player says (a fame request by BotFameRequest.isFameRequest). */
    static final String SU2_ASK = "fame me pls";

    /** t-su-2: what is wrong with the three asks (stranger, friend, friend again). Pure, for the tests. */
    static List<String> fameProblems(boolean strangerHandled, int strangerGain, boolean firstHandled, int firstGain,
                                     boolean secondHandled, int secondGain) {
        List<String> bad = new ArrayList<>();
        if (strangerHandled || strangerGain != 0) {
            bad.add("a bot that is not a friend answered the ask (handled=" + strangerHandled + ", fame +" + strangerGain
                    + ")");
        }
        if (!firstHandled || firstGain != 1) {
            bad.add("the friend's first answer gave fame +" + firstGain + " (handled=" + firstHandled + "), want +1");
        }
        if (!secondHandled || secondGain != 0) {
            bad.add("the second ask gave fame +" + secondGain + " (handled=" + secondHandled + "), want a refusal and +0");
        }
        return bad;
    }

    /**
     * t-su-2 ({@code slums.bots.famerequest}): a fresh level-20 bot (tick stopped) next to the pretend player. Asked
     * "{@value #SU2_ASK}" while not a friend it must not answer; staged a FRIEND ({@code BotAffinity.stage}) it must
     * fame the player once ({@code BotFameRequest.tryHandle}, the chat path's call; fame +1), and a second ask must be
     * refused (fame +0). The fame point, the bot's famelog row and the staged tie are taken back.
     */
    static List<String> fameRequest(SyntheticPlayer p) throws Exception {
        if (!soloMapling.ArtificialPlayer.BotSupportSwitches.fameRequest()) {
            return List.of("SKIP fame requests are off (-Dslums.bots.famerequest=true)");
        }
        Character me = p.character();
        List<String> ev = new ArrayList<>();
        Character bot = spawnTraining(p, 20);
        if (bot == null) {
            return List.of("FAIL could not spawn a training bot");
        }
        int gained = 0;
        try {
            freeze(bot);
            soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.stage(bot.getId(), me.getId(),
                    soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState.NEUTRAL);
            int f0 = me.getFame();
            boolean stranger = soloMapling.ArtificialPlayer.BotFameRequest.tryHandle(bot, me, SU2_ASK);
            int f1 = me.getFame();
            boolean staged = soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.stage(bot.getId(), me.getId(),
                    soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState.FRIEND);
            long since = System.currentTimeMillis();
            boolean first = soloMapling.ArtificialPlayer.BotFameRequest.tryHandle(bot, me, SU2_ASK);
            int f2 = me.getFame();
            boolean second = soloMapling.ArtificialPlayer.BotFameRequest.tryHandle(bot, me, SU2_ASK);
            int f3 = me.getFame();
            gained = f3 - f0;
            Thread.sleep(1500);
            List<String> said = new ArrayList<>();
            for (AutoQaLine l : p.heard(since)) {
                if (l.message().contains(bot.getName())) {
                    said.add(l.kind() + " " + l.message());
                }
            }
            ev.add("bot " + bot.getName() + " lv " + bot.getLevel() + " on " + bot.getMapId() + ", player on "
                    + me.getMapId() + "; friend staged=" + staged);
            ev.add("fame " + f0 + " -> stranger ask (handled=" + stranger + ") " + f1 + " -> friend ask (handled=" + first
                    + ") " + f2 + " -> again (handled=" + second + ") " + f3);
            ev.add("the bot said: " + (said.isEmpty() ? "nothing the player heard" : String.join(" | ", said)));
            return Probes160.verdict(fameProblems(stranger, f1 - f0, first, f2 - f1, second, f3 - f2),
                    "a stranger bot ignored the ask, the friend famed the player once and refused the second ask", ev);
        } finally {
            if (gained > 0) {
                me.gainFame(-gained);
            }
            try (java.sql.Connection con = tools.DatabaseConnection.getConnection();
                 java.sql.PreparedStatement ps = con.prepareStatement(
                         "DELETE FROM famelog WHERE characterid = ? AND characterid_to = ?")) {
                ps.setInt(1, bot.getId());
                ps.setInt(2, me.getId());
                ev.add("famelog rows removed: " + ps.executeUpdate() + ", fame given back: " + Math.max(0, gained));
            } catch (java.sql.SQLException e) {
                ev.add("famelog cleanup failed: " + e.getMessage());
            }
            soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.stage(bot.getId(), me.getId(),
                    soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState.NEUTRAL);
            ev.add(TalkQa.teardown(List.of(bot)));
        }
    }

    // ── t-su-3: Magic Guard up splits a bot's damage into MP ────────────────────────────────────

    /** t-su-3: the magician's level and Magic Guard level. */
    static final int SU3_LEVEL = 30, SU3_MG = 20;

    /**
     * t-su-3: what is wrong. {@code plain} is the HP/MP loss of a hit with no guard up, {@code guarded} of the same hit
     * with Magic Guard cast, {@code want} the split {@code BotVitals.magicGuardSplit} gives. Pure, for the tests.
     */
    static List<String> guardProblems(int damage, int[] plain, int[] guarded, int[] want) {
        List<String> bad = new ArrayList<>();
        if (plain[0] != damage || plain[1] != 0) {
            bad.add("with no guard up the hit took " + plain[0] + " HP and " + plain[1] + " MP, want " + damage + " HP");
        }
        if (want[1] <= 0) {
            bad.add("the guard's share is 0 (no Magic Guard percent for the skill level)");
        }
        if (guarded[0] != want[0] || guarded[1] != want[1]) {
            bad.add("with Magic Guard up the hit took " + guarded[0] + " HP and " + guarded[1] + " MP, want " + want[0]
                    + " HP and " + want[1] + " MP");
        }
        return bad;
    }

    /**
     * t-su-3 ({@code slums.bots.magicguard}): a fresh level-30 bot (tick stopped) is made a Magician with Magic Guard
     * 20 and full HP/MP. A hit of a tenth of its HP through {@code BotVitals.hurt} with no guard cast must come off HP
     * only; after {@code BotBuffDriver.markCastForTest} (the cast's timer, as the buff driver records it) the same hit
     * must split by the skill's x percent into MP ({@code BotVitals.magicGuardSplit}).
     */
    static List<String> magicGuard(SyntheticPlayer p) throws InterruptedException {
        if (!soloMapling.ArtificialPlayer.BotSupportSwitches.magicGuard()) {
            return List.of("SKIP bot Magic Guard is off (-Dslums.bots.magicguard=true)");
        }
        List<String> ev = new ArrayList<>();
        Character bot = spawnTraining(p, SU3_LEVEL);
        if (bot == null) {
            return List.of("FAIL could not spawn a training bot");
        }
        try {
            freeze(bot);
            int mg = constants.skills.Magician.MAGIC_GUARD;
            bot.setJob(Job.MAGICIAN);
            bot.changeSkillLevel(client.SkillFactory.getSkill(mg), (byte) SU3_MG, 20, -1);
            soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver.clearBot(bot.getId());
            bot.updateHpMp(bot.getCurrentMaxHp(), bot.getCurrentMaxMp());
            int damage = Math.max(10, bot.getCurrentMaxHp() / 10);
            int hp0 = bot.getHp(), mp0 = bot.getMp();
            soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.hurt(bot, damage);
            int[] plain = {hp0 - bot.getHp(), mp0 - bot.getMp()};
            bot.updateHpMp(bot.getCurrentMaxHp(), bot.getCurrentMaxMp());
            int percent = client.SkillFactory.getSkill(mg).getEffect(SU3_MG).getX();
            soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver.markCastForTest(bot.getId(), mg,
                    System.currentTimeMillis() + 60_000);
            int hp1 = bot.getHp(), mp1 = bot.getMp();
            int[] want = soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.magicGuardSplit(damage, percent, mp1);
            soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.hurt(bot, damage);
            int[] guarded = {hp1 - bot.getHp(), mp1 - bot.getMp()};
            ev.add("magician " + bot.getName() + " lv " + bot.getLevel() + " Magic Guard " + bot.getSkillLevel(mg)
                    + " (x " + percent + "%), HP " + hp0 + " MP " + mp0 + "; hit " + damage);
            ev.add("no guard: -" + plain[0] + " HP, -" + plain[1] + " MP; guard up: -" + guarded[0] + " HP, -"
                    + guarded[1] + " MP (want -" + want[0] + " HP, -" + want[1] + " MP)");
            return Probes160.verdict(guardProblems(damage, plain, guarded, want), "with Magic Guard up the bot paid "
                    + want[1] + " of a " + damage + " hit in MP; with none up it all came off HP", ev);
        } finally {
            soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver.clearBot(bot.getId());
            ev.add(TalkQa.teardown(List.of(bot)));
        }
    }

    // ── t-jb-1: a watched bot walks to its instructor and advances, or falls back ──────────────

    /** t-jb-1: the errand's timeout for the check (the default is 6 min), and how many fresh bots it may try. */
    static final long JB1_TIMEOUT_MS = 240_000L;
    static final int JB1_TRIES = 4;

    /** t-jb-1: the counters of {@code JobErrand.stats()} (started, arrived, fellBack, blocked, active), or null. */
    static long[] errandCounts(String stats) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "started=(\\d+) arrived=(\\d+) fellBack=(\\d+) blocked=(\\d+) active=(\\d+)").matcher(stats);
        if (!m.find()) {
            return null;
        }
        long[] out = new long[5];
        for (int i = 0; i < 5; i++) {
            out[i] = Long.parseLong(m.group(i + 1));
        }
        return out;
    }

    /** t-jb-1: what is wrong. {@code held} = the errand held the advance; times in ms. Pure, for the tests. */
    static List<String> errandProblems(boolean held, boolean ended, long arrived, long fellBack, int jobBefore,
                                       int jobAfter, int mapAfter, int instructorMap) {
        List<String> bad = new ArrayList<>();
        if (!held) {
            bad.add("the watched bot advanced at once: no errand was started (watched? reachable?)");
            return bad;
        }
        if (!ended) {
            bad.add("the errand still holds the bot past its timeout");
        }
        if (jobAfter == jobBefore) {
            bad.add("the bot is still job " + jobBefore + " after the errand");
        }
        if (arrived == 0 && fellBack == 0 && ended) {
            bad.add("the errand ended neither by arriving nor by falling back");
        }
        if (arrived > 0 && mapAfter != instructorMap) {
            bad.add("counted as arrived but the bot is on " + mapAfter + ", not the instructor's " + instructorMap);
        }
        return bad;
    }

    /**
     * t-jb-1 ({@code solomapling.jobErrand}): a fresh level-9 beginner (its tick running, the pretend player on its map
     * so it is watched) gains the EXP for level 10 the observed way ({@code gainExp}, the bot progression hook). Its
     * advance must be held by an errand to its instructor; within the errand's timeout (set to
     * {@link #JB1_TIMEOUT_MS} for the check, put back after) it must arrive and advance, or fall back and advance.
     */
    static List<String> jobErrand(SyntheticPlayer p) throws InterruptedException {
        if (!soloMapling.ArtificialPlayer.BotProgressionSystem.JobErrand.enabled()) {
            return List.of("SKIP job errands are off (-Dsolomapling.jobErrand=true)");
        }
        List<String> ev = new ArrayList<>();
        List<Character> spawned = new ArrayList<>();
        String timeoutWas = System.getProperty(soloMapling.ArtificialPlayer.BotProgressionSystem.JobErrand.TIMEOUT_PROP);
        try {
            Character bot = null;
            for (int i = 0; i < JB1_TRIES && bot == null; i++) {
                Character c = spawnTraining(p, 9);
                if (c == null) {
                    continue;
                }
                spawned.add(c);
                if (c.getJob().getId() == 0 && BotProgression.isReal(c.getId())) {
                    bot = c;
                } else {
                    ev.add("bot " + c.getName() + " is job " + c.getJob().getId() + " at level " + c.getLevel()
                            + " (real=" + BotProgression.isReal(c.getId()) + "): not a beginner to stage");
                }
            }
            if (bot == null) {
                ev.add(0, "FAIL no fresh level-9 beginner to stage in " + JB1_TRIES + " tries");
                return ev;
            }
            System.setProperty(soloMapling.ArtificialPlayer.BotProgressionSystem.JobErrand.TIMEOUT_PROP,
                    Long.toString(JB1_TIMEOUT_MS));
            long[] c0 = errandCounts(soloMapling.ArtificialPlayer.BotProgressionSystem.JobErrand.stats());
            boolean watched = soloMapling.ArtificialPlayer.GCMoveSystem.WatchGate.watched(bot);
            int jobBefore = bot.getJob().getId();
            long need = constants.game.ExpTable.getExpNeededForLevel(bot.getLevel()) - bot.getExp() + 1;
            bot.gainExp((int) need);
            long t0 = System.currentTimeMillis();
            boolean held = false;
            while (System.currentTimeMillis() - t0 < 5_000 && !held) {
                held = soloMapling.ArtificialPlayer.BotProgressionSystem.JobErrand.holds(bot.getId());
                if (!held) {
                    Thread.sleep(200);
                }
            }
            ev.add("bot " + bot.getName() + " lv " + bot.getLevel() + " job " + jobBefore + " on " + bot.getMapId()
                    + " watched=" + watched + "; errand held=" + held + ", job now " + bot.getJob().getId());
            boolean ended = !held;
            if (held) {
                while (System.currentTimeMillis() - t0 < JB1_TIMEOUT_MS + 30_000) {
                    if (!soloMapling.ArtificialPlayer.BotProgressionSystem.JobErrand.holds(bot.getId())) {
                        ended = true;
                        break;
                    }
                    Thread.sleep(1000);
                }
            }
            Thread.sleep(1500);   // the advance runs right after the errand ends
            long[] c1 = errandCounts(soloMapling.ArtificialPlayer.BotProgressionSystem.JobErrand.stats());
            long arrived = c0 == null || c1 == null ? 0 : c1[1] - c0[1];
            long fellBack = c0 == null || c1 == null ? 0 : c1[2] - c0[2];
            int jobAfter = bot.getJob().getId();
            var where = soloMapling.ArtificialPlayer.BotProgressionSystem.JobErrand.instructorFor(jobAfter);
            int instructorMap = where == null ? -1 : where.mapId();
            ev.add("after " + (System.currentTimeMillis() - t0) / 1000 + " s: ended=" + ended + " arrived +" + arrived
                    + " fell back +" + fellBack + "; job " + jobAfter + ", map " + bot.getMapId() + " (instructor's "
                    + instructorMap + "); " + soloMapling.ArtificialPlayer.BotProgressionSystem.JobErrand.stats());
            return Probes160.verdict(errandProblems(held, ended, arrived, fellBack, jobBefore, jobAfter,
                    bot.getMapId(), instructorMap), "the watched bot's advance waited for an errand, which "
                    + (arrived > 0 ? "reached the instructor" : "fell back") + ", and it advanced to " + jobAfter, ev);
        } finally {
            if (timeoutWas == null) {
                System.clearProperty(soloMapling.ArtificialPlayer.BotProgressionSystem.JobErrand.TIMEOUT_PROP);
            } else {
                System.setProperty(soloMapling.ArtificialPlayer.BotProgressionSystem.JobErrand.TIMEOUT_PROP, timeoutWas);
            }
            for (Character c : spawned) {
                soloMapling.ArtificialPlayer.BotProgressionSystem.JobErrand.forget(c.getId());
            }
            ev.add(TalkQa.teardown(spawned));
        }
    }

    // ── t-jb-2: a bot reads a wanted skill book; a spare one may be sold ────────────────────────

    /** t-jb-2: the 4th-job bot's level, the copies of the wanted book it is given, and the fresh bots it may try. */
    static final int JB2_LEVEL = 135, JB2_COPIES = 3, JB2_TRIES = 3;
    static final String JB2_KEPT = "kept for its skill plan";

    /** t-jb-2: what is wrong. Pure, for the tests. */
    static List<String> bookProblems(String keptBefore, int read, int raised, int masterBefore, int masterAfter,
                                     String spareRefusal) {
        List<String> bad = new ArrayList<>();
        if (!JB2_KEPT.equals(keptBefore)) {
            bad.add("the wanted book was not kept from sale before it was read (" + keptBefore + ")");
        }
        if (read == 0) {
            bad.add("the bot read none of the wanted books (BotSkillBooks.useAll)");
        }
        if (raised > 0 && masterAfter <= masterBefore) {
            bad.add("useAll counted a raise but the master level stayed " + masterBefore);
        }
        if (JB2_KEPT.equals(spareRefusal)) {
            bad.add("a book the bot does not want is still kept for its skill plan");
        }
        return bad;
    }

    /**
     * t-jb-2 ({@code solomapling.botSkillBooks}): a fresh level-135 bot (tick stopped) in a 4th job. The first mastery
     * book (228/229xxxx) it wants and can read now is found ({@code BotSkillBooks.wants/canUseNow}); before reading,
     * {@code sellRefusal} must keep it for the skill plan. Given {@link #JB2_COPIES} copies, {@code useAll} must read
     * at least one (a failed roll still uses the book up; a raise must show in the master level). A book it does not
     * want (another job's) must not be kept for the plan: it is a spare the bot may sell (only SaleRules may refuse).
     */
    static List<String> skillBooks(SyntheticPlayer p) throws InterruptedException {
        if (!soloMapling.ArtificialPlayer.BotProgressionSystem.BotSkillBooks.enabled()) {
            return List.of("SKIP bot skill books are off (-Dsolomapling.botSkillBooks=true)");
        }
        List<String> ev = new ArrayList<>();
        List<Character> spawned = new ArrayList<>();
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        try {
            for (int t = 0; t < JB2_TRIES; t++) {
                Character bot = spawnTraining(p, JB2_LEVEL);
                if (bot == null) {
                    continue;
                }
                spawned.add(bot);
                freeze(bot);
                int job = bot.getJob().getId();
                int wanted = 0, spare = 0;
                for (int id = 2280000; id < 2300000 && (wanted == 0 || spare == 0); id++) {
                    if (ii.getName(id) == null) {
                        continue;
                    }
                    if (wanted == 0 && soloMapling.ArtificialPlayer.BotProgressionSystem.BotSkillBooks.canUseNow(bot, id)) {
                        wanted = id;
                    } else if (spare == 0 && !soloMapling.ArtificialPlayer.BotProgressionSystem.BotSkillBooks.wants(bot, id)) {
                        Map<String, Integer> st = ii.getSkillStats(id, job);
                        if (st != null && st.getOrDefault("skillid", 0) == 0) {
                            spare = id;   // another job's book
                        }
                    }
                }
                if (wanted == 0) {
                    ev.add("bot " + bot.getName() + " job " + job + " lv " + bot.getLevel() + " wants no book it can read now");
                    continue;
                }
                int skillId = ii.getSkillStats(wanted, job).get("skillid");
                Item probe = new Item(wanted, (short) 0, (short) 1);
                String keptBefore = soloMapling.ArtificialPlayer.BotProgressionSystem.BotSkillBooks.sellRefusal(bot, probe);
                int masterBefore = bot.getMasterLevel(skillId);
                for (int i = 0; i < JB2_COPIES; i++) {
                    bot.getInventory(InventoryType.USE).addItem(new Item(wanted, (short) 0, (short) 1));
                }
                int held0 = bot.getInventory(InventoryType.USE).countById(wanted);
                int raised = soloMapling.ArtificialPlayer.BotProgressionSystem.BotSkillBooks.useAll(bot);
                int held1 = bot.getInventory(InventoryType.USE).countById(wanted);
                int masterAfter = bot.getMasterLevel(skillId);
                String spareRefusal = spare == 0 ? null
                        : soloMapling.ArtificialPlayer.BotProgressionSystem.BotSkillBooks.sellRefusal(bot,
                        new Item(spare, (short) 0, (short) 1));
                ev.add("bot " + bot.getName() + " job " + job + " lv " + bot.getLevel() + "; wanted book " + wanted + " ("
                        + ii.getName(wanted) + ") for skill " + skillId + ", before reading: " + keptBefore);
                ev.add("useAll: read " + (held0 - held1) + " of " + held0 + ", raised " + raised + "; master level "
                        + masterBefore + " -> " + masterAfter);
                ev.add("spare book " + spare + (spare == 0 ? " (none found)" : " (" + ii.getName(spare) + "): "
                        + (spareRefusal == null ? "may be sold" : spareRefusal)));
                return Probes160.verdict(bookProblems(keptBefore, held0 - held1, raised, masterBefore, masterAfter,
                        spareRefusal), "the bot kept its wanted book from sale, read it (master " + masterBefore
                        + " -> " + masterAfter + "), and a book it does not want may be sold", ev);
            }
            ev.add(0, "FAIL no fresh level-" + JB2_LEVEL + " bot wanted a book it could read now in " + JB2_TRIES
                    + " tries");
            return ev;
        } finally {
            ev.add(TalkQa.teardown(spawned));
        }
    }

    // ── t-nn-1 / t-nn-2: the NN-011 nav ports, replayed in the server ───────────────────────────

    /** t-nn-1: game ticks of the swim replay; t-nn-2: AI ticks of the parked replay (as the unit tests). */
    static final int NN1_TICKS = 300, NN2_TICKS = 40;

    /** t-nn-1: what is wrong with the swim replay (the rock's top is y -600; the target is past x 100). Pure. */
    static List<String> swimProblems(NavPortQa.SwimRun run) {
        List<String> bad = new ArrayList<>();
        if (run.highestAtWestFace() >= -600) {
            bad.add("the swimmer stayed pinned to the rock: highest y on its face " + run.highestAtWestFace()
                    + " (the top is -600)");
        }
        if (run.furthestEast() <= 100) {
            bad.add("the swimmer never got east of the rock toward its target (furthest x " + run.furthestEast() + ")");
        }
        return bad;
    }

    /** t-nn-2: what is wrong with the parked replay. Pure, for the tests. */
    static List<String> parkedProblems(NavPortQa.ParkedRun run) {
        List<String> bad = new ArrayList<>();
        if (run.ticks() == 0) {
            bad.add("no nav graph could be built for the replay map");
            return bad;
        }
        if (run.gatedTicks() == 0) {
            bad.add("the fixture did not park the bot: no launch gate refused it (the department store's geometry"
                    + " changed?)");
        }
        if (run.giveUps() < 3) {
            bad.add("the parked bot gave its hop up " + run.giveUps() + " time(s) in " + run.ticks()
                    + " AI ticks, want at least 3 (every 6-10 ticks)");
        }
        return bad;
    }

    /**
     * t-nn-1 ({@code bot.nav.swimWallJump}): the server's own navigation and swim physics replay the unit test's
     * scenario (a detached swimmer pressed against a 600 px rock, open water beyond) on a made-up map with the live
     * switches; the swim-jump burst must lift it over the rock and on east. No real map is touched; how it looks on a
     * real client still needs eyes (audit: never watched on a client).
     */
    static List<String> swimWallJump() {
        if (!NavPortQa.swimWallJump()) {
            return List.of("SKIP the swim wall jump is off (-Dbot.nav.swimWallJump=true)");
        }
        NavPortQa.SwimRun run = NavPortQa.swimWall(NN1_TICKS);
        List<String> ev = new ArrayList<>();
        ev.add("replay on made-up map " + NavPortQa.SWIM_MAP + " (swim navigator " + (NavPortQa.swimNavigator() ? "on"
                : "off") + "): " + run.ticks() + " ticks, highest y on the rock's west face " + run.highestAtWestFace()
                + ", furthest x " + run.furthestEast());
        return Probes160.verdict(swimProblems(run), "a swimmer pinned to a 600 px rock jumped over it and swam on", ev);
    }

    /**
     * t-nn-2 ({@code bot.nav.blockedPosWatchdog}): the server's navigation replays the unit test's scenario: a bot
     * parked on the department store's top floor (the real map's geometry under a made-up id, so the real map's graph
     * and learning are untouched), its launch gate refusing it, the nav cleared after every AI tick. It must give the
     * hop up at least 3 times in 40 ticks. Today's real GAVE_UP spots from the nav lab's log are listed for context.
     */
    static List<String> blockedPosWatchdog() {
        if (!NavPortQa.blockedPosWatchdog()) {
            return List.of("SKIP the blocked-position watchdog is off (-Dbot.nav.blockedPosWatchdog=true)");
        }
        NavPortQa.ParkedRun run = NavPortQa.parkedBot(NN2_TICKS);
        List<String> ev = new ArrayList<>();
        ev.add("replay of map " + NavPortQa.WATCHDOG_REAL_MAP + " as " + NavPortQa.WATCHDOG_MAP + ": " + run.ticks()
                + " AI ticks, launch gate refused " + run.gatedTicks() + ", gave up " + run.giveUps());
        ev.add("real maps today (GAVE_UP): " + NavPortQa.today(
                soloMapling.ArtificialPlayer.GCMoveSystem.NavStuckLog.Kind.GAVE_UP, 3));
        return Probes160.verdict(parkedProblems(run), "a bot parked at a launch gate gave the hop up " + run.giveUps()
                + " times in " + run.ticks() + " AI ticks instead of standing there", ev);
    }

    static long delta(Map<String, Long> a, Map<String, Long> b, String k) {
        return b.getOrDefault(k, 0L) - a.getOrDefault(k, 0L);
    }

    /** A FAIL with what went wrong (the PASS text is never used: callers only reach here with problems). */
    static List<String> verdict(List<String> bad, List<String> ev) {
        return Probes160.verdict(bad, "", ev);
    }
}

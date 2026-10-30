package soloMapling.autoqa;

import client.Character;
import client.QuestStatus;
import server.life.LifeFactory;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotConvo.TalkQa;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePlanner;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotQuestSystem.BotQuestLog;
import soloMapling.ArtificialPlayer.BotQuestSystem.BotQuests;
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
    static final List<String> NAMES = List.of("botQuest");

    private Probes162() {
    }

    /** The probe's lines, or null when {@code what} is not one of these. */
    static List<String> probe(String what, SyntheticPlayer p, String... a) {
        try {
            return switch (what) {
                case "botQuest" -> botQuest(p);
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

    static long delta(Map<String, Long> a, Map<String, Long> b, String k) {
        return b.getOrDefault(k, 0L) - a.getOrDefault(k, 0L);
    }

    /** A FAIL with what went wrong (the PASS text is never used: callers only reach here with problems). */
    static List<String> verdict(List<String> bad, List<String> ev) {
        return Probes160.verdict(bad, "", ev);
    }
}

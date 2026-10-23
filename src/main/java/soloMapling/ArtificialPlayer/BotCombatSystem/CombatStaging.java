package soloMapling.ArtificialPlayer.BotCombatSystem;

import client.Character;
import client.Job;
import client.command.commands.gm2.ClassTestCommand;
import server.life.Monster;
import server.maps.MapleMap;
import server.maps.Rope;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;
import soloMapling.ArtificialPlayer.BotSupplySystem.StarterStash;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.worldevents.WorldEventSteer;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 1.5.0 rc3: GM and {@code !qa} (cb-1 .. cb-4, sp-1) staging for the human combat layer. Grinders are fresh
 * on-demand training bots held on the GM's map (WorldEventSteer, as the af-2 rival is), with starter potions and
 * ammo, so the GM watches them fight, buff, loot, drink, die and spread out. {@link #unstage} takes them away.
 */
public final class CombatStaging {

    private CombatStaging() {
    }

    /**
     * Grinders on the GM's map. {@code jobs} given: one bot per job at that job's check level with its line's skills
     * maxed (cb-1, cb-2). No jobs: {@code count} bots of random classes at the map's mob level plus
     * {@code levelOffset} (cb-3 uses a negative offset: a harder map). {@code around} null = next to the GM.
     * {@code hold} keeps them on the map no matter what (as an event would); without it they only start here
     * (station-here) and may still move on from a crowded map (cb-4, sp-1).
     */
    public static List<Integer> stage(Character gm, List<Job> jobs, int count, int levelOffset, Point around,
                                      boolean hold) {
        MapleMap map = gm.getMap();
        Point pos = around != null ? around : gm.getPosition();
        List<Integer> ids = new ArrayList<>();
        int n = jobs.isEmpty() ? count : jobs.size();
        int slotBase = BotStore.nextDynamicSlots(n);
        int mobLevel = Math.max(1, MapMobIndex.level(gm.getMapId()));
        for (int i = 0; i < n; i++) {
            Point at = new Point(pos.x + (i % 2 == 0 ? 1 : -1) * (30 + 25 * (i / 2)), pos.y);
            int id;
            if (!jobs.isEmpty()) {
                Job job = jobs.get(i);
                int level = ClassTestCommand.defaultLevel(job);
                int niche = Math.max(1, Math.min(5, job.getJobNiche()));
                id = BotGeneration.createBot(slotBase + i, at, map, niche, level, level, job.getId());
            } else {
                int level = Math.max(10, Math.min(200, mobLevel + levelOffset));
                id = BotGeneration.createBot(slotBase + i, at, map, 0, level, level);
            }
            Character bot = id > 0 ? BotHelpers.getCharFromChannelStorage(id) : null;
            if (bot == null) {
                continue;
            }
            if (!jobs.isEmpty()) {
                for (int image : ClassTestCommand.lineageImages(jobs.get(i))) {
                    ClassTestCommand.maxSkills(bot, image);
                }
            }
            BotWeapons.ensureFittingWeapon(bot);
            StarterStash.grant(bot);
            if (hold) {
                WorldEventSteer.hold(id, gm.getMapId());
            } else {
                soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager.markStationHere(id);
            }
            BotTypeManager.convertBotType(bot, BotTypeManager.BotType.TRAINING_BOT);
            ids.add(id);
        }
        return ids;
    }

    /** A job by name (HERO, fp_archmage) or id (112), or null. */
    public static Job job(String word) {
        if (word == null) {
            return null;
        }
        if (word.matches("\\d{3,4}")) {
            Job j = Job.getById(Integer.parseInt(word));
            return j != null && j != Job.BEGINNER ? j : null;
        }
        try {
            return Job.valueOf(word.toUpperCase(java.util.Locale.ROOT).replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Takes staged grinders out of the world. */
    public static void unstage(List<Integer> ids) {
        for (int id : ids) {
            WorldEventSteer.unhold(id);
            BotDeaths.clearBot(id);
            Character bot = BotHelpers.getCharFromChannelStorage(id);
            if (bot == null) {
                continue;
            }
            BotSM sm = CharacterStorage.getBotById(id);
            if (sm != null) {
                sm.setRunning(false);
                sm.stopScheduledTask();
            }
            BotGeneration.removeBotFromServer(bot);
        }
    }

    /** One line per bot for a read-out: {@code buffs | position | habits}. */
    public static List<String> report(List<Integer> ids, String what) {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int id : ids) {
            Character bot = BotHelpers.getCharFromChannelStorage(id);
            if (bot == null) {
                out.add(id + ": gone");
                continue;
            }
            String head = bot.getName() + " " + bot.getJob() + " lv " + bot.getLevel();
            switch (what) {
                case "buffs" -> {
                    StringBuilder b = new StringBuilder(head + " buffs:");
                    for (Map.Entry<Integer, Long> e : new TreeMap<>(BotBuffDriver.timersOf(id)).entrySet()) {
                        b.append(' ').append(e.getKey()).append(" due ").append((e.getValue() - now) / 1000).append("s");
                    }
                    out.add(b.toString());
                }
                case "position" -> out.add(head + " " + position(bot));
                default -> out.add(head + ": HP " + pct(bot.getHp(), bot.getCurrentMaxHp()) + "%, HP pots "
                        + BotVitals.potionCount(bot, true) + (BotDeaths.isDead(id) ? ", DEAD" : "") + " | "
                        + BotHabits.of(id).describe());
            }
        }
        return out;
    }

    // "at (x,y) facing left, nearest mob Snail dx 64 (outside its body)"
    private static String position(Character bot) {
        Point p = bot.getPosition();
        Boolean left = GCMovement.isFacingLeft(bot);
        Monster nearest = null;
        double best = Double.MAX_VALUE;
        if (bot.getMap() != null) {
            for (Monster m : bot.getMap().getAllMonsters()) {
                if (m.isAlive() && m.getPosition() != null && p.distanceSq(m.getPosition()) < best) {
                    best = p.distanceSq(m.getPosition());
                    nearest = m;
                }
            }
        }
        String mob = "no mob";
        if (nearest != null) {
            int dx = nearest.getPosition().x - p.x;
            java.awt.Rectangle body = GCMovement.mobBounds(nearest);
            boolean inside = body != null && body.contains(p.x, p.y - 8);
            boolean facesIt = left == null || Math.abs(dx) <= 15 || (dx < 0) == left;
            mob = "nearest " + nearest.getName() + " dx " + dx + (inside ? " INSIDE its body" : "")
                    + (facesIt ? ", facing it" : ", FACING AWAY");
        }
        return "at (" + p.x + "," + p.y + ")" + (left == null ? "" : left ? " facing left" : " facing right") + ", " + mob;
    }

    /**
     * sp-1: how spread the bots on this map instance are. Bots, distinct 100 px columns, pairs closer than 34 px on
     * one ledge, bots on each rope.
     */
    public static List<String> spreadTally(MapleMap map) {
        List<Point> bots = new ArrayList<>();
        Map<String, Integer> ropes = new TreeMap<>();
        for (Character c : map.getCharacters()) {
            if (c == null || !BotHelpers.isBot(c) || c.getPosition() == null) {
                continue;
            }
            bots.add(c.getPosition());
            Rope r = GCMovement.climbingRope(c);
            if (r != null) {
                ropes.merge((r.isLadder() ? "ladder x" : "rope x") + r.x(), 1, Integer::sum);
            }
        }
        java.util.Set<Long> columns = new java.util.HashSet<>();
        int close = 0;
        for (int i = 0; i < bots.size(); i++) {
            Point a = bots.get(i);
            columns.add(((long) (a.y / 60) << 32) | ((a.x / 100) & 0xFFFFFFFFL));
            for (int j = i + 1; j < bots.size(); j++) {
                Point b = bots.get(j);
                if (Math.abs(a.x - b.x) < 34 && Math.abs(a.y - b.y) < 20) {
                    close++;
                }
            }
        }
        List<String> out = new ArrayList<>();
        out.add("SPREAD map " + map.getId() + " ch " + map.getChannelId() + ": " + bots.size() + " bots over "
                + columns.size() + " 100px spots, " + close + " pair(s) closer than 34 px, ropes " + (ropes.isEmpty()
                ? "none" : ropes));
        return out;
    }

    /** A cb-3 step on the staged bots: {@code die | lowpots | hurt}; the lines of what happened. */
    public static List<String> act(List<Integer> ids, String action) {
        List<String> out = new ArrayList<>();
        if ("rope".equals(action)) { // rc6 (QA t-sp-1): two grinders take a rope rest now
            for (int id : ids) {
                if (out.size() < 2 && soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById(id)
                        instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot tb && tb.forceRopeBreakNow()) {
                    out.add(tb.getChr().getName() + " takes a break on a rope (a safe rope above the mobs; else it"
                            + " rests on the ground)");
                }
            }
            return out.isEmpty() ? List.of("no grinder is fighting right now: try again in a few seconds") : out;
        }
        for (int id : ids) {
            Character bot = BotHelpers.getCharFromChannelStorage(id);
            if (bot == null) {
                continue;
            }
            switch (action) {
                case "die" -> {
                    BotDeaths.forceNextDeath(id, true);
                    BotVitals.drainHpPotionsTo(bot, 0);
                    BotVitals.hurt(bot, bot.getHp() + 1);
                    out.add(bot.getName() + (BotDeaths.isDead(id) ? " died" : " did not die (not grinding yet?)"));
                    BotDeaths.forceNextDeath(id, false);
                    return out; // one bot is enough
                }
                case "lowpots" -> {
                    int mark = BotHabits.of(id).restockBelow();
                    int gone = BotVitals.drainHpPotionsTo(bot, Math.max(0, mark - 1));
                    out.add(bot.getName() + ": " + gone + " HP pots used up, " + BotVitals.potionCount(bot, true)
                            + " left (mark " + (mark == 0 ? "none: runs dry" : String.valueOf(mark)) + ")");
                }
                case "hurt" -> {
                    int dmg = (int) (bot.getCurrentMaxHp() * 0.55);
                    BotVitals.hurt(bot, dmg);
                    out.add(bot.getName() + " takes " + dmg + ": HP now " + pct(bot.getHp(), bot.getCurrentMaxHp())
                            + "% (line " + Math.round(BotHabits.of(id).hpPotAt() * 100) + "%)");
                }
                default -> {
                    return List.of("steps: die, lowpots, hurt, rope");
                }
            }
        }
        return out;
    }

    private static long pct(int a, int b) {
        return Math.round(100.0 * a / Math.max(1, b));
    }
}

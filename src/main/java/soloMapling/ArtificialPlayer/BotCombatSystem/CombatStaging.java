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
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.worldevents.WorldEventSteer;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 1.5.0 rc3: GM and {@code !qa} (cb-1 .. cb-4, sp-1) staging for the human combat layer. Grinders are fresh
 * on-demand training bots held on the GM's map (WorldEventSteer, as the af-2 rival is), with starter potions and
 * ammo, so the GM watches them fight, buff, loot, drink, die and spread out. {@link #unstage} takes them away.
 */
public final class CombatStaging {

    /** The map each staged bot was put on, so a later step can put it back to work where the check watches it. */
    private static final Map<Integer, Integer> stagedMap = new ConcurrentHashMap<>();

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
        return stage(gm, jobs, List.of(), count, levelOffset, around, hold);
    }

    /**
     * As above; {@code levels} (parallel to {@code jobs}, 0 or missing = the job's check level) sets each job bot's
     * level (cb-2 stages its classes near the map's mob level, rc6 triage).
     */
    public static List<Integer> stage(Character gm, List<Job> jobs, List<Integer> levels, int count, int levelOffset,
                                      Point around, boolean hold) {
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
                int level = i < levels.size() && levels.get(i) != null && levels.get(i) > 0 ? levels.get(i)
                        : ClassTestCommand.defaultLevel(job);
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
            stagedMap.put(id, gm.getMapId());
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
            stagedMap.remove(id);
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
        MapleMap map = bot.getMap();
        List<MobAt> mobs = new ArrayList<>();
        if (map != null && p != null) {
            for (Monster m : map.getAllMonsters()) {
                Point mp = m.getPosition();
                if (m.isAlive() && mp != null) {
                    mobs.add(new MobAt(m.getName(), mp, GCMovement.mobBounds(m),
                            onOwnLedge(p, mp, (ax, ay, bx, by) -> GCMovement.onDifferentLedge(map, ax, ay, bx, by))));
                }
            }
        }
        return positionLine(p, left, mobs);
    }

    /** A live mob for a POSITION read-out: name, where, its body, whether it stands on the bot's own ledge. */
    record MobAt(String name, Point at, java.awt.Rectangle body, boolean ownLedge) {
    }

    interface LedgeSplit {
        boolean differentLedge(int ax, int ay, int bx, int by);
    }

    /** The vertical slack for "on the same ledge" (KS manners' own: a sloped ledge's height). */
    static final int OWN_LEDGE_DY = 90;

    static boolean onOwnLedge(Point bot, Point mob, LedgeSplit split) {
        return Math.abs(mob.y - bot.y) <= OWN_LEDGE_DY && !split.differentLedge(bot.x, bot.y, mob.x, mob.y);
    }

    /**
     * One POSITION line. rc6 auto-QA triage (t-cb-2, 25 of 52 positions bad): the nearest mob was taken from the whole
     * map, so a mob on the ledge above or below - one the bot could not hit and never faced - counted as "facing away"
     * or "inside its body". Only mobs on the bot's own ledge count now; with none there it says so.
     */
    static String positionLine(Point p, Boolean left, List<MobAt> mobs) {
        MobAt nearest = null;
        double best = Double.MAX_VALUE;
        for (MobAt m : mobs) {
            if (m.ownLedge() && p.distanceSq(m.at()) < best) {
                best = p.distanceSq(m.at());
                nearest = m;
            }
        }
        String mob = "no mob on its ledge";
        if (nearest != null) {
            int dx = nearest.at().x - p.x;
            boolean inside = nearest.body() != null && nearest.body().contains(p.x, p.y - 8);
            boolean facesIt = left == null || Math.abs(dx) <= 15 || (dx < 0) == left;
            mob = "nearest " + nearest.name() + " dx " + dx + (inside ? " INSIDE its body" : "")
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
                if (out.size() < 2 && CharacterStorage.getBotById(id)
                        instanceof TrainingBot tb && tb.forceRopeBreakNow()) {
                    out.add(tb.getChr().getName() + " takes a break on a rope (a safe rope above the mobs; else it"
                            + " rests on the ground)");
                }
            }
            return out.isEmpty() ? List.of("no grinder is fighting right now: try again in a few seconds") : out;
        }
        if ("die".equals(action)) { // its own pass: the death has to land on a bot that can still die
            return die(ids);
        }
        for (int id : ids) {
            Character bot = BotHelpers.getCharFromChannelStorage(id);
            if (bot == null) {
                continue;
            }
            switch (action) {
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

    /**
     * The die step's view of one staged bot; the real one wraps the bot in channel storage. Split out so the step's
     * order and precondition - it only ever hits a bot that is fighting - can be tested without a world.
     */
    interface DieSubject {

        String name();

        /** In {@code Phase.GRIND}: the state {@link BotDeaths} rolls a death for. */
        boolean grinding();

        /** Run the return-to-work trip the bot's own restock would end with; true once it is grinding again. */
        boolean backToGrind();

        /** Drain its HP pots and land the lethal hit; true when it actually died. */
        boolean killNow();
    }

    static List<String> die(List<Integer> ids) {
        return die(ids, CombatStaging::subject);
    }

    /**
     * QA t-cb-3, rc6 triage: {@code lowpots} runs inside the per-id loop, so it drains every staged bot and sends
     * them all to town - and a bot that left the grind is not death-eligible any more ({@link BotDeaths} rolls
     * nothing for it), so a forced lethal hit on it did nothing and the check failed "did not die". So the step
     * establishes its own precondition: put the subject back to work on its staging map first (the trip its own
     * restock ends with), and only hit it once it actually is in {@code Phase.GRIND}. Bots that are already fighting
     * are tried first, so a bot mid-grind is not moved for nothing.
     */
    static List<String> die(List<Integer> ids, Function<Integer, DieSubject> subject) {
        List<DieSubject> bots = new ArrayList<>();
        for (int id : ids) {
            DieSubject bot = subject.apply(id);
            if (bot != null) {
                bots.add(bot);
            }
        }
        if (bots.isEmpty()) {
            return List.of("no staged bot is here: nothing to kill");
        }
        for (DieSubject bot : grindersFirst(bots)) {
            if (!bot.grinding() && !bot.backToGrind()) {
                continue; // could not be brought back to work: not a bot the deaths system watches
            }
            if (!bot.grinding()) {
                continue; // the return trip did not settle it back into the grind after all
            }
            if (bot.killNow()) {
                return List.of(bot.name() + " died");
            }
        }
        StringBuilder names = new StringBuilder();
        for (DieSubject bot : bots) {
            names.append(names.length() == 0 ? "" : ", ").append(bot.name());
        }
        return List.of("staging: no staged bot could be returned to the grind and killed (" + names + ")");
    }

    /** The real subject: the staged bot in channel storage, put back to work through {@link TrainingBot}. */
    private static DieSubject subject(int id) {
        Character bot = BotHelpers.getCharFromChannelStorage(id);
        if (bot == null || !(CharacterStorage.getBotById(id) instanceof TrainingBot tb)) {
            return null;
        }
        return new DieSubject() {
            @Override
            public String name() {
                return bot.getName();
            }

            @Override
            public boolean grinding() {
                return tb.isGrinding();
            }

            @Override
            public boolean backToGrind() {
                Integer map = stagedMap.get(id);
                return map != null && tb.forceGrindOn(map);
            }

            @Override
            public boolean killNow() {
                BotDeaths.forceNextDeath(id, true);
                BotVitals.drainHpPotionsTo(bot, 0);
                BotVitals.hurt(bot, bot.getHp() + 1);
                boolean dead = BotDeaths.isDead(id);
                BotDeaths.forceNextDeath(id, false);
                return dead;
            }
        };
    }

    /** The staged bots, the ones that are fighting first: a bot already at work is never moved for nothing. */
    static <T extends DieSubject> List<T> grindersFirst(List<T> bots) {
        List<T> order = new ArrayList<>(bots);
        order.sort(java.util.Comparator.comparing(bot -> !bot.grinding())); // stable: staging order within a group
        return order;
    }

    private static long pct(int a, int b) {
        return Math.round(100.0 * a / Math.max(1, b));
    }
}

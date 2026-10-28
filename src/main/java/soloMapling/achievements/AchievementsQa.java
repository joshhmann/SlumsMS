package soloMapling.achievements;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Staging for {@code !qa run ac-1} and auto-QA t-ac-1: two goals driven through the real event path. */
public final class AchievementsQa {
    /** The goals the staging uses (Mano's kill, ten fishing catches) and their medals. */
    public static final String KILL_GOAL = "kill-mano", FISH_GOAL = "fish-10";
    public static final int MANO = 2220000;

    private AchievementsQa() {
    }

    /** Forgets both goals for the character and takes their medals out of the bag. */
    public static void clear(AchievementService s, Character me) throws SQLException {
        for (String id : new String[]{KILL_GOAL, FISH_GOAL}) {
            Goal g = s.catalog().get(id);
            s.reset(me.getId(), g);
            int n = me.getItemQuantity(g.medal(), true);
            if (n > 0) {
                InventoryManipulator.removeById(me.getClient(), InventoryType.EQUIP, g.medal(), n, false, false);
                if (me.getInventory(InventoryType.EQUIPPED).findById(g.medal()) != null) {
                    InventoryManipulator.removeById(me.getClient(), InventoryType.EQUIPPED, g.medal(), 1, false, false);
                }
            }
        }
    }

    /** One kill of Mano, through the same service the hooks feed. */
    public static List<Goal> kill(AchievementService s, Character me) {
        return s.handle(Subject.of(me), new Event(Trigger.MOB_KILL, MANO), true);
    }

    /** {@code n} kept catches. */
    public static List<Goal> fish(AchievementService s, Character me, int n) {
        List<Goal> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.addAll(s.handle(Subject.of(me), Event.of(Trigger.FISH), true));
        }
        return out;
    }

    /** The medal in the bag: its stat total (0 expected), or -1 when there is none. */
    public static int medalStats(Character me, int medal) {
        var item = me.getInventory(InventoryType.EQUIP).findById(medal);
        if (!(item instanceof Equip e)) {
            return -1;
        }
        return e.getStr() + e.getDex() + e.getInt() + e.getLuk() + e.getHp() + e.getMp() + e.getWatk() + e.getMatk()
                + e.getWdef() + e.getMdef() + e.getAcc() + e.getAvoid() + e.getSpeed() + e.getJump();
    }

    /**
     * The auto-QA check: an unrelated mob and an unfinished count change nothing; Mano's kill and the tenth catch each
     * hand over exactly one zero-stat medal and record it; repeating them hands over nothing more. Clears up after
     * itself. Returns the problems found (empty = pass) and appends what it saw to {@code evidence}.
     */
    public static List<String> selfCheck(AchievementService s, Character me, List<String> evidence) throws SQLException {
        List<String> bad = new ArrayList<>();
        Goal kill = s.catalog().get(KILL_GOAL);
        Goal fish = s.catalog().get(FISH_GOAL);
        if (kill == null || fish == null) {
            return List.of("achievements.yaml has no " + KILL_GOAL + " / " + FISH_GOAL);
        }
        clear(s, me);
        try {
            if (me.getInventory(InventoryType.EQUIP).isFull(2)) {
                return List.of("the pretend player's Equip inventory is full (needs room for two medals)");
            }
            if (!s.handle(Subject.of(me), new Event(Trigger.MOB_KILL, 100100), true).isEmpty()) {
                bad.add("a Snail kill completed a goal");
            }
            List<Goal> first = kill(s, me);
            List<Goal> again = kill(s, me);
            evidence.add("Mano kill -> " + first.stream().map(Goal::id).toList() + ", again -> "
                    + again.stream().map(Goal::id).toList() + "; medal " + kill.medal() + " x"
                    + me.getItemQuantity(kill.medal(), true) + ", stats " + medalStats(me, kill.medal()));
            if (first.size() != 1 || !again.isEmpty()) {
                bad.add("the Mano kill completed " + first.size() + " goals, the second " + again.size());
            }
            if (me.getItemQuantity(kill.medal(), true) != 1) {
                bad.add("expected exactly one " + kill.medal() + " in the bag");
            }
            if (medalStats(me, kill.medal()) != 0) {
                bad.add("the medal has stats");
            }
            if (rowState(s, me, kill) != AchievementStore.GRANTED) {
                bad.add("the Mano goal is not recorded as granted");
            }
            List<Goal> nine = fish(s, me, 9);
            AchievementStore.Row mid = s.row(me.getId(), fish.id());
            if (!nine.isEmpty() || mid == null || mid.progress() != 9) {
                bad.add("nine catches should leave the goal open at 9 (got " + (mid == null ? "no row" : mid.progress()) + ")");
            }
            List<Goal> tenth = fish(s, me, 1);
            fish(s, me, 5);
            evidence.add("10th catch -> " + tenth.stream().map(Goal::id).toList() + "; medal " + fish.medal() + " x"
                    + me.getItemQuantity(fish.medal(), true));
            if (tenth.size() != 1 || me.getItemQuantity(fish.medal(), true) != 1 || rowState(s, me, fish) != AchievementStore.GRANTED) {
                bad.add("the tenth catch did not hand over exactly one medal");
            }
            if (!AchievementsQaLines.listsGoal(s, me.getId(), kill)) {
                bad.add("@achievements does not list the earned goal");
            }
        } finally {
            clear(s, me);
        }
        if (me.getItemQuantity(kill.medal(), true) + me.getItemQuantity(fish.medal(), true) != 0
                || s.row(me.getId(), kill.id()) != null) {
            bad.add("cleanup left a medal or a row behind");
        }
        return bad;
    }

    private static int rowState(AchievementService s, Character me, Goal g) {
        AchievementStore.Row r = s.row(me.getId(), g.id());
        return r == null ? -1 : r.state();
    }
}

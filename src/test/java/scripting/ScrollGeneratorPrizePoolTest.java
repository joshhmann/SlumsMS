package scripting;

import client.Character;
import client.Job;
import client.inventory.InventoryType;
import org.junit.jupiter.api.Test;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import soloMapling.WzTestData;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b258: Maple TV's scroll generator ({@code scripts/npc/scroll_generator.js}) rolls a scroll tier triple and looks the
 * prize up by matching <em>both</em> the family the first number picked and the stat tier the second number picked.
 * Those two disagree for most rolls - the job weapon families (tier 1) hold PAD/MAD scrolls only, which are stat tier 1,
 * while the tier 2/3 families never give PAD/MAD - so no scroll matched and the player was told "there are no scrolls on
 * store right now" after placing supplies. Two of the triples ((1,3,3) and (1,3,1)) could not be repaired by the tier
 * shift either, and job 2001 threw instead of paying out, killing the dialog.
 * <p>
 * The pool is the real Item.wz scroll data handed to the script in place of the server's item provider (that one needs a
 * database), so the data tests skip where there is no WZ.
 */
class ScrollGeneratorPrizePoolTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** Item.wz's scroll data through the provider's two lookups, as the server loads them. */
    public static final class WzScrollData {
        private final Data consume = DataProviderFactory.getDataProvider(WZFiles.ITEM).getData("Consume/0204.img");
        private final Map<Integer, Map<String, Integer>> stats = new HashMap<>();
        private final Map<Integer, List<Integer>> reqs = new HashMap<>();

        private Data item(int itemId) {
            return consume.getChildByPath("0" + itemId);
        }

        public Map<String, Integer> getEquipStats(int itemId) {
            return stats.computeIfAbsent(itemId, id -> {
                Data data = item(id);
                Data info = data == null ? null : data.getChildByPath("info");
                if (info == null) {
                    return null;
                }
                Map<String, Integer> ret = new LinkedHashMap<>();
                for (Data child : info.getChildren()) {
                    if (child.getName().startsWith("inc")) {
                        ret.put(child.getName().substring(3), DataTool.getIntConvert(child));
                    }
                }
                ret.put("success", DataTool.getInt("success", info, 0));
                return ret;
            });
        }

        public List<Integer> getScrollReqs(int itemId) {
            return reqs.computeIfAbsent(itemId, id -> {
                Data data = item(id);
                data = data == null ? null : data.getChildByPath("req");
                List<Integer> ret = new ArrayList<>();
                if (data != null) {
                    for (Data req : data.getChildren()) {
                        ret.add(DataTool.getInt(req));
                    }
                }
                return ret;
            });
        }
    }

    /** A server whose Item.wz has no scroll in it. */
    public static final class NoScrollData {
        public Map<String, Integer> getEquipStats(int itemId) {
            return null;
        }

        public List<Integer> getScrollReqs(int itemId) {
            return List.of();
        }
    }

    /** A Maple TV dialog player: the generator reads the job, its style, Aran and the USE slot count. */
    public static final class FakePlayer {
        Job job = Job.BEGINNER;
        byte opt = (byte) 0x40;     // Character.getJobStyle(): 0x80 when STR beats DEX, which is what makes a brawler

        public Job getJob() {
            return job;
        }

        public Job getJobStyle() throws Exception {
            Method style = Character.class.getDeclaredMethod("getJobStyleInternal", int.class, byte.class);
            style.setAccessible(true);
            return (Job) style.invoke(null, job.getId(), opt);
        }

        public boolean isAran() {
            return job.getId() >= 2100 && job.getId() < 2200;
        }

        public FreeInventory getInventory(InventoryType type) {
            return new FreeInventory();
        }
    }

    /** A USE inventory with room: the generator refuses without a free slot. */
    public static final class FreeInventory {
        public int getNumFreeSlot() {
            return 10;
        }
    }

    public static final class FakeCm {
        final FakePlayer player = new FakePlayer();
        final List<Integer> gained = new ArrayList<>();
        String ok;
        int meso = 100_000_000;

        public FakePlayer getPlayer() {
            return player;
        }

        public int getMeso() {
            return meso;
        }

        public void gainMeso(int amount) {
            meso += amount;
        }

        public boolean haveItem(int id, int count) {
            return false;
        }

        public void gainItem(int id, int count) {
            gained.add(id);
        }

        public int getScrollGeneratorPrizesLeftToday() {
            return 5;
        }

        public boolean claimScrollGeneratorPrize() {
            return true;
        }

        public void sendOk(String text) {
            ok = text;
        }

        public void sendNext(String text) {
            ok = text;
        }
    }

    private static ScriptEngine load(FakeCm cm, Object scrollData) throws Exception {
        ScriptEngine engine = new Loader().load("npc/scroll_generator.js");
        assertNotNull(engine, "scroll_generator.js evaluates");
        engine.put("cm", cm);
        engine.put("testScrollData", scrollData);
        engine.eval("getItemInfoProvider = function () { return testScrollData; };");
        return engine;
    }

    private static int prizeFor(Invocable script, int typeTier, int subtypeTier, int successTier) throws Exception {
        Object prize = script.invokeFunction("getRandomScroll", new int[]{typeTier, subtypeTier, successTier});
        assertNotNull(prize, typeTier + "" + subtypeTier + "" + successTier + " has an answer");
        return ((Number) prize).intValue();
    }

    /** The scroll families a tier can draw from, straight from the script's own tables. */
    private static int[] families(ScriptEngine engine, int tier) throws Exception {
        return (int[]) engine.eval("Java.to(getScrollTypePool(" + tier + "), 'int[]')");
    }

    private static boolean fromFamily(int itemId, int[] families) {
        for (int base : families) {
            if (itemId >= base && itemId < base + 100) {
                return true;
            }
        }
        return false;
    }

    @Test
    void everyRollPaysOutAScrollFromTheFamilyItRolled() throws Exception {
        WzTestData.assumePresent("Item.wz");

        // every v83 job style: beginner, the warrior/magician/bowman/thief/pirate branches, Aran, and the job whose
        // branch digit points past every row of its table (2001)
        Job[] jobs = {Job.BEGINNER, Job.WARRIOR, Job.FIGHTER, Job.PAGE, Job.SPEARMAN, Job.MAGICIAN, Job.FP_WIZARD,
                Job.IL_ARCHMAGE, Job.CLERIC, Job.BOWMAN, Job.HUNTER, Job.CROSSBOWMAN, Job.THIEF, Job.ASSASSIN,
                Job.HERMIT, Job.BRAWLER, Job.GUNSLINGER, Job.ARAN1, Job.EVAN};
        FakeCm cm = new FakeCm();
        ScriptEngine engine = load(cm, new WzScrollData());
        Invocable script = (Invocable) engine;

        for (Job job : jobs) {
            cm.player.job = job;
            cm.player.opt = (byte) (job.getId() / 10 % 10 == 1 ? 0x80 : 0x40);   // the pirate branch that is a brawler
            int[] weaponFamilies = families(engine, 1);
            int[] tier2Families = families(engine, 2);
            int[] tier3Families = families(engine, 3);
            for (int typeTier = 1; typeTier <= 3; typeTier++) {
                for (int subtypeTier = 1; subtypeTier <= 3; subtypeTier++) {
                    for (int successTier = 1; successTier <= 3; successTier++) {
                        int prize = prizeFor(script, typeTier, subtypeTier, successTier);
                        String roll = job + " rolled " + typeTier + subtypeTier + successTier;
                        assertTrue(prize != -1, roll + " paid out nothing");
                        int[] family = switch (typeTier) {
                            case 1 -> weaponFamilies;
                            case 2 -> tier2Families;
                            default -> tier3Families;
                        };
                        assertTrue(fromFamily(prize, family), roll + " gave " + prize + ", outside the rolled family");
                    }
                }
            }
        }
    }

    @Test
    void aJobWithNoRowInTheTableGetsTheStylesTable() throws Exception {
        WzTestData.assumePresent("Item.wz");

        FakeCm cm = new FakeCm();
        cm.player.job = Job.EVAN;       // (2001 / 10) % 10 - 1 = -1: no row of its own
        Invocable script = (Invocable) load(cm, new WzScrollData());

        for (int successTier = 1; successTier <= 3; successTier++) {
            int prize = prizeFor(script, 1, 1, successTier);
            assertTrue(prize >= 2040000 && prize < 2050000, "a weapon scroll, not -1: " + prize);
        }
    }

    @Test
    void aServerWithNoScrollDataRefusesWithAMessage() throws Exception {
        FakeCm cm = new FakeCm();
        cm.player.job = Job.WARRIOR;
        Invocable script = (Invocable) load(cm, new NoScrollData());

        script.invokeFunction("generateRandomScroll");

        assertTrue(cm.ok != null && cm.ok.contains("no scrolls on store"), "a message, not a broken dialog: " + cm.ok);
        assertTrue(cm.gained.isEmpty(), "nothing handed out");
        assertEquals(100_000_000, cm.meso, "and nothing taken");
    }
}

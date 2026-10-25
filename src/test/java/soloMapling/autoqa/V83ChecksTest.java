package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import soloMapling.qa.QaScenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 69: every vb-* check is wired to a scenario of the same id, passes on the outcome lines its scenario writes,
 * and fails on the outcomes a broken feature would produce (no change, a refusal, a wrong response code). This is the
 * unit-level half of the "prove it can fail" requirement; the end-to-end half reverts the real fixes and is recorded
 * in docs/tickets/69-v83-basics-autoqa.md.
 */
class V83ChecksTest {

    private static World world(List<String> lines) {
        World w = new World();
        long t = System.currentTimeMillis();
        for (String line : lines) {
            w.lines.add(new AutoQaLine(t++, "qa", "VB", line.startsWith("VB ") ? line.substring(3) : line));
        }
        return w;
    }

    private static boolean allPass(Check check, List<String> lines) throws InterruptedException {
        CheckRun run = new CheckRun(check, world(lines));
        run.actor(1);
        List<String> failed = new ArrayList<>();
        for (Check.Expect e : check.expects()) {
            Check.Verdict v = e.eval(run);
            if (v.status() != Check.Status.PASS) {
                failed.add(e.describe() + ": " + v.evidence());
            }
        }
        assertTrue(failed.isEmpty(), () -> check.id() + " failed:\n" + String.join("\n", failed));
        return true;
    }

    private static boolean anyFails(Check check, List<String> lines) throws InterruptedException {
        CheckRun run = new CheckRun(check, world(lines));
        run.actor(1);
        for (Check.Expect e : check.expects()) {
            if (e.eval(run).status() != Check.Status.PASS) {
                return true;
            }
        }
        return false;
    }

    /**
     * vb-1's real-world proof: the salon filters styles with ItemConstants, and the 1.5.0 ranges rejected every
     * modern face (20000..30000, 50000..60000) and the VIP hair ids (30000..50000, 60000..70000), which is what made
     * sendStyle() answer "no options". Reverting 1fe365733 makes this fail; see the ticket for the captured text.
     */
    @Test
    void salonStyleIdsAreRecognisedAsCosmetics() {
        assertTrue(constants.inventory.ItemConstants.isFace(20015), "a face id (20015) is a cosmetic");
        assertTrue(constants.inventory.ItemConstants.isFace(51520), "a cash face coupon (51520) is a face item");
        assertTrue(constants.inventory.ItemConstants.isHair(30060), "a hair id (30060) is a cosmetic");
        assertTrue(constants.inventory.ItemConstants.isHair(33040), "a VIP hair id (33040) is a hair");
        assertTrue(!constants.inventory.ItemConstants.isFace(30060), "a hair id is not a face");
    }

    @Test
    void everyCheckHasAScenarioOfItsOwn() {
        for (String id : List.of("t-vb-1", "t-vb-2", "t-vb-3", "t-vb-4", "t-vb-5", "t-vb-6", "t-vb-7")) {
            assertNotNull(AutoQaCatalog.find(id), id + " is not in the catalogue");
            String scenario = id.substring(2);
            assertTrue(QaScenarios.all().stream().anyMatch(s -> s.id().equals(scenario)), id + " has no !qa scenario");
        }
    }

    @Test
    void salonPassesOnChangedStylesAndFailsOnUnchangedOrRefused() throws Exception {
        Check c = AutoQaCatalog.find("t-vb-1");
        List<String> good = new ArrayList<>();
        for (String step : List.of("hair", "haircolor", "face", "lens", "skin")) {
            good.add("VB STYLE " + step + " 30000 -> 30210 changed");
        }
        good.add("VB COUPON 5150001 1 -> 0");
        good.add("VB COUPON 5151001 1 -> 0");
        good.add("VB COUPON 5152001 1 -> 0");
        good.add("VB COUPON 5152013 1 -> 0");
        good.add("VB COUPON 5153000 1 -> 0");
        allPass(c, good);

        assertTrue(anyFails(c, good.subList(1, good.size())), "four of five styles changed must fail the check");
        List<String> unchanged = new ArrayList<>(good);
        unchanged.set(0, "VB STYLE hair 30000 -> 30000 UNCHANGED");
        assertTrue(anyFails(c, unchanged), "an unchanged style must fail the check");
        List<String> refused = new ArrayList<>(good);
        refused.add("NPC_REFUSAL NPC 1012103: Sorry, there are no options of cosmetics available for you here at the moment.");
        assertTrue(anyFails(c, refused), "the salon's 'no options' answer must fail the check");
        List<String> noCoupon = new ArrayList<>(good);
        noCoupon.set(5, "VB COUPON 5150001 1 -> 1");
        assertTrue(anyFails(c, noCoupon), "a coupon that was not taken must fail the check");
    }

    @Test
    void deletePassesOnlyOnStateZeroAndAGoneCharacter() throws Exception {
        Check c = AutoQaCatalog.find("t-vb-2");
        List<String> good = List.of("VB DELETE state=77 state 0 gone=true",
                "VB DELETE wrongpic answer=77 state 20 stillThere=true");
        allPass(c, good);
        assertTrue(anyFails(c, List.of("VB DELETE state=77 state 9 gone=false",
                "VB DELETE wrongpic answer=77 state 20 stillThere=true")), "a failed delete must fail the check");
        assertTrue(anyFails(c, List.of("VB DELETE state=77 state 0 gone=true",
                "VB DELETE wrongpic answer=77 state 0 stillThere=false")), "a wrong PIC that deleted the character must fail");
    }

    @Test
    void kpqPassesOnlyWhenClearedAndPaidOut() throws Exception {
        Check c = AutoQaCatalog.find("t-vb-3");
        List<String> good = List.of("VB KPQ instance started Kerning0", "VB KPQ cleared=true",
                "VB KPQ reward given=true items 10 -> 11 warped=true map=103000805");
        allPass(c, good);
        assertTrue(anyFails(c, List.of("VB KPQ instance started Kerning0", "VB KPQ cleared=false",
                "VB KPQ reward given=true items 10 -> 11 warped=true map=103000805")), "an uncleared PQ must fail");
        assertTrue(anyFails(c, List.of("VB KPQ instance started Kerning0", "VB KPQ cleared=true",
                "VB KPQ reward given=false items 10 -> 10 warped=true map=103000805")), "no reward must fail");
        assertTrue(anyFails(c, List.of("VB KPQ instance started Kerning0", "VB KPQ cleared=true",
                "VB KPQ reward given=true items 10 -> 11 warped=true map=103000800")), "no exit warp must fail");
    }

    @Test
    void shopPassesOnMovedMesoAndACouponRateAndFailsWhenNothingMoved() throws Exception {
        Check c = AutoQaCatalog.find("t-vb-5");
        List<String> good = List.of("VB SHOP buy item=2000000 price=25 meso 1000000 -> 999975 owned 0 -> 1",
                "VB SHOP sell item=2000000 meso 999975 -> 1000000 owned 1 -> 0",
                "VB CASH coupon=5211048 activeBefore=false activeNow=true",
                "VB CASH card=5211048 given=true rate 1 -> 2");
        allPass(c, good);
        assertTrue(anyFails(c, List.of("VB SHOP buy item=2000000 price=25 meso 1000000 -> 1000000 owned 0 -> 0",
                good.get(1), good.get(2), good.get(3))), "a purchase that moved nothing must fail");
        assertTrue(anyFails(c, List.of(good.get(0), "VB SHOP sell item=2000000 meso 999975 -> 999975 owned 1 -> 1",
                good.get(2), good.get(3))), "a sale that moved nothing must fail");
        assertTrue(anyFails(c, List.of(good.get(0), good.get(1), good.get(2),
                "VB CASH card=5211048 given=true rate 1 -> 1")), "a coupon that does not raise the rate must fail");
        assertTrue(anyFails(c, List.of(good.get(0), good.get(1), good.get(2), good.get(3),
                "NPC_REFUSAL NPC 1012004: the shop is closed for now.")), "a refused shop must fail");
    }

    @Test
    void jobPassesOnlyOnTheNewJobAndTheSpentPoint() throws Exception {
        Check c = AutoQaCatalog.find("t-vb-6");
        List<String> good = List.of("VB JOB advance 100 -> 110", "VB JOB sp spend skill=1100000 level 0 -> 1 sp 1 -> 0");
        allPass(c, good);
        assertTrue(anyFails(c, List.of("VB JOB advance 100 -> 100", good.get(1))), "no job change must fail");
        assertTrue(anyFails(c, List.of(good.get(0), "VB JOB sp spend skill=1100000 level 0 -> 0 sp 1 -> 1")),
                "an unspent skill point must fail");
    }

    @Test
    void storageDueyAndTradePassOnRealTransfersAndFailOnEmptyOnes() throws Exception {
        Check c = AutoQaCatalog.find("t-vb-7");
        List<String> good = List.of("VB STORAGE stored 4000000 bag 5 -> 4 store 0 -> 1",
                "VB STORAGE tookOut bagDelta=0 storeSize=0",
                "VB DUEY sent 4000001 to AutoQaTwo package=4",
                "VB DUEY claimed recipientDelta=1",
                "VB TRADE offered=true itemDelta=1 mesoDelta=5000");
        allPass(c, good);
        assertTrue(anyFails(c, List.of("VB STORAGE stored 4000000 bag 5 -> 5 store 0 -> 0",
                good.get(1), good.get(2), good.get(3), good.get(4))), "a storage store that moved nothing must fail");
        assertTrue(anyFails(c, List.of(good.get(0), good.get(1), good.get(2),
                "VB DUEY claimed recipientDelta=0", good.get(4))), "a Duey claim that delivered nothing must fail");
        assertTrue(anyFails(c, List.of(good.get(0), good.get(1), good.get(2), good.get(3),
                "VB TRADE offered=true itemDelta=0 mesoDelta=0")), "an empty trade must fail");
    }

    @Test
    void mountPassesOnlyOnTheFeeAndTheItemsAndOnTheBrokeRefusal() throws Exception {
        Check c = AutoQaCatalog.find("t-vb-4");
        List<String> good = List.of("VB MOUNT room started=true map=923010000 instance=true",
                "VB MOUNT quest6002 canComplete=true state=2 item1912000=1 skill1004=1",
                "VB MOUNT bottleRefused canComplete=false meso 1000000 -> 1000000 stillBottle=true",
                "VB MOUNT bottle canComplete=true meso 21000000 -> 1000000 fee=20000000 bottle 0 -> 1",
                "VB MOUNT perfume canComplete=true perfume 0 -> 1 bottleLeft=0");
        allPass(c, good);
        assertTrue(anyFails(c, List.of(good.get(0), good.get(1), good.get(2),
                "VB MOUNT bottle canComplete=true meso 21000000 -> 21000000 fee=0 bottle 0 -> 1",
                good.get(4))), "a bottle that charged no fee must fail");
        assertTrue(anyFails(c, List.of(good.get(0), "VB MOUNT quest6002 canComplete=false state=1 item1912000=0",
                good.get(2), good.get(3), good.get(4))), "an unfinished 6002 must fail");
        assertTrue(anyFails(c, List.of(good.get(0), good.get(1), good.get(2), good.get(3),
                "VB MOUNT perfume canComplete=false perfume 0 -> 0 bottleLeft=0")), "no perfume must fail");
    }

    private static final class StubActor implements AutoQaWorld.Actor {
        @Override public int id() { return 1; }
        @Override public String name() { return "AutoQaOne"; }
        @Override public int slot() { return 1; }
        @Override public void say(String text) { }
        @Override public void whisper(String target, String text) { }
        @Override public void multiChat(int type, String text) { }
        @Override public void partyInvite(String name) { }
        @Override public boolean acceptPartyInvite() { return false; }
        @Override public void guildInvite(String name) { }
        @Override public boolean acceptGuildInvite() { return false; }
        @Override public void buddyAdd(String name) { }
        @Override public List<AutoQaLine> heard(long sinceMs) { return List.of(); }
        @Override public int mapId() { return 100000000; }
        @Override public boolean isOnline() { return true; }
    }

    private static final class World implements AutoQaWorld {
        final List<AutoQaLine> lines = new ArrayList<>();

        @Override
        public String unavailableReason() {
            return null;
        }

        @Override
        public Actor actor(int slot) {
            return slot == 1 ? new StubActor() : null;
        }

        @Override
        public void logoutAll() {
        }

        @Override
        public List<AutoQaLine> qaLines(int gmId, long sinceMs) {
            return lines;
        }

        @Override
        public String nameOf(int characterId) {
            return "AutoQaOne";
        }

        @Override
        public long now() {
            return 1_000_000;
        }

        @Override
        public void sleep(long ms) {
        }

        @Override
        public List<String> probe(String what, Actor actor, String... args) {
            return List.of("FAIL no probe " + what);
        }

        @Override
        public LlmJudge judge() {
            return null;
        }

        @Override
        public Map<String, Object> lastHealth() {
            return Map.of();
        }
    }
}

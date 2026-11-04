package soloMapling.mechanics;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code gq-vassals}: Guild Quest stage 3 (row stage 5), the Fountain of the Wiseman, {@code 990000500}, with the two
 * larders {@code 990000501} / {@code 990000502} behind its doors and the arrival corridor {@code 990000401}. Row stage
 * {@code maps:} MUST list {@code 990000400, 401, 410, 420, 430, 431, 440, 501, 502} (see {@link #route}).
 *
 * <h3>What the room asks</h3>
 * {@code scripts/npc/9040006.js} (the fountain guardian). Only the event leader talks to it ({@code :27}
 * {@code cm.isEventLeader()}; others get "have your leader speak to me", :138-139):
 * <ul>
 *   <li>first talk, {@code stage3combo} null or {@code "reset"} ({@code :28-29}): {@code makeCombo()} ({@code :148-156},
 *       four digits 0-3, a plain number, so {@code 0030} is stored as {@code "30"}, :31), {@code stage3attempt=1}
 *       ({@code :33}), no feedback about the offerings;</li>
 *   <li>later talks ({@code :35-}): {@code getGroundItems()} ({@code :177-204}) wants exactly four items of ids
 *       {@code 4001027..4001030} on the whole map (:181) and one in each of {@code getArea(0..3)} (:191); the guess is
 *       {@code area0*1000 + area1*100 + area2*10 + area3} (:203) where an item's digit is {@code itemId - 4001027}
 *       (:192). A guess that is not null and not the combo costs an attempt (seven, :48; written :119) and spawns
 *       knights ({@code :115-116}); the items are NOT removed (only a right guess removes them, :45
 *       {@code removeGroundItems}); a null guess (wrong count, an empty area, :134-135) costs nothing. The right guess
 *       does {@code forceHitReactor(1)} on {@code watergate} (:41), writes {@code stage3clear = "true"} through
 *       {@code clearStage(3)} (:42, :9-13);</li>
 *   <li>after the seventh wrong guess the combo is {@code "reset"} ({@code :122}) and the next talk makes a new one.</li>
 * </ul>
 * The combo is a readable property, so the solver never guesses: it places exactly the four right items.
 *
 * <h3>Area order (how it was settled)</h3>
 * {@code Map9/990000500.img.xml:2254-2279} lists four {@code area} nodes named "1".."4" in file order; {@code
 * MapFactory.java:273-279} adds them with {@code addMapleArea} in that order (nothing sorts them), so {@code getArea(0)}
 * is node "1" = x -230..-139 (the thousands digit), {@code getArea(1)} = -103..22 (hundreds), {@code getArea(2)} =
 * 71..204 (tens), {@code getArea(3)} = 222..318 (ones). The solver reads {@code view.area(i)} (the live
 * {@code map.getAreas()}) and falls back to these WZ rectangles only when the view has none.
 * {@code Rectangle(x1, y1, x2-x1, y2-y1)} like the loader, and {@code contains} is half-open, so the drop point is the
 * middle of the width, {@link #FLOOR_Y} high ({@code 145}: the knights stand at y 144/147 on that floor, WZ life
 * {@code :290-420}; inside every rectangle's y range 116..169).
 *
 * <h3>Who does what</h3>
 * Slots 0..3 = areas 0..3 (the combo's digits left to right). Any live bot may bear a slot: the slot is
 * CLAIMED in {@code stageMemory} ({@code gq3:<combo>:slot<N>} -> character id; keyed by the combo so a reset starts
 * clean). A bot bears at most one slot per digit (its own drop of an item id is found with {@code dropBy}, which can
 * only tell the newest one), and the leader bears one only when every other live bot already bears one of that digit.
 * The bearer fetches its own item and puts it down itself - no hand-overs unless a source runs dry:
 * <ul>
 *   <li>digit 0 / 1: Medal of Valor {@code 4001027} from Black Knights {@code 9300022}, Scroll of Wisdom
 *       {@code 4001028} from Myst Knights {@code 9300023}: four of each stand on 500 ({@code :290-398}, mobTime -1: no
 *       respawn); drop rows {@code 9300022 -> 4001027}, {@code 9300023 -> 4001028} chance 999999
 *       ({@code 152-drop-data.sql:20689-20690}) are SEED data, <b>live DB unverified</b>;</li>
 *   <li>digit 2 / 3: Spoiled Food {@code 4001029} from reactors {@code 9202003} on 501, Jr. Necki Drink {@code 4001030}
 *       from reactors {@code 9202004} on 502 (four of each, {@code reactorTime -1}); {@code 131-reactordrops-data.sql:68-69}
 *       (<b>live DB unverified</b>); {@code scripts/reactor/9202003.js}/{@code 9202004.js} only call {@code
 *       rm.dropItems()}. The reactor takes FIVE hits to drop, not four: its WZ has states 0-3 with a hit event (-> 1,
 *       2, 3, 4) and state 4 only a picture, which {@code ReactorFactory.java:112-113} loads as inert (999), and a
 *       hit on an inert state falls to {@code Reactor.java:304-309} {@code state++; act()}. The solver does not rely on
 *       the count: it hits one claimed reactor with {@code HitReactorByOid} until the drop is on the floor of that
 *       room (or the reactor reaches state {@link #SPENT_STATE}), loots, walks to 500 and places. The 16 reactors
 *       {@code 9202010}/{@code 9202011} have no script and are never hit.</li>
 * </ul>
 * Strays: the script counts EVERY item of those four ids on the map, wherever it lies, so anything that is not a
 * bearer's own right placement must be picked up again: a knight's drop, a leftover of an old combo after "reset", an
 * item dropped for hand-over, a misplaced drop. A member on 500 loots what its floor scan finds above the number of
 * its own correct placements, except that a free-for-all drop is left to the bearer who still needs that kind
 * ({@code strayToLoot}). The leader talks the second time only when all four slots are verified placed ({@code
 * dropBy} position inside the area) and no member sees a stray.
 *
 * <h3>Ownership (why the checks look like this)</h3>
 * A bot's drop is owned for ever by the dropper ({@code BotHoldings.payOut} -> {@code setPermanentOwner(true)},
 * {@code PassHandover.mayTake}): another bot never sees it, so "four on the floor" is the union of what each member
 * sees ({@code floorItemsOn(500, id, member)}), and a placement is verified through its dropper
 * ({@code dropBy}). A departed bot's placed item still counts as filled (its position is queried by id) and can never be
 * picked up by anyone else.
 *
 * <h3>Frame</h3>
 * {@link #route}: members still on the previous stage's rooms walk out ({@code 410/420/430/440 out00 -> 400}, {@code 431
 * out00 -> 430}, {@code 400 next00} scripted door {@code speargate_open} -> {@code 401}, {@code 401 next01} -> 500;
 * portal names/targets read from each map's WZ portal block). The exit is the {@code watergate} reactor + portal {@code
 * next00} (script {@code watergate_open}) on 500, the next stage's concern; the row sets no {@code nextPortal}.
 * Every Guild Quest map from 300 on takes 30000 HP a tick without the Protector Rock: {@link GqProtection} comes first.
 *
 * <h3>UNPROVEN</h3>
 * Navigation up the ropes of 500 (x 519 and x -386, y -163..115/136, {@code ladderRope}) to the NPC at (45, -135) and the
 * doors {@code in00} (629,-169) / {@code in01} (-568,-167), and inside 501/502 to reactors at y -341..-157; the mob and
 * reactor drop rows (live DB); whether four Medals/Scrolls are available when a knight dies far from the party (the
 * knight's drop lands where it dies and is looted wherever it is, a missing one produces a Wait, not a guess); where
 * {@code calcDropPos} puts a drop made at (x, 145) (the verification would show a miss as an unfilled slot and the
 * placer would pick it up and drop it again); the Graal behaviour of {@code eim.setProperty("stage3combo", Number)}
 * (read back here as a decimal string, "30" or "30.0" both parse); that the knights {@code 9300036/37} (a wrong guess)
 * drop nothing (their drop table is empty in the seed; never fought for items here); the five-hit count above.
 */
public final class GqVassalsSolver implements StageSolver {

    public static final String ID = "gq-vassals";

    static final int FOUNTAIN_MAP = 990_000_500;
    static final int FOOD_ROOM = 990_000_501;
    static final int DRINK_ROOM = 990_000_502;
    static final int FOUNTAIN_NPC = 9_040_006;
    /** Medal of Valor; digit d is item {@code FIRST_ITEM + d}. */
    static final int FIRST_ITEM = 4_001_027;
    static final String[] ITEM_NAME = {"Medal of Valor", "Scroll of Wisdom", "Spoiled Food", "Jr. Necki Drink"};
    static final int[] KNIGHT = {9_300_022, 9_300_023};
    static final String[] KNIGHT_NAME = {"Black Knight", "Myst Knight"};
    /** The reactor that drops digit 2 / 3 (index = digit). */
    static final int[] REACTOR = {0, 0, 9_202_003, 9_202_004};
    /** A reactor whose state reached this has had its fifth hit: nothing more to get. */
    static final int SPENT_STATE = 5;
    static final int FLOOR_Y = 145;
    private static final String PROPERTY_COMBO = "stage3combo";
    private static final String PROPERTY_ATTEMPT = "stage3attempt";
    /** {@code Map9/990000500.img.xml:2254-2279}: x1, y1, x2, y2 of area nodes 1..4 in file order. */
    private static final int[][] WZ_AREAS = {{-230, 120, -139, 167}, {-103, 116, 22, 167}, {71, 117, 204, 169},
            {222, 128, 318, 168}};

    @Override
    public String id() {
        return ID;
    }

    // ---------------------------------------------------------------- combo and area arithmetic

    /** The combo as the property holds it and as four digits, area 0 (the thousands) first; digits null = unreadable. */
    record Combo(String raw, int[] digits) {
        boolean valid() {
            return digits != null;
        }
    }

    /** Null while the combo is not made (property absent or {@code "reset"}). */
    static Combo readCombo(StageView view) {
        String raw = view.eventProperty(PROPERTY_COMBO).map(String::trim).orElse("");
        if (raw.isEmpty() || raw.equalsIgnoreCase("reset")) {
            return null;
        }
        return new Combo(raw, digitsOf(raw));
    }

    /**
     * {@code "30"} -> {0, 0, 3, 0}: the property is a number without leading zeros, so it is left-padded to four
     * digits; null when it is not a number, or has a digit above 3 or more than four digits.
     */
    static int[] digitsOf(String raw) {
        double v;
        try {
            v = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (Double.isNaN(v) || v != Math.rint(v) || v < 0 || v > 9999) {
            return null;
        }
        int n = (int) v;
        int[] d = new int[4];
        for (int slot = 0; slot < 4; slot++) {
            int pow = slot == 0 ? 1000 : slot == 1 ? 100 : slot == 2 ? 10 : 1;
            d[slot] = (n / pow) % 10;
            if (d[slot] > 3) {
                return null;
            }
        }
        return d;
    }

    /** The item that stands for a digit. */
    static int itemFor(int digit) {
        return FIRST_ITEM + digit;
    }

    /** {@code getGroundItems}' formula (9040006.js:224) from the item id lying in each of area 0..3. */
    static int comboOf(int[] itemIdInArea) {
        return (itemIdInArea[0] - FIRST_ITEM) * 1000 + (itemIdInArea[1] - FIRST_ITEM) * 100
                + (itemIdInArea[2] - FIRST_ITEM) * 10 + (itemIdInArea[3] - FIRST_ITEM);
    }

    /** The area the script reads as {@code getArea(slot)}: the view's, else the WZ rectangle. */
    static Rectangle area(StageView view, int slot) {
        Rectangle r = view.area(slot);
        if (r != null) {
            return r;
        }
        int[] a = WZ_AREAS[slot];
        return new Rectangle(a[0], a[1], a[2] - a[0], a[3] - a[1]);
    }

    /** Where to put the item for a slot: the middle of the area's width, on the floor. */
    static int[] placePoint(Rectangle r) {
        int y = Math.max(r.y + 1, Math.min(FLOOR_Y, r.y + r.height - 1));
        return new int[]{r.x + r.width / 2, y};
    }

    // ---------------------------------------------------------------- claims

    static String claimKey(String comboRaw, int slot) {
        return "gq3:" + comboRaw + ":slot" + slot;
    }

    private static String reactorKey(String comboRaw, int room, int objectId) {
        return "gq3:" + comboRaw + ":reactor" + room + ":" + objectId;
    }

    private static boolean liveBot(StageView view, int id) {
        for (RunMember m : view.members()) {
            if (m.characterId() == id && m.bot() && view.alive(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inPlace(StageView view, Combo c, int slot, int bearer) {
        Optional<StageView.FloorDrop> drop = view.dropBy(bearer, itemFor(c.digits()[slot]));
        return drop.isPresent() && area(view, slot).contains(drop.get().x(), drop.get().y());
    }

    /**
     * The bearer of a slot: a live bot that claimed it, or a departed one whose item still lies in the area; null
     * when the slot is free (an unclaimed one, or a claimant who is gone with nothing placed - its claim is dropped).
     */
    private static Integer bearer(StageView view, Combo c, int slot) {
        String key = claimKey(c.raw(), slot);
        Map<String, Object> mem = view.stageMemory();
        Object o = mem.get(key);
        if (!(o instanceof Integer who)) {
            return null;
        }
        if (liveBot(view, who) || inPlace(view, c, slot, who)) {
            return who;
        }
        mem.remove(key, o);
        return null;
    }

    /** Slot verified placed: its bearer's newest drop of the item lies inside the area. */
    private static boolean filled(StageView view, Combo c, int slot) {
        Integer who = bearer(view, c, slot);
        return who != null && inPlace(view, c, slot, who);
    }

    private static List<Integer> slotsOf(StageView view, Combo c, int id) {
        List<Integer> out = new ArrayList<>();
        for (int s = 0; s < 4; s++) {
            Integer who = bearer(view, c, s);
            if (who != null && who == id) {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean bearsDigit(StageView view, Combo c, int id, int digit) {
        for (int s : slotsOf(view, c, id)) {
            if (c.digits()[s] == digit) {
                return true;
            }
        }
        return false;
    }

    /** A member with nothing left to carry claims one more free slot. At most one claim per call. */
    private static void claimNext(StageView view, Combo c, RunMember member) {
        int id = member.characterId();
        if (!member.bot()) {
            return;
        }
        List<Integer> mine = slotsOf(view, c, id);
        for (int s : mine) {
            if (!filled(view, c, s)) {
                return;
            }
        }
        for (int s = 0; s < 4; s++) {
            if (bearer(view, c, s) != null) {
                continue;
            }
            int digit = c.digits()[s];
            if (bearsDigit(view, c, id, digit)) {
                continue;
            }
            if (member.leader() && othersCouldBear(view, c, id, digit)) {
                continue;
            }
            view.stageMemory().putIfAbsent(claimKey(c.raw(), s), id);
            return;
        }
    }

    /** Some other live bot does not bear this digit yet, so it should take the slot, not the leader. */
    private static boolean othersCouldBear(StageView view, Combo c, int leaderId, int digit) {
        for (RunMember m : view.members()) {
            int other = m.characterId();
            if (other != leaderId && m.bot() && !m.leader() && view.alive(other) && !bearsDigit(view, c, other, digit)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the floor

    /** Own correct placements of this item (0 or 1: one slot per digit). */
    private static int wantedInPlace(StageView view, Combo c, int id, int itemId) {
        if (c == null || !c.valid()) {
            return 0;
        }
        int n = 0;
        for (int s : slotsOf(view, c, id)) {
            if (itemFor(c.digits()[s]) == itemId && filled(view, c, s)) {
                n++;
            }
        }
        return n;
    }

    /** Items of this kind this member sees on 500 above what it correctly placed itself. */
    private static int excess(StageView view, Combo c, int id, int itemId) {
        return view.floorItemsOn(FOUNTAIN_MAP, itemId, id) - wantedInPlace(view, c, id, itemId);
    }

    /** This member bears an unfilled slot of this digit and holds none of its item yet. */
    private static boolean needs(StageView view, Combo c, int id, int digit) {
        if (c == null || !c.valid() || !liveBot(view, id) || view.itemCount(id, itemFor(digit)) > 0) {
            return false;
        }
        for (int s : slotsOf(view, c, id)) {
            if (c.digits()[s] == digit && !filled(view, c, s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The item kind this member should pick up from 500 now, or 0. A free-for-all drop (a knight's) is every member's
     * to see, so the bearer who still needs that kind takes it first and the others leave it; whatever only this member
     * sees (its own old placement, a hand-over made for it) is more than any needy bearer sees, so it takes that.
     */
    private static int strayToLoot(StageView view, Combo c, int id) {
        for (int k = 0; k < 4; k++) {
            int item = itemFor(k);
            int mine = excess(view, c, id, item);
            if (mine <= 0) {
                continue;
            }
            if (needs(view, c, id, k)) {
                return item;
            }
            int others = 0;
            for (RunMember m : view.members()) {
                int n = m.characterId();
                if (n != id && needs(view, c, n, k)) {
                    others = Math.max(others, excess(view, c, n, item));
                }
            }
            if (mine > others) {
                return item;
            }
        }
        return 0;
    }

    private static boolean anyStray(StageView view, Combo c) {
        for (RunMember m : view.members()) {
            for (int k = 0; k < 4; k++) {
                if (excess(view, c, m.characterId(), itemFor(k)) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** All four slots verified placed and no member sees another item of the four kinds: what the script accepts. */
    static boolean ready(StageView view, Combo c) {
        if (c == null || !c.valid()) {
            return false;
        }
        for (int s = 0; s < 4; s++) {
            if (!filled(view, c, s)) {
                return false;
            }
        }
        return !anyStray(view, c);
    }

    private static int attempt(StageView view) {
        try {
            return (int) Double.parseDouble(view.eventProperty(PROPERTY_ATTEMPT).orElse("1").trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // ---------------------------------------------------------------- walking

    /** One step toward {@code to} between 500 and its two larders (null when already there). */
    private static SolverStep walk(int from, int to) {
        if (from == to) {
            return null;
        }
        if (from == FOUNTAIN_MAP) {
            // 500 -> 501 by in00 at (629,-169), -> 502 by in01 at (-568,-167): Map9/990000500.img.xml:2679-2710
            return new SolverStep.EnterPortal(to == FOOD_ROOM ? "in00" : "in01");
        }
        // 501 / 502 out00 -> 500 (990000501.img.xml, 990000502.img.xml portal block); another larder is via 500
        return new SolverStep.EnterPortal("out00");
    }

    /**
     * Members still on the previous stage's rooms, or the corridor, walk in (null on the stage's own three maps):
     * {@code 410/420/440/430 out00} -> 400, {@code 431 out00} -> 430, {@code 400 next00} (pt 8, script
     * {@code speargate_open}, needs the spear stage cleared) -> 401, {@code 401 next01} (pt 1) -> 500.
     */
    static SolverStep route(int map) {
        return switch (map) {
            case FOUNTAIN_MAP, FOOD_ROOM, DRINK_ROOM -> null;
            case 990_000_401 -> new SolverStep.EnterPortal("next01");
            case 990_000_400 -> new SolverStep.ScriptedPortal("next00");
            case 990_000_410, 990_000_420, 990_000_430, 990_000_431, 990_000_440 -> new SolverStep.EnterPortal("out00");
            default -> new SolverStep.Wait(2_000, "map " + map + " is not a Fountain stage map: the orchestrator walks "
                    + "this member in");
        };
    }

    // ---------------------------------------------------------------- the steps

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        SolverStep protection = GqProtection.step(view, member);
        if (protection != null) {
            return protection;
        }
        int id = member.characterId();
        int map = view.mapOf(id);
        SolverStep routed = route(map);
        if (routed != null) {
            return routed;
        }
        Combo combo = readCombo(view);
        if (combo != null && !combo.valid()) {
            return new SolverStep.Wait(2_000, PROPERTY_COMBO + " is \"" + combo.raw() + "\", not four digits of 0-3 "
                    + "(9040006.js:176-183 makes it from Math.random() * 4): the vassals' wish cannot be read");
        }
        if (combo == null) {
            return noCombo(view, member, map);
        }
        claimNext(view, combo, member);
        if (map == FOUNTAIN_MAP) {
            int stray = strayToLoot(view, combo, id);
            if (stray != 0) {
                return new SolverStep.Loot(stray);
            }
        }
        SolverStep handOver = handOver(view, combo, member, map);
        if (handOver != null) {
            return handOver;
        }
        for (int s : slotsOf(view, combo, id)) {
            if (!filled(view, combo, s)) {
                return work(view, combo, member, map, s);
            }
        }
        if (member.leader()) {
            return leaderDuty(view, combo, map);
        }
        SolverStep back = walk(map, FOUNTAIN_MAP);
        if (back != null) {
            return back;
        }
        return new SolverStep.Wait(1_500, "offering down, waiting for the other vassals' offerings (" + filledCount(view,
                combo) + "/4 in place)");
    }

    /** Before the combo exists: the leader's first talk; everyone else waits on 500 (and clears the floor). */
    private SolverStep noCombo(StageView view, RunMember member, int map) {
        SolverStep back = walk(map, FOUNTAIN_MAP);
        if (back != null) {
            return back;
        }
        int stray = strayToLoot(view, null, member.characterId());
        if (stray != 0) {
            return new SolverStep.Loot(stray);
        }
        if (member.leader()) {
            return new SolverStep.TalkNpc(FOUNTAIN_NPC, 0, 0);
        }
        return new SolverStep.Wait(1_500, "waiting for the leader to talk to the fountain guardian " + FOUNTAIN_NPC
                + " once: it makes " + PROPERTY_COMBO + " (9040006.js:40-44), and the offerings depend on it");
    }

    private static int filledCount(StageView view, Combo c) {
        int n = 0;
        for (int s = 0; s < 4; s++) {
            if (filled(view, c, s)) {
                n++;
            }
        }
        return n;
    }

    private SolverStep leaderDuty(StageView view, Combo combo, int map) {
        SolverStep back = walk(map, FOUNTAIN_MAP);
        if (back != null) {
            return back;
        }
        if (!ready(view, combo)) {
            return new SolverStep.Wait(1_000, "waiting for the four offerings (" + describeMissing(view, combo) + ")");
        }
        int tried = attempt(view);
        if (tried > 1) {
            return new SolverStep.Wait(5_000, "the vassals refused attempt " + (tried - 1) + " although all four "
                    + "offerings sit where the solver computed them (area 0..3 = digit 1000s..1s, item id - 4001027, "
                    + "9040006.js:198-226): not talking again, every wrong guess costs one of seven attempts");
        }
        return new SolverStep.TalkNpc(FOUNTAIN_NPC, 0, 0);
    }

    private static String describeMissing(StageView view, Combo c) {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < 4; s++) {
            if (filled(view, c, s)) {
                continue;
            }
            Integer who = bearer(view, c, s);
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("area ").append(s).append(" needs ").append(ITEM_NAME[c.digits()[s]]).append(' ')
                    .append(itemFor(c.digits()[s])).append(who == null ? ", no bearer yet" : ", bearer " + who);
        }
        if (sb.length() == 0) {
            sb.append("all four placed, a stray item still lies on the floor");
        }
        return sb.toString();
    }

    /** One slot's work: place when holding the item, otherwise fetch it from its source. */
    private SolverStep work(StageView view, Combo c, RunMember member, int map, int slot) {
        int id = member.characterId();
        int digit = c.digits()[slot];
        int item = itemFor(digit);
        if (view.itemCount(id, item) >= 1) {
            SolverStep back = walk(map, FOUNTAIN_MAP);
            if (back != null) {
                return back;
            }
            int[] at = placePoint(area(view, slot));
            return new SolverStep.PlaceItem(item, 1, "", at[0], at[1], ITEM_NAME[digit] + " for the vassal of area "
                    + slot + " (" + describeDigit(digit) + ")");
        }
        return digit <= 1 ? fromKnights(view, id, map, digit, item) : fromReactors(view, c, id, map, digit, item);
    }

    private static String describeDigit(int digit) {
        return "digit " + digit + " of the wish";
    }

    private static SolverStep fromKnights(StageView view, int id, int map, int digit, int item) {
        SolverStep there = walk(map, FOUNTAIN_MAP);
        if (there != null) {
            return there;
        }
        if (view.mobsAliveOn(FOUNTAIN_MAP, KNIGHT[digit]) > 0) {
            return new SolverStep.Fight(KNIGHT[digit], item, 1);
        }
        return new SolverStep.Wait(2_000, sourceMissing(digit, item));
    }

    private static SolverStep fromReactors(StageView view, Combo c, int id, int map, int digit, int item) {
        int room = digit == 2 ? FOOD_ROOM : DRINK_ROOM;
        SolverStep there = map == room ? null : (map == FOUNTAIN_MAP ? walk(map, room) : walk(map, FOUNTAIN_MAP));
        if (there != null) {
            return there;
        }
        if (view.floorItemsOn(room, item, id) > 0) {
            return new SolverStep.Loot(item);
        }
        StageView.ReactorPlacement pick = null;
        List<StageView.ReactorPlacement> all = view.reactorsOn(room);
        for (StageView.ReactorPlacement r : all) {
            if (r.reactorId() == REACTOR[digit] && r.state() < SPENT_STATE
                    && Integer.valueOf(id).equals(view.stageMemory().get(reactorKey(c.raw(), room, r.objectId())))) {
                pick = r;
                break;
            }
        }
        for (StageView.ReactorPlacement r : all) {
            if (pick != null) {
                break;
            }
            if (r.reactorId() == REACTOR[digit] && r.state() < SPENT_STATE) {
                String key = reactorKey(c.raw(), room, r.objectId());
                Object held = view.stageMemory().get(key);
                if (held instanceof Integer who && !liveBot(view, who)) {
                    view.stageMemory().remove(key, held);
                }
                if (view.stageMemory().putIfAbsent(key, id) == null) {
                    pick = r;
                }
            }
        }
        if (pick != null) {
            return new SolverStep.HitReactorByOid(pick.objectId());
        }
        return new SolverStep.Wait(2_000, sourceMissing(digit, item));
    }

    private static String sourceMissing(int digit, int item) {
        if (digit <= 1) {
            return "no " + KNIGHT_NAME[digit] + " (" + KNIGHT[digit] + ") is alive on " + FOUNTAIN_MAP + " and no "
                    + ITEM_NAME[digit] + " (" + item + ") lies there for this member: its only source is that knight's "
                    + "drop (" + KNIGHT[digit] + " -> " + item + ", seed data, live DB unverified)";
        }
        int room = digit == 2 ? FOOD_ROOM : DRINK_ROOM;
        return "no unspent free reactor " + REACTOR[digit] + " is left on " + room + " and no " + ITEM_NAME[digit] + " ("
                + item + ") lies there for this member: its only source is that reactor's drop (" + REACTOR[digit]
                + " -> " + item + ", seed reactordrops row, live DB unverified)";
    }

    /**
     * A bot holding more of a kind than it needs gives one to a bearer whose source ran dry (no knight alive, no unspent
     * reactor, nothing on its floor): the only way a kill or a hit that fell to the wrong bot is not lost.
     */
    private static SolverStep handOver(StageView view, Combo c, RunMember member, int map) {
        int id = member.characterId();
        for (int k = 0; k < 4; k++) {
            int item = itemFor(k);
            int bag = view.itemCount(id, item);
            if (bag <= 0) {
                continue;
            }
            int need = 0;
            for (int s : slotsOf(view, c, id)) {
                if (c.digits()[s] == k && !filled(view, c, s)) {
                    need = 1;
                }
            }
            if (bag - need <= 0) {
                continue;
            }
            for (RunMember other : view.members()) {
                int n = other.characterId();
                if (n == id || !other.bot() || !liveBot(view, n) || view.itemCount(n, item) > 0) {
                    continue;
                }
                boolean needy = false;
                for (int s : slotsOf(view, c, n)) {
                    if (c.digits()[s] == k && !filled(view, c, s)) {
                        needy = true;
                    }
                }
                if (!needy || !sourceDry(view, k, n)) {
                    continue;
                }
                int there = view.mapOf(n);
                if (there != FOUNTAIN_MAP && there != FOOD_ROOM && there != DRINK_ROOM) {
                    continue;
                }
                if (there != map) {
                    return map == FOUNTAIN_MAP ? walk(map, there) : walk(map, FOUNTAIN_MAP);
                }
                return new SolverStep.DropFor(item, 1, n);
            }
        }
        return null;
    }

    private static boolean sourceDry(StageView view, int digit, int bearerId) {
        int item = itemFor(digit);
        if (digit <= 1) {
            return view.mobsAliveOn(FOUNTAIN_MAP, KNIGHT[digit]) == 0
                    && view.floorItemsOn(FOUNTAIN_MAP, item, bearerId) == 0;
        }
        int room = digit == 2 ? FOOD_ROOM : DRINK_ROOM;
        for (StageView.ReactorPlacement r : view.reactorsOn(room)) {
            if (r.reactorId() == REACTOR[digit] && r.state() < SPENT_STATE) {
                return false;
            }
        }
        return view.floorItemsOn(room, item, bearerId) == 0;
    }

    // ---------------------------------------------------------------- a human leader

    @Override
    public Optional<String> leaderPrompt(StageView view, RunMember leader) {
        if (view.cleared()) {
            return Optional.empty();
        }
        Combo c = readCombo(view);
        int id = leader.characterId();
        if (c == null) {
            return Optional.of("@" + id + " talk to the fountain guardian once (9040006): it sets the vassals' wish "
                    + "and then we bring the offerings");
        }
        if (c.valid() && ready(view, c) && attempt(view) <= 1) {
            return Optional.of("@" + id + " all four offerings are down in front of the vassals, talk to the fountain "
                    + "guardian (9040006) again");
        }
        return Optional.empty();
    }
}

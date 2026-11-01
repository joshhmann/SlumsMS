package soloMapling.mechanics;

import client.BotClient;
import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapItem;
import soloMapling.mechanics.MechanicsModel.Stage;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * t-mx-2 (TEST 2026-10-31, cap 1 and cap 4): KPQ stage 5 stalled with the leader at Cloto on {@code passes (9/10)}
 * while three collector bots repeated "monsters cleared, passes handed over" until the stall guard stopped the run.
 * Stage 5's monsters drop their passes as monster drops, owned by the bot that killed them ({@code dropType 1}, a
 * party drop). The floor scan only honoured {@link PassHandover#mayTake}, which refuses any drop another member owns,
 * so a pass a fellow member's kill left was invisible to the leader and to every other member: once the last monster
 * died nobody could pick it up. The scan now follows the game's loot rule for a monster's drop
 * ({@link QuestionCouponsSolver#mayTakeMobCoupon}), and a collector fetches a stray pass before it parks by the leader.
 * A pass a bot dropped <em>for</em> the leader is still nobody else's to take.
 */
class KpqStrayPassTest {

    static final int PASS = KpqFullRunTest.PASS;
    static final int LEADER = 31, A = 32, B = 33, C = 34;

    /** KPQ stage 5 (103000804) with four bots; the floor rule is the production one. */
    static final class View implements StageView {
        final Stage stage = KpqFullRunTest.kpq().stage(5).orElseThrow();
        final Map<Integer, int[]> pos = new HashMap<>();
        final Map<Integer, Integer> passes = new HashMap<>();
        final List<MapItem> floor = new ArrayList<>();
        int mobs;

        @Override
        public Stage stage() {
            return stage;
        }

        @Override
        public List<RunMember> members() {
            return List.of(new RunMember(LEADER, true, true, MechanicsPersona.PERFECT),
                    KpqFullRunTest.bot(A, false, MechanicsPersona.PERFECT),
                    KpqFullRunTest.bot(B, false, MechanicsPersona.PERFECT),
                    KpqFullRunTest.bot(C, false, MechanicsPersona.PERFECT));
        }

        @Override
        public String roleOf(int id) {
            return id == LEADER ? "leader" : "collector";
        }

        @Override
        public OptionalInt answerFor(int id) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int id, int itemId) {
            return itemId == PASS ? passes.getOrDefault(id, 0) : 0;
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.empty();
        }

        /** Everyone stands near Cloto unless a test moves them. */
        @Override
        public int[] positionOf(int id) {
            return pos.getOrDefault(id, new int[]{265, -2715}).clone();
        }

        @Override
        public int liveMobs() {
            return mobs;
        }

        @Override
        public int floorItems(int itemId, int forId) {
            return (int) floor.stream().filter(mi -> mi.getItemId() == itemId
                    && LiveStageView.mayTake(this, forId, mi)).count();
        }

        @Override
        public Optional<int[]> npcPosition(int npcId) {
            return Optional.of(new int[]{305, -2715});
        }
    }

    static Character bot(int id) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(id);
        return c;
    }

    /** A pass a monster left where it fell, {@code owner} having killed it (a party drop, as the game makes it). */
    static MapItem monsterPass(Character owner) {
        MapItem mi = new MapItem(new Item(PASS, (short) 0, (short) 1), new Point(180, -2715),
                mock(Monster.class), owner, owner.getClient(), (byte) 1, false);
        mi.setDropTime(System.currentTimeMillis());
        return mi;
    }

    /** A pass {@code dropper} dropped on the floor for the leader. */
    static MapItem handOver(Character dropper, Character leader) {
        MapItem mi = new MapItem(new Item(PASS, (short) 0, (short) 1), new Point(200, -2715), dropper, leader,
                leader.getClient(), (byte) 0, false);
        mi.setDropTime(System.currentTimeMillis());
        return mi;
    }

    static RunMember member(StageView view, int id) {
        return view.members().stream().filter(m -> m.characterId() == id).findFirst().orElseThrow();
    }

    @Test
    void theLeadersFloorScanSeesAPassAMembersKillLeft() {
        View v = new View();
        MapItem stray = monsterPass(bot(B));
        v.floor.add(stray);

        assertTrue(LiveStageView.mayTake(v, LEADER, stray), "the leader may take a pass his party's kill left");
        assertTrue(LiveStageView.mayTake(v, C, stray), "so may a fellow collector, to carry it over");
        assertEquals(1, v.floorItems(PASS, LEADER));
    }

    @Test
    void theLeaderGoesForTheLastPassInsteadOfWaitingByCloto() {
        View v = new View();
        v.passes.put(LEADER, 9);
        v.mobs = 0;
        v.floor.add(monsterPass(bot(B)));

        SolverStep step = new KillCollectHandInSolver().next(v, member(v, LEADER));
        SolverStep.Loot loot = assertInstanceOf(SolverStep.Loot.class, step, "goes for the pass, not a wait: " + step);
        assertEquals(PASS, loot.itemId());
    }

    @Test
    void aCollectorFetchesAPassLeftOnTheFloor() {
        View v = new View();
        v.mobs = 0;
        v.floor.add(monsterPass(bot(B)));

        SolverStep step = new KillCollectHandInSolver().next(v, member(v, A));
        assertInstanceOf(SolverStep.Loot.class, step, "fetches the pass instead of parking: " + step);
    }

    @Test
    void aPassDroppedForTheLeaderIsStillNobodysButHis() {
        View v = new View();
        Character collector = bot(A);
        MapItem forLeader = handOver(collector, bot(LEADER));
        v.floor.add(forLeader);

        assertTrue(LiveStageView.mayTake(v, LEADER, forLeader));
        assertEquals(1, v.floorItems(PASS, LEADER));
        assertFalse(LiveStageView.mayTake(v, C, forLeader), "not another bot's to take");
        assertEquals(0, v.floorItems(PASS, C));

        forLeader.setDropTime(System.currentTimeMillis() - 60_000L); // the ownership window runs out
        assertFalse(LiveStageView.mayTake(v, C, forLeader), "a hand-over is not free loot once it ages, either");
    }
}

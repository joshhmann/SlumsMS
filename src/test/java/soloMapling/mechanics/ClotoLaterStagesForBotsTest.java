package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import scripting.AbstractScriptManager;
import soloMapling.mechanics.MechanicsModel.Position;
import soloMapling.mechanics.MechanicsModel.Stage;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 KPQ automation: bots talk to Cloto on stages 2-5 through our own {@code scripts/npc/9020001.js}
 * ({@code BotNpcTalk}), like {@link ClotoScriptForBotsTest} does for stage 1. The fakes below have exactly the calls
 * those branches make on a bot's conversation and the event, so a script change that reaches for anything else fails
 * here. It also plays the script against the solver's own numbers: a formation of members standing where
 * {@link CombinationSolver} sends them (each position's centre) is what the script accepts, a wrong one shows the wrong
 * effect, ten passes clear stage 5, and the prize conversation ({@code Converse [0]}) gives the prize and warps.
 */
class ClotoLaterStagesForBotsTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class Member {
        final int id;
        Point at = new Point(0, 0);

        Member(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }

        public Point getPosition() {
            return at;
        }
    }

    public static final class Eim {
        final Map<String, String> props = new HashMap<>();
        final List<Member> players = new ArrayList<>();
        int leaderId = 1;
        int wrongEffects;
        boolean cleared;
        final List<Integer> rewarded = new ArrayList<>();

        public String getProperty(String k) {
            return props.get(k);
        }

        public void setProperty(String k, String v) {
            props.put(k, v);
        }

        public boolean isEventLeader(Member m) {
            return m.getId() == leaderId;
        }

        public List<Member> getPlayers() {
            return players;
        }

        public int getPlayerCount() {
            return players.size();
        }

        public void showClearEffect(boolean gate) {
        }

        public void linkToNextStage(int stage, String family, int mapId) {
        }

        public void showWrongEffect() {
            wrongEffects++;
        }

        public void clearPQ() {
            cleared = true;
        }

        public boolean giveEventReward(Member m) {
            rewarded.add(m.getId());
            return true;
        }
    }

    /** What BotNpcTalk.BotCm answers for a bot on these stages, nothing more. */
    public static final class Cm {
        final Member player;
        final Eim eim;
        final int mapId;
        final Map<Integer, Integer> items = new HashMap<>();
        final List<String> said = new ArrayList<>();
        boolean disposed;
        int warpedTo = -1;

        Cm(Member player, Eim eim, int mapId) {
            this.player = player;
            this.eim = eim;
            this.mapId = mapId;
        }

        public Member getPlayer() {
            return player;
        }

        public int getMapId() {
            return mapId;
        }

        public Eim getEventInstance() {
            return eim;
        }

        public boolean isEventLeader() {
            return eim.isEventLeader(player);
        }

        public boolean haveItem(int id, int qty) {
            return items.getOrDefault(id, 0) >= qty;
        }

        public void gainItem(int id, int qty) {
            items.merge(id, qty, Integer::sum);
        }

        public void sendNext(String text) {
            said.add(text);
        }

        public void sendOk(String text) {
            said.add(text);
        }

        public void warp(int map, String portal) {
            warpedTo = map;
        }

        public void dispose() {
            disposed = true;
        }
    }

    /** One conversation: the click, then "next" for each answer while the script has not disposed (BotNpcTalk). */
    private static Cm converse(Member who, Eim eim, int mapId, Map<Integer, Integer> items, int nexts) throws Exception {
        ScriptEngine engine = new Loader().load("npc/9020001.js");
        assertNotNull(engine);
        Cm cm = new Cm(who, eim, mapId);
        cm.items.putAll(items);
        engine.put("cm", cm);
        ((Invocable) engine).invokeFunction("start");
        for (int i = 0; i < nexts && !cm.disposed; i++) {
            ((Invocable) engine).invokeFunction("action", (byte) 1, (byte) 0, 0);
        }
        items.clear();
        items.putAll(cm.items);
        return cm;
    }

    @Test
    void combinationStagesAcceptWhereTheSolverStandsMembers() throws Exception {
        var kpq = KpqStageDataTest.kpq();
        for (int stage = 2; stage <= 4; stage++) {
            Stage s = kpq.stage(stage).orElseThrow();
            Eim eim = new Eim();
            for (int id = 1; id <= 4; id++) {
                Member m = new Member(id);
                m.at = new Point(-3000, 0); // off every position
                eim.players.add(m);
            }
            Member leader = eim.players.get(0);
            Cm open = converse(leader, eim, s.mapId(), new HashMap<>(), 0);
            assertTrue(open.disposed);
            String key = s.answerProperty();
            int answer = Integer.parseInt(eim.props.get(key));
            assertNull(eim.props.get(stage + "stageclear"), "the first talk only opens the stage");

            Member notLeader = eim.players.get(1);
            converse(notLeader, eim, s.mapId(), new HashMap<>(), 0);
            assertEquals(String.valueOf(answer), eim.props.get(key), "a member's talk does not change the answer");
            assertEquals(0, eim.wrongEffects, "nor does it check");

            // a wrong formation (one mover away): the wrong effect, no clear
            int wrong = -1;
            for (int j = 0; j < s.combos().size(); j++) {
                if (CombinationSolver.oneMover(s, j, answer)) {
                    wrong = j;
                    break;
                }
            }
            stand(eim, s, wrong);
            converse(leader, eim, s.mapId(), new HashMap<>(), 0);
            assertEquals(1, eim.wrongEffects, "stage " + stage + ": the wrong formation is a visible try");
            assertNull(eim.props.get(stage + "stageclear"));

            stand(eim, s, answer);
            converse(leader, eim, s.mapId(), new HashMap<>(), 0);
            assertEquals("true", eim.props.get(stage + "stageclear"), "stage " + stage + " accepts the solver's spots");
        }
    }

    /** Members 2-4 stand at the centres of the combo's positions (the solver's Occupy aim), the leader stays off. */
    private static void stand(Eim eim, Stage s, int combo) {
        List<Integer> wanted = s.comboPositions(combo);
        for (int i = 1; i < eim.players.size(); i++) {
            Position p = s.positions().get(wanted.get(i - 1));
            SolverStep.Occupy o = CombinationSolver.occupy(p);
            eim.players.get(i).at = new Point(o.x(), o.y());
            assertTrue(o.inside(o.x(), o.y()));
        }
    }

    @Test
    void stageFiveTakesTenPassesFromTheLeaderAndThePrizeConversationWarpsToTheBonus() throws Exception {
        Eim eim = new Eim();
        Member leader = new Member(1);
        Member bot = new Member(2);
        eim.players.add(leader);
        eim.players.add(bot);
        Map<Integer, Integer> items = new HashMap<>(Map.of(4001008, 9));
        converse(leader, eim, 103000804, items, 0);
        assertNull(eim.props.get("5stageclear"), "nine passes are short");
        items.put(4001008, 10);
        converse(leader, eim, 103000804, items, 0);
        assertEquals("true", eim.props.get("5stageclear"));
        assertTrue(eim.cleared, "Cloto clears the PQ (eim.clearPQ)");
        assertEquals(0, items.get(4001008));

        // the prize: the click shows "Incredible!...", "next" gives it and warps (Converse with answers [0])
        Cm click = converse(bot, eim, 103000804, new HashMap<>(), 0);
        assertTrue(click.said.get(0).startsWith("Incredible!"), click.said.toString());
        assertTrue(eim.rewarded.isEmpty(), "the first page gives nothing yet");
        Cm prize = converse(bot, eim, 103000804, new HashMap<>(), 1);
        assertEquals(List.of(2), eim.rewarded);
        assertEquals(103000805, prize.warpedTo);
        assertTrue(prize.disposed);
    }
}

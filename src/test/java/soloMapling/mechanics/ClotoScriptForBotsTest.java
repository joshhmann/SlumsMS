package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import scripting.AbstractScriptManager;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 130 phase 1: bots talk to Cloto through our own {@code scripts/npc/9020001.js} ({@link BotNpcTalk}), with a
 * conversation manager whose player is the bot. That is only safe if the stage 1 branch calls nothing but what
 * {@code BotNpcTalk.BotCm} answers for a bot (getPlayer, getMapId, getEventInstance, isEventLeader, hasItem,
 * itemQuantity, gainItem, sendNext, dispose). The fake below has exactly those methods, so a script change that
 * reaches for anything else fails here before a bot gets stuck at Cloto on TEST. It also checks the answers the
 * solver relies on: a question goes into the grid, a wrong count is refused, the right count gives one pass, the
 * leader's passes clear the stage.
 */
class ClotoScriptForBotsTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakePlayer {
        final int id;

        FakePlayer(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }
    }

    public static final class FakeEim {
        final Map<String, String> props = new HashMap<>();
        final Map<Integer, Integer> grid = new HashMap<>();
        int players = 4;
        int leaderId = 1;
        boolean linked;

        public String getProperty(String k) {
            return props.get(k);
        }

        public void setProperty(String k, String v) {
            props.put(k, v);
        }

        public int gridCheck(FakePlayer p) {
            return grid.getOrDefault(p.getId(), -1);
        }

        public void gridInsert(FakePlayer p, int v) {
            grid.put(p.getId(), v);
        }

        public void gridClear() {
            grid.clear();
        }

        public int getPlayerCount() {
            return players;
        }

        public void showClearEffect(boolean gate) {
        }

        public void linkToNextStage(int stage, String family, int mapId) {
            linked = true;
        }
    }

    /** The bot-safe surface of BotNpcTalk.BotCm, nothing more. */
    public static final class BotSafeCm {
        final FakePlayer player;
        final FakeEim eim;
        final Map<Integer, Integer> items = new HashMap<>();
        final List<String> said = new ArrayList<>();
        boolean disposed;

        BotSafeCm(FakePlayer player, FakeEim eim) {
            this.player = player;
            this.eim = eim;
        }

        public FakePlayer getPlayer() {
            return player;
        }

        public int getMapId() {
            return 103000800;
        }

        public FakeEim getEventInstance() {
            return eim;
        }

        public boolean isEventLeader() {
            return player.getId() == eim.leaderId;
        }

        public boolean hasItem(int id, int qty) {
            return items.getOrDefault(id, 0) >= qty;
        }

        public int itemQuantity(int id) {
            return items.getOrDefault(id, 0);
        }

        public void gainItem(int id, int qty) {
            items.merge(id, qty, Integer::sum);
        }

        public void sendNext(String text) {
            said.add(text);
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static BotSafeCm talk(FakePlayer p, FakeEim eim, Map<Integer, Integer> items) throws Exception {
        ScriptEngine engine = new Loader().load("npc/9020001.js");
        assertNotNull(engine, "9020001.js evaluates");
        BotSafeCm cm = new BotSafeCm(p, eim);
        cm.items.putAll(items);
        engine.put("cm", cm);
        ((Invocable) engine).invokeFunction("start");
        items.clear();
        items.putAll(cm.items);
        assertTrue(cm.disposed, "Cloto disposes after every stage 1 talk");
        return cm;
    }

    @Test
    void stage1IsBotSafeAndAnswersAsTheSolverExpects() throws Exception {
        FakeEim eim = new FakeEim();
        FakePlayer bot = new FakePlayer(2);
        Map<Integer, Integer> items = new HashMap<>();
        var stage = StageData.loadDir(java.nio.file.Path.of(Mechanics.DEFAULT_DIR)).content().get(0).stage(1)
                .orElseThrow();

        talk(bot, eim, items);
        int q = eim.grid.get(2);
        assertTrue(q >= 1 && q <= 7, "a question id in the grid: " + q);
        int answer = stage.answer(q).orElseThrow();

        items.put(4001007, answer - 1);
        BotSafeCm wrong = talk(bot, eim, items);
        assertTrue(wrong.said.get(0).startsWith("I'm sorry, but that is not the right answer!"), wrong.said.toString());
        assertEquals(answer - 1, items.get(4001007), "a wrong count takes nothing");
        assertEquals(q, eim.grid.get(2));

        items.put(4001007, answer);
        talk(bot, eim, items);
        assertEquals(0, items.get(4001007));
        assertEquals(1, items.get(4001008), "one pass");
        assertEquals(0, eim.grid.get(2), "grid 0 = done");

        FakePlayer leader = new FakePlayer(1);
        Map<Integer, Integer> leaderItems = new HashMap<>(Map.of(4001008, 2));
        talk(leader, eim, leaderItems);
        assertEquals(null, eim.props.get("1stageclear"), "two passes are short for a party of four");
        leaderItems.put(4001008, 3);
        talk(leader, eim, leaderItems);
        assertEquals("true", eim.props.get("1stageclear"), "the clear property the run watches");
        assertEquals(0, leaderItems.get(4001008));
        assertTrue(eim.linked);
    }
}

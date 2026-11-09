package scripting;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;
import soloMapling.qa.QaLog;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 boss-feed-lines batch 2: the LPQ scripts write their facts to the MECHANICS feed as
 * {@code RUN lpq ... [lpq, leader X]}. Every hook runs the real script against a mocked event instance and reads what
 * the QA feed heard. Nothing about the PQ itself is asserted to change: the same scripts, the same mocks, one more line.
 */
class LpqFeedLinesTest {

    private static final String TAG = " [lpq, leader Josh]";

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    private final List<String> feed = new CopyOnWriteArrayList<>();
    private final Map<String, String> props = new HashMap<>();
    private QaLog.SinkHandle sink;
    private EventInstanceManager eim;
    private Character leader;

    @BeforeEach
    void listen() {
        sink = QaLog.sink((topic, mapId, charIds, message) -> feed.add(message));
        sink.topics(List.of(QaLog.Topic.MECHANICS));
        leader = human(1, "Josh");
        eim = mock(EventInstanceManager.class);
        when(eim.getName()).thenReturn("Ludi0");
        when(eim.getLeader()).thenReturn(leader);
        when(eim.getLeaderId()).thenReturn(1);
        when(eim.isEventLeader(any())).thenAnswer(inv -> ((Character) inv.getArgument(0)).getId() == 1);
        when(eim.getPlayers()).thenReturn(new ArrayList<>(List.of(leader)));
        when(eim.getPlayerCount()).thenReturn(5);
        when(eim.getClearStageBonus(9)).thenReturn(List.of(5950, 0));
        when(eim.getProperty(anyString())).thenAnswer(inv -> props.get(inv.<String>getArgument(0)));
        when(eim.getIntProperty(anyString())).thenAnswer(inv -> Integer.parseInt(props.get(inv.<String>getArgument(0))));
        doAnswer(inv -> props.put(inv.getArgument(0), inv.getArgument(1))).when(eim).setProperty(anyString(), anyString());
        doAnswer(inv -> props.put(inv.getArgument(0), String.valueOf((Object) inv.getArgument(1))))
                .when(eim).setProperty(anyString(), any(Integer.class));
    }

    @AfterEach
    void stopListening() {
        sink.close();
    }

    private static Character human(int id, String name) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.isBot()).thenReturn(false);
        return c;
    }

    // ---- stage NPCs: hand-ins and refusals ------------------------------------------------------------------------

    public static final class Cm {
        final Character player;
        final EventInstanceManager eim;
        final int mapId;
        final Map<Integer, Integer> bag = new HashMap<>();
        public boolean disposed;
        public final List<Integer> gained = new ArrayList<>();

        Cm(Character player, EventInstanceManager eim, int mapId) {
            this.player = player;
            this.eim = eim;
            this.mapId = mapId;
        }

        public Character getPlayer() {
            return player;
        }

        public EventInstanceManager getEventInstance() {
            return eim;
        }

        public int getMapId() {
            return mapId;
        }

        public int getItemQuantity(int id) {
            return bag.getOrDefault(id, 0);
        }

        public boolean haveItem(int id, int qty) {
            return getItemQuantity(id) >= qty;
        }

        public void gainItem(int id, int qty) {
            gained.add(qty);
            bag.merge(id, qty, Integer::sum);
        }

        public void sendOk(String text) {
        }

        public void sendNext(String text) {
        }

        public void dispose() {
            disposed = true;
        }
    }

    private void talk(int npc, Cm cm) throws Exception {
        ScriptEngine engine = new Loader().load("npc/" + npc + ".js");
        assertNotNull(engine, "npc/" + npc + ".js");
        engine.put("cm", cm);
        ((Invocable) engine).invokeFunction("start");
    }

    private Cm cmAt(int stage, int item, int held) {
        when(leader.getEventInstance()).thenReturn(eim);
        props.put("statusStg" + stage, "0");
        Cm cm = new Cm(leader, eim, 922010100 + (stage - 1) * 100);
        cm.bag.put(item, held);
        return cm;
    }

    /** stage, npc, item, passes asked */
    @ParameterizedTest
    @CsvSource({"1,2040036,4001022,25", "2,2040037,4001022,15", "3,2040038,4001022,32", "4,2040039,4001022,6",
            "5,2040040,4001022,24", "7,2040042,4001022,3", "9,2040044,4001023,1"})
    void aHandInIsOneFeedLine(int stage, int npc, int item, int asked) throws Exception {
        talk(npc, cmAt(stage, item, asked));
        assertEquals(List.of("RUN lpq stage " + stage + " hand-in " + asked + " passes" + TAG), feed);
    }

    @ParameterizedTest
    @CsvSource({"1,2040036,4001022,25", "2,2040037,4001022,15", "3,2040038,4001022,32", "4,2040039,4001022,6",
            "5,2040040,4001022,24", "7,2040042,4001022,3", "9,2040044,4001023,1"})
    void aRefusalSaysWhatTheLeaderHolds(int stage, int npc, int item, int asked) throws Exception {
        Cm cm = cmAt(stage, item, asked - 1);
        talk(npc, cm);
        assertEquals(List.of("RUN lpq stage " + stage + " hand-in refused: has " + (asked - 1) + " of " + asked
                + " passes" + TAG), feed);
        assertEquals(List.of(), cm.gained, "a refusal takes nothing");
    }

    @Test
    void theFirstTalkAndANonLeaderWriteNoLine() throws Exception {
        props.put("statusStg1", "-1");
        when(leader.getEventInstance()).thenReturn(eim);
        talk(2040036, new Cm(leader, eim, 922010100));          // the preamble
        Character other = human(2, "Other");
        when(other.getEventInstance()).thenReturn(eim);
        props.put("statusStg1", "0");
        Cm cm = new Cm(other, eim, 922010100);
        cm.bag.put(4001022, 25);
        talk(2040036, cm);                                      // not the leader
        assertEquals(List.of(), feed);
    }

    // ---- stage 8: the combination ---------------------------------------------------------------------------------

    private Cm boxes(int... boxOfEachPlayer) {
        MapleMap map = mock(MapleMap.class);
        List<Rectangle> areas = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            areas.add(new Rectangle(i * 100, 0, 50, 50));
        }
        when(map.getAreas()).thenReturn(areas);
        when(map.getArea(anyInt())).thenAnswer(inv -> areas.get(inv.getArgument(0)));
        List<Character> party = new ArrayList<>();
        for (int i = 0; i < boxOfEachPlayer.length; i++) {
            Character c = i == 0 ? leader : human(10 + i, "P" + i);
            when(c.getPosition()).thenReturn(new Point(boxOfEachPlayer[i] * 100 + 10, 10));
            party.add(c);
        }
        when(eim.getPlayers()).thenReturn(party);
        when(leader.getMap()).thenReturn(map);
        when(leader.gmLevel()).thenReturn(0);
        when(leader.getEventInstance()).thenReturn(eim);
        props.put("statusStg8", "0");
        props.put("stage8combo", "1,1,1,0,0,1,1,0,0");
        return new Cm(leader, eim, 922010800);
    }

    @Test
    void everyCheckWithFiveBoxesCoveredIsATryWithItsRightCount() throws Exception {
        talk(2040043, boxes(0, 1, 2, 3, 4));     // boxes 0,1,2 are in the combination: 3 right
        talk(2040043, boxes(0, 1, 5, 6, 7));     // 0,1,5,6 right: 4
        assertEquals(List.of("RUN lpq stage 8 combination try 1, 3 right" + TAG,
                "RUN lpq stage 8 combination try 2, 4 right" + TAG), feed);
    }

    @Test
    void theRightCombinationIsTheLastTryAndFewerThanFiveBoxesIsNoTry() throws Exception {
        talk(2040043, boxes(0, 1, 2, 3));        // only four covered: the NPC asks for five, no try
        assertEquals(List.of(), feed);
        talk(2040043, boxes(0, 1, 2, 5, 6));
        assertEquals(List.of("RUN lpq stage 8 combination try 1, 5 right" + TAG), feed);
    }

    // ---- LudiPQ.js: stage 6, clear ---------------------------------------------------------------------------------

    private ScriptEngine event() throws Exception {
        ScriptEngine engine = new Loader().load("event/LudiPQ.js");
        assertNotNull(engine);
        engine.eval("var __now = 1000000; Date.now = function () { return __now; };");
        return engine;
    }

    private final Point at = new Point();

    private Character climber(int id) {
        Character c = human(id, "C" + id);
        when(c.getMapId()).thenReturn(922010600);
        when(c.getPosition()).thenAnswer(inv -> new Point(at));
        return c;
    }

    /** One portal use: standing at fromY when it starts, at toY once it is done. */
    private void use(Invocable iv, Character c, int fromY, int toY) throws Exception {
        at.setLocation(0, fromY);
        iv.invokeFunction("changedMap", eim, c, 922010600);
        at.setLocation(0, toY);
        iv.invokeFunction("afterChangedMap", eim, c, 922010600);
    }

    @Test
    void stage6WritesOneLinePerNewHighestRow() throws Exception {
        ScriptEngine engine = event();
        Invocable iv = (Invocable) engine;
        Character a = climber(1);
        Character b = climber(2);
        use(iv, a, -553, -723);      // row 1's right portal lands on row 2
        use(iv, b, -556, -724);      // another climber up row 1 again: not a new row
        use(iv, a, -725, -915);      // row 2
        use(iv, b, -723, -915);      // row 2 again
        assertEquals(List.of("RUN lpq stage 6 puzzle row 1 of 15" + TAG, "RUN lpq stage 6 puzzle row 2 of 15" + TAG),
                feed);
    }

    @Test
    void theTopRowCountsWhenTheLandingIsTheTopPortal() throws Exception {
        Invocable iv = (Invocable) event();
        use(iv, climber(1), -2993, -3163);
        assertEquals(List.of("RUN lpq stage 6 puzzle row 15 of 15" + TAG), feed);
    }

    @Test
    void aFallIsOneLinePerTenSecondsPerInstance() throws Exception {
        ScriptEngine engine = event();
        Invocable iv = (Invocable) engine;
        Character a = climber(1);
        use(iv, a, -1090, -225);                       // row 4 -> the start
        use(iv, a, -555, -225);                        // a second fall straight after: swallowed
        engine.eval("__now += 9999;");
        use(iv, a, -555, -225);                        // still inside the 10 s
        engine.eval("__now += 1;");
        use(iv, a, -723, -225);                        // 10 s after the first
        assertEquals(List.of("RUN lpq stage 6 fall to start from row 4" + TAG,
                "RUN lpq stage 6 fall to start from row 2" + TAG), feed);
    }

    @Test
    void enteringTheStageOrLeavingItWritesNoRowLine() throws Exception {
        Invocable iv = (Invocable) event();
        Character a = climber(1);
        when(a.getMapId()).thenReturn(922010500);      // arrives from stage 5
        at.setLocation(0, -500);
        iv.invokeFunction("changedMap", eim, a, 922010600);
        at.setLocation(103, -225);
        when(a.getMapId()).thenReturn(922010600);
        iv.invokeFunction("afterChangedMap", eim, a, 922010600);
        assertEquals(List.of(), feed);
    }

    @Test
    void clearPqWritesTheClearAndTheRewards() throws Exception {
        ((Invocable) event()).invokeFunction("clearPQ", eim);
        assertEquals(List.of("RUN lpq cleared, 5 members to 922011000" + TAG,
                "RUN lpq rewards exp 5950 to 5 members" + TAG), feed);
    }

    @Test
    void theOldMapChangeRuleIsUntouched() throws Exception {
        Invocable iv = (Invocable) event();
        Character a = climber(3);
        when(eim.isEventTeamLackingNow(anyBoolean(), anyInt(), any())).thenReturn(false);
        iv.invokeFunction("changedMap", eim, a, 100000000);     // out of the PQ's maps: unregistered, as before
        org.mockito.Mockito.verify(eim).unregisterPlayer(a);
        assertEquals(List.of(), feed);
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}

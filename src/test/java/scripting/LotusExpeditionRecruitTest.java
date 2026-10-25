package scripting;

import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Lotus recruiter (2491006) let a leader create an expedition but never start it: it read the expedition only
 * inside one state, so every new conversation offered to create another one, the start block was never reached, and the
 * manager was re-resolved to Normal Lotus on every click, which threw away the Hard Lotus choice.
 */
class LotusExpeditionRecruitTest {

    /** Stands in for EventManager: records what the script asks it to do. */
    public static final class FakeEm {
        private final String name;
        private final Map<String, String> props = new HashMap<>();
        public Expedition started;
        public Integer channel;
        public boolean party = true;

        FakeEm(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public String getProperty(String key) {
            return props.get(key);
        }

        public void setProperty(String key, String value) {
            props.put(key, value);
        }

        public void setProperty(String key, int value) {
            if ("channel".equals(key)) {
                channel = value;
            }
            props.put(key, String.valueOf(value));
        }

        public boolean startInstance(Expedition expedition) {
            started = expedition;
            return true;
        }

        public Object getInstance(String name) {
            return null;
        }
    }

    /** Stands in for NPCConversationManager: only what the recruiter touches. */
    public static final class FakeCm {
        private final Character player;
        private final Map<String, Expedition> expeditions = new HashMap<>();
        private final Map<String, FakeEm> events = new HashMap<>();
        /** Event scripts that exist; anything else resolves to the 0_EXAMPLE fallback, as EventScriptManager does. */
        public final List<String> existingEvents = new ArrayList<>(List.of("HardLotusBattle"));
        private final List<ExpeditionType> created = new ArrayList<>();
        public final List<String> resolved = new ArrayList<>();
        public String sent;
        public boolean disposed;
        public int createResult = 0;

        FakeCm(Character player) {
            this.player = player;
        }

        public Character getPlayer() {
            return player;
        }

        public Expedition getExpedition(ExpeditionType type) {
            return expeditions.get(type.name());
        }

        public int createExpedition(ExpeditionType type) {
            created.add(type);
            return createResult;
        }

        public Object getEventManager(String name) {
            resolved.add(name);
            String effective = existingEvents.contains(name) ? name : "0_EXAMPLE";
            return events.computeIfAbsent(effective, FakeEm::new);
        }

        public void sendNext(String text) {
            sent = text;
        }

        public void sendSimple(String text) {
            sent = text;
        }

        public void sendOk(String text) {
            sent = text;
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static Character player(int level) {
        Client client = mock(Client.class);
        when(client.getChannel()).thenReturn(3);
        Character chr = mock(Character.class);
        when(chr.getLevel()).thenReturn(level);
        when(chr.getName()).thenReturn("Tester");
        when(chr.getClient()).thenReturn(client);
        when(chr.getId()).thenReturn(42);
        return chr;
    }

    private static Expedition expedition(ExpeditionType type, Character leader) {
        Expedition exped = mock(Expedition.class);
        when(exped.isLeader(any(Character.class))).thenAnswer(invocation -> invocation.getArgument(0) == leader);
        when(exped.contains(any(Character.class))).thenAnswer(invocation -> invocation.getArgument(0) == leader);
        when(exped.isRegistering()).thenReturn(true);
        when(exped.getLeader()).thenReturn(leader);
        when(exped.getType()).thenReturn(type);
        return exped;
    }

    private static ScriptEngine engine() {
        return new AbstractScriptManager() {
            ScriptEngine load() {
                return getInvocableScriptEngine("npc/2491006.js");
            }
        }.load();
    }

    @Test
    void aLeaderCanStartTheExpeditionTheyCreated() throws Exception {
        Character chr = player(250);
        FakeCm cm = new FakeCm(chr);
        ScriptEngine engine = engine();
        assertNotNull(engine, "2491006.js evaluates");
        engine.put("cm", cm);
        Invocable script = (Invocable) engine;

        // first conversation: pick Hard Lotus, confirm, register the expedition
        script.invokeFunction("start");
        script.invokeFunction("action", 1, 0, 0);          // "Looking for me?" -> level check -> mode menu
        assertTrue(cm.sent.contains("#L2#Hard Mode#l"), cm.sent);
        script.invokeFunction("action", 1, 0, 2);          // Hard Mode -> confirmation
        script.invokeFunction("action", 1, 0, 1);          // Yes -> createExpedition
        assertEquals(List.of(ExpeditionType.HARDLOTUS), cm.created, "the hard expedition is the one registered");

        // second conversation, exactly as the player would: a new conversation, the old one was disposed
        Expedition hard = expedition(ExpeditionType.HARDLOTUS, chr);
        List<Map.Entry<Integer, String>> members = new ArrayList<>();
        for (int i = 1; i <= ExpeditionType.HARDLOTUS.getMinSize(); i++) {
            members.add(Map.entry(i, i == 1 ? "Tester" : "Member" + i));
        }
        when(hard.getMemberList()).thenReturn(members);
        cm.expeditions.put(ExpeditionType.HARDLOTUS.name(), hard);
        FakeCm second = new FakeCm(chr);
        second.expeditions.putAll(cm.expeditions);
        ScriptEngine hiddenEngine = engine();
        hiddenEngine.put("cm", second);
        Invocable revisit = (Invocable) hiddenEngine;
        revisit.invokeFunction("start");
        revisit.invokeFunction("action", 1, 0, 0);
        assertTrue(second.sent.contains("#L2#Start the fight!#l"), "the leader manages their expedition instead of creating a new one: " + second.sent);
        revisit.invokeFunction("action", 1, 0, 2);          // start the fight
        revisit.invokeFunction("action", 1, 0, 0);          // click through

        FakeEm hardEvent = second.events.get("HardLotusBattle");
        assertNotNull(hardEvent, "the hard battle manager is used");
        assertEquals(hard, hardEvent.started, "the expedition finally starts");
        FakeEm normalEvent = second.events.get("0_EXAMPLE");
        assertTrue(normalEvent == null || normalEvent.started == null, "the normal battle is never started when hard was chosen");
        assertEquals("Tester", hardEvent.getProperty("leader"));
        assertEquals(3, hardEvent.channel, "the channel of the leader is handed to the battle, as an int property");
    }

    @Test
    void aMissingBattleScriptIsRefusedInsteadOfRegisteringADeadExpedition() throws Exception {
        Character chr = player(250);
        FakeCm cm = new FakeCm(chr);
        ScriptEngine engine = engine();
        engine.put("cm", cm);
        Invocable script = (Invocable) engine;

        script.invokeFunction("start");
        script.invokeFunction("action", 1, 0, 0);
        script.invokeFunction("action", 1, 0, 1);          // Normal Mode

        assertTrue(cm.sent.contains("not available right now"), cm.sent);
        assertTrue(cm.created.isEmpty(), "no expedition is registered against a battle that cannot start");
        assertTrue(cm.disposed);
    }

    @Test
    void decliningTheConfirmationDoesNotRegisterAnExpedition() throws Exception {
        Character chr = player(250);
        FakeCm cm = new FakeCm(chr);
        ScriptEngine engine = engine();
        engine.put("cm", cm);
        Invocable script = (Invocable) engine;

        script.invokeFunction("start");
        script.invokeFunction("action", 1, 0, 0);
        script.invokeFunction("action", 1, 0, 2);          // Hard Mode
        script.invokeFunction("action", 1, 0, 2);          // No

        assertTrue(cm.created.isEmpty());
        assertTrue(cm.disposed);
    }

    @Test
    void underLevelledPlayersAreTurnedAway() throws Exception {
        Character chr = player(100);
        FakeCm cm = new FakeCm(chr);
        ScriptEngine engine = engine();
        engine.put("cm", cm);
        Invocable script = (Invocable) engine;

        script.invokeFunction("start");
        script.invokeFunction("action", 1, 0, 0);

        assertTrue(cm.sent.contains("do not meet the criteria"), cm.sent);
        assertFalse(cm.sent.contains("#L1#Normal Mode#l"), "no mode menu for a player who cannot fight");
    }

    @Test
    void anExistingExpeditionIsJoinedRatherThanDuplicated() throws Exception {
        Character leader = player(250);
        Character other = player(250);
        FakeCm cm = new FakeCm(other);
        Expedition normal = expedition(ExpeditionType.NORMALLOTUS, leader);
        when(normal.addMember(other)).thenReturn("You have registered for the expedition successfully!");
        cm.expeditions.put(ExpeditionType.NORMALLOTUS.name(), normal);

        ScriptEngine engine = engine();
        engine.put("cm", cm);
        ((Invocable) engine).invokeFunction("action", 1, 0, 0);

        verify(normal).addMember(other);
        assertTrue(cm.created.isEmpty(), "joining does not create a second expedition");
        assertTrue(cm.disposed);
    }

    @Test
    void hardModeNeverFallsBackToTheNormalBattle() throws Exception {
        Character chr = player(250);
        FakeCm cm = new FakeCm(chr);
        ScriptEngine engine = engine();
        engine.put("cm", cm);
        Invocable script = (Invocable) engine;

        script.invokeFunction("start");
        script.invokeFunction("action", 1, 0, 0);
        script.invokeFunction("action", 1, 0, 2);          // Hard Mode -> confirmation

        assertEquals("HardLotusBattle", cm.resolved.get(cm.resolved.size() - 1),
                "the manager resolved for the confirmation is the hard one, not the normal fallback");
    }
}

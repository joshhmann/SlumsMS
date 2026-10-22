package scripting;

import net.packet.Packet;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.0 review: the wedding event scripts called Java.type('tools.packets.Wedding'), a class that doesn't exist (it is
 * WeddingPackets), so every priest message threw and showMarriedMsg never reached restartEventTimer(partyTime).
 */
class WeddingScriptTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakeMap {
        public final List<Packet> broadcasts = new ArrayList<>();

        public void broadcastMessage(Packet packet) {
            broadcasts.add(packet);
        }
    }

    public static final class FakeChr {
        public final FakeMap map = new FakeMap();

        public int getGender() {
            return 0;
        }

        public FakeMap getMap() {
            return map;
        }
    }

    public static final class FakeEim {
        public final FakeChr leader = new FakeChr();
        public final Map<Integer, FakeMap> maps = new HashMap<>();
        public final Map<String, Integer> ints = new HashMap<>();
        public final List<String> scheduled = new ArrayList<>();
        public long restartedTimer = -1;

        public FakeChr getLeader() {
            return leader;
        }

        public FakeMap getMapInstance(int id) {
            return maps.computeIfAbsent(id, k -> new FakeMap());
        }

        public int getIntProperty(String key) {
            return ints.getOrDefault(key, 0);
        }

        public void setIntProperty(String key, Integer value) {
            ints.put(key, value);
        }

        public void schedule(String method, long delay) {
            scheduled.add(method);
        }

        public void restartEventTimer(long time) {
            restartedTimer = time;
        }
    }

    private static Invocable load(String script) {
        ScriptEngine engine = new Loader().load("event/" + script);
        assertNotNull(engine, script);
        return (Invocable) engine;
    }

    @ParameterizedTest
    @ValueSource(strings = {"WeddingChapel.js", "WeddingCathedral.js"})
    void theMarriedMessageRestartsThePartyTimer(String script) throws Exception {
        FakeEim eim = new FakeEim();
        eim.ints.put("groomId", 11);
        eim.ints.put("brideId", 22);

        load(script).invokeFunction("showMarriedMsg", eim);

        assertEquals(45 * 60_000L, eim.restartedTimer, "partyTime");
        assertEquals(1, eim.leader.map.broadcasts.size(), "the vows packet went out");
        assertTrue(eim.scheduled.contains("hidePriestMsg"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WeddingChapel.js", "WeddingCathedral.js"})
    void thePriestMessagesSend(String script) throws Exception {
        Invocable wedding = load(script);
        FakeEim eim = new FakeEim();
        int altar = (script.contains("Chapel") ? 680000100 : 680000200) + 10;

        wedding.invokeFunction("showStartMsg", eim);
        wedding.invokeFunction("showBlessMsg", eim);
        wedding.invokeFunction("hidePriestMsg", eim);

        assertEquals(2, eim.getMapInstance(altar).broadcasts.size());
        assertEquals(1, eim.leader.map.broadcasts.size());
        assertEquals(1, eim.getIntProperty("guestBlessings"));
    }

    /** Every Java.type the wedding scripts name is a class on the classpath. */
    @ParameterizedTest
    @ValueSource(strings = {"WeddingChapel.js", "WeddingCathedral.js"})
    void everyJavaTypeExists(String script) throws Exception {
        String source = Files.readString(Path.of("scripts", "event", script));
        Matcher m = Pattern.compile("Java\\.type\\('([^']+)'\\)").matcher(source);
        int found = 0;
        while (m.find()) {
            Class.forName(m.group(1));
            found++;
        }
        assertTrue(found > 0);
    }
}

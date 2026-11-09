package scripting.event;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.7 boss-feed-lines: the three expedition scripts (Zakum, Chaos Zakum, Horntail) write one observe-only line per
 * event through {@code eim.feed(text)}. Each script runs in the server's Graal engine; the {@code eim} is a test double
 * that records the fed lines, so a script that drops a hook fails here.
 */
class BossFeedScriptsTest {

    // ---------------------------------------------------------------- doubles

    public static class Leader {
        public String getName() {
            return "AqLeader";
        }
    }

    public static class Interaction {
        final List<String> items;

        Interaction(List<String> items) {
            this.items = items;
        }

        public void gainItem(int id, int qty) {
            items.add(id + "x" + qty);
        }
    }

    public static class Client {
        final List<String> items;

        Client(List<String> items) {
            this.items = items;
        }

        public Interaction getAbstractPlayerInteraction() {
            return new Interaction(items);
        }
    }

    public static class Player {
        final boolean bot;
        final List<String> items;

        Player(boolean bot, List<String> items) {
            this.bot = bot;
            this.items = items;
        }

        public boolean isBot() {
            return bot;
        }

        public Client getClient() {
            return new Client(items);
        }

        public double getExpRate() {
            return 2.0;
        }

        public void gainExp(int exp, boolean a, boolean b, boolean c) {
        }

        public String getName() {
            return bot ? "RegularA" : "Human";
        }

        public void changeMap(int map, int portal) {
        }
    }

    /** A list the script can call {@code size()} / {@code get(i)} on, as with the real eim.getPlayers(). */
    public static class Party {
        final List<Player> players;

        Party(List<Player> players) {
            this.players = players;
        }

        public int size() {
            return players.size();
        }

        public Player get(int i) {
            return players.get(i);
        }
    }

    public static class Eim {
        final Map<String, String> props = new LinkedHashMap<>();
        final List<String> fed = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final List<String> given = new ArrayList<>();
        final List<Player> players = new ArrayList<>();
        boolean noLeader;

        public void feed(String text) {
            fed.add(text);
        }

        public Leader getLeader() {
            return noLeader ? null : new Leader();
        }

        public void setProperty(String k, String v) {
            props.put(k, v);
        }

        public String getProperty(String k) {
            return props.get(k);
        }

        public void setIntProperty(String k, int v) {
            props.put(k, String.valueOf(v));
        }

        public int getIntProperty(String k) {
            return Integer.parseInt(props.get(k));
        }

        public void showClearEffect(int map) {
        }

        public void clearPQ() {
        }

        public void stopEventTimer() {
        }

        public void setEventCleared(Object type) {
        }

        public void dispatchRaiseQuestMobCount(int a, int b) {
        }

        public Party getPlayers() {
            return new Party(players);
        }

        public void unregisterPlayer(Object p) {
            calls.add("unregister");
        }

        public void dispose() {
            calls.add("dispose");
        }
    }

    public static class Mob {
        final int id;

        Mob(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }

        public Map1 getMap() {
            return new Map1();
        }
    }

    public static class Map1 {
        public int getId() {
            return 1;
        }
    }

    public static class Em {
        public Channel getChannelServer() {
            return new Channel();
        }
    }

    public static class Channel {
        public Factory getMapFactory() {
            return new Factory();
        }
    }

    public static class Factory {
        public Gates getMap(int id) {
            return new Gates();
        }
    }

    public static class Gates {
        public Gate getReactorById(int id) {
            return new Gate();
        }
    }

    public static class Gate {
        public void forceHitReactor(int state) {
        }
    }

    private static ScriptEngine engine(String file) throws Exception {
        Path script = Path.of("scripts/event/" + file);
        assertTrue(Files.isRegularFile(script), file + " is missing");
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostAccess", true);
        engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostClassLookup", true);
        engine.put("em", new Em());
        engine.eval(Files.readString(script));
        return engine;
    }

    private static void call(ScriptEngine e, String fn, Object... args) throws Exception {
        ((Invocable) e).invokeFunction(fn, args);
    }

    private static Eim started() {
        Eim eim = new Eim();
        eim.props.put("feedT0", String.valueOf(System.currentTimeMillis() - 90_000));
        return eim;
    }

    /** "after 90 s" with a few seconds of slack for a slow machine. */
    private static String secs(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("after (\\d+) s").matcher(line);
        assertTrue(m.find(), line);
        int s = Integer.parseInt(m.group(1));
        assertTrue(s >= 90 && s < 120, "elapsed seconds are read from the instance start: " + line);
        return line.replaceAll("after \\d+ s", "after S s");
    }

    // ---------------------------------------------------------------- Zakum and Chaos Zakum

    private void zakumLines(String file, String id, int[] arms, int[] bodies, int clearId) throws Exception {
        ScriptEngine e = engine(file);
        Eim eim = started();
        String tag = " [" + id + ", leader AqLeader]";

        for (int a = arms[0]; a < arms[1]; a++) {
            call(e, "monsterKilled", new Mob(a), eim);
        }
        assertEquals(List.of(), eim.fed, "seven arms down: no line yet");
        call(e, "monsterKilled", new Mob(arms[1]), eim);
        assertEquals(List.of("RUN boss " + id + " arms down: all 8 after S s" + tag), secs0(eim));

        eim.fed.clear();
        call(e, "monsterKilled", new Mob(bodies[0]), eim);
        call(e, "monsterKilled", new Mob(bodies[1]), eim);
        assertEquals(List.of("RUN boss " + id + " body " + (bodies[0] + 1) + " up after S s" + tag,
                "RUN boss " + id + " body " + (bodies[1] + 1) + " up after S s" + tag), secs0(eim));

        eim.fed.clear();
        call(e, "clearPQ", eim);
        assertEquals(List.of("RUN boss " + id + " cleared after S s: defeatedBoss=1" + tag), secs0(eim));

        eim.fed.clear();
        eim.players.add(new Player(false, eim.given));
        eim.players.add(new Player(true, eim.given));
        eim.players.add(new Player(false, eim.given));
        call(e, "monsterKilled", new Mob(clearId), eim);
        assertEquals(List.of("RUN boss " + id + " rewards: item 4001126 x20 to 2 members, 1 bots skipped" + tag), eim.fed);
        assertEquals(List.of("4001126x20", "4001126x20"), eim.given, "the reward itself is unchanged");

        eim.fed.clear();
        call(e, "end", eim);
        assertEquals(List.of("RUN boss " + id + " cleanup: 3 members moved to 211042400, instance disposed" + tag), eim.fed);
        assertTrue(eim.calls.contains("dispose"));
    }

    private static List<String> secs0(Eim eim) {
        List<String> out = new ArrayList<>();
        for (String l : eim.fed) {
            out.add(secs(l));
        }
        return out;
    }

    @Test
    void zakumWritesItsSixLifecycleLines() throws Exception {
        zakumLines("ZakumBattle.js", "zakum", new int[]{8800003, 8800010}, new int[]{8800000, 8800001}, 8800002);
    }

    @Test
    void chaosZakumWritesTheSameLinesUnderItsOwnTag() throws Exception {
        zakumLines("ChaosZakumBattle.js", "chaoszakum", new int[]{8800103, 8800110}, new int[]{8800100, 8800101}, 8800102);
    }

    @Test
    void zakumSetupStampsTheStartTimeForTheSecondsInEveryLine() throws Exception {
        // setup() needs a channel and maps; the stamp is the one thing it adds for the feed, so check the source
        // shape instead: the property read by feedSecs is the one setup writes.
        String src = Files.readString(Path.of("scripts/event/ZakumBattle.js"));
        assertTrue(src.contains("eim.setProperty(\"feedT0\""), "setup stamps feedT0");
        assertTrue(Files.readString(Path.of("scripts/event/ChaosZakumBattle.js")).contains("eim.setProperty(\"feedT0\""));
        assertTrue(Files.readString(Path.of("scripts/event/HorntailBattle.js")).contains("eim.setProperty(\"feedT0\""));
    }

    @Test
    void aMissingLeaderDropsOnlyTheLeaderPartOfTheTag() throws Exception {
        ScriptEngine e = engine("ZakumBattle.js");
        Eim eim = started();
        eim.noLeader = true;
        call(e, "clearPQ", eim);
        assertEquals("RUN boss zakum cleared after S s: defeatedBoss=1 [zakum]", secs(eim.fed.get(0)));
    }

    @Test
    void aFeedThatThrowsNeverBreaksTheFight() throws Exception {
        ScriptEngine e = engine("ZakumBattle.js");
        Eim eim = new Eim() {
            @Override
            public void feed(String text) {
                throw new IllegalStateException("sink down");
            }
        };
        eim.players.add(new Player(false, eim.given));
        call(e, "monsterKilled", new Mob(8800002), eim);
        assertEquals(List.of("4001126x20"), eim.given, "the reward is still handed out");
    }

    // ---------------------------------------------------------------- Horntail

    @Test
    void horntailWritesHeadsPartsClearRewardsAndCleanup() throws Exception {
        ScriptEngine e = engine("HorntailBattle.js");
        Eim eim = started();
        eim.props.put("defeatedHead", "0");
        String tag = " [horntail, leader AqLeader]";

        call(e, "monsterKilled", new Mob(8810000), eim);
        call(e, "monsterKilled", new Mob(8810001), eim);
        assertEquals(List.of("RUN boss horntail head 1 down after S s" + tag,
                "RUN boss horntail head 2 down after S s" + tag), secs0(eim));

        eim.fed.clear();
        for (int p = 8810002; p < 8810009; p++) {
            call(e, "monsterKilled", new Mob(p), eim);
        }
        assertEquals(List.of(), eim.fed, "seven parts down: no line yet");
        call(e, "monsterKilled", new Mob(8810009), eim);
        assertEquals(List.of("RUN boss horntail parts down: all 8 after S s" + tag), secs0(eim));

        eim.fed.clear();
        call(e, "clearPQ", eim);
        assertEquals(List.of("RUN boss horntail cleared after S s: defeatedBoss=1" + tag), secs0(eim));

        eim.fed.clear();
        eim.players.add(new Player(false, eim.given));
        eim.players.add(new Player(true, eim.given));
        call(e, "monsterKilled", new Mob(8810018), eim);
        assertEquals(List.of("RUN boss horntail rewards: exp 107545760 each (rate-scaled), item 4001126 x40 to 1 members, 1 bots skipped" + tag),
                eim.fed);
        assertEquals(List.of("4001126x40"), eim.given);

        eim.fed.clear();
        call(e, "end", eim);
        assertEquals(List.of("RUN boss horntail cleanup: 2 members moved to 240050600, instance disposed" + tag), eim.fed);
    }
}

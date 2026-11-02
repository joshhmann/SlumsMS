package scripting;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.Item;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 182: the Papulatus / Scarga / Showa clear handlers hand the leaf reward to every member of
 * {@code eim.getPlayers()} through that member's client, with no bot check. A population bot's client is the shared
 * headless {@link BotClient}: it is built with no player and nothing ever sets one, so {@code Client.getPlayer()} is
 * null on the bot's client. {@code AbstractPlayerInteraction.gainItem} therefore reaches its "your inventory is full"
 * line with a null player — {@code InventoryManipulator.checkSpace(Client, ...)} maps the missing player to
 * {@code false}, and the very next line calls {@code getPlayer().dropMessage(...)} — a NullPointerException. The
 * throw aborted the loop, so every member after the bot silently got nothing, and Scarga's {@code defeatedBoss}
 * counter, written after the loop, stopped advancing for the rest of the instance.
 *
 * <p>Each case below kills the boss with a bot as the <em>first</em> member and a real player second: before the
 * guard the pass dies on the bot and the player's bag stays empty, after it the player gets exactly what a
 * human-only party gets and the bot's shared client is never asked for an interaction at all.
 */
class BossClearRewardBotGuardTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** The real gainItem chain funnels every overload into the six-arg one, so overriding that records the reward. */
    static final class RecordingApi extends AbstractPlayerInteraction {
        final Map<Integer, Integer> items = new HashMap<>();

        RecordingApi(Client client) {
            super(client);
        }

        @Override
        public Item gainItem(int id, short quantity, boolean randomStats, boolean showMessage, long expires, Pet from) {
            items.merge(id, (int) quantity, Integer::sum);
            return null;
        }
    }

    /** A real player: a client whose interaction records into a bag. */
    private static Character human(Client client) {
        Character c = mock(Character.class);
        when(c.getClient()).thenReturn(client);
        when(c.isBot()).thenReturn(false);
        return c;
    }

    /**
     * The bot's shared headless client. Only {@code getAbstractPlayerInteraction} is overridden: the production
     * {@code AbstractPlayerInteraction.gainItem} needs the game's WZ data and database, neither of which a test
     * checkout has, so the double runs the two production lines that matter for a client with no player —
     * {@code InventoryManipulator.checkSpace} (which returns false for a null player) and then the reward message
     * to that null player. Everything else, including the null {@code getPlayer()}, is the real {@link BotClient}.
     */
    static final class HeadlessBotClient extends BotClient {
        int interactions;

        HeadlessBotClient() {
            super(0, 0);
        }

        @Override
        public AbstractPlayerInteraction getAbstractPlayerInteraction() {
            interactions++;
            return new AbstractPlayerInteraction(this) {
                @Override
                public Item gainItem(int id, short quantity, boolean randomStats, boolean showMessage, long expires, Pet from) {
                    if (!InventoryManipulator.checkSpace(HeadlessBotClient.this, id, quantity, "")) {
                        // AbstractPlayerInteraction.java:712, the statement the audit named
                        getPlayer().dropMessage(1, "Your inventory is full. Please remove an item from your inventory.");
                    }
                    return null;
                }
            };
        }
    }

    private static Character bot(HeadlessBotClient client) {
        Character c = mock(Character.class);
        when(c.getClient()).thenReturn(client);
        when(c.isBot()).thenReturn(true);
        return c;
    }

    public static final class FakeMob {
        private final int id;

        FakeMob(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }
    }

    /** The parts of EventInstanceManager the clear handlers touch. */
    public static final class FakeEim {
        final Map<String, Integer> props = new HashMap<>();
        final List<Character> players = new ArrayList<>();
        boolean clearEffect;
        boolean cleared;

        public List<Character> getPlayers() {
            return players;
        }

        public void showClearEffect() {
            clearEffect = true;
        }

        public void clearPQ() {
            cleared = true;
        }

        public void setIntProperty(String key, int value) {
            props.put(key, value);
        }

        public int getIntProperty(String key) {
            return props.getOrDefault(key, 0);
        }
    }

    private static void monsterKilled(String script, int mobId, FakeEim eim) throws Exception {
        ScriptEngine engine = new Loader().load("event/" + script);
        assertNotNull(engine, script + " evaluates");
        ((Invocable) engine).invokeFunction("monsterKilled", new FakeMob(mobId), eim);
    }

    private static Client clientRecording(RecordingApi api) {
        Client client = mock(Client.class);
        when(client.getAbstractPlayerInteraction()).thenReturn(api);
        return client;
    }

    @ParameterizedTest(name = "{0}: a bot first in the party does not swallow {2} x{3}")
    @CsvSource({
            "PapulatusBattle.js, 8500002, 4001126, 5",
            "ScargaBattle.js, 9420544, 4001006, 30",
            "ShowaBattle.js, 9400300, 4001006, 1",
            "LatanicaBattle.js, 9420513, 4001006, 1",
            "MahaBattle.js, 9001014, 4001006, 1"
    })
    void aBotInThePartyDoesNotStopTheClearReward(String script, int mobId, int item, int quantity) throws Exception {
        FakeEim eim = new FakeEim();
        eim.props.put("defeatedBoss", 1); // Scarga only rewards on the first kill; the others ignore it
        HeadlessBotClient botClient = new HeadlessBotClient();
        eim.players.add(bot(botClient)); // a bot's id usually sorts last, but the loop must not depend on that
        RecordingApi playerApi = new RecordingApi(mock(Client.class));
        eim.players.add(human(clientRecording(playerApi)));

        monsterKilled(script, mobId, eim);

        assertEquals(quantity, playerApi.items.get(item), script + " gives the player the leaf after the bot");
        assertEquals(0, botClient.interactions, script + " never asks the bot's headless client for a reward");
        assertNull(botClient.getPlayer(), "the reason: a bot's shared client has no player to hand a leaf to");
    }

    /** Scarga writes its kill counter after the loop; the throw used to skip it for the rest of the instance. */
    @Test
    void scargaStillCountsTheKillWithABotInTheParty() throws Exception {
        FakeEim eim = new FakeEim();
        eim.props.put("defeatedBoss", 1);
        eim.players.add(bot(new HeadlessBotClient()));
        eim.players.add(human(clientRecording(new RecordingApi(mock(Client.class)))));

        monsterKilled("ScargaBattle.js", 9420544, eim);

        assertEquals(2, eim.getIntProperty("defeatedBoss"), "the kill was counted after the reward pass");
    }

    /** What the guard prevents, at the seam the scripts use: the shared client has no player to hand a leaf to. */
    @Test
    void aRewardThatReachesTheBotsClientThrows() {
        HeadlessBotClient botClient = new HeadlessBotClient();

        assertThrows(NullPointerException.class,
                () -> botClient.getAbstractPlayerInteraction().gainItem(4001006, (short) 1),
                "an unguarded loop throws on the first bot it reaches");
        assertEquals(1, botClient.interactions);
        assertNull(botClient.getPlayer());
    }
}

package server.maps;

import client.BotClient;
import client.Character;
import net.opcodes.SendOpcode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGenerationSpawnAccess;
import server.maps.Foothold;
import server.maps.FootholdTree;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2 rc (owner, 2026-10-31, "incomplete playerbots"): a bot that joins a map before it has been decorated reaches
 * every watching client with the source character's look (its hair, its face, no clothes) and nothing ever re-sends
 * the look, because a bot's client is a shared headless {@link BotClient} - the spawn packet is the only chance.
 */
class BotSpawnPacketLookTest {
    static final int SPAWN_PLAYER = SendOpcode.SPAWN_PLAYER.getValue();

    /** The source character's look, as {@code BotGeneration.loadTemplate} hands it over. */
    private static final int TEMPLATE_HAIR = 30000;
    private static final int TEMPLATE_FACE = 20000;
    /** What the dressing pass picks. Distinct from the template's, so the packet says which one went out. */
    private static final int DRESSED_HAIR = 31040;
    private static final int DRESSED_FACE = 21601;
    private static final int BOT_ID = 1_001_234;

    @BeforeAll
    static void init() throws Exception {
        MapEntryHarness.initItemProvider();
    }

    /** {@code addCharLook} writes the face and the hair as ints; the packet is searched for them. */
    private static boolean hasInt(byte[] bytes, int value) {
        for (int i = 0; i + 4 <= bytes.length; i++) {
            if ((bytes[i] & 0xFF) == (value & 0xFF)
                    && (bytes[i + 1] & 0xFF) == ((value >>> 8) & 0xFF)
                    && (bytes[i + 2] & 0xFF) == ((value >>> 16) & 0xFF)
                    && (bytes[i + 3] & 0xFF) == ((value >>> 24) & 0xFF)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theSpawnPacketCarriesTheLookTheBotIsDressedWithNotTheTemplates() {
        try (MapEntryHarness h = new MapEntryHarness(100000000)) {
            Character watcher = h.player(0);
            h.enter(watcher);

            Character bot = Character.getDefault(new BotClient(0, 0));
            bot.setID(BOT_ID);
            bot.setName("Freshbot");
            bot.setHair(TEMPLATE_HAIR);
            bot.setFace(TEMPLATE_FACE);

            BotGenerationSpawnAccess.joinMap(bot, new Point(10, 10), h.map, b -> {
                b.setHair(DRESSED_HAIR);
                b.setFace(DRESSED_FACE);
            });

            List<MapEntryHarness.Sent> spawns = h.sent.stream()
                    .filter(s -> s.opcode() == SPAWN_PLAYER && s.intAt(2) == BOT_ID)
                    .toList();
            assertEquals(1, spawns.size(), "the watcher got exactly one spawn packet for the bot");
            byte[] packet = spawns.get(0).bytes();
            assertTrue(hasInt(packet, DRESSED_HAIR), "the spawn packet carries the dressed hair");
            assertTrue(hasInt(packet, DRESSED_FACE), "the spawn packet carries the dressed face");
            assertFalse(hasInt(packet, TEMPLATE_HAIR), "the spawn packet must not carry the template's hair");
            assertFalse(hasInt(packet, TEMPLATE_FACE), "the spawn packet must not carry the template's face");
        }
    }

    /**
     * Owner, same report: "one floated in mid air". A bot spawns on a map spawn point or a portal, which sits above
     * its floor, and a bot in an unobserved map gets no physics to fall with — so the point the spawn packet carries
     * is where every viewer sees it stand until someone walks in.
     */
    @Test
    void aBotThatSpawnsAboveItsFloorReachesTheMapOnTheGround() {
        try (MapEntryHarness h = new MapEntryHarness(100000000)) {
            FootholdTree tree = new FootholdTree(new Point(-1000, -1000), new Point(3000, 3000));
            tree.insert(new Foothold(new Point(100, 200), new Point(500, 200), 1));
            h.map.setFootholds(tree);

            Character bot = Character.getDefault(new BotClient(0, 0));
            bot.setID(BOT_ID);
            bot.setName("Floatbot");

            BotGenerationSpawnAccess.joinMap(bot, new Point(300, 160), h.map, b -> { });

            assertSame(bot, h.map.getCharacterById(BOT_ID), "the bot is in the map it joined");
            assertEquals(200, bot.getPosition().y, "it stands on the floor below its spawn point, not in mid air");
            assertEquals(300, bot.getPosition().x, "and keeps the x it was given");
        }
    }
}

package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.maps.Portal;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * Owner review of the KS rally: a helper already on the player's map was moved with changeMap, so a real player saw
 * the bot vanish and reappear beside them. bringTo must walk a same-map bot over (bounded, so it can fight) and only
 * warp for a real map change.
 */
class DramaKitBringToTest {

    static final int PLAYER = 42;
    static final int MAP = 104040000;
    static final int OTHER_MAP = 104040001;
    static final int BOT_ID = BotStore.BOT_ID_BASE + 7;

    MapleMap map;
    Character player;
    Character bot;
    MockedStatic<BotChannels> channels;

    @BeforeEach
    void setUp() {
        map = mock(MapleMap.class);
        when(map.getId()).thenReturn(MAP);
        when(map.getFootholds()).thenReturn(platform());
        when(map.getCharacters()).thenReturn(List.of());

        player = Character.getDefault(new BotClient(0, 0));
        player.setID(PLAYER);
        player.setName("AutoQaOne");
        player.setMap(map);
        player.setPosition(new Point(600, 100));

        bot = spy(Character.getDefault(new BotClient(0, 0)));
        bot.setID(BOT_ID);
        bot.setName("helper");
        bot.setMap(map);
        bot.setPosition(new Point(100, 100)); // 500 px away: the old code warped it to the player

        channels = Mockito.mockStatic(BotChannels.class);
        channels.when(() -> BotChannels.map(anyInt(), anyInt())).thenReturn(map);
    }

    @AfterEach
    void tearDown() {
        channels.close();
        GCMovement.disable(bot);
        if (CharacterStorage.getBotById(BOT_ID) != null) {
            BotTypeManager.manuallyStopBot(bot);
        }
    }

    @Test
    void aBotAlreadyOnThePlayersMapWalksInsteadOfWarping() {
        assertTrue(DramaKit.bringTo(bot, player, 0, true), "bringTo reports the bot on the player's map");

        verify(bot, never()).changeMap(any(MapleMap.class), any(Point.class));
        assertTrue(GCMovement.isMoving(bot), "a walk intent was set toward the player");
    }

    /*
     * 1.5.1 owner QA: "KS-war bots still appear out of thin air; maybe they should come in from the map." A bot on
     * another map enters at a portal away from the player and walks up; it is never placed beside them.
     */
    @Test
    void aBotOnAnotherMapEntersAtAPortalAwayFromThePlayerAndWalksUp() {
        Portal besidePlayer = portal(2, 620, 100); // 20 px from the player: entering here is appearing beside them
        Portal near = portal(2, 950, 100);
        Portal far = portal(0, -900, 100);
        when(map.getPortals()).thenReturn(List.of(besidePlayer, near, far));
        MapleMap elsewhere = mock(MapleMap.class);
        when(elsewhere.getId()).thenReturn(OTHER_MAP);
        bot.setMap(elsewhere);
        doAnswer(inv -> { // a real map change needs a channel; set what it would
            bot.setMap(inv.getArgument(0));
            bot.setPosition(new Point(inv.<Point>getArgument(1)));
            return null;
        }).when(bot).changeMap(any(MapleMap.class), any(Point.class));
        // Already a training bot (most KS helpers): re-typing it stops its old FSM, whose stop disables movement.
        // The walk must start after that, or the helper stands where it entered.
        BotTypeManager.convertBotType(bot, BotTypeManager.BotType.TRAINING_BOT);

        assertTrue(DramaKit.bringTo(bot, player, 0, true), "the bot came to the player's map");

        ArgumentCaptor<Point> at = ArgumentCaptor.forClass(Point.class);
        verify(bot, times(1)).changeMap(any(MapleMap.class), at.capture());
        assertEquals(new Point(950, 100), at.getValue(), "the nearest portal that is not beside the player");
        assertTrue(at.getValue().distance(player.getPosition()) >= DramaKit.ARRIVAL_MIN_GAP,
                "no warp lands a bot within " + DramaKit.ARRIVAL_MIN_GAP + " px of the player");
        assertTrue(GCMovement.isMoving(bot), "it walks the rest of the way");
    }

    @Test
    void aBotOnAnotherMapDoesNotComeWhenTheMapHasNoPortalToEnterAt() {
        when(map.getPortals()).thenReturn(List.of());
        MapleMap elsewhere = mock(MapleMap.class);
        when(elsewhere.getId()).thenReturn(OTHER_MAP);
        bot.setMap(elsewhere);

        assertFalse(DramaKit.bringTo(bot, player, 40, false));
        verify(bot, never()).changeMap(any(MapleMap.class), any(Point.class));
    }

    @Test
    void theEntryIsTheShortestWalkAmongPortalsFarEnoughFromThePlayer() {
        Point player = new Point(0, 0);
        Point beside = new Point(50, 0);
        Point upAbove = new Point(250, -300); // 250 px across but a ledge up: a longer walk
        Point sameFloor = new Point(-400, 0);
        assertEquals(sameFloor, DramaKit.chooseEntry(List.of(beside, upAbove, sameFloor), player, 200));
        assertEquals(new Point(250, 0), DramaKit.chooseEntry(List.of(beside, new Point(250, 0), sameFloor), player,
                200));
    }

    @Test
    void whenEveryPortalIsCloseTheFarthestIsUsedAndNoPortalsMeansNoEntry() {
        Point player = new Point(0, 0);
        assertEquals(new Point(-150, 0), DramaKit.chooseEntry(List.of(new Point(30, 0), new Point(-150, 0)), player,
                200));
        assertNull(DramaKit.chooseEntry(List.of(), player, 200));
    }

    @Test
    void noEntryChoiceLandsWithinTheGapWhenAFartherPortalExists() {
        java.util.Random rng = new java.util.Random(7);
        for (int round = 0; round < 500; round++) {
            Point player = new Point(rng.nextInt(2000) - 1000, rng.nextInt(600) - 300);
            List<Point> spots = new java.util.ArrayList<>();
            int n = 1 + rng.nextInt(6);
            for (int i = 0; i < n; i++) {
                spots.add(new Point(rng.nextInt(2400) - 1200, rng.nextInt(800) - 400));
            }
            boolean anyFar = spots.stream().anyMatch(p -> p.distance(player) >= DramaKit.ARRIVAL_MIN_GAP);
            Point pick = DramaKit.chooseEntry(spots, player, DramaKit.ARRIVAL_MIN_GAP);
            if (anyFar) {
                assertTrue(pick.distance(player) >= DramaKit.ARRIVAL_MIN_GAP, "round " + round + ": " + pick);
            }
        }
    }

    @Test
    void onlyPortalsAPlayerIsSeenEnteringAtCount() {
        assertTrue(DramaKit.entryType(0));
        assertTrue(DramaKit.entryType(2));
        assertTrue(DramaKit.entryType(7));
        assertFalse(DramaKit.entryType(1)); // invisible in-map teleport
        assertFalse(DramaKit.entryType(3)); // collision
    }

    /** Every changeMap of a bot in the drama package goes through the arrival (or home, off the player's map). */
    @Test
    void noDramaCodeWarpsABotStraightToThePlayer() throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Path.of("src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem");
        try (var files = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path f : files.toList()) {
                List<String> lines = java.nio.file.Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    String l = lines.get(i).trim();
                    if (!l.contains(".changeMap(") || l.startsWith("//") || l.startsWith("*")) {
                        continue;
                    }
                    String where = f.getFileName() + ":" + (i + 1) + " " + l;
                    boolean ok = l.startsWith("gm.changeMap(") // a GM command moving the GM
                            || l.equals("bot.changeMap(map, entry);") // DramaKit.arrive: at a portal
                            || l.equals("bot.changeMap(back, new Point(home.x(), home.y()));"); // sendHome
                    assertTrue(ok, "a bot warped by drama code outside DramaKit.arrive: " + where);
                }
            }
        }
    }

    private static Portal portal(int type, int x, int y) {
        Portal p = mock(Portal.class);
        when(p.getType()).thenReturn(type);
        when(p.getPosition()).thenReturn(new Point(x, y));
        return p;
    }

    /** One continuous foothold under both characters, so the walk has ground to path on. */
    private static FootholdTree platform() {
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        tree.insert(new Foothold(new Point(-1000, 100), new Point(1000, 100), 1));
        return tree;
    }
}

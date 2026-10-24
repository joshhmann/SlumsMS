package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

    @Test
    void aBotOnAnotherMapStillWarpsToThePlayer() {
        MapleMap elsewhere = mock(MapleMap.class);
        when(elsewhere.getId()).thenReturn(OTHER_MAP);
        bot.setMap(elsewhere);

        DramaKit.bringTo(bot, player, 0, true);

        verify(bot, times(1)).changeMap(any(MapleMap.class), any(Point.class));
    }

    /** One continuous foothold under both characters, so the walk has ground to path on. */
    private static FootholdTree platform() {
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        tree.insert(new Foothold(new Point(-1000, 100), new Point(1000, 100), 1));
        return tree;
    }
}

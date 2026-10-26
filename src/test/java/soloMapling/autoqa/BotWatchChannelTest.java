package soloMapling.autoqa;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * TEST 2026-10-26 bot watch: knucklegolem "jumped 1253 px past a walk on map 103000000 with a player there". The only
 * player on Kerning City was the observer on channel 2; the bot was on channel 1, where nobody could see it and the
 * likeness rule (WatchGate, per map instance) lets a snap happen. The watch must judge "watched" the same way: the
 * bot's own channel's map, not the map id on any channel.
 */
class BotWatchChannelTest {

    private static final int ID = 1_000_777_001;
    private static final int KERNING = 103000000;

    @AfterEach
    void clear() {
        CharacterStorage.removeActiveBot(ID);
    }

    private Character bot(MapleMap map) {
        Character chr = spy(Character.getDefault(new BotClient(0, 0)));
        chr.setID(ID);
        chr.setName("knuckle");
        doReturn(map).when(chr).getMap();
        doReturn(KERNING).when(chr).getMapId();
        BotSM bot = mock(BotSM.class);
        when(bot.getChr()).thenReturn(chr);
        CharacterStorage.addActiveBot(ID, bot);
        return chr;
    }

    private static MapleMap kerning(int channel, List<Character> players) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(KERNING);
        when(map.getChannelId()).thenReturn(channel);
        when(map.getAllPlayers()).thenReturn(players);
        return map;
    }

    private static BotWatch.Tracked jump(Character chr) {
        BotWatch.Tracked t = new BotWatch.Tracked(ID, "knuckle", "RESTOCK");
        chr.setPosition(new Point(-1888, 6)); // the cab drops it at Kerning's portal 0
        BotWatch.get().positionTick(t, 10_000);
        chr.setPosition(new Point(877, -151)); // 2 s later across town
        BotWatch.get().positionTick(t, 12_000);
        return t;
    }

    @Test
    void aPlayerOnAnotherChannelsKerningDoesNotWatchThisBot() {
        GCMovement.markObservedNow(KERNING); // the observer on channel 2: the map id counts as observed
        Character chr = bot(kerning(1, List.of()));
        BotWatch.Tracked t = jump(chr);
        assertTrue(t.jumpPx > 0, "the jump is still measured");
        assertFalse(t.jumpWatched, "nobody on channel 1's Kerning could see it");
        assertFalse(BotWatch.watchedHere(chr));
    }

    @Test
    void aPlayerOnTheBotsOwnKerningWatchesIt() {
        GCMovement.markObservedNow(KERNING);
        Character player = mock(Character.class);
        when(player.getClient()).thenReturn(mock(Client.class)); // a real client, not a bot's
        Character chr = bot(kerning(1, List.of(player)));
        BotWatch.Tracked t = jump(chr);
        assertTrue(t.jumpPx > 0);
        assertTrue(t.jumpWatched, "a jump in front of a player on the same channel is TELEPORTY");
    }
}

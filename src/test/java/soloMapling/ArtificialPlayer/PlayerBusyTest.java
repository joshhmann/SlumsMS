package soloMapling.ArtificialPlayer;

import client.Character;
import client.Client;
import client.BotClient;
import net.opcodes.RecvOpcode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import scripting.event.EventInstanceManager;
import server.Storage;
import server.Trade;
import server.Shop;
import server.maps.MapleMap;
import server.maps.PlayerShop;
import server.maps.HiredMerchant;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeCommands;
import soloMapling.Economy.BotDirectTrade;
import soloMapling.Economy.BotGifts;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerBusyTest {
    Character player;
    Client client;
    MapleMap map;
    long now = System.currentTimeMillis();

    @BeforeEach void setup() {
        player = mock(Character.class);
        client = mock(Client.class);
        map = mock(MapleMap.class);
        when(player.getClient()).thenReturn(client);
        when(player.getMap()).thenReturn(map);
        when(player.getMapId()).thenReturn(100000000);
        when(client.getLastPlayerAction()).thenReturn(now);
    }

    @AfterEach void cleanup() {
        System.clearProperty("solomapling.botHoldOffBusy");
    }

    @Test void ordinaryActivePlayerIsAvailable() {
        assertNull(PlayerBusy.reason(player, now));
        assertFalse(PlayerBusy.isBusy(player));
    }

    @Test void afkBoundaryIsStrictlyMoreThanFiveMinutes() {
        when(client.getLastPlayerAction()).thenReturn(now - 300_000);
        assertNull(PlayerBusy.reason(player, now));
        assertEquals("AFK over five minutes", PlayerBusy.reason(player, now + 1));
        when(client.getLastPlayerAction()).thenReturn(now + 1000);
        assertNull(PlayerBusy.reason(player, now));
    }

    @Test void eventOnCharacterOrMapHoldsOff() {
        when(player.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertTrue(PlayerBusy.isBusy(player));
        when(player.getEventInstance()).thenReturn(null);
        when(map.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertTrue(PlayerBusy.isBusy(player));
    }

    @ParameterizedTest @ValueSource(ints = {280030000, 240060201, 220080001, 230040420})
    void bossMapsHoldOffEvenBetweenSpawns(int id) {
        when(player.getMapId()).thenReturn(id);
        assertTrue(PlayerBusy.isBusy(player));
    }

    @Test void liveBossesAndNonInstancedGmEventsAlsoHoldOff() {
        when(map.isEventMap()).thenReturn(true);
        assertTrue(PlayerBusy.isBusy(player));
        when(map.isEventMap()).thenReturn(false);
        server.life.Monster boss = mock(server.life.Monster.class);
        when(boss.isBoss()).thenReturn(true);
        when(map.getAllMonsters()).thenReturn(java.util.List.of(boss));
        assertTrue(PlayerBusy.isBusy(player));
    }

    enum Window { TRADE, SHOP, MERCHANT, NPC_SHOP, STORAGE, NPC_DIALOG, ORE_STORAGE }

    @ParameterizedTest @EnumSource(Window.class)
    void eachOpenWindowHoldsOff(Window window) {
        switch (window) {
            case TRADE -> when(player.getTrade()).thenReturn(mock(Trade.class));
            case SHOP -> when(player.getPlayerShop()).thenReturn(mock(PlayerShop.class));
            case MERCHANT -> when(player.getHiredMerchant()).thenReturn(mock(HiredMerchant.class));
            case NPC_SHOP -> when(player.getShop()).thenReturn(mock(Shop.class));
            case STORAGE -> {
                Storage storage = mock(Storage.class);
                when(player.getStorage()).thenReturn(storage);
                when(storage.isOpen()).thenReturn(true);
            }
            case NPC_DIALOG -> when(client.getCM()).thenReturn(mock(scripting.npc.NPCConversationManager.class));
            case ORE_STORAGE -> when(player.getUsingOreStorage()).thenReturn(true);
        }
        assertTrue(PlayerBusy.isBusy(player));
    }

    @Test void merelyOwningStorageIsNotBusy() {
        when(player.getStorage()).thenReturn(mock(Storage.class));
        assertFalse(PlayerBusy.isBusy(player));
    }

    @Test void switchDisablesHoldOffAndBotsAreExempt() {
        when(player.getTrade()).thenReturn(mock(Trade.class));
        assertTrue(PlayerBusy.isBusy(player));
        System.setProperty("solomapling.botHoldOffBusy", "false");
        assertFalse(PlayerBusy.isBusy(player));
        System.clearProperty("solomapling.botHoldOffBusy");
        when(player.getClient()).thenReturn(mock(BotClient.class));
        assertFalse(PlayerBusy.isBusy(player));
    }

    @Test void outgoingInvitesAndGiftsDoNothingWhenBusy() {
        when(player.getTrade()).thenReturn(mock(Trade.class));
        Character bot = mock(Character.class);
        assertFalse(BotPartyCommands.botInvitePlayer(bot, player));
        BotTradeCommands.sendTradeRequestToPlayer(bot, player);
        assertNull(BotGifts.give(bot, player, BotGifts.Kind.MESOS, new Random(1)));
        assertFalse(BotGifts.maybeGift(bot, player));
        assertFalse(BotDirectTrade.chat(null, bot, player, "buy work gloves").yes());
        verifyNoInteractions(bot);
        verify(player, never()).sendPacket(any());
        verify(player, never()).setTrade(any());
    }

    @Test void keepaliveDoesNotUpdateActionClockButMovementDoes() throws Exception {
        Client realClock = mock(Client.class, CALLS_REAL_METHODS);
        var field = Client.class.getDeclaredField("lastPlayerAction");
        field.setAccessible(true);
        field.setLong(realClock, 1L);
        realClock.notePlayerAction((short) RecvOpcode.PONG.getValue());
        assertEquals(1L, realClock.getLastPlayerAction());
        realClock.notePlayerAction((short) RecvOpcode.MOVE_PLAYER.getValue());
        assertTrue(realClock.getLastPlayerAction() >= now);
    }

    @ParameterizedTest
    @EnumSource(value = RecvOpcode.class, names = {"MOVE_PLAYER", "GENERAL_CHAT", "WHISPER",
            "MAGIC_ATTACK", "SPECIAL_MOVE", "NPC_SHOP", "STORAGE", "USE_ITEM", "PLAYER_INTERACTION",
            "USE_UPGRADE_SCROLL", "ITEM_PICKUP", "MAKER_SKILL", "PET_COMMAND", "BBS_OPERATION"})
    void deliberateInputCountsAsActivity(RecvOpcode opcode) {
        assertTrue(Client.isPlayerAction((short) opcode.getValue()));
    }

    @ParameterizedTest
    @EnumSource(value = RecvOpcode.class, names = {"PONG", "MOVE_PET", "MOVE_SUMMON", "TAKE_DAMAGE",
            "HEAL_OVER_TIME", "PET_AUTO_POT", "SUMMON_ATTACK", "TOUCH_MONSTER_ATTACK"})
    void passivePacketsDoNotKeepAfkPlayersActive(RecvOpcode opcode) {
        assertFalse(Client.isPlayerAction((short) opcode.getValue()));
    }
}

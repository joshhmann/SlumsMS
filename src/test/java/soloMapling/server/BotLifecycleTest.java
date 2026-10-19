package soloMapling.server;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.*;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeQueue;
import soloMapling.ArtificialPlayer.BotWanderSystem.BotWanderSystem;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotLifecycleTest {
    @Test
    void despawnStopsRegisteredBrainMovementAndPendingInteractions() {
        Character bot = mock(Character.class);
        Character inviter = mock(Character.class);
        when(bot.getId()).thenReturn(1_000_000_777);
        when(bot.getClient()).thenReturn(mock(BotClient.class));
        BotSM brain = mock(BotSM.class);
        CharacterStorage.addActiveBot(bot.getId(), brain);
        BotTradeQueue.getInstance().addTradeRequest(bot, inviter);
        BotPartyQueue.getInstance().addPartyInvite(bot, inviter, 77);
        try (var movement = mockStatic(GCMovement.class); var wandering = mockStatic(BotWanderSystem.class)) {
            BotLifecycle.stopActivities(bot);
            verify(brain).setRunning(false);
            verify(brain).stopScheduledTask();
            movement.verify(() -> GCMovement.disable(bot));
            wandering.verify(() -> BotWanderSystem.stop(bot));
            assertFalse(BotTradeQueue.getInstance().hasPendingTrades(bot));
            assertFalse(BotPartyQueue.getInstance().hasPendingInvite(bot));
        } finally {
            CharacterStorage.removeActiveBot(bot.getId());
            BotTradeQueue.getInstance().removeTradeRequest(bot);
            BotPartyQueue.getInstance().removePartyInvite(bot);
        }
    }

    @Test
    void clearingEitherBotTradeRemovesItsQueuedInvite() {
        Character bot = mock(Character.class);
        when(bot.getClient()).thenReturn(mock(BotClient.class));
        Character inviter = mock(Character.class);
        BotTradeQueue.getInstance().addTradeRequest(bot, inviter);
        doCallRealMethod().when(bot).setTrade(null);
        bot.setTrade(null);
        assertFalse(BotTradeQueue.getInstance().hasPendingTrades(bot));
    }

    @Test
    void botDespawnRefusesRealPlayersRegardlessOfNumericId() {
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(1_000_000_777);
        when(player.getClient()).thenReturn(mock(Client.class));
        assertThrows(IllegalArgumentException.class, () -> BotLifecycle.stopActivities(player));
        verify(player, never()).getTrade();
        verify(player, never()).getParty();
    }

    @Test
    void hiddenPlayerSpawnsDoNotRequireAPlayerOnTheSharedBotClient() {
        Character hiddenPlayer = mock(Character.class);
        when(hiddenPlayer.isHidden()).thenReturn(true);
        BotClient recipient = mock(BotClient.class);
        doCallRealMethod().when(hiddenPlayer).sendSpawnData(recipient);
        assertDoesNotThrow(() -> hiddenPlayer.sendSpawnData(recipient));
        verify(recipient, never()).getPlayer();
        verify(recipient, never()).sendPacket(any());
    }

    @Test
    void absentOptionalLoginEquipmentDoesNotGenerateAnInventoryPacket() {
        Character player = mock(Character.class);
        doCallRealMethod().when(player).forceUpdateItem(null);
        assertDoesNotThrow(() -> player.forceUpdateItem(null));
        verify(player, never()).sendPacket(any());
    }
}

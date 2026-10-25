package soloMapling.companions;

import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnedAltExclusionTest {
    @Test void ownedAltsCannotEnterPopulationRegistryOrRosterSessions() {
        Character alt = mock(Character.class); when(alt.isOwnedAlt()).thenReturn(true); when(alt.getId()).thenReturn(400_002);
        BotSM bot = mock(BotSM.class); when(bot.getChr()).thenReturn(alt);
        assertThrows(IllegalArgumentException.class, () -> CharacterStorage.addActiveBot(400_002,bot));
        assertNull(CharacterStorage.getBotById(400_002));
        assertEquals(0,BotRoster.recordSessions(java.util.List.of(alt),0,true));
    }
    @Test void newlyRecruitedFriendCountsBeforeItsFirstTick() {
        boolean enabled = config.YamlConfig.config.server.OWNED_ALTS_ENABLED;
        int id = 1_000_400_002;
        try {
            config.YamlConfig.config.server.OWNED_ALTS_ENABLED = true;
            var chr = Character.getDefault(new client.BotClient(1,1)); chr.setID(id);
            soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager.setPendingLeader(id,12);
            var follower = new soloMapling.ArtificialPlayer.BotTypes.FollowerBot(chr);
            CharacterStorage.addActiveBot(id,follower);
            assertEquals(12,follower.leaderId());
            assertTrue(new OwnedAltBackend().companions(12).contains(id));
        } finally {
            CharacterStorage.removeActiveBot(id);
            soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager.consumePendingLeader(id);
            config.YamlConfig.config.server.OWNED_ALTS_ENABLED = enabled;
        }
    }

    @Test void enabledFriendCapacityBelongsToOwnerRatherThanWholeWorld() {
        boolean enabled = config.YamlConfig.config.server.OWNED_ALTS_ENABLED;
        var manager = OwnedAltService.getInstance();
        int base = 1_000_410_000, botId = base + 100, ownerId = 410_001;
        try (var affinity = mockStatic(soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.class)) {
            config.YamlConfig.config.server.OWNED_ALTS_ENABLED = true;
            for (int n = 0; n < 30; n++) {
                BotSM bot = mock(BotSM.class); when(bot.getBotType()).thenReturn("FollowerBot");
                CharacterStorage.addActiveBot(base+n,bot);
            }
            Character bot = mock(Character.class); when(bot.getLevel()).thenReturn(10); when(bot.getId()).thenReturn(botId);
            Character owner = OwnedAltServiceTest.owner(ownerId);
            assertEquals(soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager.RecruitAnswer.ACCEPTED,
                    soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager.rollPartyAsk(bot,owner,1.0,true));
            assertTrue(soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager.tryReserveFollower(botId));
            soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager.releaseFollowerReservation(botId);
            for (int n = 0; n < 5; n++) assertTrue(manager.reserveCompanion(ownerId,base+200+n));
            assertEquals(soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager.RecruitAnswer.FOLLOWERS_FULL,
                    soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager.rollPartyAsk(bot,owner,1.0,true));
        } finally {
            for (int n = 0; n < 30; n++) CharacterStorage.removeActiveBot(base+n);
            for (int n = 0; n < 5; n++) manager.releaseCompanion(base+200+n);
            soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager.releaseFollowerReservation(botId);
            soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager.clearArmed(botId);
            config.YamlConfig.config.server.OWNED_ALTS_ENABLED = enabled;
        }
    }

}

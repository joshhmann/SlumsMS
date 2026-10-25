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

}

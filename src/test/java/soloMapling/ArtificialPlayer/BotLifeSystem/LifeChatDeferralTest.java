package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotChatActions;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LifeChatDeferralTest {
    @Test void pendingFriendPartyRequestCannotBeVoicedAsAnAcceptedAction() {
        String old=System.getProperty("slums.botlife.enabled");
        String execute=System.getProperty("slums.botlife.execute");
        var bot=mock(TrainingBot.class);
        var chr=mock(Character.class);
        var player=mock(Character.class);
        var life=mock(LifeRuntime.class);
        when(bot.getChr()).thenReturn(chr);when(bot.lifeRuntime()).thenReturn(life);
        when(player.getMapId()).thenReturn(200080101);
        when(life.friendAsk(eq(player),eq(true),anyLong())).thenReturn(true);
        try {
            System.setProperty("slums.botlife.enabled","true");
            System.setProperty("slums.botlife.execute","true");
            var answer=BotChatActions.act(bot,player,ChatTriage.Intent.PARTY);
            assertEquals(BotChatActions.Outcome.PARTY_DECLINED,answer.outcome());
            assertFalse(answer.fits("ya omw"));
            assertFalse(answer.fits("I'll come"));
            assertTrue(answer.fits("lemme check a sec"));
            verify(life).friendAsk(eq(player),eq(true),anyLong());
        } finally {
            if(old==null)System.clearProperty("slums.botlife.enabled");else System.setProperty("slums.botlife.enabled",old);
            if(execute==null)System.clearProperty("slums.botlife.execute");else System.setProperty("slums.botlife.execute",execute);
        }
    }
}

package soloMapling.ArtificialPlayer;

import client.Character;
import server.Trade;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeQueue;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

/** Stop background work before removing a headless character from world storage. */
public final class BotLifecycle {
    private BotLifecycle() {}

    public static void stopActivities(Character bot) {
        if (!BotHelpers.isBot(bot)) {
            throw new IllegalArgumentException("Only headless bots may use bot lifecycle cleanup");
        }
        BotSM state = CharacterStorage.getBotById(bot.getId());
        if (state != null) {
            state.setRunning(false);
            state.stopScheduledTask();
        }
        soloMapling.ArtificialPlayer.BotWanderSystem.BotWanderSystem.stop(bot);
        GCMovement.disable(bot);
        BotRecruitManager.clearHandoffs(bot.getId());
        BotPartyQueue.getInstance().removePartyInvite(bot);
        if (bot.getTrade() != null) Trade.cancelTrade(bot, Trade.TradeResult.PARTNER_CANCEL);
        BotTradeQueue.getInstance().removeTradeRequest(bot);
        if (bot.getParty() != null) BotPartyCommands.botLeaveParty(bot);
    }
}

package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.BotTypeManager;

import java.util.function.BiPredicate;

/**
 * {@code !followfor <botCid> <leaderName>}: makes a bot a follower of another online character, as {@code !bot followbot}
 * does for the commanding GM. QA staging only (1.6.5): a Zakum run's leader must be a non-GM player, so it cannot type
 * the GM command itself, and the expedition only registers followers of the player who talks to the NPC.
 */
public class FollowForCommand extends Command {
    {
        setDescription("Make a bot follow another online character (QA staging). !followfor <botCid> <leaderName>");
    }

    /** Converts the bot to a follower; false when refused (mid-trade). Swapped by tests. */
    static BiPredicate<Character, Character> follow = (bot, leader) -> {
        BotRecruitManager.setPendingLeader(bot.getId(), leader.getId());
        return BotTypeManager.convertBotType(bot, BotTypeManager.BotType.FOLLOWER_BOT);
    };

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        Integer cid = params.length == 2 ? parse(params[0]) : null;
        if (cid == null) {
            gm.yellowMessage("Syntax: !followfor <botCid> <leaderName>");
            return;
        }
        Character bot = BotHelpers.getCharFromChannelStorage(cid);
        Character leader = c.getWorldServer().getPlayerStorage().getCharacterByName(params[1]);
        gm.yellowMessage(apply(bot, leader, params[1]));
    }

    static String apply(Character bot, Character leader, String leaderName) {
        if (bot == null || !BotHelpers.isBot(bot)) {
            return "No bot with that character id is online.";
        }
        if (leader == null) {
            return leaderName + " is not online.";
        }
        if (BotHelpers.isBot(leader)) {
            return leaderName + " is a bot; a follower needs a player leader.";
        }
        return follow.test(bot, leader)
                ? bot.getName() + " now follows " + leader.getName() + "."
                : "Conversion refused - " + bot.getName() + " is mid-trade.";
    }

    private static Integer parse(String s) {
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

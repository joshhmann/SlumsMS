package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter;

/**
 * Ticket 47: per-player bot noise. Hides bot megaphones and/or ambient bot chatter for this character only. Real
 * players' chat and megaphones and GM notices are never hidden. 1.6.0 (S5): {@code @quiet bots} also stops every
 * unasked bot act aimed at the player - whispers, invites, buddy requests, greetings, taunts, side-eye, gifts, call-outs
 * (SocialGate) - while bots they talk to still answer; the choice is kept across restarts (migration 031).
 */
public class QuietCommand extends Command {
    {
        setDescription("Hide bot noise: @quiet megaphones | bots | all | off (megaphones off / bots off: show that one again). @settings has a menu.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        BotNoiseFilter.Setting now = BotNoiseFilter.get(player.getId());
        String arg = params.length == 0 ? "" : params[0];
        // 1.5.0 (@settings): "@quiet megaphones off" / "@quiet bots off" show that one again and keep the other.
        boolean hide = !(params.length > 1 && "off".equals(params[1]));
        BotNoiseFilter.Setting next = switch (arg) {
            case "megaphones", "megaphone", "megas", "mega", "smega" -> new BotNoiseFilter.Setting(hide, now.bots());
            case "bots", "bot", "chatter", "chat" -> new BotNoiseFilter.Setting(now.megaphones(), hide);
            case "all", "both" -> new BotNoiseFilter.Setting(true, true);
            case "off", "none", "reset" -> new BotNoiseFilter.Setting(false, false);
            default -> null;
        };
        if (next == null) {
            player.yellowMessage("Usage: @quiet megaphones | @quiet bots | @quiet all | @quiet off (@quiet megaphones off shows them again)");
            player.yellowMessage("Now: " + describe(now) + ". Real players and GM notices are never hidden.");
            return;
        }
        BotNoiseFilter.set(player.getId(), next);
        player.yellowMessage("Bot noise: " + describe(next) + ". Real players and GM notices are never hidden.");
    }

    static String describe(BotNoiseFilter.Setting s) {
        if (s.megaphones() && s.bots()) {
            return "bot megaphones and bot chatter hidden, and nothing unasked from bots aimed at you";
        }
        if (s.megaphones()) {
            return "bot megaphones hidden";
        }
        if (s.bots()) {
            return "bot chatter hidden and nothing unasked from bots aimed at you (bots you talk to or party with still"
                    + " show)";
        }
        return "everything shown";
    }
}

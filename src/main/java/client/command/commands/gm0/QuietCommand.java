package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter;

/**
 * Ticket 47: per-player bot noise. Hides bot megaphones and/or ambient bot chatter for this character only. Real
 * players' chat and megaphones and GM notices are never hidden. Kept until the server restarts.
 */
public class QuietCommand extends Command {
    {
        setDescription("Hide bot noise: @quiet megaphones | bots | all | off.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        BotNoiseFilter.Setting now = BotNoiseFilter.get(player.getId());
        String arg = params.length == 0 ? "" : params[0];
        BotNoiseFilter.Setting next = switch (arg) {
            case "megaphones", "megaphone", "megas", "mega", "smega" -> new BotNoiseFilter.Setting(true, now.bots());
            case "bots", "bot", "chatter", "chat" -> new BotNoiseFilter.Setting(now.megaphones(), true);
            case "all", "both" -> new BotNoiseFilter.Setting(true, true);
            case "off", "none", "reset" -> new BotNoiseFilter.Setting(false, false);
            default -> null;
        };
        if (next == null) {
            player.yellowMessage("Usage: @quiet megaphones | @quiet bots | @quiet all | @quiet off");
            player.yellowMessage("Now: " + describe(now) + ". Real players and GM notices are never hidden.");
            return;
        }
        BotNoiseFilter.set(player.getId(), next);
        player.yellowMessage("Bot noise: " + describe(next) + ". Real players and GM notices are never hidden.");
    }

    static String describe(BotNoiseFilter.Setting s) {
        if (s.megaphones() && s.bots()) {
            return "bot megaphones and bot chatter hidden";
        }
        if (s.megaphones()) {
            return "bot megaphones hidden";
        }
        if (s.bots()) {
            return "bot chatter hidden (bots you talk to or party with still show)";
        }
        return "everything shown";
    }
}

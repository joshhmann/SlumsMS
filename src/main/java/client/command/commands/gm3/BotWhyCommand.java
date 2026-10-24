package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeInspection;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.qa.QaSuite;

/** GM-only, opt-in QA diagnostic. It never invokes a bot action or chats on a bot's behalf. */
public class BotWhyCommand extends Command {
    private static final Logger log = LoggerFactory.getLogger(BotWhyCommand.class);

    {
        setDescription("Inspect a bot's daily-life preview without changing it: !botwhy <name>");
    }

    public static boolean isEnabled() {
        return QaSuite.isEnabled() && Boolean.getBoolean("slums.botlife.preview");
    }

    @Override
    public void execute(Client client, String[] params) {
        Character gm = client.getPlayer();
        if (!isEnabled()) {
            gm.yellowMessage("Bot life preview needs QA and -Dslums.botlife.preview=true.");
            return;
        }
        if (params.length != 1) {
            gm.yellowMessage("Syntax: !botwhy <online bot name>");
            return;
        }
        Character target = client.getWorldServer().getPlayerStorage().getCharacterByName(params[0]);
        BotSM bot = target == null ? null : CharacterStorage.getBotById(target.getId());
        if (bot == null || bot.getChr() != target) {
            gm.yellowMessage("No active bot with that name in this world.");
            return;
        }
        LifeInspection.Snapshot snapshot = LifeInspection.capture(bot);
        var result = LifeInspection.preview(snapshot);
        for (String line : LifeInspection.describe(snapshot, result)) {
            gm.yellowMessage(line);
            log.info("[BotLifePreview] bot={} {}", target.getId(), line);
        }
    }
}

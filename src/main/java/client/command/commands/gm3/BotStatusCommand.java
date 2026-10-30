package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ticket 157 (TEST only, {@link soloMapling.server.DebugTools}; also bug b045): one line per bot on your map - its
 * character id, name, level, job, kind, what it is doing, where it stands and what its planner last decided - so a
 * GM can name the bot a report is about. Read-only; private to the GM.
 * <pre>
 *   !botstatus          the bots on your map (first 40, by name)
 * </pre>
 */
public class BotStatusCommand extends Command {
    {
        setDescription("One line per bot on your map: cid, name, level, job, doing, nav (!botstatus)");
    }

    static final int MAX_LINES = 40;

    /** One bot's line. */
    static String line(int cid, String name, int level, String job, String kind, String doing, int x, int y, String nav) {
        return cid + " " + name + " Lv" + level + " " + job + " " + kind + (doing.isEmpty() ? "" : "/" + doing)
                + " (" + x + "," + y + ") nav " + nav;
    }

    /** The lines for a list of characters (the bots among them, by name), with a header. */
    static List<String> lines(int mapId, String tier, List<Character> onMap) {
        List<Character> bots = new ArrayList<>();
        for (Character chr : onMap) {
            if (chr != null && BotHelpers.isBot(chr)) {
                bots.add(chr);
            }
        }
        bots.sort(Comparator.comparing(Character::getName, String.CASE_INSENSITIVE_ORDER));
        List<String> out = new ArrayList<>();
        out.add("Bots on map " + mapId + " (" + tier + "): " + bots.size()
                + (bots.size() > MAX_LINES ? ", first " + MAX_LINES : ""));
        for (Character chr : bots.subList(0, Math.min(MAX_LINES, bots.size()))) {
            BotSM bot = CharacterStorage.getBotById(chr.getId());
            String kind = bot == null ? "offline-brain" : bot.getClass().getSimpleName().replace("Bot", "").toLowerCase();
            String doing = bot instanceof TrainingBot tb ? tb.phaseName().toLowerCase() : "";
            out.add(line(chr.getId(), chr.getName(), chr.getLevel(), chr.getJob() == null ? "?" : chr.getJob().name(),
                    kind.isEmpty() ? "bot" : kind, doing, chr.getPosition().x, chr.getPosition().y,
                    GCMovement.navDecision(chr)));
        }
        return out;
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        if (gm.getMap() == null) {
            return;
        }
        for (String l : lines(gm.getMapId(), GCMovement.lodTier(gm.getMapId()), gm.getMap().getAllPlayers())) {
            gm.dropMessage(5, l);
        }
    }
}

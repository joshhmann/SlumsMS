package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance;
import soloMapling.ArtificialPlayer.BotDramaSystem.Romance;
import soloMapling.ArtificialPlayer.BotDramaSystem.RomanceWedding;
import soloMapling.server.ExecutorServiceManager;

/**
 * 1.5.0 bot drama: {@code @romance on|off}. Off (the default): no bot ever flirts with, asks out or proposes to this
 * character. On: a bot friend that likes you a lot may flirt, ask you out (or say yes when you ask), and propose; you
 * can marry a bot partner with a ring box. Also {@code @romance} (who you are with), {@code @romance wedding} (in
 * Amoria, engaged to a bot), {@code @romance breakup <name>} and {@code @romance divorce}.
 */
public class RomanceCommand extends Command {
    {
        setDescription("Bot romance: @romance on | off | wedding | breakup <name> | divorce (off by default).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        String arg = params.length == 0 ? "" : params[0].toLowerCase();
        ExecutorServiceManager.getExecutorService().execute(() -> {
            try {
                run(player, arg, params);
            } catch (RuntimeException e) {
                player.yellowMessage("@romance failed: " + e.getMessage());
            }
        });
    }

    private void run(Character player, String arg, String[] params) {
        switch (arg) {
            case "on", "yes", "1", "true" -> {
                BotRomance.setRomance(player, true).forEach(player::yellowMessage);
                player.yellowMessage("Romance is on: a bot friend who really likes you may flirt or ask you out. "
                        + "Flirty, cheesy, dramatic - never explicit. @romance off any time.");
            }
            case "off", "no", "0", "false" -> {
                BotRomance.setRomance(player, false).forEach(player::yellowMessage);
                player.yellowMessage("Romance is off: no bot will flirt with, date or propose to you.");
            }
            case "wedding" -> {
                String problem = RomanceWedding.start(player);
                player.yellowMessage(problem == null ? "Your wedding is on! Say \"i do\" when you are asked."
                        : "No wedding: " + problem + ".");
            }
            case "divorce" -> player.yellowMessage(BotRomance.divorce(player));
            case "breakup", "dump" -> {
                String name = params.length > 1 ? params[1] : null;
                Romance target = null;
                for (Romance r : BotRomance.partners(player.getId())) {
                    String n = soloMapling.ArtificialPlayer.BotBuddies.nameOf(r.botCharId());
                    if (name == null || (n != null && n.equalsIgnoreCase(name))) {
                        target = r;
                    }
                }
                if (target == null) {
                    player.yellowMessage("You are not dating " + (name == null ? "anyone" : name) + ".");
                } else if (target.stage() == Romance.Stage.MARRIED) {
                    player.yellowMessage("You are married: @romance divorce (with the ring in your inventory).");
                } else {
                    BotRomance.end(target, BotRomance.EndReason.PLAYER, true);
                    player.yellowMessage("It's over.");
                }
            }
            default -> {
                player.yellowMessage("Usage: @romance on | off | wedding | breakup <name> | divorce");
                player.yellowMessage("Now: " + (BotRomance.romanceOn(player.getId()) ? "on" : "off (default)") + ".");
                for (Romance r : BotRomance.partners(player.getId())) {
                    player.yellowMessage("You are " + switch (r.stage()) {
                        case MARRIED -> "married to ";
                        case ENGAGED -> "engaged to ";
                        default -> "dating ";
                    } + soloMapling.ArtificialPlayer.BotBuddies.nameOf(r.botCharId()) + ".");
                }
            }
        }
    }
}

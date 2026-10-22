package client.command.commands.gm1;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotConvo.ConvoQuality;

import java.util.List;

/**
 * Ticket 59, owner decision 6: {@code !rate good|bad [note]} rates the last line a bot said to you (map chat, whisper,
 * buddy chat, or a bot thread on your map). A bad bank line is down-weighted (twice: blacklisted); a bad model line
 * becomes a "never write lines like" example, and a quoted phrase in the note joins the don't-say list
 * ({@code data/convo/tune.json}). {@code !rate last} shows the line, {@code !rate reload} re-reads the tune file,
 * {@code !rate score [yyyy-mm-dd]} writes the day's scored sample now.
 */
public class RateCommand extends Command {
    {
        setDescription("Rate the last line a bot said to you: !rate good|bad [note] (also: last, reload, score).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            player.yellowMessage("Syntax: !rate good|bad [note]  |  !rate last  |  !rate reload  |  !rate score [date]");
            return;
        }
        String note = params.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(params, 1, params.length)) : "";
        String what = params[0].toLowerCase(java.util.Locale.ROOT);
        String day = params.length > 1 ? params[1] : null;
        // review 2026-10-22: the tune file, the ratings file and the daily score are file IO - never on the
        // command (packet) thread. The reply comes when the work is done.
        executor.execute(() -> {
            List<String> out = switch (what) {
                case "good", "+", "up" -> ConvoQuality.rate(player, true, note);
                case "bad", "-", "down" -> ConvoQuality.rate(player, false, note);
                case "last" -> ConvoQuality.describeLast(player);
                case "reload" -> List.of(soloMapling.ArtificialPlayer.BotConvo.ConvoTune.reload());
                case "score" -> ConvoQuality.scoreNow(day);
                default -> List.of("Syntax: !rate good|bad [note]  |  !rate last  |  !rate reload  |  !rate score [date]");
            };
            out.forEach(player::yellowMessage);
        });
    }

    /** Where the work runs: the server's async executor (a test seam). */
    static volatile java.util.concurrent.Executor executor = soloMapling.server.ExecutorServiceManager::runAsync;
}

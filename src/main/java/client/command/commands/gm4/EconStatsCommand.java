package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.Economy.stats.EconomySnapshotService;
import soloMapling.server.ExecutorServiceManager;

/**
 * 1.6.0 Track D: on-demand economy snapshot command.
 * !econstats - runs a snapshot asynchronously and reports summary stats in chat.
 */
public class EconStatsCommand extends Command {
    /** Where the snapshot work runs; tests swap it so a unit test never touches the DB or the disk. */
    private static volatile java.util.function.Consumer<Runnable> runner = ExecutorServiceManager::runAsync;

    /** Test seam: swaps the runner and returns the previous one. */
    public static java.util.function.Consumer<Runnable> setRunnerForTest(java.util.function.Consumer<Runnable> r) {
        var prev = runner;
        runner = r;
        return prev;
    }

    {
        setDescription("Run an on-demand economy snapshot: !econstats");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        player.yellowMessage("Starting economy stats snapshot...");
        runner.accept(() -> {
            try {
                var res = EconomySnapshotService.getInstance().takeOnDemandSnapshot();
                player.yellowMessage(String.format(
                        "Economy snapshot complete -> %s", res.publicFile().getFileName()));
                player.yellowMessage(String.format(
                        "Meso supply: Humans %,d mesos, Bots %,d mesos",
                        res.totalHumanMeso(), res.totalBotMeso()));
                player.yellowMessage(String.format(
                        "Gini: Humans %.3f, Bots %.3f | Flows: Faucets %,d, Sinks %,d",
                        res.humanGini(), res.botGini(), res.totalSources(), res.totalSinks()));
            } catch (Exception e) {
                player.yellowMessage("Economy snapshot failed: " + e.getMessage());
            }
        });
    }
}

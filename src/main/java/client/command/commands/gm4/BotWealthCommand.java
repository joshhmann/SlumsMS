package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotWealth;
import soloMapling.Gachapon.BotGacha;
import soloMapling.Gachapon.GachaStats;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Ticket 56B (1.5.0): the bot wealth ceiling.
 * !botwealth               - online bots per level band: how many, mesos held, how many over their ceiling, the
 *                            ceiling's ledger and the gachapon counters
 * !botwealth <bot>         - one bot: mesos vs its ceiling, intake, tickets today
 * !botwealth <bot> spend   - that bot takes one spending step now (gachapon if its town has a machine, then upkeep)
 */
public class BotWealthCommand extends Command {
    {
        setDescription("Bot wealth vs ceiling: !botwealth [<bot> [spend]]");
    }

    static final int[] BANDS = {1, 30, 50, 70, 90, 120, 201};

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            long[] n = new long[BANDS.length - 1], held = new long[BANDS.length - 1], over = new long[BANDS.length - 1],
                    excess = new long[BANDS.length - 1];
            for (BotSM sm : CharacterStorage.getAllBots().values()) {
                Character chr = sm.getChr();
                if (chr == null) {
                    continue;
                }
                int b = 0;
                while (b < BANDS.length - 2 && chr.getLevel() >= BANDS[b + 1]) {
                    b++;
                }
                n[b]++;
                held[b] += chr.getMeso();
                long x = Math.max(0, (long) chr.getMeso() - BotWealth.ceiling(chr));
                if (x > 0) {
                    over[b]++;
                    excess[b] += x;
                }
            }
            player.yellowMessage("Online bots by level: count, avg mesos, over ceiling, mesos over");
            for (int b = 0; b < n.length; b++) {
                if (n[b] == 0) {
                    continue;
                }
                player.yellowMessage(String.format("  Lv %d-%d: %d bots, avg %,d, %d over (%,d over in all), ceiling at"
                                + " Lv %d: %,d", BANDS[b], BANDS[b + 1] - 1, n[b], held[b] / n[b], over[b], excess[b],
                        BANDS[b], BotWealth.ceiling(BANDS[b], player.getWorldServer().getMesoRate())));
            }
            player.yellowMessage(soloMapling.ArtificialPlayer.PlayerWealthIndex.line());
            player.yellowMessage(BotWealth.ledgerLine());
            player.yellowMessage(GachaStats.line());
            return;
        }
        Character bot = GachaRollCommand.findBot(params[0]);
        if (bot == null) {
            player.yellowMessage("No online bot " + params[0]);
            return;
        }
        if (params.length > 1 && params[1].equalsIgnoreCase("spend")) {
            var lines = BotWealth.step(bot, ThreadLocalRandom.current(), true);
            if (lines.isEmpty()) {
                player.yellowMessage(bot.getName() + " had nothing to spend (under its ceiling, no gachapon here or not"
                        + " in the mood).");
            }
            lines.forEach(player::yellowMessage);
        }
        player.yellowMessage(BotWealth.line(bot));
        player.yellowMessage(String.format("  gachapon: %d tickets today, %s, ticket %,d mesos", BotGacha.ticketsToday(
                bot.getId()), BotGacha.isFan(bot.getId()) ? "a gacha fan" : "plays only to spend down",
                BotGacha.ticketPrice()));
    }
}

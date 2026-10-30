package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotAttackSystem.KillCalibration;

/**
 * Ticket 157 (TEST only, {@code -Dslums.killcalib}, following {@link soloMapling.server.DebugTools}): the kill
 * calibration summary - real kills a minute of solo grinding bots on full simulation against what OffscreenCombat
 * models for them, by job and level band. Read-only; private to the GM; audited like every GM command. The full
 * table is the dashboard's {@code calibration.json}.
 * <pre>
 *   !killcalib          the summary and the 12 busiest buckets
 *   !killcalib all      up to 40 buckets
 * </pre>
 */
public class KillCalibCommand extends Command {
    {
        setDescription("Kill calibration: real vs modelled offscreen kills/min by job and level band (!killcalib [all])");
    }

    static final int DEFAULT_ROWS = 12;
    static final int ALL_ROWS = 40;

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        int rows = params.length > 0 && "all".equalsIgnoreCase(params[0]) ? ALL_ROWS : DEFAULT_ROWS;
        for (String line : KillCalibration.summaryLines(rows)) {
            gm.dropMessage(5, line);
        }
    }
}

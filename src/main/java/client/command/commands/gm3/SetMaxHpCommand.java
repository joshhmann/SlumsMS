package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import constants.game.GameConstants;

/**
 * SlumsMS 1.6.0 staging command for the 4-byte HP/MP client (slums.dll 10600+): {@code !setmaxhp <hp> [<mp>]} sets your
 * own base MaxHP (and MaxMP, the same value when left out) to the number given, clamped to 1..99,999, and refills both,
 * so the HP bar, the stat window and the next map change show a value past 32,767 straight away. Gear and buffs come on
 * top, still capped at {@link GameConstants#MAX_HPMP}. {@code !maxhpmp} sets both to one total for any player.
 */
public class SetMaxHpCommand extends Command {
    {
        setDescription("Set your own base MaxHP/MaxMP (up to 99,999) and refill.");
    }

    /** The requested value, clamped to what a character may have; null when it is not a number. */
    static Integer parseCapped(String s) {
        try {
            long v = Long.parseLong(s.trim().replace(",", "").replace("_", ""));
            return (int) Math.max(1, Math.min(GameConstants.MAX_HPMP, v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        Integer hp = params.length >= 1 ? parseCapped(params[0]) : null;
        Integer mp = params.length >= 2 ? parseCapped(params[1]) : hp;
        if (hp == null || mp == null) {
            player.yellowMessage("Syntax: !setmaxhp <maxhp> [<maxmp>]   (1 to " + GameConstants.MAX_HPMP + ")");
            return;
        }

        player.updateMaxHpMaxMp(hp, mp);
        player.updateHpMp(GameConstants.MAX_HPMP);   // clamps to the new current max
        player.yellowMessage("Base MaxHP " + player.getMaxHp() + ", MaxMP " + player.getMaxMp()
                + "; with gear and buffs " + player.getCurrentMaxHp() + " / " + player.getCurrentMaxMp()
                + ". HP " + player.getHp() + ", MP " + player.getMp() + ".");
    }
}

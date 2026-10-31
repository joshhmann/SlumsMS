/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

/*
   @Author: Arthur L - Refactored command content into modules
*/
package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;

public class HurtCommand extends Command {
    {
        setDescription("Nearly kill a player: !hurt <name> [percent of max HP, 1-99].");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 1) {
            player.yellowMessage("Syntax: !hurt <name> [percent of max HP, 1-99]");
            return;
        }
        Character victim = c.getWorldServer().getPlayerStorage().getCharacterByName(params[0]);
        if (victim == null) {
            player.message("Player '" + params[0] + "' could not be found.");
            return;
        }
        Integer pct = params.length >= 2 ? percent(params[1]) : null;
        if (params.length >= 2 && pct == null) {
            player.yellowMessage("The percent must be a whole number from 1 to 99 (a trailing % is fine).");
            return;
        }
        victim.updateHp(hpFor(victim.getCurrentMaxHp(), pct));
    }

    /** Ticket 170 (client QA scripts): "40" or "40%" -> 40; anything outside 1-99 -> null. */
    static Integer percent(String arg) {
        String a = arg == null ? "" : arg.trim();
        if (a.endsWith("%")) {
            a = a.substring(0, a.length() - 1);
        }
        if (!a.matches("\\d{1,2}")) {
            return null;
        }
        int p = Integer.parseInt(a);
        return p >= 1 && p <= 99 ? p : null;
    }

    /** The HP to set: 1 without a percent (the stock command), else that share of max HP (at least 1). */
    static int hpFor(int maxHp, Integer pct) {
        if (pct == null) {
            return 1;
        }
        return (int) Math.max(1, (long) maxHp * pct / 100);
    }
}

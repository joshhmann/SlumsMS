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
package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import scripting.npc.NPCScriptManager;

/** {@code @exchange}: the item-exchange NPC. A GM's {@code !exchange status|book|halt|resume} is the NX exchange (ticket 113). */
public class ExchangeCommand extends Command {
    @Override
    public void execute(Client c, String[] splitted) {
        if (client.command.commands.gm3.NxExchangeCommand.handles(c.getPlayer(), splitted)) {
            client.command.commands.gm3.NxExchangeCommand.execute(c.getPlayer(), splitted);
            return;
        }
        NPCScriptManager.getInstance().start(c, 1300009, "exchange", c.getPlayer());
    }
}

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
import constants.id.NpcId;
import server.ItemInformationProvider;
import server.gachapon.Gachapon;
import soloMapling.Gachapon.GachaPools;

/** {@code @gacha <town>}: a machine's prizes and odds (1.5.0: from soloMapling/Gachapon/gachapon-pools.yaml). */
public class GachaCommand extends Command {
    {
        setDescription("Show a gachapon's prizes and odds.");
    }

    @Override
    public void execute(Client c, String[] params) {
        GachaPools pools = GachaPools.get();
        GachaPools.Town town = params.length == 0 ? null : pools.find(String.join(" ", params));
        if (town == null) {
            c.getPlayer().yellowMessage("Use @gacha <town>, one of:");
            for (GachaPools.Town t : pools.towns().values()) {
                c.getPlayer().yellowMessage("  " + t.name() + "  (@gacha " + t.key() + ")");
            }
            return;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        StringBuilder sb = new StringBuilder("The #b" + town.name() + "#k Gachapon: one prize per ticket.\r\n\r\n");
        for (GachaPools.TierOdds t : pools.odds(town)) {
            sb.append("#r").append(t.tier().key().toUpperCase()).append("#k ").append(Gachapon.pct(t.chance()))
                    .append(pools.announces(t.tier()) ? " (announced)" : "").append(":\r\n");
            for (GachaPools.ItemOdds item : t.items()) {
                String name = ii.getName(item.itemId());
                sb.append(" - ").append(name == null ? "#" + item.itemId() : name)
                        .append(item.quantity() > 1 ? " x" + item.quantity() : "").append("\r\n");
            }
            sb.append("\r\n");
        }
        c.getAbstractPlayerInteraction().npcTalk(NpcId.MAPLE_ADMINISTRATOR, sb.toString());
    }
}

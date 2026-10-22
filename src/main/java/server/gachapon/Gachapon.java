/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

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
package server.gachapon;

import server.ItemInformationProvider;
import soloMapling.Gachapon.GachaPools;

import java.util.ArrayList;
import java.util.List;

/**
 * The gachapon machines as the NPC scripts see them. 1.5.0: the per-town item lists that were Java classes here now
 * live in {@code soloMapling/Gachapon/gachapon-pools.yaml} ({@link GachaPools}); this class keeps the script-facing
 * read-out ({@code gachaponInfo.js}) and the remote ticket's machine numbers.
 *
 * @author Alan (SharpAceX), 1.5.0 SlumsMS
 */
public final class Gachapon {

    private Gachapon() {
    }

    /**
     * Remote Gachapon Ticket machine numbers (the client's list, 0-11) to pool town keys. 0-10 as the old handler
     * mapped them (NPC 9100100 + n, 8 = New Leaf City, 9 = Nautilus, 10 = El Nath); 11 had no machine and is
     * Ludibrium now.
     */
    static final String[] REMOTE_ORDER = {"henesys", "ellinia", "perion", "kerning", "sleepywood", "mushroom_shrine",
            "showa_male", "showa_female", "new_leaf_city", "nautilus", "el_nath", "ludibrium"};

    /** The machine a Remote Gachapon Ticket's selection (0-11) plays, or null. */
    public static GachaPools.Town remote(int selection) {
        if (selection < 0 || selection >= REMOTE_ORDER.length) {
            return null;
        }
        return GachaPools.get().town(REMOTE_ORDER[selection]);
    }

    /** For gachaponInfo.js: [menu, then one text block per machine in menu order]. */
    public static String[] getLootInfo() {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        GachaPools pools = GachaPools.get();
        List<GachaPools.Town> towns = new ArrayList<>(pools.towns().values());
        String[] out = new String[towns.size() + 1];
        StringBuilder menu = new StringBuilder();
        for (int j = 0; j < towns.size(); j++) {
            GachaPools.Town town = towns.get(j);
            menu.append("#L").append(j).append('#').append(town.name()).append("#l\r\n");
            StringBuilder sb = new StringBuilder();
            for (GachaPools.TierOdds tier : pools.odds(town)) {
                sb.append("  #r").append(cap(tier.tier().key())).append("#k (").append(pct(tier.chance()))
                        .append(" per ticket, ").append(tier.items().size()).append(" items):\r\n");
                for (GachaPools.ItemOdds item : tier.items()) {
                    String name = ii.getName(item.itemId());
                    sb.append("    ").append(name == null ? "#" + item.itemId() : name)
                            .append(item.quantity() > 1 ? " x" + item.quantity() : "").append("\r\n");
                }
                sb.append("\r\n");
            }
            out[j + 1] = sb.toString();
        }
        out[0] = menu.toString();
        return out;
    }

    /** gachaponInfo.js names the machines through this, in menu order. */
    public static String[] machineNames() {
        return GachaPools.get().towns().values().stream().map(GachaPools.Town::name).toArray(String[]::new);
    }

    public static String pct(double p) {
        if (p >= 0.1) {
            return String.format("%.0f%%", p * 100);
        }
        if (p >= 0.01) {
            return String.format("%.1f%%", p * 100);
        }
        return String.format("%.2f%%", p * 100);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

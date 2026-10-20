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
package net.server.handlers.login;

import client.Client;
import constants.game.GameConstants;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.packet.Packet;
import net.server.Server;
import net.server.world.World;
import tools.PacketCreator;
import tools.Pair;

import java.util.ArrayList;
import java.util.List;

public final class ServerlistRequestHandler extends AbstractPacketHandler {

    @Override
    public final void handlePacket(InPacket p, Client c) {
        Server server = Server.getInstance();
        List<World> worlds = server.getWorlds();
        c.requestedServerlist(worlds.size());

        for (Packet packet : serverListPackets(worlds, server.getHomeWorldId(), server.worldRecommendedList())) {
            c.sendPacket(packet);
        }
    }

    /**
     * The login screen's world list: one entry per running world under its own id and name (ticket 41: a server
     * running Bera alone lists Bera, id 1, and nothing else), the end marker, the preselected world (the home world)
     * and the recommended worlds.
     */
    static List<Packet> serverListPackets(List<World> worlds, int homeWorldId, List<Pair<Integer, String>> recommended) {
        List<Packet> packets = new ArrayList<>();
        for (World world : worlds) {
            packets.add(PacketCreator.getServerList(world.getId(), GameConstants.WORLD_NAMES[world.getId()], world.getFlag(), world.getEventMessage(), world.getChannels()));
        }
        packets.add(PacketCreator.getEndOfServerList());
        packets.add(PacketCreator.selectWorld(homeWorldId));
        packets.add(PacketCreator.sendRecommended(recommended));
        return packets;
    }
}

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
package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Item;
import client.processor.action.BuybackProcessor;
import config.YamlConfig;
import constants.id.MapId;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.server.Server;
import server.MTSItemInfo;
import server.maps.FieldLimit;
import server.maps.MiniDungeonInfo;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

// Assuming you have the required imports
import client.Character;
import client.Client;
import config.YamlConfig;
import net.packet.InPacket;
import tools.PacketCreator;

public final class EnterMTSHandler extends AbstractPacketHandler {
    @Override
    public final void handlePacket(InPacket p, Client c) {
        Character mc = c.getPlayer();

        // Check if the player is dead
        if (!mc.isAlive()) {
            c.sendPacket(PacketCreator.serverNotice(5, "You cannot use this feature while you are dead."));
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        // Check if the player is in an event instance
        if (mc.cannotEnterCashShop()) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        if (mc.getEventInstance() != null) {
            c.sendPacket(PacketCreator.serverNotice(5, "FM button is disabled when registered for an event."));
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        // Check if the player is in a Mini-Dungeon map
        if (MiniDungeonInfo.isDungeonMap(mc.getMapId())) {
            c.sendPacket(PacketCreator.serverNotice(5, "FM button is disabled when inside a Mini-Dungeon."));
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        // 1.5.2 handler sweep: the repack's FM button dropped the stock MTS button's map check, so it warped a player
        // out of any map at all: out of jail, out of a boss or party-quest map that is not an event instance, out of
        // Monster Carnival. It now leaves only where changing channel or entering the Cash Shop is allowed.
        String refusal = fmButtonRefusal(mc.getMapId(), mc.getMap().getFieldLimit());
        if (refusal != null) {
            c.sendPacket(PacketCreator.serverNotice(5, refusal));
            c.sendPacket(PacketCreator.enableActions());
            return;
        }
        // Each press saves the whole character: a packet-spammed button was a database flood.
        if (!pressAllowed(mc.getId(), System.currentTimeMillis())) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        // Save the location and change the map to FM_ENTRANCE
        mc.saveLocation("FREE_MARKET");
        mc.changeMap(c.getChannelServer().getMapFactory().getMap(MapId.MAPLEROOT_HUB), 0);
        mc.saveCharToDB();
    }

    /** Least time between two FM-button warps of one character. */
    static final long PRESS_COOLDOWN_MS = 2_000;

    private static final java.util.Map<Integer, Long> lastPress = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean pressAllowed(int characterId, long now) {
        Long last = lastPress.get(characterId);
        if (last != null && now - last < PRESS_COOLDOWN_MS) {
            return false;
        }
        lastPress.put(characterId, now);
        return true;
    }

    /** Why the FM button may not warp from this map, or null when it may. */
    static String fmButtonRefusal(int mapId, int fieldLimit) {
        if (mapId == MapId.JAIL) {
            return "You cannot leave the jail this way.";
        }
        if (mapId == MapId.MAPLEROOT_HUB) {
            return "You are already in the Free Market.";
        }
        if (FieldLimit.CANNOTMIGRATE.check(fieldLimit)) {
            return "You can't use the FM button in this map.";
        }
        return null;
    }
}

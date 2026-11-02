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

import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;

/**
 * 0xDC: the client's party search "register" notification.
 *
 * <p>ticket 164: the body below is deliberately empty and should stay that way. Nobody in this project knows the
 * layout of the 0xDC payload: it is not described in docs/, scripts/lib.d.ts, the packet comments or any other
 * fork under /root, and the working party search handlers only use the packets around it - 0xDE
 * {@code PartySearchStartHandler} reads {@code min, max, members, jobs} and registers the leader, 0xDF
 * {@code PartySearchUpdateHandler} unregisters them. Filling this in with a guessed read would throw in the channel
 * handler and, worse, a wrongly read flag would silently switch invite reception on or off for the player.
 *
 * <p>The state this opcode could carry is already handled elsewhere: invite reception is toggled from the party
 * quest NPC scripts ({@code Character.toggleRecvPartySearchInvite()} in scripts/npc/1012112.js, 1052013.js,
 * 2013000.js and siblings), and every player is attached to / detached from the candidate lists on login, logout,
 * party creation, join, leave, expel and job change. If a real 0xDC layout is ever captured from a client, the
 * implementation is {@code c.getPlayer().updatePartySearchAvailability(...)} with the flag read from the packet -
 * document the layout here when that happens.
 *
 * @author Quasar
 */
public class PartySearchRegisterHandler extends AbstractPacketHandler {

    @Override
    public void handlePacket(InPacket p, Client c) {}
}
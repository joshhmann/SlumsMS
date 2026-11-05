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
import client.processor.action.PetAutopotProcessor;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.ItemInformationProvider;
import server.StatEffect;

public final class PetAutoPotHandler extends AbstractPacketHandler {

    @Override
    public final void handlePacket(InPacket p, Client c) {
        p.readByte();
        p.readLong();
        p.readInt();
        short slot = p.readShort();
        int itemId = p.readInt();

        Character chr = c.getPlayer();
        boolean qaPetPot = soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.PETPOT); // !qa pp-1
        // what the client saw when its pet asked: the pot lands below, so read it first
        int hpAsked = qaPetPot ? chr.getHp() : 0;
        int mpAsked = qaPetPot ? chr.getMp() : 0;

        StatEffect stat = ItemInformationProvider.getInstance().getItemEffect(itemId);
        if (stat.getHp() > 0 || stat.getHpRate() > 0.0) {
            float estimatedHp = ((float) chr.getHp()) / chr.getMaxHp();
            chr.setAutopotHpAlert(estimatedHp + 0.05f);
        }

        if (stat.getMp() > 0 || stat.getMpRate() > 0.0) {
            float estimatedMp = ((float) chr.getMp()) / chr.getMaxMp();
            chr.setAutopotMpAlert(estimatedMp + 0.05f);
        }

        String refusal = PetAutopotProcessor.runAutopotAction(c, slot, itemId);
        server.reports.PlayerTrail.event(chr, "petpot", itemId, refusal);

        if (qaPetPot) {
            soloMapling.qa.QaLog.owner(soloMapling.qa.QaLog.Topic.PETPOT, chr.getId(), "PETPOT recv " + chr.getName()
                    + " slot " + slot + " item " + itemId + " hp " + hpAsked + "/" + chr.getMaxHp()
                    + " mp " + mpAsked + "/" + chr.getMaxMp()
                    + " map " + chr.getMapId() + (refusal == null ? "" : " refused " + refusal));
        }
        //Thanks RobbertW for this snippy snip
        if (chr.countItem(itemId) == 25) {
            chr.showHint("Your pet is almost out of #e#r#t" + itemId + "##k#n! (#b25#k left)", 300);
        }

        if (chr.countItem(itemId) == 0) {
            chr.showHint("Your pet just consumed your last #e#r#t" + itemId + "##k#n! Be careful!", 300);
        }
    }
    }


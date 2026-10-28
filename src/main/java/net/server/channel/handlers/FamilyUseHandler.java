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
import client.FamilyBuffs;
import client.FamilyEntitlement;
import client.FamilyEntry;
import config.YamlConfig;
import constants.id.MapId;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import server.maps.FieldLimit;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Moogra
 * @author Ubaware
 */
public final class FamilyUseHandler extends AbstractPacketHandler {
    @Override
    public final void handlePacket(InPacket p, Client c) {
        if (!YamlConfig.config.server.USE_FAMILY_SYSTEM) {
            return;
        }
        int index = p.readInt();
        if (index < 0 || index >= FamilyEntitlement.values().length) {
            return;
        }
        FamilyEntitlement type = FamilyEntitlement.values()[index];
        int cost = type.getRepCost();
        FamilyEntry entry = c.getPlayer().getFamilyEntry();
        if (entry == null || entry.getReputation() < cost || entry.isEntitlementUsed(type)) {
            return; // shouldn't even be able to request it
        }
        c.sendPacket(PacketCreator.getFamilyInfo(entry));
        Character victim;
        if (type == FamilyEntitlement.FAMILY_REUINION || type == FamilyEntitlement.SUMMON_FAMILY) {
            victim = c.getChannelServer().getPlayerStorage().getCharacterByName(p.readString());
            if (victim != null && victim != c.getPlayer()) {
                if (victim.getFamily() == c.getPlayer().getFamily()) {
                    MapleMap targetMap = victim.getMap();
                    MapleMap ownMap = c.getPlayer().getMap();
                    if (targetMap != null) {
                        if (type == FamilyEntitlement.FAMILY_REUINION) {
                            if (!FieldLimit.CANNOTMIGRATE.check(ownMap.getFieldLimit()) && !FieldLimit.CANNOTVIPROCK.check(targetMap.getFieldLimit())
                                    && (targetMap.getForcedReturnId() == MapId.NONE || MapId.isMapleIsland(targetMap.getId())) && targetMap.getEventInstance() == null) {

                                c.getPlayer().changeMap(victim.getMap(), victim.getMap().getPortal(0));
                                useEntitlement(entry, type);
                            } else {
                                c.sendPacket(PacketCreator.sendFamilyMessage(75, 0)); // wrong message, but close enough. (client should check this first anyway)
                                return;
                            }
                        } else {
                            if (!FieldLimit.CANNOTMIGRATE.check(targetMap.getFieldLimit()) && !FieldLimit.CANNOTVIPROCK.check(ownMap.getFieldLimit())
                                    && (ownMap.getForcedReturnId() == MapId.NONE || MapId.isMapleIsland(ownMap.getId())) && ownMap.getEventInstance() == null) {

                                if (InviteCoordinator.hasInvite(InviteType.FAMILY_SUMMON, victim.getId())) {
                                    c.sendPacket(PacketCreator.sendFamilyMessage(74, 0));
                                    return;
                                }
                                InviteCoordinator.createInvite(InviteType.FAMILY_SUMMON, c.getPlayer(), victim, victim.getId(), c.getPlayer().getMap());
                                victim.sendPacket(PacketCreator.sendFamilySummonRequest(c.getPlayer().getFamily().getName(), c.getPlayer().getName()));
                                useEntitlement(entry, type);
                            } else {
                                c.sendPacket(PacketCreator.sendFamilyMessage(75, 0));
                                return;
                            }
                        }
                    }
                } else {
                    c.sendPacket(PacketCreator.sendFamilyMessage(67, 0));
                }
            }
        } else {
            String refused = applyBuff(c.getPlayer(), entry, type, System.currentTimeMillis());
            if (refused != null) {
                c.getPlayer().dropMessage(5, refused);
                c.sendPacket(PacketCreator.enableActions());
                return;
            }
            useEntitlement(entry, type);
        }
    }

    /**
     * Spends {@code type}'s reputation on its buff, as the Family window does (once a day per entitlement; nothing spent
     * when refused). Returns null when given, or why not. Also what QA drives.
     */
    public static String useBuffEntitlement(Character chr, FamilyEntitlement type, long now) {
        FamilyEntry entry = chr == null ? null : chr.getFamilyEntry();
        if (entry == null) {
            return "You are not in a Family.";
        }
        if (entry.getReputation() < type.getRepCost()) {
            return "Not enough reputation (" + entry.getReputation() + " of " + type.getRepCost() + ").";
        }
        if (entry.isEntitlementUsed(type)) {
            return "Already used today.";
        }
        String refused = applyBuff(chr, entry, type, now);
        if (refused == null) {
            useEntitlement(entry, type);
        }
        return refused;
    }

    /** Family Bonding needs this many online Family members below the user (the tooltip's 6; bot juniors count). */
    public static int bondingMinimum() {
        return Math.max(1, Integer.getInteger("slums.family.bondingMin", 6));
    }

    /**
     * Ticket 53: gives the EXP / drop buff of {@code type} (stock Cosmic: "not implemented"). Self: the user. Party: the
     * user and every party member online on the same channel (the user alone outside a party). Bonding: needs
     * {@link #bondingMinimum()} online members below the user in the pedigree, and gives the user and the players among
     * them 2x EXP and drop. Bots are never buffed. Returns null when given, or why not (nothing spent then).
     */
    public static String applyBuff(Character chr, FamilyEntry entry, FamilyEntitlement type, long now) {
        FamilyBuffs.Spec spec = FamilyBuffs.specOf(type);
        if (spec == null || chr == null) {
            return "That Family entitlement has no effect here.";
        }
        List<Character> targets = new ArrayList<>();
        targets.add(chr);
        if (spec.target() == FamilyBuffs.Target.PARTY && chr.getParty() != null) {
            for (Character m : chr.getPartyMembersOnline()) {
                if (m != chr && m.getClient() != null && chr.getClient() != null
                        && m.getClient().getChannel() == chr.getClient().getChannel()) {
                    targets.add(m);
                }
            }
        } else if (spec.target() == FamilyBuffs.Target.BONDING) {
            List<Character> below = onlineBelow(entry);
            if (below.size() < bondingMinimum()) {
                return "Family Bonding needs at least " + bondingMinimum() + " Family members online below you ("
                        + below.size() + " now).";
            }
            targets.addAll(below);
        }
        for (Character t : targets) {
            if (t.isBot()) {
                continue; // a bot is never buffed (it still counts toward Bonding)
            }
            FamilyBuffs.grant(t.getId(), spec, now);
            t.dropMessage(5, FamilyBuffs.describe(spec) + (t == chr ? "" : " (from " + chr.getName() + ")"));
        }
        return null;
    }

    /** The online characters below {@code entry} in its pedigree (every generation). */
    static List<Character> onlineBelow(FamilyEntry entry) {
        List<Character> out = new ArrayList<>();
        if (entry == null) {
            return out;
        }
        ArrayDeque<FamilyEntry> todo = new ArrayDeque<>();
        pushJuniors(todo, entry);
        while (!todo.isEmpty()) {
            FamilyEntry e = todo.poll();
            if (e.getChr() != null) {
                out.add(e.getChr());
            }
            pushJuniors(todo, e);
        }
        return out;
    }

    private static void pushJuniors(ArrayDeque<FamilyEntry> todo, FamilyEntry e) {
        for (FamilyEntry j : e.getJuniors()) {
            if (j != null) {
                todo.add(j);
            }
        }
    }

    static boolean useEntitlement(FamilyEntry entry, FamilyEntitlement entitlement) {
        if (entry.useEntitlement(entitlement)) {
            entry.gainReputation(-entitlement.getRepCost(), false);
            entry.getChr().sendPacket(PacketCreator.getFamilyInfo(entry));
            return true;
        }
        return false;
    }
}

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

import client.BotClient;
import client.Character;
import client.Client;
import client.FamilyBuffs;
import client.FamilyEntitlement;
import client.FamilyEntry;
import client.FamilyTravel;
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
        if (type == FamilyEntitlement.FAMILY_REUINION || type == FamilyEntitlement.SUMMON_FAMILY) {
            String name = p.readString();
            String refused = useTravel(c, entry, type, name, System.currentTimeMillis());
            if (refused != null) {
                c.getPlayer().dropMessage(5, refused);
                c.sendPacket(PacketCreator.enableActions());
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
     * Family Reunion / Summon Family on the member called {@code name}, anywhere in the world (1.6.1 promise audit: it
     * looked only on the user's channel, and a regular junior could never answer a summon). Reunion goes to them, across
     * channels too ({@link client.FamilyTravel}); Summon asks them to come, and a regular or an owned alt answers like
     * a player who clicks Yes ({@link soloMapling.ArtificialPlayer.BotFamily#onSummon}) unless it is in something it
     * cannot drop, which is refused here before anything is spent. Returns null when done or asked (or when the client
     * already got a Family result message), else the line to show; nothing is spent then.
     */
    static String useTravel(Client c, FamilyEntry entry, FamilyEntitlement type, String name, long now) {
        Character me = c.getPlayer();
        Character victim = findMember(c, name);
        if (victim == null || victim == me) {
            c.sendPacket(PacketCreator.sendFamilyMessage(65, 0)); // the name could not be found or is not online
            return null;
        }
        if (victim.getFamily() != me.getFamily()) {
            c.sendPacket(PacketCreator.sendFamilyMessage(67, 0));
            return null;
        }
        MapleMap targetMap = victim.getMap();
        MapleMap ownMap = me.getMap();
        if (targetMap == null || ownMap == null) {
            return victim.getName() + " can't be reached right now.";
        }
        int myCh = c.getChannel(), theirCh = victim.getClient().getChannel();
        if (type == FamilyEntitlement.FAMILY_REUINION) {
            if (FieldLimit.CANNOTMIGRATE.check(ownMap.getFieldLimit()) || FieldLimit.CANNOTVIPROCK.check(targetMap.getFieldLimit())
                    || !(targetMap.getForcedReturnId() == MapId.NONE || MapId.isMapleIsland(targetMap.getId()))
                    || targetMap.getEventInstance() != null) {
                c.sendPacket(PacketCreator.sendFamilyMessage(75, 0)); // wrong message, but close enough
                return null;
            }
            if (theirCh == myCh) {
                me.changeMap(targetMap, targetMap.getPortal(0));
                useEntitlement(entry, type);
                return null;
            }
            String why = channelChangeRefusal(c);
            if (why != null) {
                return why;
            }
            // Across channels: spend first (the packet still reaches this client), then change channel; the new
            // channel lands the player on the member's map. A refused change gives it all back.
            useEntitlement(entry, type);
            FamilyTravel.expect(me.getId(), theirCh, targetMap.getId(), victim.getPosition(), now);
            if (!c.changeChannel(theirCh)) {
                FamilyTravel.cancel(me.getId());
                refund(entry, type);
                return "Family Reunion could not change your channel. Nothing was spent.";
            }
            return null;
        }
        // Summon Family
        if (FieldLimit.CANNOTMIGRATE.check(targetMap.getFieldLimit()) || FieldLimit.CANNOTVIPROCK.check(ownMap.getFieldLimit())
                || !(ownMap.getForcedReturnId() == MapId.NONE || MapId.isMapleIsland(ownMap.getId()))
                || ownMap.getEventInstance() != null) {
            c.sendPacket(PacketCreator.sendFamilyMessage(75, 0));
            return null;
        }
        String refused = summonRefusal(victim, me);
        if (refused != null) { // before anything is spent
            return refused;
        }
        if (InviteCoordinator.hasInvite(InviteType.FAMILY_SUMMON, victim.getId())) {
            c.sendPacket(PacketCreator.sendFamilyMessage(74, 0));
            return null;
        }
        InviteCoordinator.createInvite(InviteType.FAMILY_SUMMON, me, victim, victim.getId(), ownMap);
        useEntitlement(entry, type);
        if (victim.getClient() instanceof BotClient) {
            soloMapling.ArtificialPlayer.BotFamily.onSummon(victim, me);
        } else {
            victim.sendPacket(PacketCreator.sendFamilySummonRequest(me.getFamily().getName(), me.getName()));
        }
        return null;
    }

    /** The online family member called {@code name} on any channel of the world: a player, a regular or an alt. */
    static Character findMember(Client c, String name) {
        Character found = c.getChannelServer().getPlayerStorage().getCharacterByName(name);
        if (found == null && c.getWorldServer() != null) {
            found = c.getWorldServer().getPlayerStorage().getCharacterByName(name);
        }
        if (found == null || found.getClient() == null) {
            return null;
        }
        boolean bot = found.getClient() instanceof BotClient;
        return bot || found.isLoggedinWorld() ? found : null; // a bot is online without being "logged in"
    }

    /** Why this client cannot change channel for a Family perk now (null: it can), checked before anything is spent. */
    static String channelChangeRefusal(Client c) {
        Character me = c.getPlayer();
        if (c instanceof client.SyntheticClient) {
            return "An auto-QA player can't change channel."; // it has no socket to reconnect
        }
        if (!me.isAlive() || FieldLimit.CANNOTMIGRATE.check(me.getMap().getFieldLimit())
                || server.maps.MiniDungeonInfo.isDungeonMap(me.getMapId())) {
            return "You can't change channel from here, so Family Reunion can't take you. Nothing was spent.";
        }
        return null;
    }

    /** Gives back an entitlement just used (its daily use and its reputation), as a declined summon does. */
    public static void refund(FamilyEntry entry, FamilyEntitlement type) {
        entry.refundEntitlement(type);
        entry.gainReputation(type.getRepCost(), false);
        Character chr = entry.getChr();
        if (chr != null) {
            chr.sendPacket(PacketCreator.getFamilyInfo(entry));
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

    /**
     * Whether {@code victim} can be summoned by {@code summoner}: null when it will be asked (a player answers in their
     * client; a regular or an owned alt answers by itself, {@link soloMapling.ArtificialPlayer.BotFamily#summonRefusal}),
     * else the line the summoner gets. Checked before anything is spent.
     */
    public static String summonRefusal(Character victim, Character summoner) {
        if (victim != null && victim.getClient() instanceof BotClient) {
            return soloMapling.ArtificialPlayer.BotFamily.summonRefusal(victim, summoner);
        }
        return null;
    }

    /** Family Bonding needs this many online Family members below the user (the tooltip's 6; bot juniors count). */
    public static int bondingMinimum() {
        return Math.max(1, Integer.getInteger("slums.family.bondingMin", 6));
    }

    /**
     * Ticket 53: gives the EXP / drop buff of {@code type} (stock Cosmic: "not implemented"). Self: the user. Party: the
     * user and every party member online when it is used, on any channel ("[Target] My party"; 1.6.1 promise audit:
     * it was the user's channel only); the user alone outside a party. Bonding: needs
     * {@link #bondingMinimum()} online members below the user in the pedigree, and gives the user and the players among
     * them 2x EXP and drop; Bonding buffs the bot juniors it counted too (b035). Party perks skip bots. Returns null when
     * given, or why not (nothing spent then).
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
                if (m != chr && m.getClient() != null) { // 1.6.1 promise audit: every member online, any channel
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
                if (spec.target() == FamilyBuffs.Target.BONDING) {
                    // b035: it counted toward the 6 online, so it gets the buff too; its on-screen kills read it in
                    // Monster / MapleMap, and the off-screen grind (TrainingBot) multiplies its EXP and loot share
                    FamilyBuffs.grant(t.getId(), spec, now);
                }
                continue; // no message (nobody reads a bot's), and self / party perks stay players only
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

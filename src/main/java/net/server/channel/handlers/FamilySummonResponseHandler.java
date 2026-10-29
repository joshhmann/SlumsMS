package net.server.channel.handlers;

import client.BotClient;
import client.Character;
import client.Client;
import client.FamilyEntitlement;
import client.FamilyEntry;
import client.FamilyTravel;
import config.YamlConfig;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteResult;
import net.server.coordinator.world.InviteCoordinator.InviteResultType;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import server.maps.MapleMap;

public class FamilySummonResponseHandler extends AbstractPacketHandler {

    @Override
    public void handlePacket(InPacket p, Client c) {
        if (!YamlConfig.config.server.USE_FAMILY_SYSTEM) {
            return;
        }
        p.readString(); //family name
        boolean accept = p.readByte() != 0;
        answer(c.getPlayer(), accept);
    }

    /**
     * {@code responder} answers the Family summon waiting for them: a player in their client, a regular or an owned
     * alt by itself ({@code BotFamily}). Yes brings them to the summoner's map (across channels too: 1.6.1 promise
     * audit); no, or a yes that cannot get there, gives the summoner back the reputation and the day's use. Returns
     * whether they came (or are changing channel to come).
     */
    public static boolean answer(Character responder, boolean accept) {
        if (responder == null) {
            return false;
        }
        InviteResult inviteResult = InviteCoordinator.answerInvite(InviteType.FAMILY_SUMMON, responder.getId(), responder, accept);
        if (inviteResult.result == InviteResultType.NOT_FOUND) {
            return false;
        }
        Character inviter = inviteResult.from;
        FamilyEntry inviterEntry = inviter == null ? null : inviter.getFamilyEntry();
        if (inviterEntry == null) {
            return false; // the summoner left (stock threw here)
        }
        MapleMap map = (MapleMap) inviteResult.params[0];
        String why = null;
        if (accept && inviter.getMap() == map) { //cancel if inviter has changed maps
            why = travel(responder, inviter, map);
            if (why == null) {
                return true;
            }
        }
        FamilyUseHandler.refund(inviterEntry, FamilyEntitlement.SUMMON_FAMILY); //refund rep cost if declined
        inviter.dropMessage(5, why == null ? responder.getName() + " has denied the summon request."
                : responder.getName() + " could not come (" + why + "). Your reputation was given back.");
        return false;
    }

    /** Takes {@code who} to {@code map}, the summoner's; null when they arrived or are on the way, else why not. */
    static String travel(Character who, Character inviter, MapleMap map) {
        if (who.getClient() instanceof BotClient) {
            return soloMapling.ArtificialPlayer.BotFamily.arrive(who, inviter) ? null : "the way there was blocked";
        }
        int ch = inviter.getClient().getChannel();
        if (who.getClient().getChannel() == ch) {
            who.changeMap(map, map.getPortal(0));
            return null;
        }
        if (who.getClient() instanceof client.SyntheticClient) {
            return "an auto-QA player can't change channel";
        }
        FamilyTravel.expect(who.getId(), ch, map.getId(), inviter.getPosition(), System.currentTimeMillis());
        if (!who.getClient().changeChannel(ch)) {
            FamilyTravel.cancel(who.getId());
            return "they could not change channel";
        }
        return null;
    }

}

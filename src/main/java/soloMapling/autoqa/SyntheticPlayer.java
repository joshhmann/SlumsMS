package soloMapling.autoqa;

import client.Character;
import client.SyntheticClient;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufOutPacket;
import net.server.Server;

import java.util.List;

/**
 * One logged-in synthetic player ({@link SyntheticPlayers}): every action is a packet body handed to the channel's
 * real handler, the same bytes a v83 client sends.
 */
public final class SyntheticPlayer implements AutoQaWorld.Actor {

    private final int slot;
    private final SyntheticClient client;

    SyntheticPlayer(int slot, SyntheticClient client) {
        this.slot = slot;
        this.client = client;
    }

    public Character character() {
        return client.getPlayer();
    }

    public SyntheticClient client() {
        return client;
    }

    @Override
    public int id() {
        Character c = character();
        return c == null ? -1 : c.getId();
    }

    @Override
    public String name() {
        Character c = character();
        return c == null ? SyntheticPlayers.nameOf(slot) : c.getName();
    }

    @Override
    public int slot() {
        return slot;
    }

    /** Raw: a packet body for a handler (the probes use it for the rarer packets). */
    public void send(RecvOpcode op, ByteBufOutPacket body) {
        SyntheticPlayers.handle(client, op, body.getBytes());
    }

    @Override
    public void say(String text) {
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeString(text);
        p.writeByte(0);
        send(RecvOpcode.GENERAL_CHAT, p);
    }

    @Override
    public void whisper(String target, String text) {
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeByte(0x06);
        p.writeString(target);
        p.writeString(text);
        send(RecvOpcode.WHISPER, p);
    }

    @Override
    public void multiChat(int type, String text) {
        multiChat(type, text, new int[0]);
    }

    /** Buddy chat needs the recipients' ids. */
    public void multiChat(int type, String text, int... recipients) {
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeByte(type);
        p.writeByte(recipients.length);
        for (int r : recipients) {
            p.writeInt(r);
        }
        p.writeString(text);
        send(RecvOpcode.MULTI_CHAT, p);
    }

    @Override
    public void partyInvite(String name) {
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeByte(4);
        p.writeString(name);
        send(RecvOpcode.PARTY_OPERATION, p);
    }

    @Override
    public boolean acceptPartyInvite() {
        int party = client.lastPartyInviteId();
        if (party < 0) {
            return false;
        }
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeByte(3);
        p.writeInt(party);
        send(RecvOpcode.PARTY_OPERATION, p);
        client.clearPartyInvite();
        return true;
    }

    @Override
    public void guildInvite(String name) {
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeByte(0x05);
        p.writeString(name);
        send(RecvOpcode.GUILD_OPERATION, p);
    }

    @Override
    public boolean acceptGuildInvite() {
        int guild = client.lastGuildInviteId();
        if (guild <= 0) {
            return false;
        }
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeByte(0x06);
        p.writeInt(guild);
        p.writeInt(id());
        send(RecvOpcode.GUILD_OPERATION, p);
        client.clearGuildInvite();
        return true;
    }

    @Override
    public void buddyAdd(String name) {
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeByte(1);
        p.writeString(name);
        p.writeString("Default Group");
        send(RecvOpcode.BUDDYLIST_MODIFY, p);
    }

    /** Proposes with an engagement box (RingActionHandler mode 0). */
    public void ringPropose(String target, int boxItemId) {
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeByte(0);
        p.writeString(target);
        p.writeInt(boxItemId);
        send(RecvOpcode.RING_ACTION, p);
    }

    /** Answers the last proposal received (mode 2); false when there was none. */
    public boolean answerProposal(boolean yes) {
        String from = client.lastProposer();
        if (from == null) {
            return false;
        }
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeByte(2);
        p.writeByte(yes ? 1 : 0);
        p.writeString(from);
        p.writeInt(client.lastProposerId());
        send(RecvOpcode.RING_ACTION, p);
        client.clearProposal();
        return true;
    }

    public void spouseChat(String text) {
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeString("");
        p.writeString(text);
        send(RecvOpcode.SPOUSE_CHAT, p);
    }

    // ── 1.6.0 nx-1: a trade window, Maple Messenger and the Cash Shop coupon box (t-nx-1) ──

    /** Opens a trade window and invites the character (PlayerInteractionHandler CREATE type 3, then INVITE). */
    public void tradeInvite(int characterId) {
        ByteBufOutPacket open = new ByteBufOutPacket();
        open.writeByte(0);  // CREATE
        open.writeByte(3);  // a trade
        send(RecvOpcode.PLAYER_INTERACTION, open);
        ByteBufOutPacket invite = new ByteBufOutPacket();
        invite.writeByte(2); // INVITE
        invite.writeInt(characterId);
        send(RecvOpcode.PLAYER_INTERACTION, invite);
    }

    /** Puts mesos into the open trade window (SET_MESO). */
    public void tradeMeso(int meso) {
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeByte(0x10);
        p.writeInt(meso);
        send(RecvOpcode.PLAYER_INTERACTION, p);
    }

    /** Presses Trade (CONFIRM). */
    public void tradeConfirm() {
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeByte(0x11);
        send(RecvOpcode.PLAYER_INTERACTION, p);
    }

    /** Closes the trade window (EXIT). */
    public void tradeExit() {
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeByte(0x0A);
        send(RecvOpcode.PLAYER_INTERACTION, p);
    }

    /** Accepts the last Maple Messenger invite (MessengerHandler mode 0 with its id); false when there was none. */
    public boolean acceptMessenger() {
        int id = client.lastMessengerInviteId();
        if (id <= 0) {
            return false;
        }
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeByte(0x00);
        p.writeInt(id);
        send(RecvOpcode.MESSENGER, p);
        client.clearMessengerInvite();
        return true;
    }

    /** Closes Maple Messenger (mode 2); nothing happens when none is open. */
    public void leaveMessenger() {
        if (character() == null || character().getMessenger() == null) {
            return;
        }
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeByte(0x02);
        send(RecvOpcode.MESSENGER, p);
    }

    /** Types a code into the Cash Shop coupon box (CouponCodeHandler: two bytes, then the string). */
    public void redeemCoupon(String code) {
        ByteBufOutPacket p = new ByteBufOutPacket();
        p.writeShort(0);
        p.writeString(code);
        send(RecvOpcode.COUPON_CODE, p);
    }

    @Override
    public List<AutoQaLine> heard(long sinceMs) {
        return client.heardSince(sinceMs);
    }

    @Override
    public int mapId() {
        Character c = character();
        return c == null ? -1 : c.getMapId();
    }

    @Override
    public boolean isOnline() {
        Character c = character();
        if (c == null || !client.isLoggedIn()) {
            return false;
        }
        try {
            return Server.getInstance().getWorld(c.getWorld()).getPlayerStorage().getCharacterById(c.getId()) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Logs out the way a closed client does (saves, leaves party/map/world), then waits for it to finish. */
    void logout() {
        try {
            if (character() != null) {
                client.disconnect(false, false);
                for (int i = 0; i < 50 && isOnline(); i++) {
                    Thread.sleep(200);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            AutoQa.log.warn("Auto-QA: logout of synthetic player {} failed: {}", slot, e.toString());
        } finally {
            client.close();
        }
    }
}

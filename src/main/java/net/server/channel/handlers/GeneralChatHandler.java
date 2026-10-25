package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.autoban.AutobanFactory;
import client.command.CommandsExecutor;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ChatLogger;
import tools.PacketCreator;

public final class GeneralChatHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(GeneralChatHandler.class);

    /** The chat gate: at most one line per this many ms. */
    static final long CHAT_SPAM_MS = 200;

    /**
     * Whether a chat line is dropped as spam: another line less than {@link #CHAT_SPAM_MS} after the last one, on the
     * real clock (1.5.1: the old tick-clock stamps dropped any second line typed within one 777 ms tick). A GM's
     * lines are never dropped, so the owner's and the auto-QA accounts' commands never vanish.
     */
    static boolean spamBlocked(boolean gm, long lastSpam, long now) {
        return !gm && lastSpam + CHAT_SPAM_MS > now;
    }

    @Override
    public void handlePacket(InPacket p, Client c) {
        String s = p.readString();
        Character chr = c.getPlayer();
        if (spamBlocked(chr.isGM(), chr.getAutobanManager().getLastChatAt(), System.currentTimeMillis())) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }
        if (s.length() > Byte.MAX_VALUE && !chr.isGM()) {
            AutobanFactory.PACKET_EDIT.alert(c.getPlayer(), c.getPlayer().getName() + " tried to packet edit in General Chat.");
            log.warn("Chr {} tried to send text with length of {}", c.getPlayer().getName(), s.length());
            c.disconnect(true, false);
            return;
        }
        char heading = s.charAt(0);
        if (CommandsExecutor.isCommand(c, s)) {
            CommandsExecutor.getInstance().handle(c, s);
        } else if (heading != '/') {
            int show = p.readByte();
            if (chr.getMap().isMuted() && !chr.isGM()) {
                chr.dropMessage(5, "The map you are in is currently muted. Please try again later.");
                return;
            }
            if (!chr.isHidden()) { // 1.4.1: bots don't hear (or answer, or buff) a GM in /hide
                soloMapling.server.MapleRootSolo.runHook("general chat", () -> {
                    // owner report 2026-10-22: the map's public chat log, which every bot reply prompt shows
                    soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog.record(
                            soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog.channelOf(chr.getMap()),
                            chr.getMapId(), chr.getId(), chr.getName(), s, false, System.currentTimeMillis());
                    // 1.5.0 bot drama: an answer to a bot's question, the wedding vows, a demanded apology
                    if (soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.onPlayerChat(chr, s)) {
                        return;
                    }
                    soloMapling.ArtificialPlayer.BotMessagingSystem.MessageQueue.getInstance().addMessage(
                        "primary", new soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage(chr, s));
                    soloMapling.ArtificialPlayer.BotBuffRequestSystem.BotBuffRequestHandler.tryHandle(chr, s);
                });
            }
            if (!chr.isHidden()) {
                if(chr.getWorldChatOnOff()) {
                    Server.getInstance().sendGlobal(c.getWorld(), PacketCreator.serverNotice(6, chr.getName() +  " [Ch." + chr.getChannel().getId() + "]: " + s));
                    ChatLogger.log(c, "Global", s);
                } else {
                    chr.getMap().broadcastMessage(PacketCreator.getChatText(chr.getId(), s, chr.getWhiteChat(), show));
                    ChatLogger.log(c, "General", s);
                }
            } else {
                if(chr.getWorldChatOnOff()) {
                    Server.getInstance().sendGlobal(c.getWorld(), PacketCreator.serverNotice(6, chr.getName() +  " [Ch." + chr.getChannel().getId() + "]: " + s));
                    ChatLogger.log(c, "GM Global", s);
                } else {
                    chr.getMap().broadcastGMMessage(PacketCreator.getChatText(chr.getId(), s, chr.getWhiteChat(), show));
                    ChatLogger.log(c, "GM General", s);
                }
            }

            chr.getAutobanManager().spam(7);
        chr.getAutobanManager().chatted();
        }
    }
}
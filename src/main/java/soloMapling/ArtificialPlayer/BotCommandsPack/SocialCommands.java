package soloMapling.ArtificialPlayer.BotCommandsPack;

import client.Character;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.List;

import static soloMapling.server.SoloMaplingUtilities.generateRandomNumber;

/*
Commands related to bots socializing with the player
 */

public class SocialCommands {
    static boolean botChatTypingStyle = false;
    static boolean debugSkipDialog = false;

    public static void BotFullChat(Character fakechar, String message) {
        fullChat(fakechar, message, soloMapling.ArtificialPlayer.BotConvo.MessyChat.forChat(fakechar, message));
    }

    // 1.6.0 messy chat: say-do reads what the bot meant; the map sees how this bot types it
    private static void fullChat(Character fakechar, String meant, String typed) {
        soloMapling.ArtificialPlayer.BotConvo.SayDo.said(fakechar, meant); // rc6 say-do: claims in what bots say
        BotNoiseFilter.broadcastBotChat(fakechar, PacketCreator.getChatText(fakechar.getId(), typed, fakechar.isGM(), (byte) 0), typed);
        // 1.6.0 (owner, FM on TEST): an idle bot in town hops 3-4 times while its bubble is up, to catch the eye
        soloMapling.ArtificialPlayer.GCMoveSystem.AttentionHops.onPublicLine(fakechar);
    }

    /** Idle/ambient speech shares the per-map and world budgets; dropped lines are never queued. */
    public static void BotAmbientSpeak(Character bot, String message) {
        // rc6 (QA t-we-1): at a bot wedding the crowd talks about the wedding, the couple says only its vows
        String line = soloMapling.ArtificialPlayer.BotCoupleSystem.BotWeddings.ambientLine(bot, message);
        if (line == null) {
            return;
        }
        // rc6 say-do: a line that claims an action ("brb pots", "gtg", "going to orbis") is said only by a bot that
        // then does it (ClaimActions); otherwise it is dropped and the bot stays quiet this time
        java.util.List<soloMapling.ArtificialPlayer.BotConvo.ClaimRules.Claim> claims =
                soloMapling.ArtificialPlayer.BotConvo.ClaimRules.classify(line);
        if (!soloMapling.ArtificialPlayer.BotConvo.ClaimActions.canBack(bot, claims)) {
            return;
        }
        if (soloMapling.ArtificialPlayer.BotChatTuning.allowAmbient(bot)) {
            BotSpeak(bot, line);
            soloMapling.ArtificialPlayer.BotConvo.ClaimActions.start(bot, claims);
        }
    }

    public static void BotSpeak(Character fakechar, String message) {
        soloMapling.ArtificialPlayer.BotMessagingSystem.BotChatRouting.noteBotSpoke(fakechar, message); // ticket 19
        if (botChatTypingStyle) {
            BotChatbubbleTyping(fakechar, message, 150);
        } else {
            BotFullChat(fakechar, message);
        }
    }

    public static void BotChatbubble(Character fakechar, String message) {
        soloMapling.ArtificialPlayer.BotConvo.SayDo.said(fakechar, message);
        String typed = soloMapling.ArtificialPlayer.BotConvo.MessyChat.forChat(fakechar, message); // 1.6.0 messy chat
        BotNoiseFilter.broadcastBotChat(fakechar, PacketCreator.getChatText(fakechar.getId(), typed, false, 1), typed); // Send message just bubble, no chat box
    }

    public static void BotChatbubbleTyping(Character fakechar, String message, int delayMillis) {
        BotChatbubbleTyping(fakechar, message, delayMillis, true);
    }

    // Deliberate blocking leaf primitive: the typing rhythm IS the effect and the
    // duration is data-driven (message length). Callers pick the thread.
    public static void BotChatbubbleTyping(Character fakechar, String message, int delayMillis, boolean showInChat) {
        String meant = message;
        message = soloMapling.ArtificialPlayer.BotConvo.MessyChat.forChat(fakechar, message); // 1.6.0 messy chat
        StringBuilder displayedMessage = new StringBuilder();
        for (int i = 0; i < message.length(); i++) {
            displayedMessage.append(message.charAt(i)); // Add the next character

            // Broadcast the current state of the message
            BotNoiseFilter.broadcastBotChat(fakechar, PacketCreator.getChatText(fakechar.getId(), displayedMessage.toString(), false, 1), message);

            // If the next character exists, add it to simulate 1-2 letters at a time
            if (i + 1 < message.length()) {
                displayedMessage.append(message.charAt(++i));
                BotNoiseFilter.broadcastBotChat(fakechar, PacketCreator.getChatText(fakechar.getId(), displayedMessage.toString(), false, 1), message);
            }

            // Pause to simulate typing
            BotHelpers.blockingSleep(delayMillis);
        }

        if (showInChat) {
            fullChat(fakechar, meant, message);
        }

    }

    // Deliberate blocking leaf primitive: line count is data-driven; callers
    // (dialogue playback) depend on this holding their thread until done.
    public static void BotDialogue(Character fakechar, List<String> dialogue) {
        if (debugSkipDialog) {
            return;
        }
        for (int i = 0; i < dialogue.size(); i++) {
            BotSpeak(fakechar, dialogue.get(i));
            if (i < dialogue.size() - 1) { // Skip sleep for the last element
                BotHelpers.blockingSleep(5000);
            }
        }
    }

    public static void BotEmote(Character fakechar) {
        int emote = generateRandomNumber(1, 22);
        BotEmote(fakechar, emote);
    }

    public static void BotEmote(Character fakechar, int emote) {
        if (fakechar != null) {
            fakechar.getMap().broadcastMessage(fakechar, PacketCreator.facialExpression(fakechar, emote), true);
            fakechar.getMap().broadcastMessage(fakechar, PacketCreator.facialExpression(fakechar, emote), fakechar.getPosition());
        }
    }

    public static void displayPlayerChatCommands(Character chr, List<String> commands) {
        displayPlayerChatCommands(chr, buildPlayerChatCommands(commands));
    }

    public static void displayPlayerChatCommands(Character chr, String str) {
        List<String> singleStr = new ArrayList<>();
        singleStr.add(str);
        displayPlayerChatCommands(chr, singleStr);
    }

    public static void displayPlayerChatCommands(Character chr, ChatCommandResult msg1) {
        int maxWidth = msg1.getMaxWidth();
        if (maxWidth < 5) {
            maxWidth = 60;
        } else {
            maxWidth = msg1.getMaxWidth() * 10;
        }
        int duration = 25;
        chr.getClient().sendPacket(PacketCreator.sendHint(msg1.getMessage(), maxWidth, duration));
        chr.getClient().sendPacket(PacketCreator.enableActions());
    }

    public static void expirePlayerChatCommands(Character chr) {
        chr.getClient().sendPacket(PacketCreator.sendHint(".", 40, 0));
        chr.getClient().sendPacket(PacketCreator.enableActions());
    }

    public static void spawnCygnusGuide(Character chr, boolean spawn) {
        chr.getClient().sendPacket(PacketCreator.spawnGuide(spawn));
    }

    public static void talkCygnusGuide(Character chr, String msg) {
        chr.getClient().sendPacket(PacketCreator.talkGuide(msg));
    }

    public static void talkCygnusGuideCommands(Character chr, List<String> commands) {
        ChatCommandResult msg1 = buildPlayerChatCommands(commands);
        talkCygnusGuide(chr, msg1.getMessage());
    }

    public static void botSetChalkboard(Character fakechar, String chalkboardMessage) {
        fakechar.setChalkboard(chalkboardMessage);
        fakechar.getMap().broadcastMessage(PacketCreator.useChalkboard(fakechar, false));
        fakechar.sendPacket(PacketCreator.enableActions());
    }

    public static void botClearChalkboard(Character fakechar) {
        fakechar.setChalkboard(null);
        fakechar.getMap().broadcastMessage(PacketCreator.useChalkboard(fakechar, true));
    }

    protected static ChatCommandResult buildPlayerChatCommands(List<String> commands) {
        StringBuilder msgBuilder = new StringBuilder();
        int maxWidth = 0;

        // First pass: Calculate max width
        for (String command : commands) {
            maxWidth = Math.max(maxWidth, command.length());
        }

        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i);
            // Pad the command to the max width
            String paddedCommand = command + " ".repeat((maxWidth - command.length()));

            msgBuilder.append(i + 1)  // Line number starts from 1
                    .append(". ")  // Add period and space
                    .append(command) // Append command
                    .append("\r\n"); // Append new line
        }

        return new ChatCommandResult(msgBuilder.toString(), commands.size(), maxWidth);
    }

    public static class ChatCommandResult {
        private final String message;
        private final int numberOfLines;
        private final int maxWidth;

        public ChatCommandResult(String message, int numberOfLines, int maxWidth) {
            this.message = message;
            this.numberOfLines = numberOfLines;
            this.maxWidth = maxWidth;
        }

        public String getMessage() {
            return message;
        }

        public int getNumberOfLines() {
            return numberOfLines;
        }

        public int getMaxWidth() {
            return maxWidth;
        }
    }
}

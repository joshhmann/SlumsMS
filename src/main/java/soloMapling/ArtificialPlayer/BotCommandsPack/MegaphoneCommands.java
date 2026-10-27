package soloMapling.ArtificialPlayer.BotCommandsPack;

import client.Character;
import client.inventory.Item;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter;
import server.TimerManager;
import server.maps.MapleTVEffect;
import tools.PacketCreator;

import java.util.LinkedList;
import java.util.List;

import static soloMapling.server.SoloMaplingUtilities.channel;
import static soloMapling.server.SoloMaplingUtilities.getRandomElement;
import static soloMapling.server.SoloMaplingUtilities.world;

public class MegaphoneCommands {

    private final static List<Integer> AvatarMegaphoneItems = List.of(5390000, 5390001, 5390002, 5390005, 5390006);
    private final static List<Integer> MapleTVItems = List.of(5075000, 5075001, 5075002);
    private final static List<Integer> MapleTVPartnerItems = List.of(5075001, 5075002);

    public static void BotItemMegaphone(Character fakechar, String message, Item item) {
        message = soloMapling.ArtificialPlayer.BotConvo.ChannelMentions.forSpeaker(fakechar, message); // never name a channel the world lacks
        soloMapling.ArtificialPlayer.BotConvo.SayDo.said(fakechar, null, message, "megaphone");
        soloMapling.site.SiteFeed.megaphone(fakechar, message); // 1.6.0 Track D: the website's feed (memory only)
        String msg = fakechar.getName() + " : " + message; // medal + // optional medal
        boolean whisper = true;
        BotNoiseFilter.broadcastBotMegaphone(world().getId(), PacketCreator.itemMegaphone(msg, whisper, fakechar.getClient().getChannel(), item));
    }

    public static void BotGachaponMegaphone(Character fakechar, Item item) {
        String map = fakechar.getMap().getMapName();
        BotNoiseFilter.broadcastBotMegaphone(world().getId(), PacketCreator.gachaponMessage(item, map, fakechar));
    }

    private static void BotSuperMegaphone(Character fakechar, String msg, boolean ear) {
        BotNoiseFilter.broadcastBotMegaphone(world().getId(), PacketCreator.serverNotice(3, fakechar.getClient().getChannel(), fakechar.getName() + " : " + msg, ear));
    }

    public static void BotSuperMegaphone(Character fakechar, String msg) {
        msg = soloMapling.ArtificialPlayer.BotConvo.ChannelMentions.forSpeaker(fakechar, msg); // never name a channel the world lacks
        soloMapling.ArtificialPlayer.BotConvo.SayDo.said(fakechar, null, msg, "megaphone");
        soloMapling.site.SiteFeed.megaphone(fakechar, msg); // 1.6.0 Track D: the website's feed (memory only)
        BotSuperMegaphone(fakechar, msg, true);
    }

    private static void BotAvatarMegaphone(Character fakechar, String message, int itemId) {
        soloMapling.site.SiteFeed.megaphone(fakechar, message); // 1.6.0 Track D: the website's feed (memory only)
        LinkedList list = stringTo4LineLinkedList(message);
        String medal = "";
        boolean ear = true;
        int worldId = world().getId();
        BotNoiseFilter.broadcastBotMegaphone(worldId, PacketCreator.getAvatarMega(fakechar, medal, fakechar.getClient().getChannel(), itemId, list, ear));
        // 1.5.1 (owner QA): the avatar banner (the tiger) stayed on screen for good, because a bot never sent the
        // clear that a player's avatar megaphone sends 10 s later (UseCashItemHandler). Same packet, same delay.
        TimerManager.getInstance().schedule(() -> BotNoiseFilter.broadcastBotMegaphone(worldId, PacketCreator.byeAvatarMega()),
                AVATAR_MEGA_MS);
    }

    /** How long an avatar megaphone banner stays up, as for a player's (UseCashItemHandler). */
    static final long AVATAR_MEGA_MS = java.util.concurrent.TimeUnit.SECONDS.toMillis(10);


    public static void BotAvatarMegaphone(Character fakechar, String msg) {
        msg = soloMapling.ArtificialPlayer.BotConvo.ChannelMentions.forSpeaker(fakechar, msg); // never name a channel the world lacks
        soloMapling.ArtificialPlayer.BotConvo.SayDo.said(fakechar, null, msg, "megaphone");
        int itemId = getRandomElement(AvatarMegaphoneItems);
        BotAvatarMegaphone(fakechar, msg, itemId);
    }

    private static void BotMapleTVFull(Character fakechar, String msg, int itemId, Character partner) {
        int tvType = itemId % 10;
        if (tvType != 4) {
            partner = partner;
        }
        LinkedList messages = stringTo5LineLinkedList(msg);
        MapleTVEffect.broadcastMapleTVIfNotActive(fakechar, partner, messages, tvType);
    }

    public static void BotMapleTV(Character fakechar, String msg) {
        msg = soloMapling.ArtificialPlayer.BotConvo.ChannelMentions.forSpeaker(fakechar, msg); // never name a channel the world lacks
        int itemId = getRandomElement(MapleTVItems);
        BotMapleTVFull(fakechar, msg, itemId, null);
    }

    public static void BotMapleTVPartner(Character fakechar, String msg, Character partner) {
        msg = soloMapling.ArtificialPlayer.BotConvo.ChannelMentions.forSpeaker(fakechar, msg); // never name a channel the world lacks
        int itemId = getRandomElement(MapleTVPartnerItems);
        BotMapleTVFull(fakechar, msg, itemId, partner);
    }

    /*
    Utility Code for Megaphone Commands
     */
    
    private static LinkedList<String> stringToLinkedList(String message, int lineCount, int charsPerLine) {
        LinkedList<String> list = new LinkedList<>();

        // Handle null input safely
        if (message == null) {
            message = "";
        }

        // Add each chunk of the string to the list
        for (int i = 0; i < lineCount; i++) {
            int startIndex = i * charsPerLine;

            // If we've reached the end of the string, add empty strings to fill remaining lines
            if (startIndex >= message.length()) {
                list.add("");
            } else {
                int endIndex = Math.min(startIndex + charsPerLine, message.length());
                list.add(message.substring(startIndex, endIndex));
            }
        }

        return list;
    }

    private static LinkedList<String> stringTo4LineLinkedList(String message) {
        return stringToLinkedList(message, 4, 13);
    }

    private static LinkedList<String> stringTo5LineLinkedList(String message) {
        return stringToLinkedList(message, 5, 20);
    }

}

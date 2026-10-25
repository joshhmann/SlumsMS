package soloMapling.menus;

import client.Character;
import client.SyntheticClient;
import client.command.commands.gm2.GotoCommand;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.menus.MenuWindow.Option;
import soloMapling.menus.MenuWindow.Page;
import soloMapling.server.SoloMaplingConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.5.0 {@code @guide} / {@code @tutorial}: a short beginner's tour in the NPC window, framed by Cody. Pages turn with
 * Next / Back, and a topic list jumps between them. It opens by itself once per character, a few seconds after the
 * first login on 1.5.0 (remembered in the character's area info, saved with the character, no migration).
 * "Take me there" lines go through the {@code @goto} rules ({@link GotoCommand#warp}): towns only, never from an event,
 * a mini-dungeon or a map you can't leave.
 */
public final class GuideMenu {

    /** Cody frames the window, as for {@code @settings}. */
    public static final int NPC = SettingsMenu.NPC;

    /** The area-info slot that remembers the guide was shown (not a quest id, so the client shows nothing for it). */
    public static final short SEEN_AREA = 29150;
    static final String SEEN_INFO = "guide=1.5.0";

    /** How long after the login the guide opens, so the map and the login windows are up first. */
    static final long FIRST_LOGIN_DELAY_MS = 5_000;

    /** A "take me there" line: its label and the {@code @goto} name of the town. */
    record Trip(String label, String town) {
    }

    /** One page of the guide: its topic (the first page of a topic is listed in the topic menu), text and trips. */
    record GuidePage(String topic, String text, List<Trip> trips) {
    }

    /** Warps a player under the @goto rules; null when they went, else why not (tests stand in). */
    interface Travel {
        String go(Character player, String town);
    }

    static final List<GuidePage> PAGES = List.of(
            new GuidePage("Welcome", ""
                    + "#eWelcome to SlumsMS!#n\r\n"
                    + "A small v83 server for friends. Towns are\r\n"
                    + "full of #bplayer bots#k: they train, trade,\r\n"
                    + "chat, make friends, and #bremember you#k.\r\n"
                    + "Play with them or ignore them: both are fine!\r\n"
                    + "Type #b@guide#k any time to reopen this tour.", List.of()),
            new GuidePage("The buttons by the chat bar", ""
                    + "#eThe buttons by the chat bar#n\r\n"
                    + "#bSHOP#k: the Cash Shop (opens in a window).\r\n"
                    + "#bTRADE#k: warps to #bBase of Roots#k (the hub\r\n"
                    + "   for job advances, storage and FM portal).\r\n"
                    + "#bMENU#k & #bSHORTCUT#k: normal game menus\r\n"
                    + "   for your windows, keybindings and settings.", List.of()),
            new GuidePage("Where to find things", ""
                    + "#eWhere to find things (1/3)#n\r\n"
                    + "#bGachapon#k: 12 town machines (#b@gacha <town>#k).\r\n"
                    + "#bTickets#k: #bHanako#k in the FM Entrance or CS.\r\n"
                    + "#bFree Market#k: the FM portal in any town.\r\n"
                    + "#bDaily Check-In#k: log in daily for gifts (10+).\r\n"
                    + "   Type #b@daily#k to claim your reward.",
                    List.of(new Trip("Take me to Henesys", "henesys"))),
            new GuidePage(null, ""
                    + "#eWhere to find things (2/3)#n\r\n"
                    + "#bHeracle#k (Orbis Guild Base): make guilds.\r\n"
                    + "#bHonorable Rock#k (towns): guild rankings.\r\n"
                    + "#bMoony#k (Amoria): rings. Weddings too!\r\n"
                    + "#bRydole#k (Ludibrium) crafts weapons.",
                    List.of(new Trip("Take me to Orbis", "orbis"), new Trip("Take me to Amoria", "amoria"),
                            new Trip("Take me to Ludibrium", "ludi"))),
            new GuidePage(null, ""
                    + "#eWhere to find things (3/3)#n\r\n"
                    + "In #bBase of Roots#k (click TRADE):\r\n"
                    + "#bJob Master#k (advances), #bGerrard#k (storage),\r\n"
                    + "#bCotton Warper#k (bosses), #bWyatt#k (rebirth 200).\r\n"
                    + "#bCody#k: that's me! I run this guide and\r\n"
                    + "#b@settings#k, and post news on Discord.", List.of()),
            new GuidePage("Bots", ""
                    + "#eBots (1/2): talking and friends#n\r\n"
                    + "Chat in plain words: name a bot, face it, or\r\n"
                    + "stand close. They understand natural talk!\r\n"
                    + "#bFriends#k: party, buff, trade, and fame them.\r\n"
                    + "   Friends greet, invite, and gift you.\r\n"
                    + "#bRivals#k: KS or insult a bot; it remembers!", List.of()),
            new GuidePage(null, ""
                    + "#eBots (2/2): settings and guilds#n\r\n"
                    + "#b@settings#k: one-click bot drama & options.\r\n"
                    + "#b@botdrama off#k: disable rival grudges.\r\n"
                    + "#b@romance on#k: bot dating (off by default).\r\n"
                    + "#bBuddies#k: add friendly bots to your list.\r\n"
                    + "#bGuilds#k: 10 bot guilds at war (#b@guildwar#k).", List.of()),
            new GuidePage("Handy commands", ""
                    + "#eHandy commands#n (type in chat):\r\n"
                    + "#b@settings#k bot options  | #b@daily#k daily gift\r\n"
                    + "#b@goto <town>#k hop to town | #b@commands#k list\r\n"
                    + "#b@price <item>#k FM market price check\r\n"
                    + "#b@gacha <town>#k prizes | #b@guildwar#k war score\r\n"
                    + "#b@dispose#k unfreezes stuck NPC chat", List.of()),
            new GuidePage("Trading and shops", ""
                    + "#eTrading and shops#n\r\n"
                    + "#bBuy from a bot#k: ask in chat/trade to haggle.\r\n"
                    + "#bSell to a bot#k: invite to trade, put items in.\r\n"
                    + "#bBot shops#k: FM rooms 1-12, channels 1-3.\r\n"
                    + "#bYour shop#k: open one in the Free Market.\r\n"
                    + "Shops pay a #b3% tax#k on what they sell.", List.of()));

    static final Travel GOTO = GotoCommand::warp;
    private static volatile Travel travel = GOTO;

    private GuideMenu() {
    }

    /** {@code @guide}: opens the guide at the first page; false when it could not (another NPC window is open). */
    public static boolean open(Character player) {
        return open(player, 0);
    }

    static boolean open(Character player, int page) {
        if (!MenuWindow.open(player, NPC, show(page))) {
            return false;
        }
        markSeen(player);
        return true;
    }

    /**
     * At login: a real player who has not seen the guide gets it a few seconds later. It is marked seen only once it
     * opened, so a player who was busy in another NPC window gets it at their next login.
     */
    public static void onLogin(Character player) {
        if (!isPlayer(player) || seen(player)) {
            return;
        }
        MenuWindow.later(() -> {
            if (player.isLoggedinWorld() && !seen(player)) {
                open(player);
            }
        }, FIRST_LOGIN_DELAY_MS);
    }

    static boolean isPlayer(Character player) {
        return player != null && !BotHelpers.isBot(player) && !(player.getClient() instanceof SyntheticClient)
                && player.getId() < SoloMaplingConstants.GameConstants.BOT_BASE_ID;
    }

    public static boolean seen(Character player) {
        return player.getAreaInfo(SEEN_AREA) != null;
    }

    static void markSeen(Character player) {
        // saved with the character (area_info); no packet: the client has no use for it
        player.getAreaInfos().put(SEEN_AREA, SEEN_INFO);
    }

    /** {@code @guide reset} (GMs, QA): the guide opens again at the next login. */
    public static void forget(Character player) {
        player.getAreaInfos().remove(SEEN_AREA);
    }

    static MenuWindow.Menu show(int page) {
        return who -> page(page);
    }

    /** Page {@code i} (clamped) with its trips, Next / Back, the topic list and Close. */
    static Page page(int i) {
        int p = Math.max(0, Math.min(i, PAGES.size() - 1));
        GuidePage g = PAGES.get(p);
        List<Option> lines = new ArrayList<>();
        for (Trip t : g.trips()) {
            lines.add(new Option("#b" + t.label() + "#k", who -> go(who, t.town())));
        }
        if (p + 1 < PAGES.size()) {
            lines.add(new Option("#bNext >#k", who -> show(p + 1)));
        }
        if (p > 0) {
            lines.add(new Option("#b< Back#k", who -> show(p - 1)));
        }
        lines.add(new Option("#bTopics#k", who -> GuideMenu::topics));
        lines.add(new Option("Close", who -> null));
        String head = "#d(" + (p + 1) + "/" + PAGES.size() + ")#k ";
        return new Page(head + g.text() + "\r\n", lines);
    }

    /** The topic list: the first page of each topic. */
    static Page topics(Character player) {
        List<Option> lines = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < PAGES.size(); i++) {
            String topic = PAGES.get(i).topic();
            if (topic != null) {
                int target = i;
                lines.add(new Option("#b" + (++n) + ". " + topic + "#k", who -> show(target)));
            }
        }
        lines.add(new Option("Close", who -> null));
        return new Page("#eSlumsMS guide#n: pick a topic.\r\n"
                + "Reopen it any time with #b@guide#k.\r\n", lines);
    }

    /** A "take me there" click: the @goto rules decide; the window closes either way. */
    static MenuWindow.Menu go(Character player, String town) {
        String refused = travel.go(player, town);
        if (refused != null) {
            player.dropMessage(5, refused);
        }
        return null;
    }

    /** Test seam (null restores the real @goto). */
    static void useForTest(Travel testTravel) {
        travel = testTravel == null ? GOTO : testTravel;
    }
}

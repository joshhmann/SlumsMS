package soloMapling.menus;

import client.Character;
import client.Job;
import config.YamlConfig;
import constants.id.MapId;
import server.maps.MapleMap;

import java.util.List;

/**
 * 1.6.1 (ticket 29): "Start on Maple Island?" - a yes/no in the NPC window at a new Explorer's first login. Yes warps
 * to the classic island (Mushroom Town, map 10000, where the Maple Road quests and tips are; Shanks at Southperry
 * takes them to Lith Harbor at level 7), No keeps the usual start. Asked once per character (remembered in the area
 * info, saved with the character, no migration), only for a real Beginner of level 1-9 still standing on the start map;
 * Cygnus and Aran keep their own starts and bots never get it. Switch: {@code TUTORIAL_ISLAND_PROMPT}.
 * The guide ({@link GuideMenu}) opens after the answer instead of over the question.
 */
public final class TutorialIslandMenu {

    /** Cody frames the window, as for the guide. */
    public static final int NPC = SettingsMenu.NPC;

    /** The classic island start (v83: Mushroom Town). */
    public static final int ISLAND_START = MapId.MUSHROOM_TOWN;

    /** The area-info slot that remembers the question was asked (not a quest id, so the client shows nothing). */
    public static final short ASKED_AREA = 29152;
    static final String INFO = "island=1.6.1";

    static final long FIRST_LOGIN_DELAY_MS = 3_000;
    /** After the answer, the guide opens this much later (the closed window's cooldown plus the map load). */
    static final long GUIDE_DELAY_MS = 3_000;
    static final int MAX_LEVEL = 9;

    /** Warps to a map; null when it went, else why not (tests stand in). */
    interface Warp {
        String go(Character player, int mapId);
    }

    static final Warp REAL = (player, mapId) -> {
        MapleMap map = player.getClient().getChannelServer().getMapFactory().getMap(mapId);
        if (map == null) {
            return "Maple Island is not available right now.";
        }
        player.changeMap(map, map.getRandomPlayerSpawnpoint());
        return null;
    };
    private static volatile Warp warp = REAL;

    private TutorialIslandMenu() {
    }

    /** The rule, pure (tests, auto-QA): who is asked. */
    public static boolean shouldAsk(boolean realPlayer, int jobId, int level, int mapId, boolean asked, boolean switchOn) {
        return switchOn && realPlayer && !asked && jobId == Job.BEGINNER.getId() && level >= 1 && level <= MAX_LEVEL
                && mapId == MapId.MAPLEROOT_HUB;
    }

    /** Whether this character would be asked now. */
    public static boolean eligible(Character player) {
        return player != null && shouldAsk(GuideMenu.isPlayer(player), player.getJob().getId(), player.getLevel(),
                player.getMapId(), player.getAreaInfo(ASKED_AREA) != null,
                YamlConfig.config.server.TUTORIAL_ISLAND_PROMPT);
    }

    /**
     * At login: an eligible new character is asked a few seconds later. Marked asked only once the window opened,
     * so a player who was busy in another NPC window is asked at the next login.
     */
    public static void onLogin(Character player) {
        if (!eligible(player)) {
            return;
        }
        MenuWindow.later(() -> {
            if (player.isLoggedinWorld() && eligible(player) && MenuWindow.open(player, NPC, TutorialIslandMenu::page)) {
                player.getAreaInfos().put(ASKED_AREA, INFO);
            }
        }, FIRST_LOGIN_DELAY_MS);
    }

    /** QA ({@code !qa run ti-1}): the question, for any character, without marking it asked. */
    public static boolean openForQa(Character player) {
        return MenuWindow.open(player, NPC, TutorialIslandMenu::page);
    }

    /** The question's page (auto-QA picks Yes and No from it). */
    public static MenuWindow.Page pageForQa(Character player) {
        return page(player);
    }

    /** QA: ask again at the next login. */
    public static void forget(Character player) {
        player.getAreaInfos().remove(ASKED_AREA);
    }

    static MenuWindow.Page page(Character player) {
        return new MenuWindow.Page(""
                + "#eWelcome! Start on Maple Island?#n\r\n"
                + "#bYes#k: the classic beginner island, with its\r\n"
                + "quests. Shanks at Southperry ships you to\r\n"
                + "Lith Harbor at level 7.\r\n"
                + "#bNo#k: straight to the usual start.\r\n",
                List.of(new MenuWindow.Option("#bYes, take me to Maple Island#k", who -> answer(who, true)),
                        new MenuWindow.Option("#bNo, I'll start here#k", who -> answer(who, false))));
    }

    private static MenuWindow.Menu answer(Character player, boolean yes) {
        if (yes) {
            String refused = warp.go(player, ISLAND_START);
            if (refused != null) {
                player.dropMessage(5, refused);
                return null;
            }
        }
        if (!GuideMenu.seen(player)) {
            MenuWindow.later(() -> {
                if (player.isLoggedinWorld() && !GuideMenu.seen(player)) {
                    GuideMenu.open(player);
                }
            }, GUIDE_DELAY_MS);
        }
        return null;
    }

    /** Test seam (null restores the real warp). */
    static void useForTest(Warp testWarp) {
        warp = testWarp == null ? REAL : testWarp;
    }
}

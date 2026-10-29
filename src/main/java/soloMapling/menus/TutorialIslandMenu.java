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
 * info, saved with the character, no migration), for every real starter-job character (Beginner, Noblesse, Legend,
 * Evan) of level 1-9 wherever they land, except already on the island or in the Aran opening; bots never get it.
 * Yes also makes Shanks' trip free for them ({@link #freeTrip}). Switch: {@code TUTORIAL_ISLAND_PROMPT}.
 * The guide ({@link GuideMenu}) opens after the answer instead of over the question.
 */
public final class TutorialIslandMenu {

    /** Cody frames the window, as for the guide. */
    public static final int NPC = SettingsMenu.NPC;

    /** The classic island start (v83: Mushroom Town). */
    public static final int ISLAND_START = MapId.MUSHROOM_TOWN;

    /** The area-info slot that remembers the question was asked (not a quest id, so the client shows nothing). */
    public static final short ASKED_AREA = 29154; // 29152 is StarterChairs.NOTICED_AREA (live since 1.6.0)
    static final String INFO = "island=1.6.1";

    /**
     * Remembers that this character came to the island through the "Start on Maple Island?" Yes, so Shanks' ship to
     * Lith Harbor is free for them (cleared when they sail). Saved with the character, no migration.
     */
    public static final short TRIP_AREA = 29156;
    static final String TRIP_INFO = "freetrip=1.6.1";

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

    /** The starter jobs: Beginner, Noblesse, Legend (Aran) and Evan, all at level 1. */
    static boolean isStarterJob(int jobId) {
        return jobId == Job.BEGINNER.getId() || jobId == Job.NOBLESSE.getId() || jobId == Job.LEGEND.getId()
                || jobId == Job.EVAN.getId();
    }

    /**
     * The rule, pure (tests, auto-QA): who is asked. Any new player character of a starter job at level 1-9, once,
     * wherever they stand, except where the question is pointless or would cut in on a scripted scene: already on
     * the island, or in the Aran opening maps.
     */
    public static boolean shouldAsk(boolean realPlayer, int jobId, int level, int mapId, boolean asked, boolean switchOn) {
        return switchOn && realPlayer && !asked && isStarterJob(jobId) && level >= 1 && level <= MAX_LEVEL
                && !MapId.isMapleIsland(mapId)
                && !(mapId >= MapId.ARAN_TUTORIAL_START && mapId <= MapId.ARAN_TUTORIAL_MAX);
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

    /** Whether Shanks takes this character to Lith Harbor for free (they came through the island option). */
    public static boolean freeTrip(Character player) {
        return player != null && player.getAreaInfo(TRIP_AREA) != null;
    }

    /** Shanks sailed them: the free trip is used up. */
    public static void tripUsed(Character player) {
        player.getAreaInfos().remove(TRIP_AREA);
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
                + "Lith Harbor for free once you reach level 7.\r\n"
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
            player.getAreaInfos().put(TRIP_AREA, TRIP_INFO);
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

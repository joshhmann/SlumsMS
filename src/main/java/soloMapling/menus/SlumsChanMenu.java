package soloMapling.menus;

import client.Character;
import soloMapling.menus.MenuWindow.Option;
import soloMapling.menus.MenuWindow.Page;

import java.util.List;

/**
 * 1.6.1 Slums-chan (NPC 9990000): the friendly face of the guide. She stands in Base of Roots and Henesys (migration
 * 042); clicking her shows a short welcome with the guide and the settings menu one click away. The first-login guide
 * and {@code @settings} are framed by her head too ({@link GuideMenu#NPC}, {@link SettingsMenu#NPC}). Her script is
 * {@code scripts/npc/9990000.js}.
 */
public final class SlumsChanMenu {

    public static final int NPC = 9990000;

    static final String WELCOME = ""
            + "Hi, I'm #bSlums-chan#k! Welcome to SlumsMS.\r\n"
            + "Look around, say hi to whoever you meet, and\r\n"
            + "take your time: nobody is in a rush here.\r\n"
            + "Want a hand getting started?\r\n";

    /** The area-info slot that remembers her login welcome was said (29150-29156 are the other first-login flags). */
    public static final short WELCOMED_AREA = 29155;
    static final String WELCOMED_INFO = "welcome=1.6.1";

    /** Her first-login line, a yellow chat line with her name, before the island question and the guide open. */
    static final String LOGIN_LINE = "Slums-chan: Hi, I'm Slums-chan! Welcome to SlumsMS. Take your time, say hi to the "
            + "folks around you, and I'll show you around in a moment.";

    /** Whether another character of the account was already welcomed or shown the guide (a seam for tests). */
    interface AccountHistory {
        boolean welcomed(int accountId, int exceptCharId);
    }

    static final AccountHistory DATABASE = (accountId, exceptCharId) -> {
        try (java.sql.Connection con = tools.DatabaseConnection.getConnection();
             java.sql.PreparedStatement ps = con.prepareStatement("SELECT 1 FROM area_info a JOIN characters c ON c.id = a.charid "
                     + "WHERE c.accountid = ? AND a.charid <> ? AND a.area IN (?, ?) LIMIT 1")) {
            ps.setInt(1, accountId);
            ps.setInt(2, exceptCharId);
            ps.setInt(3, WELCOMED_AREA);
            ps.setInt(4, GuideMenu.SEEN_AREA);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (java.sql.SQLException e) {
            return true; // cannot tell: stay quiet rather than greet an old account again
        }
    };
    private static volatile AccountHistory history = DATABASE;

    private SlumsChanMenu() {
    }

    /**
     * At login: the first time an account ever logs in, Slums-chan says hello (once per account: the mark is on the
     * character, and any other character of the account that was welcomed or saw the guide counts).
     */
    public static void onLogin(Character player) {
        if (!GuideMenu.isPlayer(player) || player.getAreaInfo(WELCOMED_AREA) != null || GuideMenu.seen(player)
                || history.welcomed(player.getAccountID(), player.getId())) {
            return;
        }
        player.yellowMessage(LOGIN_LINE);
        player.getAreaInfos().put(WELCOMED_AREA, WELCOMED_INFO);
    }

    /** Test seam (null restores the database). */
    static void useForTest(AccountHistory testHistory) {
        history = testHistory == null ? DATABASE : testHistory;
    }

    /** Her welcome page: the guide, the settings, or nothing today. */
    public static Page page(Character player) {
        return new Page(WELCOME, List.of(
                new Option("Show me around (the guide)", who -> {
                    GuideMenu.markSeen(who);
                    return GuideMenu.show(0);
                }),
                new Option("My settings", who -> SettingsMenu::page),
                new Option("I'm fine for now, thanks!", who -> null)));
    }

    /** Called by {@code 9990000.js} when a player clicks her: the welcome starts in the already open NPC window. */
    public static void talk(Character player) {
        MenuWindow.attach(player, NPC, SlumsChanMenu::page);
    }
}

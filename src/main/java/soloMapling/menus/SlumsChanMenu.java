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

    private SlumsChanMenu() {
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

package soloMapling.menus;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.menus.MenuWindow.Page;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.1 Slums-chan (NPC 9990000): she frames the guide and settings windows and welcomes whoever clicks her. */
class SlumsChanMenuTest {

    @AfterEach
    void real() {
        MenuWindow.useForTest(null, null);
    }

    private static Character player(int id) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName("Newbie" + id);
        return chr;
    }

    @Test
    void theGuideAndTheSettingsWindowsAreFramedByHer() {
        assertEquals(9990000, SlumsChanMenu.NPC);
        assertEquals(9990000, GuideMenu.NPC);
        assertEquals(9990000, SettingsMenu.NPC);
    }

    @Test
    void herWelcomeIsWarmNeverAnAiAndOpensTheGuideAndTheSettings() {
        Page page = SlumsChanMenu.page(null);
        String all = page.render().toLowerCase();
        assertTrue(all.contains("slums-chan") && all.contains("welcome"), all);
        for (String banned : List.of(" ai ", "a.i.", "bot ", "artificial", "language model", "program")) {
            assertFalse((all + " ").contains(banned), "she never talks like that: " + banned);
        }
        assertEquals(3, page.labels().size());
        Page guide = page.find("guide").pick().apply(player(3100)).page(null);
        assertTrue(guide.text().contains("Slums-chan"), guide.text());
        Page settings = SlumsChanMenu.page(null).find("settings").pick().apply(null).page(player(3101));
        assertTrue(settings.text().toLowerCase().contains("settings") || !settings.labels().isEmpty());
        assertNull(page.find("fine").pick().apply(null), "the last line closes the window");
    }

    @Test
    void talkingToHerStartsHerMenuInTheOpenWindowWithoutOpeningAnother() {
        List<Integer> opened = new ArrayList<>();
        MenuWindow.useForTest((p, npc) -> opened.add(npc), (task, delay) -> task.run());
        Character me = player(3102);
        SlumsChanMenu.talk(me);
        assertTrue(opened.isEmpty(), "the NPC window is already open from the click");
        assertTrue(MenuWindow.text(me).contains("Slums-chan"));
        assertTrue(MenuWindow.select(me, 0), "the guide shows in the same window");
        assertTrue(MenuWindow.text(me).contains("Welcome to SlumsMS"));
        assertTrue(GuideMenu.seen(me), "going through her counts as having seen the guide");
    }
}

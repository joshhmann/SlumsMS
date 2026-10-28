package soloMapling.menus;

import client.BotClient;
import client.Character;
import client.Client;
import client.command.commands.gm0.GuideCommand;
import client.command.commands.gm2.GotoCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import soloMapling.menus.MenuWindow.Page;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class GuideMenuTest {

    /** The v83 NPC window fits about this many characters on a line before it wraps. */
    static final int LINE_CHARS = 46;
    /** Lines of page text (wrapped) before the reader has to scroll far. */
    static final int TEXT_LINES = 7;
    /** All lines on a page, text plus clickable lines. */
    static final int ALL_LINES = 13;

    @AfterEach
    void real() {
        GuideMenu.useForTest(null);
        MenuWindow.useForTest(null, null);
    }

    /** A real player (not a bot client) who is in the world. */
    private static Character player(int id) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName("Newbie" + id);
        chr.setClient(mock(Client.class));
        Character p = spy(chr);
        doReturn(true).when(p).isLoggedinWorld();
        return p;
    }

    private static String visible(String s) {
        return s.replaceAll("#L\\d+#", "").replaceAll("#[bkendrl]", "");
    }

    private static int wrapped(String line) {
        return Math.max(1, (visible(line).length() + LINE_CHARS - 1) / LINE_CHARS);
    }

    @Test
    void everyPageFitsTheDialog() {
        for (int i = 0; i < GuideMenu.PAGES.size(); i++) {
            Page page = GuideMenu.page(i);
            int textLines = 0;
            for (String line : page.text().split("\r\n", -1)) {
                assertTrue(visible(line).length() <= LINE_CHARS, "page " + (i + 1) + " line too long: " + line);
                textLines += wrapped(line);
            }
            assertTrue(textLines <= TEXT_LINES, "page " + (i + 1) + " has " + textLines + " lines of text");
            int all = 0;
            for (String line : page.render().split("\r\n", -1)) {
                all += wrapped(line);
            }
            assertTrue(all <= ALL_LINES, "page " + (i + 1) + " has " + all + " lines in all");
        }
        assertTrue(visible(GuideMenu.topics(null).render()).split("\r\n").length <= ALL_LINES);
    }

    @Test
    void theTopicsAreTheSixChapters() {
        assertEquals(List.of("#b1. Welcome#k", "#b2. The buttons by the chat bar#k", "#b3. Where to find things#k",
                "#b4. Bots#k", "#b5. Handy commands#k", "#b6. Trading and shops#k", "Close"),
                GuideMenu.topics(null).labels());
        Page bots = GuideMenu.topics(null).find("4. Bots").pick().apply(null).page(null);
        assertTrue(bots.text().contains("Bots (1/2)"), bots.text());
    }

    @Test
    void pagesTurnWithNextAndBack() {
        Page first = GuideMenu.page(0);
        assertFalse(first.labels().contains("#b< Back#k"), "nothing before the first page");
        Page second = first.find("Next").pick().apply(null).page(null);
        assertTrue(second.text().contains("SHOP"), second.text());
        assertTrue(second.find("Back").pick().apply(null).page(null).text().contains("Welcome to SlumsMS"));
        Page last = GuideMenu.page(GuideMenu.PAGES.size() - 1);
        assertFalse(last.labels().contains("#bNext >#k"), "nothing after the last page");
        assertTrue(last.text().contains("3% tax"), last.text());
        assertNull(last.find("Close").pick().apply(null), "Close closes the window");
    }

    @Test
    void thePagesCoverWhatANewPlayerAsks() {
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < GuideMenu.PAGES.size(); i++) {
            all.append(GuideMenu.page(i).render());
        }
        for (String must : List.of("SHOP", "TRADE", "MENU", "SHORTCUT", "Hanako", "Gachapon", "@price", "Heracle",
                "Honorable Rock", "Moony", "Amoria", "@daily", "Cody", "@botdrama off", "@romance on", "@settings",
                "@goto <town>", "@gacha", "@guildwar", "@dispose", "@commands", "haggle", "3% tax", "@guide")) {
            assertTrue(all.toString().toLowerCase().contains(must.toLowerCase()), "the guide never mentions " + must);
        }
    }

    @Test
    void takeMeThereOnlyGoesToTownsAPlayerCanGoto() {
        List<String> towns = new ArrayList<>();
        for (GuideMenu.GuidePage g : GuideMenu.PAGES) {
            for (GuideMenu.Trip t : g.trips()) {
                assertTrue(GotoCommand.destinations(false).containsKey(t.town()), t.town() + " is not a player town");
                towns.add(t.town());
            }
        }
        assertTrue(towns.containsAll(List.of("orbis", "amoria")), towns.toString());

        List<String> went = new ArrayList<>();
        GuideMenu.useForTest((p, town) -> {
            went.add(town);
            return null;
        });
        Page where = GuideMenu.page(3);
        assertNull(where.find("Orbis").pick().apply(player(5001)), "the window closes for the trip");
        assertEquals(List.of("orbis"), went);
    }

    @Test
    void takeMeThereObeysTheGotoRules() {
        Character me = player(5002);
        doReturn(true).when(me).isAlive();
        doReturn(mock(EventInstanceManager.class)).when(me).getEventInstance();
        List<String> said = new ArrayList<>();
        doAnswer(c -> said.add(c.getArgument(1))).when(me).dropMessage(anyInt(), anyString());

        GuideMenu.useForTest(null); // the real @goto path
        assertNull(GuideMenu.page(3).find("Orbis").pick().apply(me));
        assertEquals(List.of("This command can not be used in this map."), said, "no trip out of an event");
    }

    @Test
    void playersCanOnlyGotoTowns() {
        assertFalse(GotoCommand.destinations(false).containsKey("zakum"));
        assertFalse(GotoCommand.destinations(false).containsKey("gmmap"));
        assertTrue(GotoCommand.destinations(false).containsKey("henesys"));
        assertTrue(GotoCommand.destinations(true).containsKey("zakum"), "GMs keep the areas");
        assertNull(GotoCommand.blockedReason(false, true, false, 100000000, 0));
        assertTrue(GotoCommand.blockedReason(false, false, false, 100000000, 0).contains("dead"));
        assertTrue(GotoCommand.blockedReason(false, true, true, 100000000, 0).contains("can not"));
        assertTrue(GotoCommand.blockedReason(false, true, false, 100000000,
                (int) server.maps.FieldLimit.CANNOTMIGRATE.getValue()).contains("can not"));
        assertNull(GotoCommand.blockedReason(true, true, true, 100000000, 0), "GMs may leave events");
    }

    @Test
    void theFirstLoginShowsItOnceAndRemembersIt() {
        List<Integer> opened = new ArrayList<>();
        MenuWindow.useForTest((p, npc) -> opened.add(npc), (task, delay) -> task.run());
        Character me = player(5003);
        assertFalse(GuideMenu.seen(me));

        GuideMenu.onLogin(me);
        assertEquals(List.of(GuideMenu.NPC), opened);
        assertTrue(GuideMenu.seen(me));
        assertEquals("guide=1.5.0", me.getAreaInfo(GuideMenu.SEEN_AREA), "kept in area_info, saved with the character");
        assertTrue(MenuWindow.text(me).contains("Welcome to SlumsMS"));
        MenuWindow.closed(me);

        GuideMenu.onLogin(me);
        assertEquals(1, opened.size(), "only the first login");
    }

    @Test
    void aBusyPlayerGetsItNextLogin() {
        boolean[] free = {false};
        MenuWindow.useForTest((p, npc) -> free[0], (task, delay) -> task.run());
        Character me = player(5004);
        GuideMenu.onLogin(me);
        assertFalse(GuideMenu.seen(me), "another NPC window was open: not shown, not marked");
        free[0] = true;
        GuideMenu.onLogin(me);
        assertTrue(GuideMenu.seen(me));
    }

    @Test
    void botsNeverGetIt() {
        List<Integer> opened = new ArrayList<>();
        MenuWindow.useForTest((p, npc) -> opened.add(npc), (task, delay) -> task.run());
        BotClient c = new BotClient(0, 0);
        Character bot = Character.getDefault(c);
        bot.setID(1_000_000_123);
        c.setPlayer(bot);
        GuideMenu.onLogin(bot);
        assertTrue(opened.isEmpty());
        assertFalse(GuideMenu.seen(bot));
    }

    @Test
    void atGuideReopensIt() {
        List<Integer> opened = new ArrayList<>();
        MenuWindow.useForTest((p, npc) -> opened.add(npc), (task, delay) -> task.run());
        Character me = player(5005);
        GuideMenu.onLogin(me);
        MenuWindow.closed(me);

        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(me);
        new GuideCommand().execute(c, new String[0]);
        assertEquals(2, opened.size(), "@guide opens it again after the first-login showing");
        assertTrue(MenuWindow.text(me).contains("Welcome to SlumsMS"));
        MenuWindow.closed(me);

        new GuideCommand().execute(c, new String[]{"reset"});
        assertTrue(GuideMenu.seen(me), "players can't reset it (it just opens the guide)");
        MenuWindow.closed(me);
        doReturn(true).when(me).isGM();
        new GuideCommand().execute(c, new String[]{"reset"});
        assertFalse(GuideMenu.seen(me), "a GM resets it for QA");
        GuideMenu.onLogin(me);
        assertEquals(4, opened.size(), "and it shows at the next login");
    }

    @Test
    void theUiTipShowsOnceAndNeverToBots() {
        Character me = player(5010);
        Client c = me.getClient();
        doReturn(10600).when(c).getClientBuild();
        GuideMenu.onLoginUiTip(me);
        org.mockito.Mockito.verify(c, org.mockito.Mockito.times(GuideMenu.UI_TIP.length))
                .sendPacket(org.mockito.ArgumentMatchers.any());
        assertEquals(GuideMenu.UI_TIP_INFO, me.getAreaInfo(GuideMenu.UI_TIP_AREA));
        GuideMenu.onLoginUiTip(me);
        org.mockito.Mockito.verify(c, org.mockito.Mockito.times(GuideMenu.UI_TIP.length))
                .sendPacket(org.mockito.ArgumentMatchers.any());

        Character old = player(5011);
        doReturn(10500).when(old.getClient()).getClientBuild();
        GuideMenu.onLoginUiTip(old);
        assertNull(old.getAreaInfo(GuideMenu.UI_TIP_AREA), "old client: nothing");

        BotClient bc = new BotClient(0, 0);
        Character bot = Character.getDefault(bc);
        bot.setID(1_000_000_124);
        bc.setPlayer(bot);
        GuideMenu.onLoginUiTip(bot);
        assertNull(bot.getAreaInfo(GuideMenu.UI_TIP_AREA));
    }
}

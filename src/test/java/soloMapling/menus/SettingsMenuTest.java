package soloMapling.menus;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter;
import soloMapling.menus.MenuWindow.Page;
import soloMapling.menus.SettingsMenu.State;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsMenuTest {

    @AfterEach
    void real() {
        SettingsMenu.useForTest(null, null);
        MenuWindow.useForTest(null, null);
    }

    private static Character player(int id) {
        BotClient c = new BotClient(0, 0);
        Character chr = Character.getDefault(c);
        chr.setID(id);
        chr.setName("Setter" + id);
        c.setPlayer(chr);
        return chr;
    }

    private static final State DEFAULTS = new State(true, false, false, false);

    @Test
    void eachLineShowsItsState() {
        Page p = SettingsMenu.page(DEFAULTS);
        String text = p.render();
        assertTrue(text.contains("Bot drama: rivals, trash talk and KS wars. #bON#k"), text);
        assertTrue(text.contains("Romance: bots may flirt, date, propose. #rOFF#k"), text);
        assertTrue(text.contains("Bot megaphones: #bSHOWN#k"), text);
        assertTrue(text.contains("Bot chatter around you: #bSHOWN#k"), text);
        assertTrue(text.contains("More commands"), text);

        String flipped = SettingsMenu.page(new State(false, true, true, true)).render();
        assertTrue(flipped.contains("KS wars. #rOFF#k"), flipped);
        assertTrue(flipped.contains("propose. #bON#k"), flipped);
        assertTrue(flipped.contains("Bot megaphones: #rHIDDEN#k"), flipped);
        assertTrue(flipped.contains("Bot chatter around you: #rHIDDEN#k"), flipped);
    }

    @Test
    void eachClickRunsTheCommandThatFlipsIt() {
        assertEquals(List.of("@botdrama off", "@romance on", "@quiet megaphones", "@quiet bots"), clicks(DEFAULTS));
        assertEquals(List.of("@botdrama on", "@romance off", "@quiet megaphones off", "@quiet bots off"),
                clicks(new State(false, true, true, true)));
        assertEquals(List.of("@botdrama off", "@romance on", "@quiet megaphones off", "@quiet bots"),
                clicks(new State(true, false, true, false)));
    }

    private static List<String> clicks(State s) {
        List<String> ran = new ArrayList<>();
        SettingsMenu.useForTest((p, line) -> {
            ran.add(line);
            return null;
        }, p -> s);
        Character me = player(3001);
        Page page = SettingsMenu.page(s);
        for (String part : List.of("Bot drama", "Romance", "Bot megaphones", "Bot chatter")) {
            MenuWindow.Menu next = page.find(part).pick().apply(me);
            assertTrue(next != null, "a sync command redraws the settings");
        }
        return ran;
    }

    @Test
    void theRedrawShowsTheNewState() {
        State[] now = {DEFAULTS};
        SettingsMenu.useForTest((p, line) -> {
            if (line.equals("@botdrama off")) {
                now[0] = new State(false, now[0].romanceOn(), now[0].hideMegaphones(), now[0].hideBots());
            }
            return null;
        }, p -> now[0]);
        Character me = player(3002);
        MenuWindow.useForTest((p, npc) -> true, (task, delay) -> task.run());
        assertTrue(SettingsMenu.open(me));
        assertTrue(MenuWindow.text(me).contains("KS wars. #bON#k"));
        assertTrue(MenuWindow.select(me, 0), "the window stays open");
        assertTrue(MenuWindow.text(me).contains("KS wars. #rOFF#k"));
    }

    @Test
    void romanceRunsOffTheGameThreadSoTheMenuOpensAgainWhenItIsDone() {
        CompletableFuture<Void> work = new CompletableFuture<>();
        List<Integer> opened = new ArrayList<>();
        SettingsMenu.useForTest((p, line) -> work, p -> DEFAULTS);
        MenuWindow.useForTest((p, npc) -> opened.add(npc), (task, delay) -> task.run());
        Character me = player(3003);
        assertNull(SettingsMenu.page(DEFAULTS).find("Romance").pick().apply(me), "the window closes for now");
        assertTrue(opened.isEmpty());
        work.complete(null);
        assertEquals(List.of(SettingsMenu.NPC), opened);
    }

    @Test
    void helpIsReadOnlyWithABackLine() {
        Page help = SettingsMenu.help(player(3004));
        assertTrue(help.text().contains("@price <item>"), help.text());
        assertTrue(help.text().contains("@guildwar"), help.text());
        assertEquals(1, help.labels().size());
        assertTrue(help.labels().get(0).contains("Back"));
    }

    @Test
    void theQuietLinesGoThroughTheRealQuietCommand() {
        Character me = player(3005);
        BotNoiseFilter.set(me.getId(), new BotNoiseFilter.Setting(false, true));
        SettingsMenu.useForTest(null, null); // the real command path and the real state
        Page page = SettingsMenu.page(me);
        assertTrue(page.render().contains("Bot megaphones: #bSHOWN#k"));
        assertTrue(page.render().contains("Bot chatter around you: #rHIDDEN#k"));

        page.find("Bot megaphones").pick().apply(me);
        assertEquals(new BotNoiseFilter.Setting(true, true), BotNoiseFilter.get(me.getId()));
        page = SettingsMenu.page(me);
        page.find("Bot chatter").pick().apply(me);
        assertEquals(new BotNoiseFilter.Setting(true, false), BotNoiseFilter.get(me.getId()),
                "@quiet bots off shows chatter again and keeps megaphones hidden");
        page = SettingsMenu.page(me);
        page.find("Bot megaphones").pick().apply(me);
        assertEquals(new BotNoiseFilter.Setting(false, false), BotNoiseFilter.get(me.getId()));
        assertFalse(SettingsMenu.page(me).render().contains("HIDDEN"));
    }
}

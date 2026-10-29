package soloMapling.menus;

import client.BotClient;
import client.Character;
import client.Client;
import client.Job;
import config.YamlConfig;
import constants.id.MapId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.quest.HiddenQuests;
import soloMapling.menus.MenuWindow.Page;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/** 1.6.1 ticket 29: the "Start on Maple Island?" yes/no at a new Explorer's first login. */
class TutorialIslandMenuTest {

    private boolean switchWas;

    @BeforeEach
    void on() {
        switchWas = YamlConfig.config.server.TUTORIAL_ISLAND_PROMPT;
        YamlConfig.config.server.TUTORIAL_ISLAND_PROMPT = true;
    }

    @AfterEach
    void real() {
        YamlConfig.config.server.TUTORIAL_ISLAND_PROMPT = switchWas;
        TutorialIslandMenu.useForTest(null);
        MenuWindow.useForTest(null, null);
        GuideMenu.useForTest(null);
    }

    /** A new real Explorer standing in the hub. */
    private static Character newbie(int id) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName("Newbie" + id);
        chr.setClient(mock(Client.class));
        Character p = spy(chr);
        doReturn(true).when(p).isLoggedinWorld();
        doReturn(MapId.MAPLEROOT_HUB).when(p).getMapId();
        return p;
    }

    @Test
    void theIslandStartIsMushroomTownAndTheExitIsShanksToLith() {
        assertEquals(10000, TutorialIslandMenu.ISLAND_START);
        assertEquals(MapId.MUSHROOM_TOWN, TutorialIslandMenu.ISLAND_START);
        assertTrue(MapId.isMapleIsland(TutorialIslandMenu.ISLAND_START));
    }

    @Test
    void onlyANewRealExplorerInTheHubIsAsked() {
        Character me = newbie(6001);
        assertTrue(TutorialIslandMenu.eligible(me));

        YamlConfig.config.server.TUTORIAL_ISLAND_PROMPT = false;
        assertFalse(TutorialIslandMenu.eligible(me), "the switch is off");
        YamlConfig.config.server.TUTORIAL_ISLAND_PROMPT = true;

        assertFalse(TutorialIslandMenu.eligible(null));
        assertTrue(TutorialIslandMenu.shouldAsk(true, Job.NOBLESSE.getId(), 1, MapId.MAPLEROOT_HUB, false, true),
                "Cygnus starters are asked too");
        assertTrue(TutorialIslandMenu.shouldAsk(true, Job.LEGEND.getId(), 1, MapId.MAPLEROOT_HUB, false, true),
                "Aran starters are asked too");
        assertTrue(TutorialIslandMenu.shouldAsk(true, Job.EVAN.getId(), 1, MapId.MAPLEROOT_HUB, false, true));
        assertFalse(TutorialIslandMenu.shouldAsk(true, Job.WARRIOR.getId(), 1, MapId.MAPLEROOT_HUB, false, true),
                "only a Beginner");
        assertFalse(TutorialIslandMenu.shouldAsk(true, Job.BEGINNER.getId(), 10, MapId.MAPLEROOT_HUB, false, true),
                "the island is for level 1 to 9");
        assertTrue(TutorialIslandMenu.shouldAsk(true, Job.BEGINNER.getId(), 1, 100000000, false, true),
                "wherever the first login lands");
        assertFalse(TutorialIslandMenu.shouldAsk(true, Job.BEGINNER.getId(), 1, TutorialIslandMenu.ISLAND_START, false, true),
                "already on the island");
        assertFalse(TutorialIslandMenu.shouldAsk(true, Job.LEGEND.getId(), 1, MapId.ARAN_TUTORIAL_START, false, true),
                "not over the Aran opening");
        assertFalse(TutorialIslandMenu.shouldAsk(true, Job.BEGINNER.getId(), 1, MapId.MAPLEROOT_HUB, true, true),
                "already asked");
        assertFalse(TutorialIslandMenu.shouldAsk(false, Job.BEGINNER.getId(), 1, MapId.MAPLEROOT_HUB, false, true),
                "a bot");
        assertTrue(TutorialIslandMenu.shouldAsk(true, Job.BEGINNER.getId(), 1, MapId.MAPLEROOT_HUB, false, true));
    }

    @Test
    void firstLoginAsksOnceAndYesWarpsToTheIsland() {
        List<Integer> opened = new ArrayList<>();
        MenuWindow.useForTest((p, npc) -> opened.add(npc), (task, delay) -> task.run());
        List<Integer> warped = new ArrayList<>();
        TutorialIslandMenu.useForTest((p, map) -> {
            warped.add(map);
            return null;
        });
        Character me = newbie(6002);

        TutorialIslandMenu.onLogin(me);
        assertEquals(List.of(TutorialIslandMenu.NPC), opened);
        String text = MenuWindow.text(me);
        assertTrue(text.contains("Maple Island"), text);
        assertEquals(TutorialIslandMenu.INFO, me.getAreaInfo(TutorialIslandMenu.ASKED_AREA), "remembered with the character");

        assertFalse(MenuWindow.select(me, 0), "the window closes");
        assertEquals(List.of(10000), warped, "Yes warps to the island start");
        assertEquals(List.of(TutorialIslandMenu.NPC, GuideMenu.NPC), opened, "then the guide, as it would have");
        MenuWindow.closed(me);

        TutorialIslandMenu.onLogin(me);
        assertEquals(2, opened.size(), "asked once per character");
    }

    @Test
    void yesMakesShanksTripFreeAndNoDoesNot() {
        MenuWindow.useForTest((p, npc) -> true, (task, delay) -> task.run());
        TutorialIslandMenu.useForTest((p, map) -> null);
        Character no = newbie(6010);
        TutorialIslandMenu.page(no).find("No").pick().apply(no);
        assertFalse(TutorialIslandMenu.freeTrip(no));
        Character yes = newbie(6011);
        assertFalse(TutorialIslandMenu.freeTrip(yes));
        TutorialIslandMenu.page(yes).find("Yes").pick().apply(yes);
        assertTrue(TutorialIslandMenu.freeTrip(yes), "came through the island option");
        TutorialIslandMenu.tripUsed(yes);
        assertFalse(TutorialIslandMenu.freeTrip(yes), "used up when they sail");
    }

    @Test
    void aRefusedWarpDoesNotGrantTheFreeTrip() {
        MenuWindow.useForTest((p, npc) -> true, (task, delay) -> task.run());
        TutorialIslandMenu.useForTest((p, map) -> "no");
        Character me = newbie(6012);
        TutorialIslandMenu.page(me).find("Yes").pick().apply(me);
        assertFalse(TutorialIslandMenu.freeTrip(me));
    }

    @Test
    void aCygnusStarterOnAnyMapGetsTheQuestionAtLogin() {
        List<Integer> opened = new ArrayList<>();
        MenuWindow.useForTest((p, npc) -> opened.add(npc), (task, delay) -> task.run());
        Character me = newbie(6013);
        me.setJob(Job.NOBLESSE);
        doReturn(100000000).when(me).getMapId();
        TutorialIslandMenu.onLogin(me);
        assertEquals(List.of(TutorialIslandMenu.NPC), opened);
    }

    @Test
    void noKeepsTodaysStart() {
        MenuWindow.useForTest((p, npc) -> true, (task, delay) -> task.run());
        List<Integer> warped = new ArrayList<>();
        TutorialIslandMenu.useForTest((p, map) -> {
            warped.add(map);
            return null;
        });
        Character me = newbie(6003);
        Page page = TutorialIslandMenu.page(me);
        assertEquals(2, page.labels().size());
        assertNull(page.find("No").pick().apply(me), "No closes the window");
        assertTrue(warped.isEmpty(), "No stays where the character is");
    }

    @Test
    void aRefusedWarpSaysWhy() {
        MenuWindow.useForTest((p, npc) -> true, (task, delay) -> task.run());
        TutorialIslandMenu.useForTest((p, map) -> "You can not leave here right now.");
        Character me = newbie(6004);
        List<String> said = new ArrayList<>();
        org.mockito.Mockito.doAnswer(c -> said.add(c.getArgument(1))).when(me)
                .dropMessage(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
        assertNull(TutorialIslandMenu.page(me).find("Yes").pick().apply(me));
        assertEquals(List.of("You can not leave here right now."), said);
    }

    @Test
    void aBusyPlayerIsAskedNextLoginAndBotsNever() {
        boolean[] free = {false};
        MenuWindow.useForTest((p, npc) -> free[0], (task, delay) -> task.run());
        Character me = newbie(6005);
        TutorialIslandMenu.onLogin(me);
        assertNull(me.getAreaInfo(TutorialIslandMenu.ASKED_AREA), "window was busy: not asked, not marked");
        free[0] = true;
        TutorialIslandMenu.onLogin(me);
        assertEquals(TutorialIslandMenu.INFO, me.getAreaInfo(TutorialIslandMenu.ASKED_AREA));

        List<Integer> opened = new ArrayList<>();
        MenuWindow.useForTest((p, npc) -> opened.add(npc), (task, delay) -> task.run());
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(1_000_000_321);
        bot.setName("SomeBot");
        Character b = spy(bot);
        doReturn(MapId.MAPLEROOT_HUB).when(b).getMapId();
        TutorialIslandMenu.onLogin(b);
        assertTrue(opened.isEmpty(), "bots never get the prompt");
        assertFalse(TutorialIslandMenu.eligible(b));
    }

    @Test
    void theGuideWaitsForTheAnswerWhileThePromptIsPending() {
        List<Integer> opened = new ArrayList<>();
        MenuWindow.useForTest((p, npc) -> opened.add(npc), (task, delay) -> task.run());
        Character me = newbie(6006);
        GuideMenu.onLogin(me);
        assertTrue(opened.isEmpty(), "the island question goes first; the guide opens after the answer");
        assertFalse(GuideMenu.seen(me));

        YamlConfig.config.server.TUTORIAL_ISLAND_PROMPT = false;
        GuideMenu.onLogin(me);
        assertEquals(List.of(GuideMenu.NPC), opened, "with the switch off the guide is as before");
    }

    @Test
    void islandQuestsAreNotHiddenForABeginner() {
        // Maple Island quests (Heena/Sera/Lucas/Roger/Pio/Biggs/Maria/Sen...), ids 1000-1049 and Amherst's 1031-1046
        for (int q = 1000; q <= 1049; q++) {
            assertFalse(HiddenQuests.isHidden(q, Job.BEGINNER.getId()), "island quest " + q + " is hidden");
        }
    }
}

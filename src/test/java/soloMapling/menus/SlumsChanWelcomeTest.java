package soloMapling.menus;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/** 1.6.1: Slums-chan welcomes a new account on its first login, once, before the island question and the guide. */
class SlumsChanWelcomeTest {

    @AfterEach
    void real() {
        SlumsChanMenu.useForTest(null);
    }

    private static Character newbie(int id, List<String> said) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName("Fresh" + id);
        chr.setClient(mock(Client.class));
        Character p = spy(chr);
        doAnswer(c -> said.add(c.getArgument(0))).when(p).yellowMessage(anyString());
        return p;
    }

    @Test
    void aNewAccountHearsHerOnceInHerVoice() {
        SlumsChanMenu.useForTest((acc, except) -> false);
        List<String> said = new ArrayList<>();
        Character me = newbie(8001, said);
        SlumsChanMenu.onLogin(me);
        assertEquals(1, said.size());
        assertTrue(said.get(0).startsWith("Slums-chan: "), said.get(0));
        assertNotNull(me.getAreaInfo(SlumsChanMenu.WELCOMED_AREA), "remembered with the character");
        SlumsChanMenu.onLogin(me);
        assertEquals(1, said.size(), "once");
    }

    @Test
    void anAccountWithAWelcomedOrGuidedCharacterIsNotGreetedAgain() {
        SlumsChanMenu.useForTest((acc, except) -> true);
        List<String> said = new ArrayList<>();
        SlumsChanMenu.onLogin(newbie(8002, said));
        assertTrue(said.isEmpty(), "once per account, not per character");
    }

    @Test
    void aCharacterWhoAlreadySawTheGuideIsNotGreeted() {
        SlumsChanMenu.useForTest((acc, except) -> false);
        List<String> said = new ArrayList<>();
        Character me = newbie(8003, said);
        GuideMenu.markSeen(me);
        SlumsChanMenu.onLogin(me);
        assertTrue(said.isEmpty());
    }

    @Test
    void botsAreNeverGreeted() {
        SlumsChanMenu.useForTest((acc, except) -> false);
        List<String> said = new ArrayList<>();
        Character bot = newbie(1_000_000_555, said);
        SlumsChanMenu.onLogin(bot);
        assertTrue(said.isEmpty());
        assertNull(bot.getAreaInfo(SlumsChanMenu.WELCOMED_AREA));
    }
}

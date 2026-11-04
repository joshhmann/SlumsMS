package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Goal;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Mind;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialActions;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialDecider;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMetrics;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialWorld;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Ticket 181 stage 4 at the bot-talk seam: the bot's emotions and needs reach {@link TalkBrain}'s inputs
 * ({@code WorldSense.mind}) when social acts are on and change nothing when they are off; avoid records keep two bots
 * from greeting or chatting; the decision tick is invoked from the talk tick only when the switch is on.
 */
class BotTalkSocialActsTest {

    static final long NOW = 500L * 86_400_000L;
    final AtomicLong clock = new AtomicLong(NOW);

    @BeforeEach
    void setUp() {
        BotTalk.resetForTest();
        LifeMemoryStore.useConnectionSourceForTest(null);
        SocialSim.useForTest(clock::get, 3L);
        SocialActions.useForTest(null, null, null);
        System.clearProperty(SocialActions.SWITCH);
        SocialDecider.resetForTest();
        SocialMetrics.resetActCountersForTest();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SocialActions.SWITCH);
        SocialActions.useForTest(null, null, null);
        SocialSim.reset();
        LifeMemoryStore.useConnectionSourceForTest(null);
        BotTalk.resetForTest();
    }

    private static Character chr(int slot, String name) {
        return TalkPlannerTest.chr(slot, name, 30);
    }

    private Mind mindOf(Character c, LifeMemory mem) {
        BotTalk.memories = id -> mem;
        return new BotTalk.WorldSense().mind(c, NOW);
    }

    @Test
    void anAngryLonelyMindMovesTheTalkInputsWhenOnAndNotWhenOff() {
        Character c = chr(11, "Moody");
        LifeMemory calm = LifeMemoryStore.of(c.getId());
        Mind base = mindOf(c, calm);
        // the same bot, but furious and starved of company
        LifeMemory upset = LifeMemoryStore.of(chr(12, "Moody2").getId());
        upset.mind(m -> {
            m.feel(SocialMind.Emotion.ANGER, 1.0, NOW);
            m.feel(SocialMind.Emotion.SADNESS, 0.8, NOW);
            m.feel(SocialMind.Emotion.LONELINESS, 0.9, NOW);
            m.satisfy(SocialMind.Need.COMPANIONSHIP, -1.0, NOW);
            return null;
        });
        Character c2 = chr(12, "Moody2");
        Mind angry = mindOf(c2, upset);
        assertTrue(angry.mood() < base.mood(), "mood: " + angry.mood() + " vs " + base.mood());
        assertTrue(angry.killer() > base.killer(), "an angry bot has more of the killer in it");
        assertEquals(Goal.COMPANY, angry.goal(), "a company-starved bot wants company");
        assertTrue(angry.pressure() > 0.6);
        // off: the mind is not read at all; the inputs are what they were before this change
        System.setProperty(SocialActions.SWITCH, "false");
        Mind offAngry = mindOf(c2, upset);
        Mind offCalm = mindOf(c2, LifeMemoryStore.of(chr(13, "Calm").getId()));
        assertEquals(offCalm.mood(), offAngry.mood(), 1e-12, "no mind input with the switch off");
        assertEquals(offCalm.killer(), offAngry.killer(), 1e-12);
        assertEquals(offCalm.social(), offAngry.social(), 1e-12);
        assertEquals(offCalm.goal(), offAngry.goal());
    }

    @Test
    void anAvoidRecordKeepsTwoBotsFromGreetingOrChattingAndOnlyWhenOn() {
        Character a = chr(21, "Ann");
        Character b = chr(22, "Bea");
        assertFalse(BotTalk.avoids(a, b, NOW));
        SocialActions.startAvoid(new SocialActions.Avoid(a.getId(), b.getId(), NOW, NOW + 60_000, 450, "a grudge"));
        assertTrue(BotTalk.avoids(a, b, NOW + 1), "either direction counts");
        assertTrue(BotTalk.avoids(b, a, NOW + 1));
        assertFalse(BotTalk.avoids(a, b, NOW + 61_000), "expired");
        System.setProperty(SocialActions.SWITCH, "false");
        assertFalse(BotTalk.avoids(a, b, NOW + 1), "off: the old behaviour");
    }

    private static Character onMap(int slot, MapleMap map) {
        Character c = TalkPlannerTest.chr(slot, "Bot" + slot, 30);
        c.setMap(map);
        return c;
    }

    @Test
    void theTalkTickCallsTheDecisionTickOnlyWhenTheSwitchIsOn() {
        SocialWorld world = mock(SocialWorld.class);
        when(world.free(anyInt())).thenReturn(false);
        when(world.mindReady(anyInt())).thenReturn(true);
        SocialActions.useForTest(world, null, null);
        MapleMap map = mock(MapleMap.class, Mockito.withSettings().stubOnly());
        when(map.getId()).thenReturn(100000000);
        when(map.getChannelId()).thenReturn(1);
        Character a = onMap(31, map);
        Character b = onMap(32, map);
        LifeMemoryStore.of(a.getId());
        LifeMemoryStore.of(b.getId());
        List<Map.Entry<Long, List<Character>>> maps = List.of(Map.entry(BotTalk.key(1, 100000000), List.of(a, b)));

        System.setProperty(SocialActions.SWITCH, "false");
        BotTalk.socialActs(maps, NOW);
        verifyNoInteractions(world);
        assertTrue(SocialMetrics.actCounters().isEmpty(), "off: nothing counted");

        System.clearProperty(SocialActions.SWITCH);
        BotTalk.socialActs(maps, NOW);
        verify(world, Mockito.atLeastOnce()).free(anyInt());
        assertEquals(2L, SocialMetrics.actCounters().get("decision.decisions"));
        assertEquals(2L, SocialMetrics.actCounters().get("decision.skipped-busy"), "busy bots decide nothing");

        // an event map gets no social acts, as it gets no talks
        SocialDecider.resetForTest();
        SocialMetrics.resetActCountersForTest();
        when(map.isEventMap()).thenReturn(true);
        BotTalk.socialActs(maps, NOW);
        assertTrue(SocialMetrics.actCounters().isEmpty());
        verify(world, never()).say(anyInt(), Mockito.anyString());
    }
}

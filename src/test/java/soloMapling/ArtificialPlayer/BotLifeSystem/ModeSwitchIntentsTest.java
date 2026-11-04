package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialActKind;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialIntent;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialIntents;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Ticket 271 stage 5: a bot with a promise falling due within two minutes is committed for the mode switch too.
 * {@code ModeSwitch.blocker} already asks {@code BotCommitments.inFlight} (which now has the intent clause), so the swap
 * waits; once the promise is kept or lapses the same bot is free. The real {@code BotCommitments} and
 * {@code BotPopulationDirector} run; only the game's lookups are faked.
 */
class ModeSwitchIntentsTest {

    private static final java.util.concurrent.atomic.AtomicInteger NEXT = new java.util.concurrent.atomic.AtomicInteger(1_000_090_000);
    private int ID, FRIEND; // a fresh pair per test: the memory store keeps what a test leaves in it
    private static final long MIN = TimeUnit.MINUTES.toMillis(1);
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private Character chr;
    private TrainingBot bot;
    private LifeRuntime runtime;

    @BeforeEach
    void setUp() {
        ID = NEXT.incrementAndGet();
        FRIEND = NEXT.incrementAndGet();
        System.clearProperty(SocialIntents.SWITCH);
        SocialSim.useForTest(clock::get, 3);
        SocialIntents.resetForTest();
        chr = mock(Character.class);
        when(chr.getId()).thenReturn(ID);
        when(chr.getMap()).thenReturn(mock(MapleMap.class));
        when(chr.getMapId()).thenReturn(100000000);
        bot = mock(TrainingBot.class);
        when(bot.getChr()).thenReturn(chr);
        runtime = mock(LifeRuntime.class);
        when(runtime.commitBlocker(anyLong())).thenReturn(null);
        LifeMemoryStore.of(ID);
    }

    @AfterEach
    void tearDown() {
        SocialIntents.resetForTest();
        SocialSim.reset();
    }

    private String blocker() {
        try (MockedStatic<CharacterStorage> storage = mockStatic(CharacterStorage.class);
             MockedStatic<BotConversation> chats = mockStatic(BotConversation.class);
             MockedStatic<LifeTowns> towns = mockStatic(LifeTowns.class);
             MockedStatic<soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples> couples =
                     mockStatic(soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.class)) {
            storage.when(() -> CharacterStorage.getBotById(ID)).thenReturn(bot);
            chats.when(() -> BotConversation.hasAnyChat(ID)).thenReturn(false);
            towns.when(() -> LifeTowns.isTownMap(anyInt())).thenReturn(true);
            couples.when(() -> soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.partnerOf(ID)).thenReturn(-1);
            return ModeSwitch.blocker(bot, runtime, clock.get());
        }
    }

    @Test
    void aPromiseDueWithinTwoMinutesHoldsTheSwapUntilItIsKeptOrLapses() {
        assertNull(blocker(), "control: a free bot on a town map may switch");
        SocialIntent far = SocialIntents.promise(ID, FRIEND, SocialActKind.INVITE, 20 * MIN, 100000000, "talk:PARTY",
                clock.get()).intent();
        assertNull(blocker(), "due in twenty minutes: not yet a commitment");
        clock.addAndGet(18 * MIN + 1);
        assertEquals("committed", blocker(), "due in under two minutes: the swap waits");
        clock.set(far.expiresAt() + 1);
        assertNull(blocker(), "past its deadline it holds nobody");
        SocialIntent next = SocialIntents.promise(ID, FRIEND, SocialActKind.SPEND_TIME, MIN, 100000000, "talk:COME_ALONG",
                clock.get()).intent();
        assertEquals("committed", blocker());
        SocialIntents.complete(ID, next.id(), clock.get());
        assertNull(blocker(), "kept: free again");
    }

    @Test
    void withTheFlagOffAnIntentHoldsNobody() {
        SocialIntents.promise(ID, FRIEND, SocialActKind.INVITE, MIN, 100000000, "talk:PARTY", clock.get());
        assertEquals("committed", blocker());
        System.setProperty(SocialIntents.SWITCH, "false");
        assertNull(blocker());
    }
}

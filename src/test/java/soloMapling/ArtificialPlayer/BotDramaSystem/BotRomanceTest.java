package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotRomanceTest {

    static final int PLAYER = 4242;
    static final int BOT = BotStore.BOT_ID_BASE + 77;
    static final int BOT2 = BotStore.BOT_ID_BASE + 78;
    static final long DAY = TimeUnit.DAYS.toMillis(1);

    final RomanceStore.Memory store = new RomanceStore.Memory();
    final AtomicLong clock = new AtomicLong(1000 * DAY);

    @BeforeEach
    void setUp() {
        BotRomance.useForTest(store, id -> null, id -> null, clock::get);
        DramaKit.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotRomance.useForTest(null, null, null, null);
        DramaKit.resetForTest();
    }

    static Character chr(int id, String name) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        return c;
    }

    @Test
    void romanceIsOffByDefaultAndTheOptInIsStored() {
        Character p = chr(PLAYER, "Bob");
        assertFalse(BotRomance.romanceOn(PLAYER));
        BotRomance.setRomance(p, true);
        assertTrue(BotRomance.romanceOn(PLAYER));
        assertTrue(store.in.contains(PLAYER));
        BotRomance.useForTest(store, id -> null, id -> null, clock::get); // a restart
        BotRomance.ensureLoaded();
        assertTrue(BotRomance.romanceOn(PLAYER), "read back at boot");
        BotRomance.setRomance(p, false);
        assertFalse(store.in.contains(PLAYER));
    }

    @Test
    void withoutTheOptInThePromptForbidsFlirtingAndFlirtyRepliesAreDropped() {
        Character p = chr(PLAYER, "Bob");
        Character b = chr(BOT, "Amy");
        BotRomance.ensureLoaded();
        assertTrue(BotRomance.promptLine(BOT, p).contains("Never flirt"));
        assertFalse(BotRomance.replyAllowed(b, p, "hey cutie wanna go out"));
        assertTrue(BotRomance.replyAllowed(b, p, "ant tunnel is good for ur level"));
        assertFalse(BotRomance.replyAllowed(b, p, "ur so ugly lol"), "off-limits even when not flirting");
    }

    @Test
    void datingIsStoredAndTheBotIsTakenAndSweetInThePrompt() {
        Character p = chr(PLAYER, "Bob");
        Character b = chr(BOT, "Amy");
        BotRomance.setRomance(p, true);
        Romance r = BotRomance.startDating(b, p, false);
        assertEquals(Romance.Stage.DATING, r.stage());
        assertNotNull(BotRomance.ofBot(BOT));
        assertTrue(BotRomance.taken(BOT));
        assertTrue(BotRomance.promptLine(BOT, p).contains("PG-13"));
        assertTrue(BotRomance.replyAllowed(b, p, "miss u cutie"));
        assertEquals(1, BotRomance.partners(PLAYER).size());
    }

    @Test
    void turningRomanceOffEndsDatingQuietly() {
        Character p = chr(PLAYER, "Bob");
        Character b = chr(BOT, "Amy");
        BotRomance.setRomance(p, true);
        BotRomance.startDating(b, p, false);
        BotRomance.setRomance(p, false);
        assertNull(BotRomance.ofBot(BOT));
        assertEquals(Romance.Stage.EX, BotRomance.with(PLAYER, BOT).stage());
    }

    @Test
    void neglectWarnsFirstThenBreaksUp() {
        Character p = chr(PLAYER, "Bob");
        Character b = chr(BOT, "Amy");
        BotRomance.useForTest(store, id -> id == BOT ? b : null, id -> id == PLAYER ? p : null, clock::get);
        when(b.getMapId()).thenReturn(1);
        when(p.getMapId()).thenReturn(2);
        BotRomance.setRomance(p, true);
        BotRomance.startDating(b, p, false);
        clock.addAndGet(5 * DAY);
        BotRomance.step(clock.get(), new java.util.Random(1));
        assertEquals(Romance.Stage.DATING, BotRomance.with(PLAYER, BOT).stage(), "a warning first");
        clock.addAndGet(TimeUnit.MINUTES.toMillis(31));
        BotRomance.step(clock.get(), new java.util.Random(1));
        assertEquals(Romance.Stage.EX, BotRomance.with(PLAYER, BOT).stage(), "then it is over");
    }

    @Test
    void theQaNeglectStepWarnsAPartnerStandingNextToThePlayer() {
        // rc6 auto-QA triage (t-rm-3): !qa do neglect backdated the romance and ran a step, but the staged partner
        // stands on the player's map, so the step counted them together and never warned.
        Character p = chr(PLAYER, "Bob");
        Character b = chr(BOT, "Amy");
        BotRomance.useForTest(store, id -> id == BOT ? b : null, id -> id == PLAYER ? p : null, clock::get);
        when(b.getMapId()).thenReturn(1);
        when(p.getMapId()).thenReturn(1);
        BotRomance.setRomance(p, true);
        BotRomance.startDating(b, p, false);
        assertTrue(BotRomance.backdate(PLAYER, BOT, 5 * DAY));
        BotRomance.step(clock.get(), new java.util.Random(1));
        assertFalse(BotRomance.neglectNow(p, b), "a step on one map refreshed 'together': nothing to warn about");
        assertTrue(BotRomance.backdate(PLAYER, BOT, 5 * DAY));
        assertTrue(BotRomance.neglectNow(p, b), "the QA step warns straight after the backdate");
        assertFalse(BotRomance.neglectNow(p, b), "once");
        assertEquals(Romance.Stage.DATING, BotRomance.with(PLAYER, BOT).stage(), "a warning, not a breakup");
    }

    @Test
    void twoPartnersAreTracked() {
        Character p = chr(PLAYER, "Bob");
        BotRomance.setRomance(p, true);
        BotRomance.startDating(chr(BOT, "Amy"), p, false);
        clock.addAndGet(1000);
        BotRomance.startDating(chr(BOT2, "Cid"), p, false);
        assertEquals(2, BotRomance.partners(PLAYER).size());
        assertEquals(BOT2, BotRomance.partners(PLAYER).get(1).botCharId(), "newest last");
        assertTrue(BotRomance.confrontNow(p).contains("online"), "both must be online to confront");
    }

    @Test
    void aFailedReadKeepsEverythingClosed() {
        store.failing = true;
        BotRomance.ensureLoaded();
        assertFalse(BotRomance.loaded());
        assertNull(BotRomance.onPlayerLine(chr(BOT, "Amy"), chr(PLAYER, "Bob"), "wanna go out with me"));
    }

    @Test
    void anEngagedBreakupNeedsThePlayerOnline() {
        Romance engaged = new Romance(PLAYER, 77, Romance.Stage.ENGAGED, clock.get(), clock.get(), 0, 1112803, 0, false);
        BotRomance.putForTest(engaged);
        assertFalse(BotRomance.end(engaged, BotRomance.EndReason.NEGLECT, true), "the stock engagement needs the player");
        assertEquals(Romance.Stage.ENGAGED, BotRomance.with(PLAYER, BOT).stage());
    }
}

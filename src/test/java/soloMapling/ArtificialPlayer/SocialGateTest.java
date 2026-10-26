package soloMapling.ArtificialPlayer;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.Trade;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter;
import soloMapling.ArtificialPlayer.SocialGate.Act;
import soloMapling.ArtificialPlayer.SocialGate.Provocation;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 1.6.0 S5: each owner rule of the social gate, one test each (they fail with the rule taken out). */
class SocialGateTest {

    static final int PLAYER = 4242;
    static final int OTHER_PLAYER = 4343;
    static final int BOT_A = BotStore.BOT_ID_BASE + 101;
    static final int BOT_B = BotStore.BOT_ID_BASE + 102;
    static final int BOT_C = BotStore.BOT_ID_BASE + 103;
    static final long DAY = TimeUnit.DAYS.toMillis(1);

    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    SocialGateStore.Memory store;

    @BeforeEach
    void setUp() {
        store = new SocialGateStore.Memory();
        SocialGate.useForTest(store, Runnable::run, clock::get);
        SocialGate.useRomanceOnForTest(id -> false);
        SocialGate.useBusyForTest(p -> false);
        BotNoiseFilter.forgetAllForTest();
        BotWhispers.resetForTest();
    }

    @AfterEach
    void tearDown() {
        SocialGate.useForTest(null, null, null);
        SocialGate.useRomanceOnForTest(null);
        SocialGate.useBusyForTest(null);
        BotNoiseFilter.forgetAllForTest();
        BotWhispers.resetForTest();
        System.clearProperty("solomapling.chat.whisperPlayerGapMs");
    }

    static Character player(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("p" + id);
        return c;
    }

    static Character bot(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("b" + id);
        return c;
    }

    // ── rule 1: started it ──

    @Test
    void spiceNeedsThePlayerToHaveStartedItWithThatBotAndFadesInAboutTwoWeeks() {
        assertFalse(SocialGate.rules(BOT_A, PLAYER, Act.TAUNT).ok(), "a fresh player gets no spice");
        assertFalse(SocialGate.rules(BOT_A, PLAYER, Act.KS_SPREE).ok());
        SocialGate.playerStarted(BOT_A, PLAYER, Provocation.KS);
        for (Act spice : new Act[]{Act.TAUNT, Act.ROAST, Act.KS_SPREE, Act.KS_WAR, Act.CALL_OUT, Act.DEFAME, Act.REVENGE}) {
            assertTrue(SocialGate.rules(BOT_A, PLAYER, spice).ok(), spice + " after the player KS'd this bot");
        }
        assertFalse(SocialGate.rules(BOT_B, PLAYER, Act.TAUNT).ok(), "only the bot they started it with");
        assertFalse(SocialGate.rules(BOT_A, OTHER_PLAYER, Act.TAUNT).ok(), "only toward the player who started it");

        clock.addAndGet(13 * DAY);
        assertTrue(SocialGate.rules(BOT_A, PLAYER, Act.TAUNT).ok(), "one KS still counts after 13 days");
        clock.addAndGet(2 * DAY);
        assertFalse(SocialGate.rules(BOT_A, PLAYER, Act.TAUNT).ok(), "half-life two weeks: faded after 15 days");

        SocialGate.playerStarted(BOT_A, PLAYER, Provocation.ROAST);
        SocialGate.playerStarted(BOT_A, PLAYER, Provocation.ROAST);
        clock.addAndGet(20 * DAY);
        assertTrue(SocialGate.rules(BOT_A, PLAYER, Act.TAUNT).ok(), "renewed grudges last longer");
    }

    @Test
    void theLedgerMapsOnlyThePlayersOwnWrongs() {
        assertEquals(Provocation.KS, SocialGate.provocationOf(
                soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent.KS));
        assertEquals(Provocation.ROAST, SocialGate.provocationOf(
                soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent.RUDE_CHAT));
        assertEquals(null, SocialGate.provocationOf(
                soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent.HEARSAY_BAD), "gossip starts nothing");
        SocialGate.playerStarted(PLAYER, BOT_A, Provocation.KS); // ids the wrong way round: ignored
        assertFalse(SocialGate.startedIt(BOT_A, PLAYER));
    }

    @Test
    void friendlyTeasingAndFriendlyActsAreOpenToEveryone() {
        for (Act open : new Act[]{Act.TEASE, Act.GREET, Act.WHISPER, Act.PARTY_INVITE, Act.BUDDY_REQUEST, Act.GIFT,
                Act.FOLLOW_UP, Act.HELP}) {
            assertTrue(SocialGate.rules(BOT_A, PLAYER, open).ok(), open + " to a fresh player");
            assertTrue(SocialGate.check(bot(BOT_A), player(PLAYER), open).ok(), open + " through the whole gate");
        }
    }

    // ── rule 2: crew side-eye ──

    @Test
    void aWrongedBotsCrewGetsSideEyeAndGossipButNoKsWarsOrCallOuts() {
        SocialGate.addCrewSource((a, b) -> (a == BOT_A && b == BOT_B) || (a == BOT_B && b == BOT_A));
        assertFalse(SocialGate.rules(BOT_B, PLAYER, Act.SIDE_EYE).ok(), "nobody was wronged yet");
        SocialGate.playerStarted(BOT_A, PLAYER, Provocation.KS);
        assertTrue(SocialGate.rules(BOT_B, PLAYER, Act.SIDE_EYE).ok());
        assertTrue(SocialGate.rules(BOT_B, PLAYER, Act.GOSSIP).ok());
        assertTrue(SocialGate.rules(BOT_B, PLAYER, Act.TEASE).ok());
        for (Act notForCrew : new Act[]{Act.KS_WAR, Act.CALL_OUT, Act.KS_SPREE, Act.TAUNT, Act.DEFAME, Act.REVENGE}) {
            assertFalse(SocialGate.rules(BOT_B, PLAYER, notForCrew).ok(), notForCrew + " stays with the wronged bot");
        }
        assertFalse(SocialGate.rules(BOT_C, PLAYER, Act.SIDE_EYE).ok(), "a bot outside the crew gets nothing");
        assertTrue(SocialGate.rules(BOT_A, PLAYER, Act.KS_WAR).ok(), "the wronged bot itself gets full rivalry");
    }

    @Test
    void guildMatesAreCrewByDefault() {
        // The built-in source reads BotGuilds' in-memory roster: two bots in no guild are not crew.
        assertFalse(SocialCrew.GUILD.crew(BOT_A, BOT_B));
    }

    // ── rule 3: romance ──

    @Test
    void aFlirtFirstUnlocksThatOneBotAndRomanceOffShutsItAll() {
        assertFalse(SocialGate.romanceAllowed(BOT_A, PLAYER), "no @romance on: no flirting");
        SocialGate.playerFlirted(BOT_A, PLAYER);
        assertTrue(SocialGate.romanceAllowed(BOT_A, PLAYER), "they flirted with A first");
        assertFalse(SocialGate.romanceAllowed(BOT_B, PLAYER), "only that bot");
        SocialGate.setRomanceOff(PLAYER, true);
        assertFalse(SocialGate.romanceAllowed(BOT_A, PLAYER), "@romance off shuts the flirt-first unlock too");
        SocialGate.playerFlirted(BOT_A, PLAYER);
        assertFalse(SocialGate.romanceAllowed(BOT_A, PLAYER), "and flirting again does not reopen it");
        SocialGate.setRomanceOff(PLAYER, false);
        assertTrue(SocialGate.romanceAllowed(BOT_A, PLAYER), "@romance on again: back");
    }

    @Test
    void romanceOnStillOpensFlirtingButNotAfterAnExplicitOff() {
        SocialGate.useRomanceOnForTest(id -> id == PLAYER);
        assertTrue(SocialGate.romanceAllowed(BOT_B, PLAYER));
        SocialGate.setRomanceOff(PLAYER, true);
        assertFalse(SocialGate.romanceAllowed(BOT_B, PLAYER));
    }

    // ── rule 4: guardrails ──

    @Test
    void nothingUnpromptedWhileThePlayerIsBusy() {
        SocialGate.useBusyForTest(null); // the real PlayerBusy
        SocialGate.playerStarted(BOT_A, PLAYER, Provocation.KS);
        Character trading = player(PLAYER);
        when(trading.getTrade()).thenReturn(mock(Trade.class));
        for (Act a : new Act[]{Act.GREET, Act.WHISPER, Act.PARTY_INVITE, Act.TAUNT, Act.KS_SPREE, Act.GIFT}) {
            assertFalse(SocialGate.check(bot(BOT_A), trading, a).ok(), a + " while busy");
        }
        assertTrue(SocialGate.check(bot(BOT_A), player(PLAYER), Act.TAUNT).ok(), "free again: allowed");
    }

    @Test
    void oneBotNeverRepeatsALineToTheSamePlayer() {
        assertTrue(SocialGate.fresh(BOT_A, PLAYER, "yo whats up"));
        assertFalse(SocialGate.fresh(BOT_A, PLAYER, "yo whats up"));
        assertFalse(SocialGate.fresh(BOT_A, PLAYER, "Yo, whats up!!"), "case and punctuation do not make it new");
        assertTrue(SocialGate.fresh(BOT_B, PLAYER, "yo whats up"), "another bot may say it");
        assertTrue(SocialGate.fresh(BOT_A, OTHER_PLAYER, "yo whats up"), "to another player too");
        clock.addAndGet(8 * DAY);
        assertTrue(SocialGate.fresh(BOT_A, PLAYER, "yo whats up"), "remembered for a week");
    }

    @Test
    void theRepeatGuardIsBoundedPerPair() {
        for (int i = 0; i < SocialGate.LINES_PER_PAIR; i++) {
            assertTrue(SocialGate.fresh(BOT_A, PLAYER, "line " + i));
        }
        assertFalse(SocialGate.fresh(BOT_A, PLAYER, "line 5"));
        assertTrue(SocialGate.fresh(BOT_A, PLAYER, "one more"));
        assertTrue(SocialGate.fresh(BOT_A, PLAYER, "line 0"), "the oldest fell out of the ring");
    }

    @Test
    void anUnsolicitedWhisperIsNeverTheSameLineTwiceModelLinesIncluded() {
        System.setProperty("solomapling.chat.whisperPlayerGapMs", "0");
        Character p = player(PLAYER);
        assertTrue(BotWhispers.send(p, "b1", 0, "u wanna do kpq later?", false));
        assertFalse(BotWhispers.send(p, "b1", 0, "u wanna do kpq later?", false), "the same line from the same bot");
        assertTrue(BotWhispers.send(p, "b1", 0, "u wanna do kpq later?", true), "a reply is never held");
        verify(p, times(2)).sendPacket(any());
    }

    @Test
    void quietBotsBlocksUnsolicitedWhispersInvitesAndBuddyRequests() {
        Character p = player(PLAYER);
        BotNoiseFilter.set(PLAYER, new BotNoiseFilter.Setting(false, true));
        for (Act held : new Act[]{Act.WHISPER, Act.FOLLOW_UP, Act.PARTY_INVITE, Act.GUILD_INVITE, Act.PQ_INVITE,
                Act.BUDDY_REQUEST}) {
            assertFalse(SocialGate.check(bot(BOT_A), p, held).ok(), held + " with @quiet bots");
        }
        assertTrue(SocialGate.check(bot(BOT_A), p, Act.TRADE_OFFER).ok(), "an answer to their own ask still goes");
        // owner (S5 follow-up): everything unasked aimed at them, map chat included, even when they started it
        SocialGate.playerStarted(BOT_A, PLAYER, Provocation.KS);
        for (Act held : new Act[]{Act.GREET, Act.TEASE, Act.TAUNT, Act.ROAST, Act.SIDE_EYE, Act.GIFT, Act.CALL_OUT,
                Act.KS_SPREE, Act.KS_WAR, Act.FLIRT, Act.HELP}) {
            assertFalse(SocialGate.check(bot(BOT_A), p, held).ok(), held + " with @quiet bots");
        }
        assertFalse(BotWhispers.send(p, "b1", 0, "hey", false));
        verify(p, never()).sendPacket(any());
        assertTrue(BotWhispers.send(p, "b1", 0, "hey", true), "a reply to their whisper still goes");

        BotNoiseFilter.set(PLAYER, new BotNoiseFilter.Setting(true, false));
        assertTrue(SocialGate.check(bot(BOT_A), p, Act.WHISPER).ok(), "@quiet megaphones alone does not");
    }

    @Test
    void quietAndRomanceOffAndTheLedgerSurviveARestart() {
        BotNoiseFilter.set(PLAYER, new BotNoiseFilter.Setting(true, true));
        SocialGate.setRomanceOff(OTHER_PLAYER, true);
        SocialGate.playerStarted(BOT_A, PLAYER, Provocation.DEFAME);
        SocialGate.playerFlirted(BOT_B, PLAYER);

        SocialGate.reloadForTest();

        assertTrue(BotNoiseFilter.get(PLAYER).bots());
        assertTrue(BotNoiseFilter.get(PLAYER).megaphones());
        assertFalse(SocialGate.check(bot(BOT_A), player(PLAYER), Act.BUDDY_REQUEST).ok(), "quiet holds after a restart");
        assertTrue(SocialGate.romanceOff(OTHER_PLAYER));
        assertTrue(SocialGate.startedIt(BOT_A, PLAYER));
        assertTrue(SocialGate.flirtedFirst(BOT_B, PLAYER));

        BotNoiseFilter.set(PLAYER, BotNoiseFilter.Setting.NONE);
        SocialGate.reloadForTest();
        assertFalse(BotNoiseFilter.get(PLAYER).bots(), "@quiet off is kept too");
        assertFalse(store.opts.containsKey(PLAYER), "a player back on the defaults has no row");
    }

    @Test
    void aStoreThatCannotBeReadLeavesMemoryAsItIs() {
        BotNoiseFilter.set(PLAYER, new BotNoiseFilter.Setting(false, true));
        store.failing = true;
        SocialGate.reloadForTest();
        assertFalse(BotNoiseFilter.get(PLAYER).bots(), "nothing read back (the gate retries in the background)");
    }

    @Test
    void theBusyServerBudgetIsAboutThreeTimesTheOldCaps() {
        assertEquals(40, SocialGate.pairMinutes(Act.GREET));
        assertEquals(60, SocialGate.pairMinutes(Act.PARTY_INVITE));
        assertEquals(480, SocialGate.pairMinutes(Act.BUDDY_REQUEST));
        assertEquals(7, SocialGate.pairMinutes(Act.TAUNT));
        assertEquals(10, SocialGate.pairMinutes(Act.KS_SPREE), "owner: 10 minutes, not 5");
        assertEquals(1440, SocialGate.pairMinutes(Act.DEFAME), "a defame changes fame: still once a day");
        assertEquals(60, SocialGate.perHour(Act.TAUNT));
        assertEquals(30, SocialGate.perHour(Act.KS_SPREE));
        assertEquals(18, SocialGate.perHour(Act.PARTY_INVITE));
        assertEquals(200_000, SocialGate.whisperGapMs());
        long now = clock.get();
        assertTrue(SocialGate.take(Act.TAUNT, BOT_A, PLAYER, now));
        assertFalse(SocialGate.take(Act.TAUNT, BOT_A, PLAYER, now + TimeUnit.MINUTES.toMillis(6)));
        assertTrue(SocialGate.take(Act.TAUNT, BOT_A, PLAYER, now + TimeUnit.MINUTES.toMillis(7)));
    }

    @Test
    void unpromptedModelLinesHaveTheirOwnHourlyBudget() {
        System.setProperty("solomapling.social.llmPerHour", "2");
        try {
            assertTrue(SocialGate.llmOk());
            assertTrue(SocialGate.llmOk());
            assertFalse(SocialGate.llmOk(), "over budget: the bank or the script says it");
            clock.addAndGet(TimeUnit.HOURS.toMillis(1) + 1);
            assertTrue(SocialGate.llmOk());
        } finally {
            System.clearProperty("solomapling.social.llmPerHour");
        }
    }

    @Test
    void botToBotIsNotGated() {
        Character otherBot = player(BOT_B);
        when(otherBot.getClient()).thenReturn(mock(client.BotClient.class));
        assertTrue(SocialGate.check(bot(BOT_A), otherBot, Act.KS_WAR).ok());
    }

    @Test
    void theGateCanBeTurnedOffForThe15xRules() {
        System.setProperty("solomapling.socialGate", "false");
        try {
            assertTrue(SocialGate.rules(BOT_A, PLAYER, Act.TAUNT).ok());
            assertFalse(SocialGate.rules(BOT_A, PLAYER, Act.FLIRT).ok(), "romance still needs @romance on");
        } finally {
            System.clearProperty("solomapling.socialGate");
        }
    }
}

package soloMapling.ArtificialPlayer;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter;
import soloMapling.ArtificialPlayer.SocialGate.Act;
import soloMapling.ArtificialPlayer.SocialGate.Tier;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.0 S7 "Beef" through the social gate: friendly teasing is open to everyone but held by {@code @quiet bots} and a
 * busy player, and budgeted (30 min per pair, 40 an hour); the tiers toward players are what S5 built (the rename to
 * BEEF changed no rule).
 */
class SocialGateBeefTest {

    static final int PLAYER = 6161;
    static final int BOT = BotStore.BOT_ID_BASE + 611;

    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    boolean busy;

    @BeforeEach
    void setUp() {
        SocialGate.useForTest(new SocialGateStore.Memory(), Runnable::run, clock::get);
        SocialGate.useRomanceOnForTest(id -> false);
        SocialGate.useBusyForTest(p -> busy);
        BotNoiseFilter.forgetAllForTest();
    }

    @AfterEach
    void tearDown() {
        SocialGate.useForTest(null, null, null);
        SocialGate.useRomanceOnForTest(null);
        SocialGate.useBusyForTest(null);
        BotNoiseFilter.forgetAllForTest();
    }

    @Test
    void teasingIsOpenToAFreshPlayerButNotToAQuietOrBusyOne() {
        Character bot = SocialGateTest.bot(BOT), player = SocialGateTest.player(PLAYER);
        assertTrue(SocialGate.check(bot, player, Act.TEASE).ok(), "open to all: no started-it needed");
        assertFalse(SocialGate.check(bot, player, Act.TAUNT).ok(), "but beef is not");
        busy = true;
        assertFalse(SocialGate.check(bot, player, Act.TEASE).ok(), "not while busy");
        busy = false;
        BotNoiseFilter.set(PLAYER, new BotNoiseFilter.Setting(false, true));
        assertFalse(SocialGate.check(bot, player, Act.TEASE).ok(), "@quiet bots holds it");
    }

    @Test
    void oneTeasePerPairPerHalfHourAndFortyAnHourWorldWide() {
        long now = clock.get();
        assertEquals(30, SocialGate.pairMinutes(Act.TEASE));
        assertEquals(40, SocialGate.perHour(Act.TEASE));
        assertTrue(SocialGate.take(Act.TEASE, BOT, PLAYER, now));
        assertFalse(SocialGate.take(Act.TEASE, BOT, PLAYER, now + TimeUnit.MINUTES.toMillis(29)));
        assertTrue(SocialGate.take(Act.TEASE, BOT, PLAYER, now + TimeUnit.MINUTES.toMillis(30)));
        int taken = 2;
        for (int i = 0; i < 60; i++) {
            if (SocialGate.take(Act.TEASE, BOT + 1 + i, PLAYER, now + TimeUnit.MINUTES.toMillis(31))) {
                taken++;
            }
        }
        assertEquals(40, taken, "the world-wide hourly cap");
    }

    @Test
    void theTiersTowardPlayersAreUnchanged() {
        Map<Act, Tier> expected = new EnumMap<>(Act.class);
        for (Act a : new Act[]{Act.GREET, Act.WHISPER, Act.FOLLOW_UP, Act.PARTY_INVITE, Act.GUILD_INVITE, Act.PQ_INVITE,
                Act.BUDDY_REQUEST, Act.GIFT, Act.HELP, Act.TRADE_OFFER}) {
            expected.put(a, Tier.FRIENDLY);
        }
        expected.put(Act.TEASE, Tier.TEASE);
        expected.put(Act.SIDE_EYE, Tier.CREW);
        expected.put(Act.GOSSIP, Tier.CREW);
        for (Act a : new Act[]{Act.TAUNT, Act.ROAST, Act.KS_SPREE, Act.KS_WAR, Act.CALL_OUT, Act.DEFAME, Act.REVENGE}) {
            expected.put(a, Tier.BEEF);
        }
        expected.put(Act.FLIRT, Tier.ROMANCE);
        for (Act a : Act.values()) {
            assertEquals(expected.get(a), a.tier, a + " keeps its tier");
        }
        // beef needs started-it with that very bot; the crew only side-eyes; flirting needs @romance on or a flirt
        assertFalse(SocialGate.rules(BOT, PLAYER, Act.CALL_OUT).ok());
        assertFalse(SocialGate.rules(BOT, PLAYER, Act.SIDE_EYE).ok());
        assertFalse(SocialGate.rules(BOT, PLAYER, Act.FLIRT).ok());
        SocialGate.playerFlirted(BOT, PLAYER);
        assertTrue(SocialGate.rules(BOT, PLAYER, Act.FLIRT).ok(), "flirt-first unlocks that one bot");
        assertFalse(SocialGate.rules(BOT + 1, PLAYER, Act.FLIRT).ok(), "only that one");
        SocialGate.setRomanceOff(PLAYER, true);
        assertFalse(SocialGate.rules(BOT, PLAYER, Act.FLIRT).ok(), "@romance off shuts it");
        SocialGate.playerStarted(BOT, PLAYER, SocialGate.Provocation.KS);
        assertTrue(SocialGate.rules(BOT, PLAYER, Act.KS_WAR).ok());
        assertFalse(SocialGate.rules(BOT + 1, PLAYER, Act.KS_WAR).ok(), "not another bot");
    }
}

package client;

import net.server.channel.handlers.FamilyUseHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ticket 53 (1.6.1): the Family EXP / drop entitlements Cosmic left "not implemented" give what their tooltips say,
 * through FamilyUseHandler.applyBuff, and the multipliers reach the EXP and drop maths.
 */
class FamilyBuffsTest {

    static final long NOW = 1_000_000_000L;
    static final int ME = 101, PAL = 102, FAR = 105, BOT = 1_000_000_007;

    /** Keeps the rows in memory, as family_buffs would (no database in unit tests). */
    static final class MemStore implements FamilyBuffs.Store {
        final java.util.Map<Integer, FamilyBuffs.Saved> rows = new java.util.HashMap<>();

        @Override
        public void save(FamilyBuffs.Saved row) {
            rows.put(row.chrId(), row);
        }

        @Override
        public void delete(int chrId) {
            rows.remove(chrId);
        }

        @Override
        public List<FamilyBuffs.Saved> loadAll(long now) {
            rows.values().removeIf(r -> r.expEnds() <= now && r.dropEnds() <= now);
            return new java.util.ArrayList<>(rows.values());
        }
    }

    @BeforeEach
    void noEvents() {
        FamilyBuffs.useStore(new MemStore());
        FamilyBuffs.useEventRules(c -> false, c -> false);
    }

    @AfterEach
    void tearDown() {
        for (int id : new int[]{ME, PAL, FAR, BOT}) {
            FamilyBuffs.clear(id);
        }
        FamilyBuffs.useStore(null);
        FamilyBuffs.useEventRules(null, null);
        System.clearProperty("slums.family.bondingMin");
    }

    @Test
    void everyBuffEntitlementMatchesItsTooltip() {
        for (FamilyEntitlement e : FamilyEntitlement.values()) {
            FamilyBuffs.Spec s = FamilyBuffs.specOf(e);
            if (e == FamilyEntitlement.FAMILY_REUINION || e == FamilyEntitlement.SUMMON_FAMILY) {
                assertNull(s, e.name());
                continue;
            }
            assertNotNull(s, e.name());
            String tip = e.getDescription();
            assertTrue(tip.contains((s.rate() == 1.5 ? "1.5x" : "2x")), e + " rate " + s.rate());
            assertTrue(e.getName().contains(s.minutes() + " min"), e + " minutes " + s.minutes());
            assertEquals(tip.contains("EXP"), s.exp(), e + " exp");
            assertEquals(tip.toLowerCase().contains("drop rate"), s.drop(), e + " drop");
            assertEquals(tip.contains("My party"), s.target() == FamilyBuffs.Target.PARTY, e + " party");
        }
    }

    @Test
    void aBuffLastsItsMinutesAndTheHigherRateWins() {
        FamilyBuffs.grant(ME, FamilyBuffs.specOf(FamilyEntitlement.SELF_EXP_1_5), NOW);
        assertEquals(1.5, FamilyBuffs.expMultiplier(ME, NOW));
        assertEquals(1.0, FamilyBuffs.dropMultiplier(ME, NOW));
        FamilyBuffs.grant(ME, FamilyBuffs.specOf(FamilyEntitlement.SELF_EXP_2), NOW + 1000);
        assertEquals(2.0, FamilyBuffs.expMultiplier(ME, NOW + 2000), "2x replaces 1.5x, never 3x");
        FamilyBuffs.grant(ME, FamilyBuffs.specOf(FamilyEntitlement.SELF_EXP_1_5), NOW + 2000);
        assertEquals(2.0, FamilyBuffs.expMultiplier(ME, NOW + 3000), "a weaker one does not lower it");
        long end = NOW + 1000 + TimeUnit.MINUTES.toMillis(15);
        assertEquals(2.0, FamilyBuffs.expMultiplier(ME, end - 1));
        assertEquals(1.0, FamilyBuffs.expMultiplier(ME, end), "gone after 15 minutes");
    }

    @Test
    void aFractionalDropRateIsRolledAndNeverBelowTheRate() {
        assertEquals(3, server.maps.MapleMap.familyDropRateForTest(3, 1.0, 0.0));
        assertEquals(2, server.maps.MapleMap.familyDropRateForTest(1, 1.5, 0.4));
        assertEquals(1, server.maps.MapleMap.familyDropRateForTest(1, 1.5, 0.6));
        assertEquals(6, server.maps.MapleMap.familyDropRateForTest(3, 2.0, 0.99));
    }

    @Test
    void selfBuffIsGivenAndPartyBuffReachesEveryOnlinePartyMemberButNotBots() {
        Character me = chr(ME, 1), pal = chr(PAL, 1), bot = chr(BOT, 1);
        when(bot.isBot()).thenReturn(true);
        assertNull(FamilyUseHandler.applyBuff(me, null, FamilyEntitlement.SELF_DROP_2, NOW));
        assertEquals(2.0, FamilyBuffs.dropMultiplier(ME, NOW));
        verify(me).dropMessage(5, "[Family] Drop rate x2 for 15 minutes.");

        net.server.world.Party party = mock(net.server.world.Party.class);
        when(me.getParty()).thenReturn(party);
        Character far = chr(FAR, 3); // 1.6.1 promise audit: a party member on another channel is in "my party" too
        when(me.getPartyMembersOnline()).thenReturn(List.of(me, pal, bot, far));
        assertNull(FamilyUseHandler.applyBuff(me, null, FamilyEntitlement.PARTY_EXP_2_30MIN, NOW));
        assertEquals(2.0, FamilyBuffs.expMultiplier(ME, NOW));
        assertEquals(2.0, FamilyBuffs.expMultiplier(PAL, NOW + TimeUnit.MINUTES.toMillis(29)));
        assertEquals(2.0, FamilyBuffs.expMultiplier(FAR, NOW), "the party member on channel 3 gets it");
        assertEquals(1.0, FamilyBuffs.expMultiplier(BOT, NOW), "bots are never buffed");
        verify(bot, never()).dropMessage(anyInt(), anyString());
    }

    @Test
    void bondingNeedsEnoughOnlineMembersBelow() {
        System.setProperty("slums.family.bondingMin", "2");
        Character me = chr(ME, 1), pal = chr(PAL, 1);
        FamilyEntry mine = mock(FamilyEntry.class), j1 = mock(FamilyEntry.class), j2 = mock(FamilyEntry.class);
        when(mine.getJuniors()).thenReturn(java.util.Arrays.asList(j1, null));
        when(j1.getJuniors()).thenReturn(java.util.Arrays.asList(j2, null));
        when(j2.getJuniors()).thenReturn(java.util.Arrays.asList(null, null));
        when(j1.getChr()).thenReturn(pal);
        String refused = FamilyUseHandler.applyBuff(me, mine, FamilyEntitlement.FAMILY_BONDING, NOW);
        assertNotNull(refused, "one online below is not enough");
        assertEquals(1.0, FamilyBuffs.expMultiplier(ME, NOW));
        Character third = chr(103, 1);
        when(j2.getChr()).thenReturn(third);
        Character botJr = chr(BOT, 2);
        when(botJr.isBot()).thenReturn(true);
        when(j2.getChr()).thenReturn(botJr); // b035: a bot junior counts toward Bonding, so it is buffed too
        assertNull(FamilyUseHandler.applyBuff(me, mine, FamilyEntitlement.FAMILY_BONDING, NOW));
        assertEquals(2.0, FamilyBuffs.expMultiplier(ME, NOW));
        assertEquals(2.0, FamilyBuffs.dropMultiplier(PAL, NOW));
        assertEquals(2.0, FamilyBuffs.expMultiplier(BOT, NOW), "the bot junior it counted gets Bonding");
        assertEquals(2.0, FamilyBuffs.dropMultiplier(BOT, NOW));
        verify(botJr, never()).dropMessage(anyInt(), anyString());
        when(j2.getChr()).thenReturn(third);
        FamilyBuffs.clear(BOT);
        assertNull(FamilyUseHandler.applyBuff(me, mine, FamilyEntitlement.FAMILY_BONDING, NOW + 1));
        assertEquals(2.0, FamilyBuffs.expMultiplier(103, NOW));
        FamilyBuffs.clear(103);
    }

    @Test
    void aBotJuniorGivesItsSeniorAtMostTheDailyCap() {
        Family fam = mock(Family.class);
        FamilyEntry senior = new FamilyEntry(fam, ME, "Senior", 50, null);
        FamilyEntry bot = new FamilyEntry(fam, BOT, "BotJr", 40, null);
        assertTrue(bot.setSenior(senior, false));
        for (int i = 0; i < 200; i++) {
            bot.giveReputationToSenior(4, true);
        }
        assertEquals(FamilyEntry.botJuniorDailyRep(), senior.getReputation());
        FamilyEntry human = new FamilyEntry(fam, PAL, "Human", 40, null);
        FamilyEntry senior2 = new FamilyEntry(fam, 104, "Senior2", 50, null);
        human.setSenior(senior2, false);
        for (int i = 0; i < 200; i++) {
            human.giveReputationToSenior(4, true);
        }
        assertEquals(800, senior2.getReputation(), "a player junior is not capped (stock)");
    }

    @Test
    void aBotJuniorsOffscreenGrindGivesItsSeniorRepUpToTheCap() {
        Family fam = mock(Family.class);
        FamilyEntry senior = new FamilyEntry(fam, ME, "Senior", 50, null);
        FamilyEntry bot = new FamilyEntry(fam, BOT, "BotJr", 40, null);
        assertTrue(bot.setSenior(senior, false));
        var cfg = config.YamlConfig.config.server;
        int killWas = cfg.FAMILY_REP_PER_KILL, levelWas = cfg.FAMILY_REP_PER_LEVELUP;
        cfg.FAMILY_REP_PER_KILL = 4; // live's tuning (the test config has none)
        cfg.FAMILY_REP_PER_LEVELUP = 200;
        try {
        int perKill = 4, perLevel = 200;
        bot.giveOffscreenRep(2.5, 0); // b035: off-screen kills used to give nothing
        assertEquals(2 * perKill, senior.getReputation());
        bot.giveOffscreenRep(0.5, 0); // the half kill carried over
        assertEquals(3 * perKill, senior.getReputation());
        bot.giveOffscreenRep(0, 1);
        assertEquals(Math.min(FamilyEntry.botJuniorDailyRep(), 3 * perKill + perLevel), senior.getReputation());
        for (int i = 0; i < 100; i++) {
            bot.giveOffscreenRep(10, 1);
        }
        assertEquals(FamilyEntry.botJuniorDailyRep(), senior.getReputation(), "the bot junior's daily cap holds");
        } finally {
            cfg.FAMILY_REP_PER_KILL = killWas;
            cfg.FAMILY_REP_PER_LEVELUP = levelWas;
        }
    }

    @Test
    void anEntryGoingOfflineKeepsTheCharactersLatestLevel() {
        Character c = chr(ME, 1);
        when(c.getName()).thenReturn("Grown");
        when(c.getLevel()).thenReturn(57);
        FamilyEntry e = new FamilyEntry(mock(Family.class), ME, "Grown", 30, null);
        e.setCharacter(c);
        e.setCharacter(null);
        assertEquals(57, e.getLevel(), "stock cached nothing (it passed the null)");
    }

    @Test
    void aRunningBuffSurvivesARestartAndAnEndedOneDoesNot() {
        MemStore store = new MemStore();
        FamilyBuffs.useStore(store);
        FamilyBuffs.grant(ME, FamilyBuffs.specOf(FamilyEntitlement.SELF_EXP_2_30MIN), NOW);
        FamilyBuffs.grant(PAL, FamilyBuffs.specOf(FamilyEntitlement.SELF_DROP_1_5), NOW);
        assertEquals(2, store.rows.size(), "each grant is written");
        FamilyBuffs.forgetAllForTest(); // the restart
        assertEquals(1.0, FamilyBuffs.expMultiplier(ME, NOW + 1));
        long later = NOW + TimeUnit.MINUTES.toMillis(20); // the 15-minute drop buff ended while down
        assertEquals(1, FamilyBuffs.load(later));
        assertEquals(2.0, FamilyBuffs.expMultiplier(ME, later), "30-min EXP 2x is back with its old end");
        assertEquals(1.0, FamilyBuffs.expMultiplier(ME, NOW + TimeUnit.MINUTES.toMillis(30)));
        assertEquals(1.0, FamilyBuffs.dropMultiplier(PAL, later));
        FamilyBuffs.clear(ME);
        assertTrue(store.rows.isEmpty(), "cleared buffs are deleted");
    }

    @Test
    void anEventNullifiesTheMatchingBuffAsTheTooltipSays() {
        Character me = chr(ME, 1);
        FamilyBuffs.grant(ME, FamilyBuffs.specOf(FamilyEntitlement.FAMILY_BONDING), System.currentTimeMillis());
        assertEquals(2.0, FamilyBuffs.expMultiplier(me));
        assertEquals(2.0, FamilyBuffs.dropMultiplier(me));
        FamilyBuffs.useEventRules(c -> true, c -> false); // an EXP event (hot spot / GM EXP rate) is on
        assertEquals(1.0, FamilyBuffs.expMultiplier(me), "nullified during the EXP event");
        assertEquals(2.0, FamilyBuffs.dropMultiplier(me), "no drop event: the drop half still works");
        assertNotNull(FamilyBuffs.eventNote(me, FamilyBuffs.specOf(FamilyEntitlement.SELF_EXP_2)));
        assertNull(FamilyBuffs.eventNote(me, FamilyBuffs.specOf(FamilyEntitlement.SELF_DROP_2)));
        FamilyBuffs.useEventRules(c -> false, c -> true);
        assertEquals(2.0, FamilyBuffs.expMultiplier(me));
        assertEquals(1.0, FamilyBuffs.dropMultiplier(me), "nullified during the drop event");
        FamilyBuffs.useEventRules(c -> false, c -> false);
        assertEquals(2.0, FamilyBuffs.expMultiplier(me), "back when the event ends (its time kept running)");
        for (FamilyEntitlement e : FamilyEntitlement.values()) {
            if (FamilyBuffs.specOf(e) != null) {
                assertTrue(e.getDescription().contains("nullified"), e + ": the server-sent tooltip promises it");
            }
        }
    }

    static Character chr(int id, int channel) {
        Character c = mock(Character.class);
        Client client = mock(Client.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("c" + id);
        when(c.getClient()).thenReturn(client);
        when(client.getChannel()).thenReturn(channel);
        return c;
    }
}

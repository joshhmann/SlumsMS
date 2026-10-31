package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.BuffStat;
import client.Character;
import client.Job;
import constants.skills.Cleric;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotPartySupport.Member;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotPartySupport.Pot;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 161 item 2: a partied bot heals the member who needs it, for MP and on a cooldown, and drinks buff pots. */
class BotPartySupportTest {

    private static final int BOT = 1_000_000_000 + 9;
    private static final long T0 = 1_800_000_000_000L;

    private final List<Integer> healed = new ArrayList<>();
    private final List<Integer> paid = new ArrayList<>();
    private final List<Integer> drunk = new ArrayList<>();
    private boolean hasMp = true;
    private boolean realPlayerInParty = true;
    private boolean fighting = true;
    private List<Character> party = new ArrayList<>();
    private List<Pot> pots = new ArrayList<>();
    private Set<BuffStat> active = Set.of();

    @BeforeEach
    void seam() {
        System.setProperty("slums.bots.partysupport", "true");
        BotPartySupport.useForTest(new BotPartySupport.Effects() {
            @Override public List<Character> partyInReach(Character bot) { return party; }
            @Override public boolean partiedWithPlayer(Character bot) { return realPlayerInParty; }
            @Override public boolean fighting(Character bot) { return fighting; }
            @Override public int mpCost(Character bot, int skillId, int level) { return 40; }
            @Override public boolean pay(Character bot, int mp) { if (hasMp) paid.add(mp); return hasMp; }
            @Override public void heal(Character bot, Character target, int skillId, int level) { healed.add(target.getId()); }
            @Override public List<Pot> pots(Character bot) { return pots; }
            @Override public Set<BuffStat> active(Character bot) { return active; }
            @Override public boolean drink(Character bot, Pot pot) { drunk.add(pot.itemId()); return true; }
        });
    }

    @AfterEach
    void clean() {
        System.clearProperty("slums.bots.partysupport");
        BotPartySupport.useForTest(null);
    }

    private static Character member(int id, int hp, int maxHp) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getHp()).thenReturn(hp);
        when(c.getCurrentMaxHp()).thenReturn(maxHp);
        when(c.isAlive()).thenReturn(hp > 0);
        return c;
    }

    private static Character cleric(int healLevel) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(BOT);
        when(bot.getMap()).thenReturn(mock(MapleMap.class));
        when(bot.getSkillLevel(Cleric.HEAL)).thenReturn(healLevel);
        when(bot.getJob()).thenReturn(Job.CLERIC);
        return bot;
    }

    @Test
    void picksTheMemberWithTheLowestHpUnderTheLine() {
        Member low = new Member(1, 200, 1000, true, false);
        Member lower = new Member(2, 100, 1000, true, false);
        assertEquals(2, BotPartySupport.pickHealTarget(List.of(low, lower, new Member(3, 900, 1000, true, false))).id());
        assertNull(BotPartySupport.pickHealTarget(List.of(new Member(4, 600, 1000, true, false))), "60% is not under 60%");
        assertNull(BotPartySupport.pickHealTarget(List.of(new Member(5, 0, 1000, false, false))), "the dead are not healed");
        assertNull(BotPartySupport.pickHealTarget(List.of()));
    }

    @Test
    void aClericHealsTheHurtMemberForMpAndThenWaitsOutTheGap() {
        party = List.of(member(7, 900, 1000), member(8, 250, 1000));
        Character bot = cleric(10);
        assertTrue(BotPartySupport.tick(bot, T0));
        assertEquals(List.of(8), healed);
        assertEquals(List.of(40), paid, "it paid the skill's MP");
        assertFalse(BotPartySupport.tick(bot, T0 + 1_000), "still inside the gap");
        assertTrue(BotPartySupport.tick(bot, T0 + BotPartySupport.HEAL_GAP_MS));
        assertEquals(List.of(8, 8), healed);
    }

    @Test
    void noMpNoHealAndNoRetryUntilTheGapPasses() {
        hasMp = false;
        party = List.of(member(8, 250, 1000));
        Character bot = cleric(10);
        assertFalse(BotPartySupport.tick(bot, T0));
        assertTrue(healed.isEmpty());
        hasMp = true;
        assertFalse(BotPartySupport.tick(bot, T0 + 500), "not every tick");
        assertTrue(BotPartySupport.tick(bot, T0 + BotPartySupport.HEAL_GAP_MS));
    }

    @Test
    void nothingHappensWithoutTheSwitchTheSkillARealPlayerOrAnyoneHurt() {
        party = List.of(member(8, 250, 1000));
        assertFalse(BotPartySupport.tick(cleric(0), T0), "a bot without Heal");
        realPlayerInParty = false;
        assertFalse(BotPartySupport.tick(cleric(10), T0), "a party of bots only");
        realPlayerInParty = true;
        party = List.of(member(8, 990, 1000));
        assertFalse(BotPartySupport.tick(cleric(10), T0), "nobody hurt");
        party = List.of(member(8, 250, 1000));
        System.clearProperty("slums.bots.partysupport");
        assertFalse(BotPartySupport.tick(cleric(10), T0), "switch off");
        assertTrue(healed.isEmpty());
    }

    @Test
    void aMagePicksTheMagicPotAndAWarriorTheWeaponOne() {
        Pot watk = new Pot(2022001, BuffStat.WATK, 10);
        Pot matk = new Pot(2022002, BuffStat.MATK, 12);
        Pot wdef = new Pot(2022003, BuffStat.WDEF, 30);
        assertEquals(matk, BotPartySupport.pickPot(List.of(watk, matk, wdef), Set.of(), true));
        assertEquals(watk, BotPartySupport.pickPot(List.of(watk, matk, wdef), Set.of(), false));
        assertEquals(wdef, BotPartySupport.pickPot(List.of(watk, matk, wdef), Set.of(BuffStat.WATK), false),
                "the attack stat is already up: the defence pot is next");
        assertEquals(new Pot(2022009, BuffStat.WATK, 20), BotPartySupport.pickPot(
                List.of(watk, new Pot(2022009, BuffStat.WATK, 20)), Set.of(), false), "the stronger of two");
        assertNull(BotPartySupport.pickPot(List.of(new Pot(1, BuffStat.SPEED, 20)), Set.of(), false), "speed pots are not drunk");
        assertNull(BotPartySupport.pickPot(List.of(matk), Set.of(), false), "a swordsman does not drink a magic pot");
    }

    @Test
    void aHeldPotIsDrunkInAFightOnceEveryGap() {
        pots = List.of(new Pot(2022001, BuffStat.WATK, 10));
        Character bot = cleric(0);
        when(bot.getJob()).thenReturn(Job.WARRIOR);
        assertTrue(BotPartySupport.tick(bot, T0));
        assertEquals(List.of(2022001), drunk);
        assertFalse(BotPartySupport.tick(bot, T0 + 1_000));
        fighting = false;
        assertFalse(BotPartySupport.tick(bot, T0 + 10_000), "no fight, no pot");
        fighting = true;
        active = Set.of(BuffStat.WATK);
        assertFalse(BotPartySupport.tick(bot, T0 + 20_000), "already up");
    }
}

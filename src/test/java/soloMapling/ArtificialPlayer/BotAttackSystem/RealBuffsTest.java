package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.BotClient;
import client.BuffStat;
import client.Character;
import client.Client;
import client.Job;
import client.Skill;
import client.inventory.WeaponType;
import constants.skills.Aran;
import constants.skills.Bishop;
import constants.skills.BlazeWizard;
import constants.skills.Bowmaster;
import constants.skills.Crusader;
import constants.skills.DarkKnight;
import constants.skills.DawnWarrior;
import constants.skills.Evan;
import constants.skills.FPArchMage;
import constants.skills.Fighter;
import constants.skills.Hermit;
import constants.skills.Hero;
import constants.skills.ILArchMage;
import constants.skills.Magician;
import constants.skills.NightLord;
import constants.skills.NightWalker;
import constants.skills.Paladin;
import constants.skills.ThunderBreaker;
import constants.skills.WhiteKnight;
import net.server.PlayerBuffValueHolder;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.StatEffect;
import server.maps.MapleMap;
import tools.Pair;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 regular-buffs-real: a party buff a bot casts really reaches a companion the policy allows (an owned alt by
 * default), at the caster's learned level, and the caster registers its own party buff on itself - the way a player's
 * cast does. A party regular with the default switches, and every bot with
 * {@code -Dslums.companion.buffs.alts=false}, keeps today's cosmetic aura.
 */
class RealBuffsTest {

    private static final int BOT = 1_000_000_000 + 501;
    private static final int ALT = 1_000_000_000 + 502;
    private static final int REGULAR = 1_000_000_000 + 503;
    private static final int PLAYER = 4242;

    private final List<Integer> paid = new ArrayList<>();
    private boolean hasMp = true;
    private StatEffect atFive;
    private StatEffect atMax;
    private MapleMap map;
    private Party party;

    @BeforeEach
    void seam() {
        atFive = buff(12);
        atMax = buff(20);
        Skill rage = mock(Skill.class);
        when(rage.getMaxLevel()).thenReturn(20);
        when(rage.getEffect(anyInt())).thenAnswer(i -> (int) i.getArgument(0) == 20 ? atMax : atFive);
        BotBuffEffects.useForTest(new BotBuffEffects.Rules() {
            @Override public Skill skill(int skillId) { return skillId == Fighter.RAGE ? rage : null; }
            @Override public boolean pay(Character bot, int skillId, int level) {
                if (hasMp) paid.add(level);
                return hasMp;
            }
        });
        map = mock(MapleMap.class);
        party = mock(Party.class);
        when(party.getId()).thenReturn(7);
    }

    @AfterEach
    void clean() {
        BotBuffEffects.useForTest(null);
        BotDamage.useSkillDataForTest(null);
        for (String p : new String[]{"slums.companion.buffs.alts", "slums.companion.buffs.regulars"}) {
            System.clearProperty(p);
        }
    }

    private static StatEffect buff(int pad) {
        StatEffect e = mock(StatEffect.class);
        when(e.isPartyBuff()).thenReturn(true);
        when(e.getDuration()).thenReturn(3_600_000);
        when(e.getStatups()).thenReturn(List.of(new Pair<>(BuffStat.WATK, pad)));
        when(e.getBuffSourceId()).thenReturn(Fighter.RAGE);
        return e;
    }

    private Character chr(int id, boolean bot, boolean alt, int x) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getMap()).thenReturn(map);
        when(c.getMapId()).thenReturn(100000000);
        when(c.getPosition()).thenReturn(new Point(x, 0));
        when(c.getParty()).thenReturn(party);
        when(c.isAlive()).thenReturn(true);
        when(c.getName()).thenReturn("c" + id);
        when(c.getClient()).thenReturn(bot ? mock(BotClient.class) : mock(Client.class));
        if (bot) {
            when(c.isOwnedAlt()).thenReturn(alt);
        }
        return c;
    }

    /** The caster: an owned alt with Rage level 5 learned. */
    private Character caster() {
        Character bot = chr(BOT, true, true, 0);
        when(bot.getSkillLevel(Fighter.RAGE)).thenReturn(5);
        return bot;
    }

    @Test
    void anAltGetsTheRealBuffAndARegularStaysCosmetic() {
        Character bot = caster();
        Character alt = chr(ALT, true, true, 10);
        Character regular = chr(REGULAR, true, false, 20);
        Character player = chr(PLAYER, false, false, 30);
        when(map.getCharacters()).thenReturn(List.of(bot, alt, regular, player));

        BotBuffEffects.castBuff(bot, Fighter.RAGE);

        verify(atFive).applyToTarget(bot, alt);
        verify(atFive).applyToTarget(bot, player);
        verify(atFive, never()).applyToTarget(bot, regular);
        verify(atMax, never()).applyToTarget(any(), any());
    }

    @Test
    void theAltsSwitchOffGivesTodaysBehaviour() {
        System.setProperty("slums.companion.buffs.alts", "false");
        Character bot = caster();
        Character alt = chr(ALT, true, true, 10);
        when(map.getCharacters()).thenReturn(List.of(bot, alt));

        BotBuffEffects.castBuff(bot, Fighter.RAGE);

        verify(atFive, never()).applyToTarget(any(), any());
    }

    @Test
    void aRegularGetsItWhenTheRegularsSwitchIsOn() {
        System.setProperty("slums.companion.buffs.regulars", "true");
        Character bot = caster();
        Character regular = chr(REGULAR, true, false, 20);
        when(map.getCharacters()).thenReturn(List.of(bot, regular));

        BotBuffEffects.castBuff(bot, Fighter.RAGE);

        verify(atFive).applyToTarget(bot, regular);
    }

    @Test
    void theCasterRegistersItsOwnPartyBuff() {
        Character bot = caster();
        when(map.getCharacters()).thenReturn(List.of(bot));

        BotBuffEffects.castBuff(bot, Fighter.RAGE);

        // the real StatEffect registers itself through applyToTarget (a mocked effect cannot): the call is the proof
        verify(atFive).applyToTarget(bot, bot);
        assertEquals(List.of(5), paid, "the cast costs its level's MP");
    }

    @Test
    void aSelfOnlyBuffIsNeverRegistered() {
        Skill combo = mock(Skill.class);
        StatEffect self = buff(3);
        when(self.isPartyBuff()).thenReturn(false);
        when(combo.getMaxLevel()).thenReturn(20);
        when(combo.getEffect(anyInt())).thenReturn(self);
        BotBuffEffects.useForTest(new BotBuffEffects.Rules() {
            @Override public Skill skill(int skillId) { return skillId == Crusader.COMBO ? combo : null; }
            @Override public boolean pay(Character bot, int skillId, int level) { return true; }
        });
        Character bot = caster();
        when(bot.getSkillLevel(Crusader.COMBO)).thenReturn(10);
        when(map.getCharacters()).thenReturn(List.of(bot));

        BotBuffEffects.castBuff(bot, Crusader.COMBO);

        verify(self, never()).applyToTarget(any(), any());
    }

    /*
     * The ticket's own list of buffs a caster must NEVER register on itself. Not one of them is a buff the caster
     * shares with the party, whatever box its WZ level happens to carry: Combo, Shadow Partner, Infinity, Berserk,
     * Enrage, the six White Knight charges and the Paladin/DawnWarrior/ThunderBreaker ones, Mana Reflection,
     * Concentrate, Shadow Stars, Magic Guard. Each effect here is deliberately given the biggest box WZ could give
     * (isPartyBuff true), so what keeps them off the caster can only be the explicit list, not the WZ bounds.
     */
    @Test
    void aSelfOnlyBuffIsNeverRegisteredOnTheCasterEvenWithAWholeMapBox() {
        List<Integer> selfOnly = List.of(
                Crusader.COMBO, DawnWarrior.COMBO, Hero.ADVANCED_COMBO, DawnWarrior.ADVANCED_COMBO,
                Hermit.SHADOW_PARTNER, NightWalker.SHADOW_PARTNER,
                FPArchMage.INFINITY, ILArchMage.INFINITY, Bishop.INFINITY,
                DarkKnight.BERSERK, Hero.ENRAGE,
                FPArchMage.MANA_REFLECTION, ILArchMage.MANA_REFLECTION, Bishop.MANA_REFLECTION,
                Bowmaster.CONCENTRATE, NightLord.SHADOW_STARS,
                Magician.MAGIC_GUARD, Evan.MAGIC_GUARD, BlazeWizard.MAGIC_GUARD,
                WhiteKnight.SWORD_FIRE_CHARGE, WhiteKnight.BW_FIRE_CHARGE,
                WhiteKnight.SWORD_ICE_CHARGE, WhiteKnight.BW_ICE_CHARGE,
                WhiteKnight.SWORD_LIT_CHARGE, WhiteKnight.BW_LIT_CHARGE,
                Paladin.SWORD_HOLY_CHARGE, Paladin.BW_HOLY_CHARGE,
                DawnWarrior.SOUL_CHARGE, ThunderBreaker.LIGHTNING_CHARGE, Aran.COMBO_ABILITY);
        List<StatEffect> effects = new ArrayList<>();
        Skill skill = mock(Skill.class);
        when(skill.getMaxLevel()).thenReturn(30);
        when(skill.getEffect(anyInt())).thenAnswer(i -> {
            StatEffect e = buff(10);
            when(e.getStatups()).thenReturn(List.of(new Pair<>(BuffStat.WATK, 10)));
            effects.add(e);
            return e;
        });
        BotBuffEffects.useForTest(new BotBuffEffects.Rules() {
            @Override public Skill skill(int skillId) { return selfOnly.contains(skillId) ? skill : null; }
            @Override public boolean pay(Character bot, int skillId, int level) { return true; }
        });
        Character bot = chr(BOT, true, true, 0);
        when(map.getCharacters()).thenReturn(List.of(bot));

        for (int id : selfOnly) {
            when(bot.getSkillLevel(id)).thenReturn(10);
            effects.clear();
            BotBuffEffects.castBuff(bot, id);

            assertTrue(!effects.isEmpty(), "the cast resolved an effect for " + id);
            for (StatEffect effect : effects) {
                verify(effect, never()).applyToTarget(bot, bot);
                verify(effect, never()).applyToTarget(any(), any());
            }
            verify(bot, never()).registerEffect(any(StatEffect.class), anyLong(), anyLong(), anyBoolean());
        }
    }

    @Test
    void noMpRegistersNothingAndLeavesTheAltCosmetic() {
        hasMp = false;
        Character bot = caster();
        Character alt = chr(ALT, true, true, 10);
        when(map.getCharacters()).thenReturn(List.of(bot, alt));

        BotBuffEffects.castBuff(bot, Fighter.RAGE);

        verify(atFive, never()).applyToTarget(any(), any());
    }

    /**
     * Wave 4 audit R1: an unpaid cast hands a real player nothing, and it must not draw a buff on them either - the
     * aura would tell the party (and the player) they are buffed when they are not. Before 1.6.7 they were left alone.
     */
    @Test
    void anUnpaidCastDrawsNoBuffOnARealPlayer() {
        hasMp = false;
        Character bot = caster();
        Character player = chr(PLAYER, false, false, 10);
        when(map.getCharacters()).thenReturn(List.of(bot, player));

        BotBuffEffects.castBuff(bot, Fighter.RAGE);

        verify(atFive, never()).applyToTarget(any(), any());
        verify(map, never()).broadcastMessage(eq(player), any(), anyBoolean());
    }

    @Test
    void anAltOnlyReachMakesTheCasterPay() {
        Character bot = chr(BOT, true, false, 0);   // a party regular: the policy does not let it be buffed itself
        when(bot.getSkillLevel(Fighter.RAGE)).thenReturn(5);
        Character alt = chr(ALT, true, true, 10);
        when(map.getCharacters()).thenReturn(List.of(bot, alt));

        BotBuffEffects.castBuff(bot, Fighter.RAGE);

        verify(atFive).applyToTarget(bot, alt);
        assertEquals(List.of(5), paid, "the alt receives a real buff, so the cast is paid");
    }

    @Test
    void aCompanionThatLostTheBuffAsksForARecastButARegularDoesNot() {
        Character bot = caster();
        Character alt = chr(ALT, true, true, 10);
        Character regular = chr(REGULAR, true, false, 20);
        when(atMax.getStatups()).thenReturn(List.of(new Pair<>(BuffStat.WATK, 20)));
        when(alt.getBuffedValue(BuffStat.WATK)).thenReturn(null);   // a mock answers 0 otherwise

        when(map.getCharacters()).thenReturn(List.of(bot, regular));
        assertTrue(!BotBuffEffects.partyMemberLacks(bot, Fighter.RAGE),
                "a regular's cosmetic aura never asks for a recast");

        when(map.getCharacters()).thenReturn(List.of(bot, alt));
        assertTrue(BotBuffEffects.partyMemberLacks(bot, Fighter.RAGE),
                "a companion that lost its buff (a mob's Dispel) does ask for one");

        when(alt.getBuffedValue(BuffStat.WATK)).thenReturn(12);
        assertTrue(!BotBuffEffects.partyMemberLacks(bot, Fighter.RAGE), "still holding it: nothing to do");
    }

    /*
     * The other half of "self-only": a buff that never lands on a companion cannot be LACKING on one. Judged by the
     * box alone, a Crusader's Combo would read as missing on every companion in reach and be recast on every maintain
     * tick, each cast paid, forever. Nothing is lacking, so nothing is recast.
     */
    @Test
    void aSelfOnlyBuffIsNeverLackingOnACompanion() {
        StatEffect combo = buff(10);
        when(combo.getBuffSourceId()).thenReturn(Crusader.COMBO);
        Skill skill = mock(Skill.class);
        when(skill.getMaxLevel()).thenReturn(20);
        when(skill.getEffect(anyInt())).thenReturn(combo);
        BotBuffEffects.useForTest(new BotBuffEffects.Rules() {
            @Override public Skill skill(int skillId) { return skillId == Crusader.COMBO ? skill : null; }
            @Override public boolean pay(Character bot, int skillId, int level) { return true; }
        });
        Character bot = caster();
        Character alt = chr(ALT, true, true, 10);
        when(map.getCharacters()).thenReturn(List.of(bot, alt));
        when(alt.getBuffedValue(BuffStat.WATK)).thenReturn(null);

        assertTrue(!BotBuffEffects.partyMemberLacks(bot, Crusader.COMBO),
                "a self-only buff is never missing from a companion, so it is never recast for one");
    }

    /*
     * The damage side, in numbers: a caster that really registered its Rage must have it counted exactly once.
     * BotDamage adds a shown buff's WATK itself (the totals do not hold it) and adds a registered buff's damage%
     * only, because Character.recalcLocalStats already put that buff's WATK into getTotalWatk(). So the two routes
     * must land on the same attack: a companion holding a registered Rage (totals 100 = 80 base + 20 Rage, and the
     * registered entry adding no WATK) and a cosmetic bot showing the same Rage (totals 80, shown entry adding 20).
     * 120 would be the double count the ticket rules out.
     */
    @Test
    void aRegisteredRageReachesTheDamageOnceAndOnlyOnce() {
        BotDamage.useSkillDataForTest((id, level) -> id == Fighter.RAGE
                ? new BotDamage.SkillNumbers(100, 20, 0, 0, 0, 0, 0, 0, 0, null)
                : BotDamage.SkillNumbers.NONE);

        StatEffect rage = buff(20);
        when(rage.getDamage()).thenReturn(100);
        Character registered = chr(BOT, true, true, 0);
        when(registered.getJob()).thenReturn(Job.FIGHTER);
        when(registered.getSkillLevel(Fighter.RAGE)).thenReturn(10);
        when(registered.hasBuffFromSourceid(Fighter.RAGE)).thenReturn(true);
        when(registered.getTotalWatk()).thenReturn(100);   // already folded in by recalcLocalStats
        when(registered.getAllBuffs()).thenReturn(List.of(new PlayerBuffValueHolder(1, rage)));

        BotCombatFormula.Attacker registeredHit = BotDamage.attacker(registered, WeaponType.GENERAL1H_SWING);

        assertTrue(BotDamage.shownBuffs(registered).isEmpty(), "a registered Rage is not shown again");
        assertEquals(100, registeredHit.watk(), "Rage's WATK is counted once, not twice (twice would be 120)");
        assertEquals(0, registeredHit.damageBonusPct(), "Rage has no damage%, so the registered entry adds none");

        StatEffect shownRage = buff(20);
        when(shownRage.getDamage()).thenReturn(100);
        Character cosmetic = chr(ALT, true, true, 0);
        when(cosmetic.getJob()).thenReturn(Job.FIGHTER);
        when(cosmetic.getSkillLevel(Fighter.RAGE)).thenReturn(10);
        when(cosmetic.getTotalWatk()).thenReturn(80);
        when(cosmetic.getAllBuffs()).thenReturn(List.of());
        BotDamage.useSkillDataForTest((id, level) -> id == Fighter.RAGE
                ? new BotDamage.SkillNumbers(100, 20, 0, 0, 0, 0, 0, 0, 0, null)
                : BotDamage.SkillNumbers.NONE);

        BotCombatFormula.Attacker shownHit = BotDamage.attacker(cosmetic, WeaponType.GENERAL1H_SWING);

        assertTrue(!BotDamage.shownBuffs(cosmetic).isEmpty(), "the cosmetic bot still shows Rage");
        assertEquals(100, shownHit.watk(), "the same attack as the registered route: Rage once either way");
    }
}

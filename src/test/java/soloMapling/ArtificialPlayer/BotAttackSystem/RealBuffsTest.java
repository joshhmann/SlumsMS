package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.BotClient;
import client.BuffStat;
import client.Character;
import client.Client;
import client.Job;
import client.Skill;
import constants.skills.Crusader;
import constants.skills.Fighter;
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

    @Test
    void noMpRegistersNothingAndLeavesTheAltCosmetic() {
        hasMp = false;
        Character bot = caster();
        Character alt = chr(ALT, true, true, 10);
        when(map.getCharacters()).thenReturn(List.of(bot, alt));

        BotBuffEffects.castBuff(bot, Fighter.RAGE);

        verify(atFive, never()).applyToTarget(any(), any());
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

    @Test
    void aRegisteredRageIsCountedOnceOnTheDamageSide() {
        Character bot = chr(BOT, true, true, 0);
        when(bot.getJob()).thenReturn(Job.FIGHTER);
        when(bot.getSkillLevel(Fighter.RAGE)).thenReturn(10);
        when(bot.hasBuffFromSourceid(Fighter.RAGE)).thenReturn(true);

        assertTrue(BotDamage.shownBuffs(bot).isEmpty(), "a registered Rage is not shown again");
    }
}

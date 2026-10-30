package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import client.Skill;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.StatEffect;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 audit #1 (b110): a population bot handed real players its job's buffs at the skill's MAX level and for 0 MP.
 * Now a bot hands out the level it has learned, pays that level's MP, and hands out nothing it has not learned.
 */
class BotBuffHandOutTest {

    private static final int HASTE = 4101004;
    private static final int BOT = 1_000_000_000 + 77;
    private static final int PLAYER = 4242;

    private final List<Integer> paidAt = new ArrayList<>();
    private boolean hasMp = true;
    private StatEffect atMax;
    private StatEffect atFive;
    private Skill haste;

    @BeforeEach
    void seam() {
        atMax = partyBuff();
        atFive = partyBuff();
        haste = mock(Skill.class);
        when(haste.getMaxLevel()).thenReturn(20);
        when(haste.getEffect(anyInt())).thenAnswer(i -> (int) i.getArgument(0) == 20 ? atMax : atFive);
        BotBuffEffects.useForTest(new BotBuffEffects.Rules() {
            @Override public Skill skill(int skillId) { return skillId == HASTE ? haste : null; }
            @Override public boolean pay(Character bot, int skillId, int level) {
                if (hasMp) paidAt.add(level);
                return hasMp;
            }
        });
    }

    @AfterEach
    void clean() {
        BotBuffEffects.useForTest(null);
    }

    private static StatEffect partyBuff() {
        StatEffect e = mock(StatEffect.class);
        when(e.isPartyBuff()).thenReturn(true);
        when(e.getDuration()).thenReturn(120_000);
        return e;
    }

    private Character[] partyOf(int botHasteLevel) {
        MapleMap map = mock(MapleMap.class);
        Party party = mock(Party.class);
        when(party.getId()).thenReturn(7);
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(BOT);
        when(bot.getMap()).thenReturn(map);
        when(bot.getParty()).thenReturn(party);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        when(bot.getSkillLevel(HASTE)).thenReturn(botHasteLevel);
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(PLAYER);
        when(player.getMap()).thenReturn(map);
        when(player.getParty()).thenReturn(party);
        when(player.getPosition()).thenReturn(new Point(50, 0));
        when(map.getCharacters()).thenReturn(List.of(bot, player));
        return new Character[]{bot, player};
    }

    @Test
    void handsOutTheLearnedLevelAndPaysForIt() {
        Character[] p = partyOf(5);
        BotBuffEffects.castBuff(p[0], HASTE);
        verify(atFive).applyToTarget(p[0], p[1]);
        verify(atMax, never()).applyToTarget(p[0], p[1]);
        assertEquals(List.of(5), paidAt, "the cast costs its level's MP");
    }

    @Test
    void anUnlearnedBuffReachesNoPlayer() {
        Character[] p = partyOf(0);
        BotBuffEffects.castBuff(p[0], HASTE);
        verify(atMax, never()).applyToTarget(p[0], p[1]);
        verify(atFive, never()).applyToTarget(p[0], p[1]);
        assertFalse(BotBuffEffects.giveExtendedBuff(p[0], HASTE, List.of(p[1]), 600_000));
        assertEquals(0, BotBuffEffects.castLevel(p[0], HASTE));
    }

    @Test
    void noMpNoBuffForThePlayer() {
        hasMp = false;
        Character[] p = partyOf(5);
        BotBuffEffects.castBuff(p[0], HASTE);
        verify(atFive, never()).applyToTarget(p[0], p[1]);
        assertFalse(BotBuffEffects.payFor(p[0], HASTE));
    }

    @Test
    void alreadyPaidCastIsNotChargedTwice() {
        Character[] p = partyOf(5);
        BotBuffEffects.castBuff(p[0], HASTE, true);
        verify(atFive).applyToTarget(p[0], p[1]);
        assertTrue(paidAt.isEmpty());
    }

    @Test
    void levelAboveTheSkillMaxIsCapped() {
        Character[] p = partyOf(30);
        assertEquals(20, BotBuffEffects.castLevel(p[0], HASTE));
    }
}

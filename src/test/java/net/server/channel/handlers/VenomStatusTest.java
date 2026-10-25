package net.server.channel.handlers;

import client.Character;
import client.Skill;
import client.status.MonsterStatusEffect;
import org.junit.jupiter.api.Test;
import server.StatEffect;
import server.life.Monster;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.5.1: MapleRoot applied Venomous Star/Stab through the 4-argument applyStatus, which passes venom=false, so
 * Monster.applyStatus never started the venom damage-over-time and venom did no damage. Cosmic passes venom=true.
 */
class VenomStatusTest {

    @Test
    void venomIsAppliedAsVenom() {
        Character thief = mock(Character.class);
        Monster mob = mock(Monster.class);
        Skill venom = mock(Skill.class);
        StatEffect effect = mock(StatEffect.class);
        when(effect.makeChanceResult()).thenReturn(true);
        when(effect.getDuration()).thenReturn(8000);
        when(mob.getVenomMulti()).thenReturn(0);

        AbstractDealDamageHandler.applyVenom(thief, mob, venom, effect, 1);

        verify(mob).setVenomMulti(1);
        verify(mob, times(1)).applyStatus(same(thief), any(MonsterStatusEffect.class), eq(false), eq(8000L), eq(true), eq(false));
    }

    @Test
    void aMobWithThreeStacksGetsNoMore() {
        Character thief = mock(Character.class);
        Monster mob = mock(Monster.class);
        StatEffect effect = mock(StatEffect.class);
        when(effect.makeChanceResult()).thenReturn(true);
        when(mob.getVenomMulti()).thenReturn(3);

        AbstractDealDamageHandler.applyVenom(thief, mob, mock(Skill.class), effect, 4);

        verify(mob, never()).applyStatus(any(), any(), anyBoolean(), anyLong(), anyBoolean(), anyBoolean());
    }
}

package net.server.channel.handlers;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.autoban.AutobanManager;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.StatEffect;
import server.life.Monster;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * b295: an attack packet for a skill whose mpCon the player cannot pay only added an MPCON autoban point; the attack
 * still went on and damaged the mob. It must now stop with enableActions (the autoban point stays).
 */
class AttackWithoutMpTest {

    private static final int MOB_OID = 4242;
    private static final int SKILL_ID = 2001004;   // Energy Bolt

    @Test
    void anAttackTheCasterCannotAffordDealsNoDamage() {
        Character player = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Monster mob = mock(Monster.class);
        AutobanManager autoban = mock(AutobanManager.class);
        Skill skill = mock(Skill.class);
        StatEffect effect = mock(StatEffect.class);
        when(player.getMap()).thenReturn(map);
        when(player.getJob()).thenReturn(Job.MAGICIAN);
        when(player.isAlive()).thenReturn(true);
        when(player.getMp()).thenReturn(0);
        when(player.getBuffedValue(any())).thenReturn(null);
        when(player.getSkillLevel(skill)).thenReturn((byte) 1);
        when(player.getAutobanManager()).thenReturn(autoban);
        when(player.getPosition()).thenReturn(new Point(0, 0));
        when(skill.getEffect(1)).thenReturn(effect);
        when(effect.getMpCon()).thenReturn((short) 10);
        when(effect.getMobCount()).thenReturn(6);
        when(effect.applyTo(player)).thenReturn(false);
        when(map.getMonsterByOid(MOB_OID)).thenReturn(mob);
        when(mob.getPosition()).thenReturn(new Point(0, 0));
        when(mob.getHp()).thenReturn(100_000L);

        AbstractDealDamageHandler.AttackInfo attack = new AbstractDealDamageHandler.AttackInfo();
        attack.skill = SKILL_ID;
        attack.magic = true;
        attack.numAttacked = 1;
        attack.numDamage = 1;
        attack.allDamage = new HashMap<>(Map.of(MOB_OID, new ArrayList<>(List.of(500))));

        try (MockedStatic<SkillFactory> factory = mockStatic(SkillFactory.class)) {
            factory.when(() -> SkillFactory.getSkill(SKILL_ID)).thenReturn(skill);
            new AbstractDealDamageHandler() {
                @Override
                public void handlePacket(net.packet.InPacket p, client.Client c) { }
            }.applyAttack(attack, player, 1);
        }

        verify(map, never()).damageMonster(any(), any(), anyLong());
        verify(autoban).addPoint(any(), any());
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(player).sendPacket(sent.capture());
        assertArrayEquals(PacketCreator.enableActions().getBytes(), sent.getValue().getBytes());
    }
}

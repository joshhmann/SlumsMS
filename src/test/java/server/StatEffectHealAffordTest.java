package server;

import client.Character;
import client.Job;
import constants.skills.Cleric;
import net.server.world.Party;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import provider.Data;
import provider.wz.XMLDomMapleData;
import server.maps.MapleMap;

import java.awt.Point;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * b295: a party Heal the caster could not pay for healed the party first and only then refused the caster's own MP.
 * The caster's affordability is now checked before anyone is healed, and the one MP charge stays applyHpMpChange.
 */
class StatEffectHealAffordTest {

    @TempDir
    Path dir;

    private StatEffect heal30() throws Exception {
        Path f = dir.resolve("30.img.xml");
        Files.writeString(f, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><imgdir name=\"30\">"
                + "<string name=\"hs\" value=\"h30\"/><vector name=\"lt\" x=\"-300\" y=\"-200\"/>"
                + "<int name=\"mobCount\" value=\"6\"/><int name=\"mpCon\" value=\"24\"/>"
                + "<vector name=\"rb\" x=\"300\" y=\"200\"/><int name=\"hp\" value=\"400\"/>"
                + "<string name=\"attackCount\" value=\"2\"/></imgdir>");
        Data level;
        try (FileInputStream in = new FileInputStream(f.toFile())) {
            level = new XMLDomMapleData(in, dir);
        }
        return StatEffect.loadSkillEffectFromData(level, Cleric.HEAL, false);
    }

    private final List<Character> chars = new ArrayList<>();
    private final List<Integer> hpHealed = new ArrayList<>();   // member ids whose HP was raised
    private final List<Integer> mpPaid = new ArrayList<>();     // character ids that paid MP

    private Character add(MapleMap map, Party party, int id, int x, int mp) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("c" + id);
        when(c.getMap()).thenReturn(map);
        when(c.getMapId()).thenReturn(101000000);
        when(c.getPosition()).thenReturn(new Point(x, 100));
        when(c.getParty()).thenReturn(party);
        when(c.isAlive()).thenReturn(true);
        when(c.getHp()).thenReturn(1);
        when(c.getMp()).thenReturn(mp);
        when(c.getBuffedValue(any())).thenReturn(null);
        when(c.getCurrentMaxHp()).thenReturn(11_117);
        when(c.getJob()).thenReturn(Job.PRIEST);
        when(c.applyHpMpChange(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int hp = inv.getArgument(1), mpc = inv.getArgument(2);
            if (mpc != 0 && mp + mpc < 0) {
                return false;
            }
            if (hp > 0) {
                hpHealed.add(id);
            }
            if (mpc < 0) {
                mpPaid.add(id);
            }
            return true;
        });
        chars.add(c);
        return c;
    }

    private Character[] scene(int casterMp) {
        MapleMap map = mock(MapleMap.class);
        when(map.getMapObjectsInRect(any(), anyList())).thenAnswer(inv -> new ArrayList<>(chars));
        Party party = mock(Party.class);
        Character caster = add(map, party, 1, 500, casterMp);
        Character member = add(map, party, 2, 600, 100);
        return new Character[]{caster, member};
    }

    @Test
    void anUnaffordablePartyHealHealsNobodyAndChargesNobody() throws Exception {
        Character[] who = scene(5);   // Heal 30 costs 24 MP
        assertFalse(heal30().applyTo(who[0], (Point) null));
        assertTrue(hpHealed.isEmpty(), "a party member's HP rose although the caster could not pay: " + hpHealed);
        assertTrue(mpPaid.isEmpty(), "nobody pays MP for a refused cast: " + mpPaid);
    }

    @Test
    void anAffordablePartyHealStillHealsTheMemberAndChargesTheCasterOnce() throws Exception {
        Character[] who = scene(100);
        assertTrue(heal30().applyTo(who[0], (Point) null));
        assertTrue(hpHealed.contains(2), "the member is healed");
        assertEquals(List.of(1), mpPaid, "exactly one MP charge, the caster's");
    }
}

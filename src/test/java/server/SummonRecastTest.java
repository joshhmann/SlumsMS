package server;

import client.BotClient;
import client.BuffStat;
import client.Character;
import client.Skill;
import constants.skills.Ranger;
import constants.skills.Sniper;
import net.server.PlayerStorage;
import net.server.channel.Channel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import provider.Data;
import provider.wz.XMLDomMapleData;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.maps.Summon;

import java.awt.Point;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 1.5.1 QA ("I could only summon Silver Hawk once until I relogged"): the server side of a summon's life. A cast
 * spawns the summon, the buff's end (expiry, or a map change first) takes it off the map and out of the owner's
 * summon list, and the next cast spawns a fresh one. What stopped the second cast was MapleRoot's skill data: every
 * level of Silver Hawk and Golden Eagle lasts 20 s with a 60 s cooldown (stock: 103-180 s, no cooldown), so the client
 * refuses the skill for 40 s after the hawk leaves. The level XML below is MapleRoot's 3111005 level 30; since the
 * stock-summon override (stock-summon-skills.yaml) StatEffect loads it with stock level 30 values: 180 s, no cooldown.
 */
class SummonRecastTest {

    private static StatEffect summonEffect(int skillId) throws Exception {
        Path dir = Files.createTempDirectory("summon-skill");
        Path f = dir.resolve("30.img.xml");
        Files.writeString(f, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><imgdir name=\"30\">"
                + "<int name=\"mpCon\" value=\"80\"/><int name=\"x\" value=\"1\"/>"
                + "<int name=\"itemCon\" value=\"4006001\"/><int name=\"itemConNo\" value=\"0\"/>"
                + "<int name=\"pad\" value=\"100\"/><int name=\"prop\" value=\"99\"/>"
                + "<int name=\"cooltime\" value=\"60\"/><int name=\"time\" value=\"20\"/></imgdir>");
        Data level;
        try (FileInputStream in = new FileInputStream(f.toFile())) {
            level = new XMLDomMapleData(in, dir);
        }
        return StatEffect.loadSkillEffectFromData(level, skillId, true);
    }

    private static Character ranger(MapleMap map) {
        BotClient client = spy(new BotClient(0, 0));
        Channel channel = mock(Channel.class);
        PlayerStorage storage = new PlayerStorage();
        when(channel.getPlayerStorage()).thenReturn(storage);
        doReturn(channel).when(client).getChannelServer();

        Character chr = spy(Character.getDefault(client));
        doReturn((byte) 30).when(chr).getSkillLevel(ArgumentMatchers.<Skill>any()); // no Skill.wz here: SkillFactory hands Summon a null skill
        doReturn(true).when(chr).applyHpMpChange(anyInt(), anyInt(), anyInt());
        chr.setID(7);
        chr.setName("HawkTester");
        storage.addPlayer(chr);
        chr.setMap(map);
        return chr;
    }

    private static int summonsOn(MapleMap map) {
        return map.getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, List.of(MapObjectType.SUMMON)).size();
    }

    @ParameterizedTest
    @ValueSource(ints = {Ranger.SILVER_HAWK, Sniper.GOLDEN_EAGLE})
    void aSummonCanBeCastAgainAfterTheFirstOneEnds(int skillId) throws Exception {
        MapleMap map = new MapleMap(910000996, 0, 1, 910000996, 1f);
        Character chr = ranger(map);
        StatEffect effect = summonEffect(skillId);
        assertEquals(180_000, effect.getDuration(), "stock level 30, not MapleRoot's 20 s");
        assertEquals(0, effect.getCooldown(), "stock has no cooldown, MapleRoot's data had 60 s");

        assertTrue(effect.applyTo(chr, new Point(0, 0)));
        Summon first = chr.getSummonByKey(skillId);
        assertNotNull(first);
        assertEquals(1, summonsOn(map));

        chr.cancelEffect(effect, false, -1); // what the buff expiry task does when the summon's time runs out
        assertNull(chr.getSummonByKey(skillId));
        assertNull(chr.getBuffEffect(BuffStat.SUMMON));
        assertEquals(0, summonsOn(map));

        assertTrue(effect.applyTo(chr, new Point(0, 0)));
        Summon second = chr.getSummonByKey(skillId);
        assertNotNull(second, "the second cast spawns a summon");
        assertNotSame(first, second);
        assertEquals(1, summonsOn(map));
    }

    @ParameterizedTest
    @ValueSource(ints = {Ranger.SILVER_HAWK, Sniper.GOLDEN_EAGLE})
    void aMapChangeBeforeTheEndDoesNotLeaveAStaleSummon(int skillId) throws Exception {
        MapleMap map = new MapleMap(910000995, 0, 1, 910000995, 1f);
        Character chr = ranger(map);
        StatEffect effect = summonEffect(skillId);

        assertTrue(effect.applyTo(chr, new Point(0, 0)));
        chr.clearSummons(); // changeMapInternal: the summon leaves the old map and follows its owner
        assertEquals(0, summonsOn(map));
        assertNotNull(chr.getSummonByKey(skillId), "the buff still owns the summon until it ends");

        chr.cancelEffect(effect, false, -1);
        assertNull(chr.getSummonByKey(skillId));

        assertTrue(effect.applyTo(chr, new Point(0, 0)));
        assertNotNull(chr.getSummonByKey(skillId));
        assertEquals(1, summonsOn(map));
    }
}

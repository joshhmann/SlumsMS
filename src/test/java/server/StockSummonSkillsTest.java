package server;

import constants.skills.Bowmaster;
import constants.skills.Outlaw;
import constants.skills.Ranger;
import constants.skills.Sniper;
import constants.skills.WindArcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import provider.Data;
import provider.wz.XMLDomMapleData;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.1: MapleRoot's server Skill.wz gives summons 20 s and a 60 s cooldown at every level. StatEffect now takes the
 * stock v83 time/cooltime from stock-summon-skills.yaml. The skill levels below carry MapleRoot's values, as the TEST
 * and live server wz have them.
 */
class StockSummonSkillsTest {

    @TempDir
    Path tempDir;

    private static String level(int lv, int time, int cooltime) {
        return "<imgdir name=\"" + lv + "\"><int name=\"mpCon\" value=\"80\"/>"
                + (cooltime > 0 ? "<int name=\"cooltime\" value=\"" + cooltime + "\"/>" : "")
                + "<int name=\"time\" value=\"" + time + "\"/></imgdir>";
    }

    /** level/N of a MapleRoot-valued skill, loaded the way SkillFactory does it. */
    private StatEffect load(int skillId, int lv, int time, int cooltime) throws Exception {
        Path file = tempDir.resolve(skillId + "-" + lv + ".img.xml");
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<imgdir name=\"" + skillId + "\"><imgdir name=\"level\">" + level(lv, time, cooltime)
                + "</imgdir></imgdir>");
        Data root = new XMLDomMapleData(new FileInputStream(file.toFile()), tempDir);
        Data node = root.getChildByPath("level/" + lv);
        return StatEffect.loadSkillEffectFromData(node, skillId, false);
    }

    @Test
    void silverHawkLevel30GetsStock180SecondsAndNoCooldown() throws Exception {
        StatEffect e = load(Ranger.SILVER_HAWK, 30, 20, 60);
        assertEquals(180_000, e.getDuration());
        assertEquals(0, e.getCooldown());
    }

    @Test
    void puppetsGetStockDurationPerLevelAndNoCooldown() throws Exception {
        assertEquals(5_000, load(Ranger.PUPPET, 1, 20, 60).getDuration());
        assertEquals(60_000, load(Ranger.PUPPET, 20, 20, 60).getDuration());
        assertEquals(0, load(Ranger.PUPPET, 20, 20, 60).getCooldown());
        assertEquals(60_000, load(Sniper.PUPPET, 20, 20, 60).getDuration());
        assertEquals(0, load(Sniper.PUPPET, 20, 20, 60).getCooldown());
    }

    @Test
    void phoenixGaviotaAndOctopusGetStockValues() throws Exception {
        StatEffect phoenix = load(Bowmaster.PHOENIX, 30, 20, 60);
        assertEquals(200_000, phoenix.getDuration());
        assertEquals(0, phoenix.getCooldown());
        StatEffect gaviota = load(Outlaw.GAVIOTA, 30, 20, 60);
        assertEquals(30_000, gaviota.getDuration());
        assertEquals(5, gaviota.getCooldown()); // stock Gaviota has a 5 s cooldown
        StatEffect octopus = load(Outlaw.OCTOPUS, 30, 3600, 10);
        assertEquals(30_000, octopus.getDuration());
        assertEquals(10, octopus.getCooldown());
    }

    @Test
    void unlistedSkillsKeepTheirWzValues() throws Exception {
        StatEffect windPuppet = load(WindArcher.PUPPET, 20, 60, 0); // already stock on MapleRoot, not listed
        assertEquals(60_000, windPuppet.getDuration());
        assertEquals(0, windPuppet.getCooldown());
        StatEffect arrowRain = load(Bowmaster.HURRICANE, 30, 20, 60); // not a summon
        assertEquals(20_000, arrowRain.getDuration());
        assertEquals(60, arrowRain.getCooldown());
        assertNull(StockSummonSkills.lookup(Ranger.SILVER_HAWK, 31)); // a level stock does not have
    }

    @Test
    void shippedTableListsEverySummonMapleRootChanged() {
        int[] ids = {Ranger.PUPPET, Sniper.PUPPET, Outlaw.OCTOPUS, Ranger.SILVER_HAWK, Sniper.GOLDEN_EAGLE, 2311006,
                3221005, Bowmaster.PHOENIX, Outlaw.GAVIOTA, 1321007, 2121005, 2221005, 2321003, 11001004, 12001004,
                12111004, 13001004, 14001005, 15001004};
        for (int id : ids) {
            int[] first = StockSummonSkills.lookup(id, 1);
            assertNotNull(first, "skill " + id + " level 1");
            assertTrue(first[0] > 0, "skill " + id + " has a stock duration");
        }
        assertEquals(19, ids.length);
        assertArrayEquals(new int[]{1200, 0}, StockSummonSkills.lookup(1321007, 10)); // Beholder: 3600 s -> 1200 s
    }

    @Test
    void badEntriesAreSkipped() {
        StockSummonSkills t = StockSummonSkills.parse(Map.of("skills", Map.of(
                "1", Map.of("time", List.of("5", "6"), "cooltime", List.of("0")),
                "2", Map.of("time", List.of("7"), "cooltime", List.of("x")),
                "3", Map.of("time", List.of("8"), "cooltime", List.of("1")))));
        assertNull(t.get(1, 1));
        assertNull(t.get(2, 1));
        assertArrayEquals(new int[]{8, 1}, t.get(3, 1));
    }
}

package client.command.commands.gm2;

import client.Job;
import constants.inventory.EquipType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClassTestCommandTest {

    @Test
    void parsesAJobAndAnOptionalLevel() {
        assertEquals(new ClassTestCommand.Request(512, 0), ClassTestCommand.parse(new String[]{"512"}));
        assertEquals(new ClassTestCommand.Request(2112, 150), ClassTestCommand.parse(new String[]{"2112", "150"}));
        assertNull(ClassTestCommand.parse(new String[]{}));
        assertNull(ClassTestCommand.parse(null));
        assertNull(ClassTestCommand.parse(new String[]{"hero"}));
        assertNull(ClassTestCommand.parse(new String[]{"112", "-1"}));
        assertNull(ClassTestCommand.parse(new String[]{"112", "200", "x"}));
    }

    @Test
    void aJobIsCheckedAtItsTiersLevel() {
        assertEquals(30, ClassTestCommand.defaultLevel(Job.PIRATE));
        assertEquals(70, ClassTestCommand.defaultLevel(Job.BRAWLER));
        assertEquals(120, ClassTestCommand.defaultLevel(Job.MARAUDER));
        assertEquals(200, ClassTestCommand.defaultLevel(Job.BUCCANEER));
        assertEquals(200, ClassTestCommand.defaultLevel(Job.ARAN4));
        assertEquals(200, ClassTestCommand.defaultLevel(Job.HERALD), "a 5th tier");
    }

    @Test
    void maxesTheSkillsOfTheJobsWholeLine() {
        assertEquals(List.of(500, 510, 511, 512), ClassTestCommand.lineageImages(Job.BUCCANEER));
        assertEquals(List.of(2100, 2110, 2111, 2112), ClassTestCommand.lineageImages(Job.ARAN4));
        assertEquals(List.of(1100, 1110, 1111, 1112), ClassTestCommand.lineageImages(Job.DAWNWARRIOR4));
        assertEquals(List.of(100, 110, 111, 112), ClassTestCommand.lineageImages(Job.HERO));
        assertEquals(List.of(200, 230), ClassTestCommand.lineageImages(Job.CLERIC));
    }

    @Test
    void givesTheAmmoTheWeaponFires() {
        assertEquals(2070006, ClassTestCommand.ammoFor(EquipType.CLAW));
        assertEquals(2060000, ClassTestCommand.ammoFor(EquipType.BOW));
        assertEquals(2061000, ClassTestCommand.ammoFor(EquipType.CROSSBOW));
        assertEquals(2330000, ClassTestCommand.ammoFor(EquipType.PISTOL));
        assertEquals(0, ClassTestCommand.ammoFor(EquipType.KNUCKLER));
    }
}

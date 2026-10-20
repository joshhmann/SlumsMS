package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import client.Job;
import constants.inventory.EquipType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/* Each job fights with its own weapon - the Cygnus Knights, Arans and pirates included. */
class BotWeaponsTest {

    @Test
    void eachJobGetsItsOwnWeapon() {
        assertEquals(List.of(EquipType.SWORD, EquipType.SWORD_2H), BotWeapons.weaponsFor(Job.HERO));
        assertEquals(List.of(EquipType.SWORD, EquipType.SWORD_2H), BotWeapons.weaponsFor(Job.DAWNWARRIOR4));
        assertEquals(List.of(EquipType.SPEAR), BotWeapons.weaponsFor(Job.DARKKNIGHT));
        assertEquals(List.of(EquipType.POLEARM), BotWeapons.weaponsFor(Job.ARAN4));
        assertEquals(List.of(EquipType.WAND, EquipType.STAFF), BotWeapons.weaponsFor(Job.BLAZEWIZARD4));
        assertEquals(List.of(EquipType.BOW), BotWeapons.weaponsFor(Job.WINDARCHER4));
        assertEquals(List.of(EquipType.CROSSBOW), BotWeapons.weaponsFor(Job.MARKSMAN));
        assertEquals(List.of(EquipType.CLAW), BotWeapons.weaponsFor(Job.NIGHTWALKER4));
        assertEquals(List.of(EquipType.DAGGER), BotWeapons.weaponsFor(Job.SHADOWER));
        assertEquals(List.of(EquipType.KNUCKLER), BotWeapons.weaponsFor(Job.THUNDERBREAKER4));
        assertEquals(List.of(EquipType.KNUCKLER), BotWeapons.weaponsFor(Job.BUCCANEER));
        assertEquals(List.of(EquipType.PISTOL), BotWeapons.weaponsFor(Job.CORSAIR));
        assertEquals(List.of(), BotWeapons.weaponsFor(Job.BEGINNER));
    }

    @Test
    void aWeaponFromAnotherJobDoesNotFit() {
        assertFalse(BotWeapons.fits(Job.HERO, 1412009));         // two-handed axe
        assertFalse(BotWeapons.fits(Job.ARAN4, 1372036));        // wand
        assertFalse(BotWeapons.fits(Job.BUCCANEER, 1402048));    // two-handed sword
        assertFalse(BotWeapons.fits(Job.DARKKNIGHT, 1442008));   // pole-arm
        assertFalse(BotWeapons.fits(Job.HERO, 0));               // nothing at all
        assertTrue(BotWeapons.fits(Job.DAWNWARRIOR4, 1302041));  // one-handed sword
        assertTrue(BotWeapons.fits(Job.NIGHTWALKER4, 1472023));  // claw
        assertTrue(BotWeapons.fits(Job.BEGINNER, 1302000));      // a beginner takes anything
    }

    @Test
    void weaponKindsComeFromTheItemId() {
        assertEquals(EquipType.SWORD_2H, BotWeapons.kindOf(1402048));
        assertEquals(EquipType.KNUCKLER, BotWeapons.kindOf(1482000));
        assertEquals(null, BotWeapons.kindOf(1092009)); // a shield
    }
}

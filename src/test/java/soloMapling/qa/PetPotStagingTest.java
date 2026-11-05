package soloMapling.qa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b280 (pp-1): the staging rules for a pet auto-potion test, without a database. What the GM must end up with is a pet
 * out, the Auto HP Potion Pouch on it, 100 red potions in USE, key 91 bound to them, and the HP alert at 50%; and a
 * second {@code !qa do petpot} must not hand out a second pet or another 100 potions.
 */
class PetPotStagingTest {

    private final FakeHost host = new FakeHost();

    @Test
    void anEmptyCharacterGetsPetPouchPotionsKeyAndAlert() {
        List<String> lines = PetPotStaging.run(host);

        assertEquals(5, lines.size(), "one line per step: " + lines);
        assertFalse(PetPotStaging.anyRefused(lines), lines.toString());
        assertTrue(lines.get(0).startsWith("pet summoned from CASH slot"), lines.get(0));
        assertTrue(lines.get(1).startsWith("pouch 1812002 on the pet (slot -24)"), lines.get(1));
        assertTrue(lines.get(2).startsWith("gave 100 red potions 2000000 (100 carried)"), lines.get(2));
        assertTrue(lines.get(3).startsWith("key 91 bound to the red potion (was empty), keymap sent"), lines.get(3));
        assertTrue(lines.get(4).startsWith("HP alert 0.0 -> 0.5"), lines.get(4));

        assertTrue(host.petOut, "the pet is out");
        assertTrue(host.pouchAt(-24), "the pouch is on the pet");
        assertEquals(100, host.potions, "100 red potions in USE");
        assertEquals(2000000, host.key, "key 91 holds the red potion");
        assertEquals(0.5f, host.alert, 0.0001f);
    }

    @Test
    void aSecondRunHandsOutNothingAgain() {
        PetPotStaging.run(host);
        host.resetCounters();

        List<String> lines = PetPotStaging.run(host);

        assertEquals(List.of("pet already out", "pouch 1812002 already on the pet",
                        "100 red potions already carried (100)",
                        "key 91 already bound to the red potion", "HP alert already 0.5"),
                lines);
        assertEquals(0, host.petsGiven + host.pouchesGiven + host.potionsGiven, "no second pet, pouch or potions");
        assertEquals(0, host.keybinds, "the keymap is not rewritten when it is already right");
        assertEquals(100, host.potions);
    }

    @Test
    void aFullEquipInventoryIsReportedAndTheRestStillHappens() {
        host.pouchGiveFails = true;

        List<String> lines = PetPotStaging.run(host);

        assertTrue(lines.get(1).startsWith("REFUSED the pouch 1812002 could not be given"), lines.get(1));
        assertTrue(PetPotStaging.anyRefused(lines));
        assertEquals(100, host.potionsGiven, "the potions are still staged: " + lines);
        assertEquals(2000000, host.key, "the key is still bound: " + lines);
    }

    @Test
    void aPetThatDoesNotComeOutIsReportedAndNothingElseAssumesIt() {
        host.summonFails = true;

        List<String> lines = PetPotStaging.run(host);

        assertTrue(lines.get(0).startsWith("REFUSED the pet in CASH slot 5 did not come out"), lines.get(0));
        assertTrue(PetPotStaging.anyRefused(lines));
        assertEquals(5, lines.size(), "every step still reports: " + lines);
    }

    /** A character that starts with nothing; the flags fake the ways the game can refuse. */
    private static final class FakeHost implements PetPotStaging.Host {

        boolean petOut;
        int cashSlot = -1;
        int equipSlot = -1;
        final List<Integer> equipped = new ArrayList<>();
        int potions;
        int key = -1;
        float alert;

        boolean petGiveFails, summonFails, pouchGiveFails, potionGiveFails, keyBindFails, pouchWearFails;
        int petsGiven, pouchesGiven, potionsGiven, keybinds, keymapSends;

        void resetCounters() {
            petsGiven = pouchesGiven = potionsGiven = keybinds = keymapSends = 0;
        }

        @Override public boolean petOut() {
            return petOut;
        }

        @Override public int petSlot() {
            return cashSlot;
        }

        @Override public boolean givePet() {
            if (petGiveFails) {
                return false;
            }
            petsGiven++;
            cashSlot = 5;
            return true;
        }

        @Override public boolean summonPet(int slot) {
            if (summonFails || slot != cashSlot) {
                return false;
            }
            petOut = true;
            return true;
        }

        @Override public boolean pouchOn() {
            return pouchAt(PetPotStaging.HP_POUCH_SLOT);
        }

        boolean pouchAt(int slot) {
            return equipped.contains(slot);
        }

        @Override public int pouchSlot() {
            return equipSlot;
        }

        @Override public boolean givePouch() {
            if (pouchGiveFails) {
                return false;
            }
            pouchesGiven++;
            equipSlot = 3;
            return true;
        }

        @Override public boolean wearPouch(int equipSlot) {
            if (pouchWearFails || equipSlot != this.equipSlot) {
                return false;
            }
            equipped.add(PetPotStaging.HP_POUCH_SLOT);
            this.equipSlot = -1;
            return true;
        }

        @Override public int redPotions() {
            return potions;
        }

        @Override public int giveRedPotions(int quantity) {
            if (potionGiveFails) {
                return 0;
            }
            potionsGiven += quantity;
            potions += quantity;
            return quantity;
        }

        @Override public int key91() {
            return key;
        }

        @Override public void bindKey91(int itemId) {
            keybinds++;
            if (!keyBindFails) {
                key = itemId;
            }
        }

        @Override public void sendKeymap() {
            keymapSends++;
        }

        @Override public float alert() {
            return alert;
        }

        @Override public void setAlert(float value) {
            alert = value;
        }
    }
}

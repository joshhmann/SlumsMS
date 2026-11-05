package soloMapling.qa;

import java.util.ArrayList;
import java.util.List;

/**
 * b280 / {@code !qa do petpot} (pp-1): puts the GM into the state a pet auto-potion test needs - a pet out, the Auto HP
 * Potion Pouch on it, 100 red potions in USE, key 91 bound to them, and the HP alert at 50% - and says what it did in
 * one line per step.
 *
 * <p>Every step is idempotent: a step that is already true is reported as such and nothing is handed out twice.
 * {@code !qa stop} does not undo this (the pouch, the pet, the potions and the key stay with the GM: they are ordinary
 * player things, and a test that leaves the pet out is what the next test wants anyway).
 *
 * <p>Split from the game so the rules can be tested without a database: {@link GameQaWorld} implements the {@link Host}
 * and the server does the work.
 */
final class PetPotStaging {

    static final int PET_ITEM = 5000000;      // Brown Kitty
    static final int HP_POUCH = 1812002;      // Auto HP Potion Pouch (equipped -24)
    static final int HP_POUCH_SLOT = -24;
    static final int RED_POTION = 2000000;
    static final int POTIONS = 100;
    static final int KEY = 91;                // the client's Auto HP Potion key
    static final int POTION_KEY_TYPE = 7;     // keymap type for a use item (KeymapChangeHandler mode 1)
    static final float ALERT = 0.5f;
    static final int PET_DAYS = 30;

    /** The game's actions, so the rules can be tested without a database. */
    interface Host {
        /** Any pet summoned right now. */
        boolean petOut();

        /** The CASH slot holding a pet item, or -1. */
        int petSlot();

        /** Puts a pet item in CASH (a new Brown Kitty). */
        boolean givePet();

        /** Summons the pet item in that CASH slot. */
        boolean summonPet(int cashSlot);

        /** The HP pouch is on the pet. */
        boolean pouchOn();

        /** The EQUIP slot holding the pouch, or -1. */
        int pouchSlot();

        boolean givePouch();

        /** Equips the pouch from that EQUIP slot into -24. */
        boolean wearPouch(int equipSlot);

        int redPotions();

        /** @return how many were handed out */
        int giveRedPotions(int quantity);

        /** The use item bound to key 91, or -1. */
        int key91();

        void bindKey91(int itemId);

        /** Sends the keymap so the client shows the new binding without a relog. */
        void sendKeymap();

        float alert();

        void setAlert(float value);
    }

    private PetPotStaging() {
    }

    /** Runs the five steps and returns one line per step: what it did, or that it was already so. */
    static List<String> run(Host h) {
        List<String> lines = new ArrayList<>(5);
        lines.add(pet(h));
        lines.add(pouch(h));
        lines.add(potions(h));
        lines.add(key(h));
        lines.add(alert(h));
        return lines;
    }

    private static String pet(Host h) {
        if (h.petOut()) {
            return "pet already out";
        }
        int slot = h.petSlot();
        if (slot < 0) {
            if (!h.givePet()) {
                return "REFUSED no pet could be given (CASH inventory full?)";
            }
            slot = h.petSlot();
            if (slot < 0) {
                return "REFUSED the pet item went in but is not in CASH";
            }
        }
        if (!h.summonPet(slot)) {
            return "REFUSED the pet in CASH slot " + slot + " did not come out";
        }
        return h.petOut() ? "pet summoned from CASH slot " + slot : "REFUSED the pet did not stay summoned";
    }

    private static String pouch(Host h) {
        if (h.pouchOn()) {
            return "pouch " + HP_POUCH + " already on the pet";
        }
        int slot = h.pouchSlot();
        if (slot < 0) {
            if (!h.givePouch()) {
                return "REFUSED the pouch " + HP_POUCH + " could not be given (EQUIP inventory full?)";
            }
            slot = h.pouchSlot();
            if (slot < 0) {
                return "REFUSED the pouch went in but is not in EQUIP";
            }
        }
        if (!h.wearPouch(slot)) {
            return "REFUSED the pouch in EQUIP slot " + slot + " could not be put on the pet";
        }
        return h.pouchOn() ? "pouch " + HP_POUCH + " on the pet (slot " + HP_POUCH_SLOT + ")"
                : "REFUSED the pouch did not stay on the pet";
    }

    private static String potions(Host h) {
        int have = h.redPotions();
        if (have >= POTIONS) {
            return POTIONS + " red potions already carried (" + have + ")";
        }
        int given = h.giveRedPotions(POTIONS - have);
        int now = h.redPotions();
        return given == POTIONS - have && now >= POTIONS
                ? "gave " + given + " red potions " + RED_POTION + " (" + now + " carried)"
                : "REFUSED gave " + given + " of " + (POTIONS - have) + " red potions (" + now + " carried)";
    }

    private static String key(Host h) {
        int bound = h.key91();
        if (bound == RED_POTION) {
            return "key " + KEY + " already bound to the red potion";
        }
        h.bindKey91(RED_POTION);
        if (h.key91() != RED_POTION) {
            return "REFUSED key " + KEY + " still holds " + h.key91();
        }
        h.sendKeymap();
        return "key " + KEY + " bound to the red potion" + (bound < 0 ? " (was empty)" : " (was " + bound + ")")
                + ", keymap sent";
    }

    private static String alert(Host h) {
        float before = h.alert();
        if (Math.abs(before - ALERT) < 0.001f) {
            return "HP alert already " + ALERT;
        }
        h.setAlert(ALERT);
        return Math.abs(h.alert() - ALERT) < 0.001f
                ? "HP alert " + before + " -> " + ALERT
                : "REFUSED the HP alert is " + h.alert() + ", not " + ALERT;
    }

    /** Whether any step refused (its line starts with REFUSED), for the caller's summary line. */
    static boolean anyRefused(List<String> lines) {
        return lines.stream().anyMatch(l -> l.startsWith("REFUSED"));
    }
}

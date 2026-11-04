package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.EquipType;
import constants.inventory.ItemConstants;
import soloMapling.ArtificialPlayer.BotCustomization;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.itemPool.ItemInformationProviderUtilities.getRandomEquipForWearing;

public class BotDecorateEquips {

    private static final short SLOT_TOP = -5;      // a coat or an overall
    private static final short SLOT_PANTS = -6;

    /**
     * Full decoration pass. Equip selection is an in-memory EquipMetadataCache
     * lookup, so this is cheap enough to run inline at spawn time. Everything
     * runs sequentially on the calling thread - do NOT fan out into async tasks,
     * or multiple threads will mutate the same Character's inventory concurrently.
     */
    public static void decorateBotEquips(Character fakechar) {
        // Level 1-9 bots all share the classless BEGINNER job; the class-aware
        // pass below would dress them in random junk, so hand them curated
        // starter gear instead and stop.
        if (BeginnerEquip.isBeginner(fakechar)) {
            BeginnerEquip.apply(fakechar);
            return;
        }
        equipTopBottom(fakechar);
        equipWeapon(fakechar);
        equipCapGloveShoes(fakechar);
        decorateBotAccessories(fakechar);
    }

    public static void decorateBotBaseArmors(Character fakechar) {
        // Define rule-based equipment configuration
        EquipDecoratorRules rules = new EquipDecoratorRules();

        // Add basic equipment types
        rules.addEquipType(EquipType.CAP, 0.25); // 25% chance to skip
        rules.addEquipType(EquipType.GLOVES, 0.25); // 25% chance to skip
        rules.addEquipType(EquipType.SHOES, 0.10); // 10% chance to skip

        // Apply the rules and equip the bot
        rules.applyRules(fakechar);
    }

    /**
     * Fills the empty half of the outfit and leaves a dressed bot alone. prog#P1: decoration used to hand every bot
     * a fresh coat and pants, which replaced (and dropped) whatever it already wore - an overall included.
     */
    static void equipTopBottom(Character fakechar) {
        Inventory equipped = fakechar.getInventory(InventoryType.EQUIPPED);
        Item top = equipped.getItem(SLOT_TOP);        // a coat or an overall: both are body part 5
        boolean pantsWorn = equipped.getItem(SLOT_PANTS) != null;
        if (top != null) {
            if (pantsWorn || ItemConstants.isOverall(top.getItemId())) {
                return;   // dressed: an overall covers the pants, a coat over pants is a full bottom half
            }
            Integer pantsId = getPantsId(fakechar);   // a coat on, only the pants slot is empty
            if (pantsId != null) {
                BotCustomization.EquipBot(fakechar, pantsId);
            }
            return;
        }

        // Nothing on the top: a coat and pants, else an overall, else whatever the empty slots can take.
        Integer coatId = getCoatId(fakechar);
        Integer pantsId = pantsWorn ? null : getPantsId(fakechar);
        if (coatId != null) {
            BotCustomization.EquipBot(fakechar, coatId);
            if (pantsId != null) {
                BotCustomization.EquipBot(fakechar, pantsId);
            }
            return;
        }
        Integer longcoatId = getLongcoatId(fakechar);
        if (longcoatId != null) {
            BotCustomization.EquipBot(fakechar, longcoatId);
        } else if (pantsId != null) {
            BotCustomization.EquipBot(fakechar, pantsId);
        } else {
            System.out.println("[equipTopBottom] No top/bottom/overall found for "
                    + fakechar.getName() + " (job=" + fakechar.getJob().name()
                    + " lv=" + fakechar.getLevel() + ")");
        }
    }

    private static Integer getCoatId(Character c) {
        return getRandomEquipForWearing(EquipType.COAT, c);
    }

    private static Integer getPantsId(Character c) {
        return getRandomEquipForWearing(EquipType.PANTS, c);
    }

    private static Integer getLongcoatId(Character c) {
        return getRandomEquipForWearing(EquipType.LONGCOAT, c);
    }

//    private static void equipCapGloveShoes(Character fakechar) {
//        Integer capId = getRandomEquipForWearing(EquipType.CAP, fakechar);
//        Integer glovesId = getRandomEquipForWearing(EquipType.GLOVES, fakechar);
//        Integer shoesId = getRandomEquipForWearing(EquipType.SHOES, fakechar);
//        Integer capeId = getRandomEquipForWearing(EquipType.CAPE, fakechar);
//        if (capId != null) {
//            BotCustomization.EquipBot(fakechar, capId);
//        }
//        if (glovesId != null) {
//            BotCustomization.EquipBot(fakechar, glovesId);
//        }
//        if (shoesId != null) {
//            BotCustomization.EquipBot(fakechar, shoesId);
//        }
//        if (capeId != null) {
//            BotCustomization.EquipBot(fakechar, capeId);
//        }
//    }

    private static void equipCapGloveShoes(Character fakechar) {
        equipIfPresent(fakechar, getCapId(fakechar));
        equipIfPresent(fakechar, getGlovesId(fakechar));
        equipIfPresent(fakechar, getShoesId(fakechar));
        equipIfPresent(fakechar, getCapeId(fakechar));
    }

    private static Integer getCapId(Character c) {
        return getRandomEquipForWearing(EquipType.CAP, c);
    }

    private static Integer getGlovesId(Character c) {
        return getRandomEquipForWearing(EquipType.GLOVES, c);
    }

    private static Integer getShoesId(Character c) {
        return getRandomEquipForWearing(EquipType.SHOES, c);
    }

    private static Integer getCapeId(Character c) {
        return getRandomEquipForWearing(EquipType.CAPE, c);
    }

    private static void equipIfPresent(Character c, Integer itemId) {
        if (itemId != null) {
            BotCustomization.EquipBot(c, itemId);
        }
    }


    /**
     * // currently not being used due to null pointers in logic
     * Equips the character with either Coat+Pants (preferred) or Longcoat (fallback)
     *
     * @param character The character to equip
     */
    private static void equipOutfit(Character character) {
        // First try to equip coat and pants (80% chance)
        Random random = new Random();
        boolean tryCoatAndPants = random.nextDouble() < 0.8; // 80% chance to try coat+pants first

        if (tryCoatAndPants) {
            // Try to find and equip a coat
            int coatId = getRandomEquipForWearing(EquipType.COAT, character);
            if (coatId > 0) { // Assuming getRandomEquip returns <= 0 if no suitable equip is found
                BotCustomization.EquipBot(character, coatId);

                // Now try to find and equip pants
                int pantsId = getRandomEquipForWearing(EquipType.PANTS, character);
                if (pantsId > 0) {
                    BotCustomization.EquipBot(character, pantsId);
                    return; // Successfully equipped coat and pants, we're done
                }

                // If we couldn't find pants, we should remove the coat
                // (Assuming there's a way to unequip - if not, we can skip this)
//                BotCustomization.UnequipBot(character, coatId);
            }
        }

        // If we're here, either:
        // 1. We decided to try longcoat first (20% chance), or
        // 2. We failed to equip both coat and pants
        // So let's try to equip a longcoat
        int longcoatId = getRandomEquipForWearing(EquipType.LONGCOAT, character);
        if (longcoatId > 0) {
            BotCustomization.EquipBot(character, longcoatId);
        } else {
            // If all else fails, make another attempt at coat and pants if we didn't try already
            if (!tryCoatAndPants) {
                int coatId = getRandomEquipForWearing(EquipType.COAT, character);
                if (coatId > 0) {
                    BotCustomization.EquipBot(character, coatId);

                    int pantsId = getRandomEquipForWearing(EquipType.PANTS, character);
                    if (pantsId > 0) {
                        BotCustomization.EquipBot(character, pantsId);
                    }
                }
            }
        }
    }

    public static void decorateBotAccessories(Character fakechar) {
        EquipDecoratorRules rules = new EquipDecoratorRules();
        rules.addEquipType(EquipType.CAPE, 0.50); // 25% chance to skip
        rules.addEquipType(EquipType.EARRING, 0.50); // 25% chance to skip
        rules.addEquipType(EquipType.ACCESSORY, 0.98); // 25% chance to skip
        rules.applyRules(fakechar);
    }

    /**
     * Equips a level-appropriate weapon of one of the kinds the character's job fights with (BotWeapons), and a
     * shield with a one-handed sword or dagger (and half the time with a wand or staff). A job that takes any weapon
     * (a beginner) gets a sword.
     *
     * @param character The character to equip
     */
    public static void equipWeapon(Character character) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<EquipType> kinds = BotWeapons.weaponsFor(character.getJob());
        EquipType kind = kinds.isEmpty() ? EquipType.SWORD : kinds.get(random.nextInt(kinds.size()));
        equipIfPresent(character, getRandomEquipForWearing(kind, character));

        boolean caster = kind == EquipType.WAND || kind == EquipType.STAFF;
        if (BotWeapons.takesShield(kind) && (!caster || random.nextBoolean())) {
            equipIfPresent(character, getRandomEquipForWearing(EquipType.SHIELD, character));
        }
    }
}

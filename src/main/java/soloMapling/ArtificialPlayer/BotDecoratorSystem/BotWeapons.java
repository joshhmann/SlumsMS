package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.EquipType;
import soloMapling.ArtificialPlayer.BotCustomization;
import soloMapling.ArtificialPlayer.BotLogic;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.itemPool.ItemInformationProviderUtilities.getRandomEquipForWearing;

/*
 * The weapons a bot's job fights with. A viewer's client checks the attacker's weapon against every skill it draws
 * (the skill's Skill.wz "weapon", or the client's own pirate / Dawn Warrior / Blaze Wizard groups) and draws nothing
 * when it does not fit - see BotSkillActions. Bots used to end up holding the wrong one: Cygnus Knights and Arans
 * were dressed by their Explorer look-alike (a Dawn Warrior could get a spear, an Aran a sword, a Night Walker a
 * dagger), pirates got the default sword, the casual QuickEquip look hands out any weapon, and a bot whose job was
 * changed kept the weapon of its old one. MapleRoot's warriors train swords (Fighter, Page) or spears (Spearman) only.
 */
public final class BotWeapons {

    private BotWeapons() {}

    private static final short WEAPON_SLOT = -11;
    private static final short SHIELD_SLOT = -10;

    /* The weapon kinds this job fights with, or an empty list for a job that takes any (beginners, GMs). */
    public static List<EquipType> weaponsFor(Job job) {
        if (job == null) {
            return List.of();
        }
        if (job.isA(Job.SPEARMAN)) {
            return List.of(EquipType.SPEAR);
        }
        if (job.isA(Job.FIGHTER) || job.isA(Job.PAGE) || job.isA(Job.DAWNWARRIOR1)) {
            return List.of(EquipType.SWORD, EquipType.SWORD_2H);
        }
        if (job.isA(Job.WARRIOR)) {
            return List.of(EquipType.SWORD, EquipType.SWORD_2H, EquipType.SPEAR);
        }
        if (job.isA(Job.ARAN1)) {
            return List.of(EquipType.POLEARM);
        }
        if (job.isA(Job.MAGICIAN) || job.isA(Job.BLAZEWIZARD1)) {
            return List.of(EquipType.WAND, EquipType.STAFF);
        }
        if (job.isA(Job.HUNTER) || job.isA(Job.WINDARCHER1)) {
            return List.of(EquipType.BOW);
        }
        if (job.isA(Job.CROSSBOWMAN)) {
            return List.of(EquipType.CROSSBOW);
        }
        if (job.isA(Job.BOWMAN)) {
            return List.of(EquipType.BOW, EquipType.CROSSBOW);
        }
        if (job.isA(Job.ASSASSIN) || job.isA(Job.NIGHTWALKER1)) {
            return List.of(EquipType.CLAW);
        }
        if (job.isA(Job.BANDIT)) {
            return List.of(EquipType.DAGGER);
        }
        if (job.isA(Job.THIEF)) {
            return List.of(EquipType.CLAW, EquipType.DAGGER);
        }
        if (job.isA(Job.BRAWLER) || job.isA(Job.THUNDERBREAKER1)) {
            return List.of(EquipType.KNUCKLER);
        }
        if (job.isA(Job.GUNSLINGER)) {
            return List.of(EquipType.PISTOL);
        }
        if (job.isA(Job.PIRATE)) {
            return List.of(EquipType.KNUCKLER, EquipType.PISTOL);
        }
        return List.of();
    }

    /* The weapon kind of an item id (1302xxx -> SWORD), or null for anything that is not a weapon. */
    public static EquipType kindOf(int itemId) {
        int prefix = itemId / 1000;
        for (EquipType type : EquipType.values()) {
            if (type.getValue() == prefix) {
                return type;
            }
        }
        return null;
    }

    /*
     * Weapons of the bots' kinds a viewer's client draws no attack for, or the wrong one (ticket 32): their
     * Character.wz info has no "afterImage" (every melee hit is dropped) or an "attack" type that is not their class's
     * (0 draws nothing; 1302261's 7 is a claw's poses). Found in the client's Character/Weapon by
     * tools/bot-attack-rules/client_attack_fixture.py; BotAttackClientValidityTest keeps this list complete.
     * 1.6.0: also a weapon whose afterImage the client has no Character/Afterimage image for, and the two pole-arms
     * drawn with the spear's afterimage (1442119, 1442120): Aran's Full Swing / Overswing combo hits have their own
     * afterimage for a pole-arm only, so with these a viewer's client finds no range for them and closes
     * (BotMeleeRange).
     */
    public static final java.util.Set<Integer> UNDRAWABLE = java.util.Set.of(1302083, 1302261, 1302284, 1302335,
            1372086, 1382058, 1382107, 1402250, 1402256, 1442119, 1442120, 1452058, 1492024);

    /* Whether the weapon (item id, 0 = none) is one this job fights with. */
    public static boolean fits(Job job, int weaponItemId) {
        if (UNDRAWABLE.contains(weaponItemId)) {
            return false;
        }
        List<EquipType> kinds = weaponsFor(job);
        return kinds.isEmpty() || (weaponItemId > 0 && kinds.contains(kindOf(weaponItemId)));
    }

    /* A one-handed weapon a bot of this kind may carry a shield with. */
    public static boolean takesShield(EquipType kind) {
        return kind == EquipType.SWORD || kind == EquipType.DAGGER || kind == EquipType.WAND || kind == EquipType.STAFF;
    }

    /*
     * Hands the bot a level-appropriate weapon of its job when the one it holds is not (or it holds none), and drops
     * a shield the new weapon cannot be held with. Returns whether it changed the weapon.
     */
    public static boolean ensureFittingWeapon(Character bot) {
        if (bot == null || bot.isOwnedAlt()) {
            return false; // 1.6.1 ticket 13: an owned alt fights with the weapon its player gave it (no item appears)
        }
        Item held = bot.getInventory(InventoryType.EQUIPPED).getItem(WEAPON_SLOT);
        if (fits(bot.getJob(), held != null ? held.getItemId() : 0)) {
            return false;
        }
        List<EquipType> kinds = weaponsFor(bot.getJob());
        if (kinds.isEmpty()) {
            return false; // a job that takes any weapon holds an undrawable one: nothing of its own to hand it
        }
        EquipType kind = kinds.get(ThreadLocalRandom.current().nextInt(kinds.size()));
        Integer weaponId = null;
        for (int tries = 0; tries < 5 && (weaponId == null || UNDRAWABLE.contains(weaponId)); tries++) {
            weaponId = getRandomEquipForWearing(kind, bot);
        }
        if (weaponId == null || kindOf(weaponId) != kind || UNDRAWABLE.contains(weaponId)) {
            return false;
        }
        Inventory worn = bot.getInventory(InventoryType.EQUIPPED);
        if (!takesShield(kind) && worn.getItem(SHIELD_SLOT) != null) {
            worn.removeSlot(SHIELD_SLOT);
        }
        if (bot.getMap() != null) {
            BotCustomization.EquipBot(bot, weaponId); // shows the new look to the map
        } else {
            // Not on a map yet (a stored bot being adopted): equipChanged would have no map to show it on.
            Equip weapon = (Equip) BotLogic.generateCleanItemEquip(weaponId);
            if (worn.getItem(WEAPON_SLOT) != null) {
                worn.removeSlot(WEAPON_SLOT);
            }
            weapon.setPosition(WEAPON_SLOT);
            worn.addItemFromDB(weapon);
        }
        return true;
    }
}

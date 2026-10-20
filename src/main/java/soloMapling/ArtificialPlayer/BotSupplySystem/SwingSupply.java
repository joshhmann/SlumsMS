package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.Character;
import client.inventory.Item;
import client.inventory.WeaponType;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotHoldings;

/*
 * Real bots 08: what one observed swing takes out of the bot. BotAttackDriver asks before the swing (prepare) and
 * pays after it (commit):
 *  - a skill the bot cannot pay the MP for, even after an MP potion, is swung without the skill - the weapon's
 *    plain attack, a plain shot for a ranged weapon that still has ammo;
 *  - a ranged swing fires from the first stack that fits (RangedAttackHandler's rules) and draws that projectile;
 *    with none left the bot swings its ranged weapon in melee, as a player out of ammo can;
 *  - Soul Arrow needs no arrows and draws none; Shadow Stars needs a stack but throws none of it, and each cast
 *    of it costs its 200 stars (BotAmmo.keepShadowStars).
 */
public final class SwingSupply {

    private final BotAttackProfile profile;
    private final WeaponType weapon;
    private final Item ammo;
    private final int ammoUsed;
    private final int mpCost;
    private final int hpCost;
    private final String fallback;

    private SwingSupply(BotAttackProfile profile, WeaponType weapon, Item ammo, int ammoUsed, int mpCost, int hpCost,
                        String fallback) {
        this.profile = profile;
        this.weapon = weapon;
        this.ammo = ammo;
        this.ammoUsed = ammoUsed;
        this.mpCost = mpCost;
        this.hpCost = hpCost;
        this.fallback = fallback;
    }

    /* The profile to swing: the one asked for, or the plain attack the bot falls back to. */
    public BotAttackProfile profile() {
        return profile;
    }

    /* The projectile the swing is drawn with (the ammo it fires), 0 for none. */
    public int projectile() {
        return ammo != null ? ammo.getItemId() : 0;
    }

    /* Why the bot fell back to a plain attack ("no ammo", "no MP"), or null. */
    public String fallback() {
        return fallback;
    }

    /*
     * Checks the swing against what the bot holds, drinking an MP potion if the cast needs one. {@code skillLevel}
     * is the level the profile's skill is cast at.
     */
    public static SwingSupply prepare(Character bot, BotAttackProfile profile, WeaponType weapon, int skillLevel) {
        BotAmmo.syncStats(bot, weapon);
        BotAmmo.keepShadowStars(bot, weapon, System.currentTimeMillis());
        String fallback = null;
        int skillId = profile.skillId;
        int level = skillLevel;

        int mp = BotVitals.mpCost(bot, skillId, level);
        if (mp > 0 && !BotVitals.affordMp(bot, mp)) {
            boolean shot = profile.route == BotAttackProfile.Route.RANGED
                    && (!BotAmmo.needsAmmo(bot, weapon, 0) || BotAmmo.usable(bot, weapon, 0, 0) != null);
            profile = shot ? BotAttackProfile.ranged(0, 1) : BotAttackProfile.basicSwing();
            skillId = 0;
            level = 0;
            mp = 0;
            fallback = "no MP";
        }

        Item ammo = null;
        int used = 0;
        if (profile.route == BotAttackProfile.Route.RANGED && BotAmmo.needsAmmo(bot, weapon, skillId)) {
            ammo = BotAmmo.usable(bot, weapon, skillId, level);
            if (ammo == null && skillId != 0) {
                ammo = BotAmmo.usable(bot, weapon, 0, 0);   // too few for the skill: a plain shot
                if (ammo != null) {
                    profile = BotAttackProfile.ranged(0, 1);
                    skillId = 0;
                    level = 0;
                    mp = 0;
                    fallback = "too little ammo for the skill";
                }
            }
            if (ammo == null) {
                profile = BotAttackProfile.basicSwing();   // the ranged weapon swung in melee
                skillId = 0;
                level = 0;
                mp = 0;
                fallback = "no ammo";
            } else {
                used = BotAmmo.usedPerSwing(bot, weapon, skillId, level);
            }
        }
        return new SwingSupply(profile, weapon, ammo, used, mp, BotVitals.hpCost(skillId, level), fallback);
    }

    /*
     * Pays for the swing that was made: the ammo when it went out as a ranged attack, the MP and HP when it went out
     * as the skill. {@code route}/{@code skillId} are what the driver finally sent (it drops a skill the weapon
     * cannot draw).
     */
    public void commit(Character bot, BotAttackProfile.Route route, int skillId) {
        if (route == BotAttackProfile.Route.RANGED && ammo != null && ammoUsed > 0) {
            BotHoldings.consumeHeld(bot, ammo, ammoUsed);
        }
        if (skillId != 0 && skillId == profile.skillId) {
            BotVitals.spend(bot, mpCost, hpCost);
        }
    }
}

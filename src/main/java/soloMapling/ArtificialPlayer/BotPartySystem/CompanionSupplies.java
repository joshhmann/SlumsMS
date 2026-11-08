package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotSupplies;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;
import soloMapling.ArtificialPlayer.BotSupplySystem.SupplyNeeds;
import soloMapling.companions.OwnedAltOrders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Ported from nutnnut/Cosmic src/main/java/server/bots/BotManager.java
 * @b684bf78588d50512bcd62db9bb7cb85e4f73829 (AGPL-3.0), changed: the autopot ratios are a boss-run override of
 * BotVitals' lines instead of a per-bot setting, and the potion-choice and keymap code (BotPotionManager, the
 * client autopot key) was not taken - our bots drink through BotVitals.drink.
 *
 * 1.6.7 alt-supplies-in-boss-fights. TEST Horntail, 8 Nov 2026 04:09-04:13 UTC: the alts joined with almost no
 * potions (HP 0-7, MP 0), the two Hero alts cast Brandish until their MP was 13 of 1200 and then swung the plain
 * attack, both died in room 3, and nothing was said to the owner before or during the run.
 *
 * What a companion needs, and what it says about it: the boss-run potion lines BotVitals drinks to, the once-per-run
 * warning to the owner (an owned alt) or the leader (a party regular), and the pre-run "short of supplies" line the
 * recruiter shows. Everything here is off for a party regular unless -Dslums.companion.supplies.regulars=true.
 */
public final class CompanionSupplies {

    // SlumsMS: nutnnut's autopot defaults (BotManager AUTOPOT_HP_THRESH / AUTOPOT_MP_THRESH).
    public static final double BOSS_HP_POT_AT = 0.70;   // in a boss run, drink HP under 70% (open world: 50%)
    public static final double BOSS_MP_POT_AT = 0.50;   // and MP under 50% (open world: 30%)

    public static final int WARN_AT = 10;        // "I'm down to 10 ..." (owner's number)
    public static final int PRE_RUN_MIN = 50;    // the recruiter calls a member short under this

    private static final Map<Integer, Run> runs = new ConcurrentHashMap<>();

    private CompanionSupplies() {
    }

    /*
     * Whether this companion is fighting in a boss run: registered with an expedition and inside its instance. The
     * event-instance read comes first so the hot paths cost one field read when the bot is out in the world.
     */
    public static boolean inBossRun(Character bot) {
        if (bot == null || bot.getEventInstance() == null) {
            return false;
        }
        return PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.SUPPLIES, bot) && ExpeditionBots.inRun(bot);
    }

    /*
     * Tells the owner (an owned alt) or the leader (a party regular) that the potions are running out: once when the
     * count first drops to {@link #WARN_AT}, once when it reaches nothing, per kind and per run. Called from
     * BotVitals.topUp, so it rides both the damage path and the attack path.
     */
    public static void warn(Character bot) {
        if (!inBossRun(bot)) {
            runs.remove(bot.getId());   // out of the run: the next one starts its warnings over
            return;
        }
        Run run = runs.computeIfAbsent(bot.getId(), id -> new Run());
        int hp = BotVitals.potionCount(bot, true);
        if (hp <= 0) {
            if (!run.hpOut) {
                run.hpOut = true;
                tell(bot, "I'm out of HP potions");
            }
        } else if (hp <= WARN_AT && !run.hpTold) {
            run.hpTold = true;
            tell(bot, "I'm down to " + hp + " HP potions");
        }
        if (run.usesMp == null) {
            run.usesMp = BotSupplies.needs(bot).usesMp();
        }
        if (!run.usesMp) {
            return;   // no mana potions to run out of
        }
        int mp = BotVitals.potionCount(bot, false);
        if (mp <= 0) {
            if (!run.mpOut) {
                run.mpOut = true;
                tell(bot, "I'm out of mana potions");
            }
        } else if (mp <= WARN_AT && !run.mpTold) {
            run.mpTold = true;
            tell(bot, "I'm down to " + mp + " mana potions");
        }
    }

    /*
     * What this companion is short of before a boss run, or null when it is ready (or the supplies switch is off for
     * it). The recruiter shows {@link ShortOf#line} for each member that would still join. Joining is not blocked.
     */
    public static ShortOf shortOf(Character c) {
        if (c == null || !PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.SUPPLIES, c)) {
            return null;
        }
        SupplyNeeds needs = BotSupplies.needs(c);
        ShortOf shortOf = new ShortOf(BotVitals.potionCount(c, true), needs.mpPotions(), needs.usesMp(),
                BotSupplies.outOfAmmo(c), noWeapon(c));
        return shortOf.any() ? shortOf : null;
    }

    /*
     * Same rule as OwnedAltCompanion.weaponProblem, for a companion: nothing equipped in the weapon slot, or a weapon
     * this client cannot draw. That helper is package-private in soloMapling.companions, so the two-line rule lives
     * here too.
     */
    private static boolean noWeapon(Character c) {
        Item held = c.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        return held == null || BotWeapons.UNDRAWABLE.contains(held.getItemId());
    }

    private static void tell(Character bot, String line) {
        if (bot.isOwnedAlt()) {
            OwnedAltOrders.say(bot, line);
        } else {
            BotPartyChat.sayInParty(bot, line);   // the party regular's leader reads it in party chat
        }
    }

    /* What a run has already been told, so nothing repeats in it. */
    private static final class Run {
        private boolean hpTold;
        private boolean hpOut;
        private boolean mpTold;
        private boolean mpOut;
        private Boolean usesMp;
    }

    /* What one member is short of before a run: the potions of each kind it uses, its ammo and its weapon. */
    public record ShortOf(int hpPotions, int mpPotions, boolean usesMp, boolean noAmmo, boolean noWeapon) {

        public boolean any() {
            return noWeapon || noAmmo || hpPotions < PRE_RUN_MIN || (usesMp && mpPotions < PRE_RUN_MIN);
        }

        /* One casual line for the recruiter's list, e.g. "Second has no mana potions."; null when nothing is short. */
        public String line(String name) {
            List<String> parts = new ArrayList<>();
            if (noWeapon) {
                parts.add("has no weapon to fight with");
            }
            if (hpPotions < PRE_RUN_MIN) {
                parts.add(hpPotions <= 0 ? "has no HP potions" : "is down to " + hpPotions + " HP potions");
            }
            if (usesMp && mpPotions < PRE_RUN_MIN) {
                parts.add(mpPotions <= 0 ? "has no mana potions" : "is down to " + mpPotions + " mana potions");
            }
            if (noAmmo) {
                parts.add("has no arrows");
            }
            if (parts.isEmpty()) {
                return null;
            }
            String joined = parts.size() == 1 ? parts.get(0)
                    : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
            return name + " " + joined + ".";
        }
    }
}

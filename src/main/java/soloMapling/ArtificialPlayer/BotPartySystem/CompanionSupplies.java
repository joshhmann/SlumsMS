package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import client.inventory.Inventory;
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

    /*
     * Owner, 8 Nov 2026: "25% is a warn from the original stack purchased". The warning comes under a quarter of the
     * stack the companion last got (it replaces the fixed 10 of the first 1.6.7 build). The stack is the count right
     * after it last went up - a purchase, a restock or a hand-over - and a falling count never moves it.
     */
    public static final int WARN_PERCENT = 25;
    public static final int PRE_RUN_MIN = 50;    // under this is always short (owner: 50 confirmed, no upper cap)

    private static final Map<Integer, Run> runs = new ConcurrentHashMap<>();
    /** Per companion: {HP stack, last HP count, MP stack, last MP count}. Process memory only, like the run flags. */
    private static final Map<Integer, int[]> stacks = new ConcurrentHashMap<>();

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
     * count drops under {@link #WARN_PERCENT}% of the stack it last got, once when it reaches nothing, per kind and per
     * stack (a restock arms both again). Called from
     * BotVitals.topUp, so it rides both the damage path and the attack path.
     */
    public static void warn(Character bot) {
        if (!inBossRun(bot)) {
            runs.remove(bot.getId());   // out of the run: the next one starts its warnings over
            if (PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.SUPPLIES, bot)) {
                note(bot, true, BotVitals.potionCount(bot, true));   // the stack it is given in town counts too
                if (BotSupplies.needs(bot).usesMp()) {
                    note(bot, false, BotVitals.potionCount(bot, false));
                }
            }
            return;
        }
        Run run = runs.computeIfAbsent(bot.getId(), id -> new Run());
        int hp = BotVitals.potionCount(bot, true);
        if (note(bot, true, hp)) {
            run.hpTold = false;   // a new stack: its quarter is warned again
            run.hpOut = false;
        }
        if (hp <= 0) {
            if (!run.hpOut) {
                run.hpOut = true;
                tell(bot, "I'm out of HP potions");
            }
        } else if (low(hp, stack(bot, true)) && !run.hpTold) {
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
        if (note(bot, false, mp)) {
            run.mpTold = false;
            run.mpOut = false;
        }
        if (mp <= 0) {
            if (!run.mpOut) {
                run.mpOut = true;
                tell(bot, "I'm out of mana potions");
            }
        } else if (low(mp, stack(bot, false)) && !run.mpTold) {
            run.mpTold = true;
            tell(bot, "I'm down to " + mp + " mana potions");
        }
    }

    /*
     * What this companion is short of before a boss run, or null when it is ready (or the supplies switch is off for
     * it). The recruiter shows {@link ShortOf#line} for each member that would still join. Joining is not blocked.
     */
    public static ShortOf shortOf(Character c) {
        if (c == null || c.getInventory(InventoryType.USE) == null                // not loaded yet: nothing to judge
                || !PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.SUPPLIES, c)) {
            return null;
        }
        SupplyNeeds needs = BotSupplies.needs(c);
        ShortOf shortOf = new ShortOf(BotVitals.potionCount(c, true), needs.mpPotions(), needs.usesMp(),
                BotSupplies.outOfAmmo(c), noWeapon(c), stack(c, true), stack(c, false));
        return shortOf.any() ? shortOf : null;
    }

    /*
     * Same rule as OwnedAltCompanion.weaponProblem, for a companion: nothing equipped in the weapon slot, or a weapon
     * this client cannot draw. That helper is package-private in soloMapling.companions, so the two-line rule lives
     * here too.
     */
    private static boolean noWeapon(Character c) {
        Inventory equipped = c.getInventory(InventoryType.EQUIPPED);
        Item held = equipped == null ? null : equipped.getItem((short) -11);
        return held == null || BotWeapons.UNDRAWABLE.contains(held.getItemId());
    }

    /**
     * Notes a count for one kind and answers whether it went up (a new stack). The stack is the count right after the
     * last rise; while the count falls it stays.
     */
    static boolean note(Character bot, boolean hp, int count) {
        int[] s = stacks.computeIfAbsent(bot.getId(), id -> new int[]{-1, -1, -1, -1});
        int at = hp ? 0 : 2;
        synchronized (s) {
            int before = s[at + 1];
            s[at + 1] = count;
            if (count > before) {
                s[at] = count;
                return before >= 0;   // a rise after a first sighting: a new stack
            }
            return false;
        }
    }

    /** The stack this companion last got of one kind, or 0 when none has been seen yet. */
    static int stack(Character bot, boolean hp) {
        int[] s = stacks.get(bot.getId());
        return s == null ? 0 : Math.max(0, s[hp ? 0 : 2]);
    }

    /** Under a quarter of the stack (no stack known: never "low" here; the 0 line still comes). */
    static boolean low(int count, int stack) {
        return stack > 0 && (long) count * 100 < (long) stack * WARN_PERCENT;
    }

    /** Tests only: forget every noted stack. */
    public static void forgetStacks() {
        stacks.clear();
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
    public record ShortOf(int hpPotions, int mpPotions, boolean usesMp, boolean noAmmo, boolean noWeapon,
                          int hpStack, int mpStack) {

        public ShortOf(int hpPotions, int mpPotions, boolean usesMp, boolean noAmmo, boolean noWeapon) {
            this(hpPotions, mpPotions, usesMp, noAmmo, noWeapon, 0, 0);
        }

        /* Short: under the fixed 50, or under a quarter of the stack it last got (owner, 8 Nov 2026). */
        private static boolean short_(int count, int stack) {
            return count < PRE_RUN_MIN || low(count, stack);
        }

        public boolean any() {
            return noWeapon || noAmmo || short_(hpPotions, hpStack) || (usesMp && short_(mpPotions, mpStack));
        }

        /* One casual line for the recruiter's list, e.g. "Second has no mana potions."; null when nothing is short. */
        public String line(String name) {
            List<String> parts = new ArrayList<>();
            if (noWeapon) {
                parts.add("has no weapon to fight with");
            }
            if (short_(hpPotions, hpStack)) {
                parts.add(hpPotions <= 0 ? "has no HP potions" : "is down to " + hpPotions + " HP potions");
            }
            if (usesMp && short_(mpPotions, mpStack)) {
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

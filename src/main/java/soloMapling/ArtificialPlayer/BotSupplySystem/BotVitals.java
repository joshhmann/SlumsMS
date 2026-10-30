package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.skills.BlazeWizard;
import constants.skills.Evan;
import constants.skills.FPMage;
import constants.skills.Hermit;
import constants.skills.ILMage;
import constants.skills.Magician;
import constants.skills.NightWalker;
import constants.skills.Warrior;
import soloMapling.ArtificialPlayer.BotHoldings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Real bots 08: a bot's HP and MP are real. A mob's touch takes HP off it (never below 1: bots do not die), a skill
 * costs its WZ mpCon (with the server's Element Amplification rule) and hpCon, and the bot drinks real potions out
 * of its USE inventory - the way a player with an auto-pot would - when HP falls under half or MP under a third, or
 * MP is short of the next cast. Potions go through BotHoldings, so they leave the ledger as well as the inventory.
 *
 * Between fights HP and MP come back slowly (a player's 10-second recovery tick) and fully when the bot rests in
 * town. All of this is shared by the observed bot (per hit, per swing) and the offscreen one (OffscreenSupplies,
 * per macro tick), so both drink at the same rate for the same fight.
 */
public final class BotVitals {

    private BotVitals() {}

    static final double HP_POT_AT = 0.50;      // drink an HP potion under half HP
    static final double MP_POT_AT = 0.30;      // and an MP potion under 30% MP
    static final double HP_RETREAT_AT = 0.25;  // out of HP potions and under a quarter: leave and heal up
    private static final int MAX_DRINKS = 6;   // potions one top-up may drink (a player can chain a few)

    // A player's natural recovery tick: every 10 s, 10 HP, and 3 + INT/10 MP (plus the recovery passives).
    static final double REGEN_TICK_MS = 10_000.0;
    static final int BASE_HP_REGEN = 10;
    static final int BASE_MP_REGEN = 3;

    // One bot's HP/MP is touched from its movement thread (a mob's touch), the shared combat ticker (a swing) and its
    // macro tick (offscreen accrual): each change is read-modify-write, so they take this per-bot lock. It is only
    // ever taken outside the Character's own locks and the ledger's.
    private static final Map<Integer, Object> locks = new ConcurrentHashMap<>();
    private static final Map<Integer, double[]> regenCarry = new ConcurrentHashMap<>();

    static Object lock(Character bot) {
        return locks.computeIfAbsent(bot.getId(), id -> new Object());
    }

    // ---------------------------------------------------------------- damage and costs

    /* The bot takes a hit: off its HP (never below 1), then it pots if it is low. */
    public static void hurt(Character bot, int damage) {
        if (bot == null || damage <= 0 || soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths.isDead(bot.getId())) {
            return; // 1.5.0 rc3: a bot lying dead takes no more hits (and is not healed back to 1)
        }
        synchronized (lock(bot)) {
            damage = magicGuardAbsorbs(bot, damage);   // 161: a bot with Magic Guard up pays part of the hit in MP
            if (bot.isOwnedAlt()) {
                // 1.6.1 ticket 13: an owned alt is its player's character - a hit that takes it to 0 kills it (the normal
                // death penalty), whatever the map; otherwise it drinks its own potions like any bot.
                if (bot.getHp() - damage <= 0) {
                    soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths.die(bot);
                    return;
                }
                setHp(bot, bot.getHp() - damage);
                topUp(bot);
                return;
            }
            // 1.6.0 world events: on an invasion map a heavy hit (under half its max HP left) may kill too
            boolean lethal = bot.getHp() - damage <= 0
                    || soloMapling.worldevents.WorldEvents.swarmed(bot, (long) bot.getHp() - damage);
            if (lethal && soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths.rollsDeath(bot)) {
                soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths.die(bot); // 1.5.0 rc3: a rare real death
                return;
            }
            setHp(bot, Math.max(1, bot.getHp() - damage));
            topUp(bot);
        }
    }

    // ---------------------------------------------------------------- Magic Guard (ticket 161 item 4)

    private static final int[] MAGIC_GUARDS = {Magician.MAGIC_GUARD, BlazeWizard.MAGIC_GUARD, Evan.MAGIC_GUARD};

    /*
     * Magic Guard's split of one hit, as the server does it for a player and as nutnnut/Cosmic 930ca5a01 does for its
     * bots: {@code percent} of the damage comes off MP, the rest off HP, and whatever MP cannot pay falls back on HP.
     * Returns {hpLoss, mpLoss}.
     */
    public static int[] magicGuardSplit(int damage, int percent, int currentMp) {
        int mpLoss = (int) (damage * (percent / 100.0));
        int hpLoss = damage - mpLoss;
        int mp = Math.max(0, currentMp);
        if (mpLoss > mp) {
            hpLoss += mpLoss - mp;
            mpLoss = mp;
        }
        return new int[]{hpLoss, mpLoss};
    }

    /*
     * The share of a hit this bot's Magic Guard turns into MP loss, or 0. An owned alt (a real character with
     * registered buffs) is read off its buff; a population bot's buffs are only shown, never registered, so it counts
     * as guarded while it holds the skill and BotBuffDriver says it cast it and the cast has not run out.
     */
    static int magicGuardPercent(Character bot, Map<Integer, Long> castUntil, long now) {
        Integer registered = bot.getBuffedValue(client.BuffStat.MAGIC_GUARD);
        if (registered != null) {
            return Math.max(0, Math.min(100, registered));
        }
        for (int skillId : MAGIC_GUARDS) {
            int level = bot.getSkillLevel(skillId);
            Long until = castUntil.get(skillId);
            if (level > 0 && until != null && until > now) {
                return Math.max(0, Math.min(100, SupplyData.get().x(skillId, level)));
            }
        }
        return 0;
    }

    /* The HP part of a hit after Magic Guard took its MP share off the bot (the whole hit with the switch off). */
    static int magicGuardAbsorbs(Character bot, int damage) {
        if (!soloMapling.ArtificialPlayer.BotSupportSwitches.magicGuard() || damage <= 0) {
            return damage;
        }
        int percent = magicGuardPercent(bot,
                soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver.timersOf(bot.getId()), System.currentTimeMillis());
        if (percent <= 0) {
            return damage;
        }
        int[] split = magicGuardSplit(damage, percent, bot.getMp());
        if (split[1] > 0) {
            setMp(bot, bot.getMp() - split[1]);
        }
        return split[0];
    }

    /*
     * 1.5.0 rc3 (owner decision 5): the HP share under which this bot drinks. On a map a player can see, each bot
     * drinks at its own habit (40-60%), and now and then notices late (15 points lower that time). Offscreen it keeps
     * the shared 50% (the simple path).
     */
    static double hpPotAt(Character bot) {
        if (!soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning.human()
                || !soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.isMapObserved(bot.getMapId())) {
            return HP_POT_AT;
        }
        soloMapling.ArtificialPlayer.BotCombatSystem.BotHabits h =
                soloMapling.ArtificialPlayer.BotCombatSystem.BotHabits.of(bot.getId());
        boolean late = java.util.concurrent.ThreadLocalRandom.current().nextDouble() < h.latePotChance();
        return late ? h.hpPotAt() - 0.15 : h.hpPotAt();
    }

    /* Whether the item is a recovery potion (not a buff pot). */
    public static boolean isRecoveryItem(int itemId) {
        return SupplyData.get().recovery(itemId) != null;
    }

    /* Gives HP back (a party Heal), up to the bot's max. */
    public static void heal(Character bot, int amount) {
        if (bot == null || amount <= 0) {
            return;
        }
        synchronized (lock(bot)) {
            setHp(bot, Math.min(bot.getCurrentMaxHp(), bot.getHp() + amount));
        }
    }

    /* What one cast of this skill costs in MP, by the server's rule (StatEffect.calcMPChange). */
    public static int mpCost(Character bot, int skillId, int skillLevel) {
        if (skillId == 0 || skillLevel <= 0) {
            return 0;
        }
        int con = SupplyData.get().mpCon(skillId, skillLevel);
        if (con <= 0) {
            return 0;
        }
        double mod = 1.0;
        int amp = amplificationSkill(bot.getJob());
        if (amp != 0) {
            int ampLevel = bot.getSkillLevel(amp);
            if (ampLevel > 0) {
                int x = SupplyData.get().x(amp, ampLevel);
                if (x > 0) {
                    mod = x / 100.0;
                }
            }
        }
        return (int) (con * mod);
    }

    /* What one cast of this skill costs in HP (its hpCon). */
    public static int hpCost(int skillId, int skillLevel) {
        return skillId == 0 || skillLevel <= 0 ? 0 : SupplyData.get().hpCon(skillId, skillLevel);
    }

    // The jobs whose Element Amplification raises their spells' MP cost (the server's own list).
    private static int amplificationSkill(Job job) {
        if (job == null) {
            return 0;
        }
        if (job.isA(Job.FP_MAGE)) {
            return FPMage.ELEMENT_AMPLIFICATION;
        }
        if (job.isA(Job.IL_MAGE)) {
            return ILMage.ELEMENT_AMPLIFICATION;
        }
        if (job.isA(Job.BLAZEWIZARD2)) {
            return BlazeWizard.ELEMENT_AMPLIFICATION;
        }
        if (job.isA(Job.EVAN7)) {
            return Evan.MAGIC_AMPLIFICATION;
        }
        return 0;
    }

    /*
     * Makes sure the bot has the MP for a cast, drinking MP potions if it has to. False when it cannot: the
     * caller swings without the skill instead.
     */
    public static boolean affordMp(Character bot, int cost) {
        if (cost <= 0) {
            return true;
        }
        synchronized (lock(bot)) {
            for (int i = 0; i < MAX_DRINKS && bot.getMp() < cost; i++) {
                if (!drink(bot, false)) {
                    break;
                }
            }
            return bot.getMp() >= cost;
        }
    }

    /* Pays a cast: the MP and HP it costs (HP never below 1), then tops up. */
    public static void spend(Character bot, int mp, int hp) {
        if (mp <= 0 && hp <= 0) {
            return;
        }
        synchronized (lock(bot)) {
            if (mp > 0) {
                setMp(bot, Math.max(0, bot.getMp() - mp));
            }
            if (hp > 0) {
                setHp(bot, Math.max(1, bot.getHp() - hp));
            }
            topUp(bot);
        }
    }

    // ---------------------------------------------------------------- potions

    /* Drinks what the bot needs: HP potions under half HP, MP potions under 30% MP, a few at most. */
    public static void topUp(Character bot) {
        synchronized (lock(bot)) {
            double hpAt = bot.getHp() < bot.getCurrentMaxHp() * 0.75 ? hpPotAt(bot) : HP_POT_AT; // roll only when low
            int before = bot.getHp();
            int drank = 0;
            for (int i = 0; i < MAX_DRINKS && bot.getHp() < bot.getCurrentMaxHp() * hpAt; i++) {
                if (!drink(bot, true)) {
                    break;
                }
                drank++;
            }
            if (drank > 0 && soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.HABITS)) {
                soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.HABITS, bot.getMapId(), "POT " + bot.getName()
                        + " drinks " + drank + " at " + Math.round(100.0 * before / Math.max(1, bot.getCurrentMaxHp()))
                        + "% HP (its line " + Math.round(hpAt * 100) + "%), " + potionCount(bot, true) + " HP pots left");
            }
            for (int i = 0; i < MAX_DRINKS && bot.getMp() < bot.getCurrentMaxMp() * MP_POT_AT; i++) {
                if (!drink(bot, false)) {
                    break;
                }
            }
        }
    }

    /*
     * Drinks one potion for HP (or MP): the one that covers what is missing with the least waste, else the
     * strongest, preferring one that restores only what is needed (an Elixir is not wasted on MP alone).
     */
    static boolean drink(Character bot, boolean forHp) {
        int missing = forHp ? bot.getCurrentMaxHp() - bot.getHp() : bot.getCurrentMaxMp() - bot.getMp();
        if (missing <= 0) {
            return false;
        }
        Item best = null;
        boolean bestPure = false;
        int bestHeal = 0;
        for (Item item : potions(bot, forHp)) {
            SupplyData.Recovery r = SupplyData.get().recovery(item.getItemId());
            boolean pure = forHp ? !r.restoresMp() : !r.restoresHp();
            int heal = forHp ? hpHeal(bot, r) : mpHeal(bot, r);
            if (best == null || better(pure, heal, bestPure, bestHeal, missing)) {
                best = item;
                bestPure = pure;
                bestHeal = heal;
            }
        }
        if (best == null || BotHoldings.consumeHeld(bot, best, 1) != 1) {
            return false;
        }
        SupplyData.Recovery r = SupplyData.get().recovery(best.getItemId());
        int hp = Math.min(bot.getCurrentMaxHp(), bot.getHp() + hpHeal(bot, r));
        int mp = Math.min(bot.getCurrentMaxMp(), bot.getMp() + mpHeal(bot, r));
        setHpMp(bot, hp, mp);
        return true;
    }

    private static boolean better(boolean pure, int heal, boolean bestPure, int bestHeal, int missing) {
        if (pure != bestPure) {
            return pure;
        }
        boolean covers = heal >= missing;
        boolean bestCovers = bestHeal >= missing;
        if (covers != bestCovers) {
            return covers;
        }
        return covers ? heal < bestHeal : heal > bestHeal;
    }

    /* The bot's potions (recovery items) for HP or MP, one entry a stack. */
    static List<Item> potions(Character bot, boolean forHp) {
        List<Item> out = new ArrayList<>();
        Inventory use = bot.getInventory(InventoryType.USE);
        use.lockInventory();
        try {
            for (Item item : use.list()) {
                if (item.getQuantity() <= 0) {
                    continue;
                }
                SupplyData.Recovery r = SupplyData.get().recovery(item.getItemId());
                if (r != null && (forHp ? r.restoresHp() : r.restoresMp())) {
                    out.add(item);
                }
            }
        } finally {
            use.unlockInventory();
        }
        return out;
    }

    /* 1.6.2 ticket 99 (SupplyShare): the potion stacks of any character, HP or MP, as the bots drink them. */
    public static List<Item> potionStacks(Character c, boolean forHp) {
        return potions(c, forHp);
    }

    /* How many HP (or MP) potions the bot holds. */
    public static int potionCount(Character bot, boolean forHp) {
        int n = 0;
        for (Item item : potions(bot, forHp)) {
            n += item.getQuantity();
        }
        return n;
    }

    /* QA cb-3: uses up HP potions until the bot holds at most {@code keep}; how many went. */
    public static int drainHpPotionsTo(Character bot, int keep) {
        int over = potionCount(bot, true) - Math.max(0, keep);
        int gone = 0;
        for (Item item : potions(bot, true)) {
            if (over - gone <= 0) {
                break;
            }
            gone += BotHoldings.consumeHeld(bot, item, Math.min(item.getQuantity(), over - gone));
        }
        return gone;
    }

    /* What all the bot's HP (or MP) potions would restore, at its current max. */
    static long potionTotal(Character bot, boolean forHp) {
        long total = 0;
        for (Item item : potions(bot, forHp)) {
            SupplyData.Recovery r = SupplyData.get().recovery(item.getItemId());
            int heal = forHp ? hpHeal(bot, r) : mpHeal(bot, r);
            total += (long) Math.min(heal, forHp ? bot.getCurrentMaxHp() : bot.getCurrentMaxMp()) * item.getQuantity();
        }
        return total;
    }

    static int hpHeal(Character bot, SupplyData.Recovery r) {
        return (int) (alchemist(bot, r.hp()) + bot.getCurrentMaxHp() * r.hpRate());
    }

    static int mpHeal(Character bot, SupplyData.Recovery r) {
        return (int) (alchemist(bot, r.mp()) + bot.getCurrentMaxMp() * r.mpRate());
    }

    // Alchemist (Hermit, Night Walker 3rd job) makes a potion's flat amount go further (StatEffect.alchemistModifyVal).
    private static int alchemist(Character bot, int value) {
        if (value <= 0) {
            return 0;
        }
        Job job = bot.getJob();
        if (job == null || !(job.isA(Job.HERMIT) || job.isA(Job.NIGHTWALKER3))) {
            return value;
        }
        int id = job.isA(Job.NIGHTWALKER3) ? NightWalker.ALCHEMIST : Hermit.ALCHEMIST;
        int level = bot.getSkillLevel(id);
        int x = level > 0 ? SupplyData.get().x(id, level) : 0;
        return x > 0 ? (int) (value * (x / 100.0)) : value;
    }

    // ---------------------------------------------------------------- recovery

    /* HP recovered every 10 s: a player's 10, plus Improved HP Recovery. */
    static int hpRegenPerTick(Character bot) {
        int level = bot.getSkillLevel(Warrior.IMPROVED_HPREC);
        return BASE_HP_REGEN + (level > 0 ? Math.max(0, SupplyData.get().x(Warrior.IMPROVED_HPREC, level)) : 0);
    }

    /* MP recovered every 10 s: 3 + INT/10, plus Improved MP Recovery. */
    static int mpRegenPerTick(Character bot) {
        int level = bot.getSkillLevel(Magician.IMPROVED_MP_RECOVERY);
        return BASE_MP_REGEN + bot.getTotalInt() / 10
                + (level > 0 ? Math.max(0, SupplyData.get().x(Magician.IMPROVED_MP_RECOVERY, level)) : 0);
    }

    /* Natural recovery over this much time (fractions carry over between calls). */
    public static void regen(Character bot, long elapsedMs) {
        if (bot == null || elapsedMs <= 0) {
            return;
        }
        synchronized (lock(bot)) {
            double[] carry = regenCarry.computeIfAbsent(bot.getId(), id -> new double[2]);
            double ticks = elapsedMs / REGEN_TICK_MS;
            carry[0] += hpRegenPerTick(bot) * ticks;
            carry[1] += mpRegenPerTick(bot) * ticks;
            int hp = (int) carry[0];
            int mp = (int) carry[1];
            carry[0] -= hp;
            carry[1] -= mp;
            if (hp > 0 || mp > 0) {
                setHpMp(bot, Math.min(bot.getCurrentMaxHp(), bot.getHp() + hp),
                        Math.min(bot.getCurrentMaxMp(), bot.getMp() + mp));
            }
        }
    }

    /* A rest in town (sitting it out between outings): back to full HP and MP. */
    public static void rest(Character bot) {
        if (bot == null) {
            return;
        }
        synchronized (lock(bot)) {
            setHpMp(bot, bot.getCurrentMaxHp(), bot.getCurrentMaxMp());
        }
    }

    // ---------------------------------------------------------------- writes

    // Through the character's own stat path, so the party HP bar and the stored row see it; no packet reaches
    // the bot's headless client.
    static void setHp(Character bot, int hp) {
        if (hp != bot.getHp()) {
            bot.updateHp(hp);
        }
    }

    static void setMp(Character bot, int mp) {
        if (mp != bot.getMp()) {
            bot.updateMp(mp);
        }
    }

    static void setHpMp(Character bot, int hp, int mp) {
        if (hp != bot.getHp() || mp != bot.getMp()) {
            bot.updateHpMp(hp, mp);
        }
    }

    static void clearBot(int botId) {
        locks.remove(botId);
        regenCarry.remove(botId);
    }
}

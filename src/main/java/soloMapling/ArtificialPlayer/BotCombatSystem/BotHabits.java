package soloMapling.ArtificialPlayer.BotCombatSystem;

import client.Character;
import client.inventory.Pet;

import java.util.SplittableRandom;

/**
 * 1.5.0 rc3: a bot's own grinding habits, drawn once from its character id so they never re-roll (the owner: "not
 * everyone has pets or loots in batches or all at once"). Pure and cheap: {@link #of(int)} rebuilds the same record
 * from the id every time, so nothing is stored or persisted.
 *
 * <ul>
 *   <li>{@link Loot}: how it picks up drops.</li>
 *   <li>{@code hpPotAt}: the HP share under which it drinks (0.40-0.60); {@code latePotChance}: how often it
 *       notices late (drinks only 15 points lower that time).</li>
 *   <li>{@code restockBelow}: the HP potion count at which it heads to town to restock (0 = only when out).</li>
 *   <li>{@code rebuffLateMaxMs}: how late it recasts an expired buff; {@code sloppyCast}: the chance a cast is the
 *       other (still class-correct) attack.</li>
 *   <li>{@code paceChance}: the chance a respawn lull is spent walking its platform; {@code ccChance}: the chance it
 *       says "cc?" when it gives up a map to a player.</li>
 *   <li>{@code carelessness}: scales its chance of a rare death on a hard map.</li>
 * </ul>
 */
public record BotHabits(Loot loot, double hpPotAt, double latePotChance, int restockBelow, long rebuffLateMaxMs,
                        double sloppyCast, double paceChance, double ccChance, double carelessness) {

    /** How a bot picks up its drops. */
    public enum Loot {
        /** Fights the pack, then sweeps the pile in the lull (the old behaviour). */
        BATCH,
        /** Walks over to a drop as soon as it has settled, between swings. */
        INSTANT,
        /** Batches, but leaves etc. junk on the ground: mesos, equips and usables only. */
        SKIP_JUNK,
        /** Its pet loots: never walks for a drop, picks up what lands near it. Only with a pet that can loot. */
        PET
    }

    /** The bot's habits (stable for its id). */
    public static BotHabits of(int characterId) {
        SplittableRandom r = new SplittableRandom(characterId * 0x9E3779B97F4A7C15L ^ 0x5DEECE66DL);
        double l = r.nextDouble();
        Loot loot = l < 0.45 ? Loot.BATCH : l < 0.72 ? Loot.INSTANT : l < 0.90 ? Loot.SKIP_JUNK : Loot.PET;
        double hpPotAt = 0.40 + 0.20 * r.nextDouble();
        double latePot = 0.03 + 0.20 * r.nextDouble() * r.nextDouble();          // most notice in time
        int restockBelow = r.nextDouble() < 0.35 ? 0 : 3 + r.nextInt(18);         // a third run it dry
        long rebuffLate = (long) (25_000 * r.nextDouble() * r.nextDouble());      // 0-25 s, skewed early
        double sloppy = 0.02 + 0.10 * r.nextDouble();
        double pace = 0.25 + 0.6 * r.nextDouble();
        double cc = 0.10 + 0.40 * r.nextDouble();
        double careless = 0.3 + 1.4 * r.nextDouble();
        return new BotHabits(loot, hpPotAt, latePot, restockBelow, rebuffLate, sloppy, pace, cc, careless);
    }

    public static BotHabits of(Character bot) {
        return of(bot.getId());
    }

    /**
     * The loot style it uses right now: {@link Loot#PET} needs a summoned pet with a Meso Magnet or Item Pouch;
     * without one the bot batches like most players.
     */
    public static Loot lootNow(Character bot) {
        Loot l = of(bot.getId()).loot();
        return l == Loot.PET && !hasLootingPet(bot) ? Loot.BATCH : l;
    }

    /** A summoned pet that picks up (Meso Magnet or Item Pouch equipped). */
    public static boolean hasLootingPet(Character bot) {
        try {
            if (bot == null || !(bot.isEquippedMesoMagnet() || bot.isEquippedItemPouch())) {
                return false;
            }
            Pet[] pets = bot.getPets();
            if (pets == null) {
                return false;
            }
            for (Pet p : pets) {
                if (p != null && p.isSummoned()) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // a mocked character in a test
        }
        return false;
    }

    /** The chance this cast is the other attack, scaled by {@code BOT_COMBAT_SLOPPINESS}. */
    public double sloppyNow() {
        return Math.min(0.5, sloppyCast * BotCombatTuning.sloppiness());
    }

    /** How late a recast may come, scaled by {@code BOT_COMBAT_SLOPPINESS}. */
    public long rebuffLateNowMs() {
        return (long) (rebuffLateMaxMs * BotCombatTuning.sloppiness());
    }

    /** "INSTANT loot, pots at 47% (late 9%), restock under 12, rebuff late <= 4 s, sloppy 6%, ...". */
    public String describe() {
        return loot + " loot, pots at " + Math.round(hpPotAt * 100) + "% (late " + Math.round(latePotChance * 100)
                + "%), restock " + (restockBelow == 0 ? "when out" : "under " + restockBelow) + ", rebuff late <= "
                + (rebuffLateMaxMs / 1000) + " s, sloppy " + Math.round(sloppyCast * 100) + "%, paces "
                + Math.round(paceChance * 100) + "%, cc " + Math.round(ccChance * 100) + "%, careless x"
                + String.format(java.util.Locale.ROOT, "%.1f", carelessness);
    }
}

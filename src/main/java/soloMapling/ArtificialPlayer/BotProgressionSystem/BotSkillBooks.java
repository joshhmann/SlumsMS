package soloMapling.ArtificialPlayer.BotProgressionSystem;

import client.Character;
import client.Skill;
import client.SkillFactory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.Economy.SaleRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

/**
 * 1.6.2 ticket 159: 4th-job skill and mastery books for bots. Switch: {@code -Dsolomapling.botSkillBooks=true}
 * (default off; with it off nothing here runs and a bot's 4th-job master levels are their plan targets, as before).
 *
 * <ul>
 *   <li><b>Master levels.</b> {@link BotSkillPlan#isBookSkill} skills start at master {@link BotSkillPlan#BASE_MASTER}
 *       and a bot's SP does not take them higher ({@link BotBuild.State#masterCaps}).</li>
 *   <li><b>Want.</b> A book is wanted when it raises a skill the bot's plan raises further than its master level now
 *       ({@link #wants}), even before the skill is high enough to read it.</li>
 *   <li><b>Keep.</b> A wanted book is never sold, listed, traded or given ({@link #sellRefusal}).</li>
 *   <li><b>Use.</b> A wanted book the bot can read is read a few seconds after it lands in the bag or after a
 *       level-up ({@link #useAll}); a success raises the master level and the SP the cap held back is spent.</li>
 *   <li><b>Spare.</b> A book the bot does not want (a duplicate, another class's, one already used up to the plan) may
 *       go into a bot shop, but only under {@link SaleRules}: boss-only books and the rest stay blocked.</li>
 * </ul>
 * Bots still get books only from drops (they loot everything but junk) and from players; nothing sends a bot hunting
 * for one. Concept from nutnnut/Cosmic dev BotSkillBookManager (see docs/PROVENANCE.md); the code is our own.
 */
public final class BotSkillBooks {

    private static final Logger log = LoggerFactory.getLogger(BotSkillBooks.class);

    public static final String PROP = "solomapling.botSkillBooks";

    /** What the item data says about one book for one job. */
    public record Stats(int skillId, int reqSkillLevel, int masterLevel, int successPercent) {
    }

    /** Book data lookup; replaced in tests (they have no item data). */
    public interface Data {
        /** The book's stats for a bot of this job, or null when it is not a book / not for this job. */
        Stats stats(int itemId, int jobId);
    }

    private static final Data SERVER = (itemId, jobId) -> {
        Map<String, Integer> m = ItemInformationProvider.getInstance().getSkillStats(itemId, jobId);
        if (m == null) {
            return null;
        }
        return new Stats(m.getOrDefault("skillid", 0), m.getOrDefault("reqSkillLevel", 0),
                m.getOrDefault("masterLevel", 0), m.getOrDefault("success", 0));
    };

    private static final java.util.concurrent.ScheduledExecutorService LATER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bot-skill-books");
                t.setDaemon(true);
                return t;
            });

    private static volatile Data data = SERVER;
    private static volatile java.util.function.Supplier<BotBuild.SkillCatalog> catalog = BotProgression::catalog;
    private static volatile IntPredicate roll = p -> ItemInformationProvider.rollSuccessChance(p);
    private static volatile java.util.function.BooleanSupplier switchOn =
            () -> "true".equalsIgnoreCase(System.getProperty(PROP, "false").trim());
    private static volatile JobErrand.Scheduler later = (task, ms) -> LATER.schedule(() -> {
        try {
            task.run();
        } catch (Throwable t) {
            log.warn("skill book task failed", t);
        }
    }, ms, TimeUnit.MILLISECONDS);

    private BotSkillBooks() {
    }

    public static boolean enabled() {
        return switchOn.getAsBoolean();
    }

    /** Skill books 2280000-2280019 and mastery books 2290000-2290139. */
    public static boolean isBook(int itemId) {
        return itemId / 10000 == 228 || itemId / 10000 == 229;
    }

    // ---------------------------------------------------------------- want

    /** Does this book raise a skill the bot's plan wants higher than its master level is now? */
    public static boolean wants(Character bot, int itemId) {
        if (!enabled() || bot == null || !isBook(itemId)) {
            return false;
        }
        Stats st = data.stats(itemId, bot.getJob().getId());
        if (st == null || st.skillId() <= 0) {
            return false;
        }
        Skill skill = SkillFactory.getSkill(st.skillId());
        if (skill == null) {
            return false;
        }
        int planned = BotBuild.targetLevel(st.skillId() / 10000, st.skillId(), catalog.get());
        return BotSkillPlan.booksRaise(planned, st.masterLevel(), bot.getMasterLevel(skill));
    }

    /** Wanted, and the bot's skill is high enough to read it now. */
    public static boolean canUseNow(Character bot, int itemId) {
        if (!wants(bot, itemId)) {
            return false;
        }
        Stats st = data.stats(itemId, bot.getJob().getId());
        Skill skill = SkillFactory.getSkill(st.skillId());
        return st.reqSkillLevel() == 0 || bot.getSkillLevel(skill) >= st.reqSkillLevel();
    }

    // ---------------------------------------------------------------- keep / sell

    /**
     * Why a bot may not list this item for sale, or null when it may: a book the bot still wants is kept for it; any
     * other item (and a spare book) answers to {@link SaleRules#whyNotListOwn}, so boss-only books stay blocked.
     */
    public static String sellRefusal(Character bot, Item item) {
        if (item != null && isBook(item.getItemId()) && wants(bot, item.getItemId())) {
            return "kept for its skill plan";
        }
        return SaleRules.whyNotListOwn(item);
    }

    // ---------------------------------------------------------------- use

    /** After a book lands in the bag or the bot levels up: read what it can, one at a time, after a human pause. */
    public static void soon(Character bot) {
        if (!enabled() || bot == null || !holdsWantedBook(bot)) {
            return;
        }
        later.schedule(() -> useAll(bot), ThreadLocalRandom.current().nextLong(2_500L, 6_501L));
    }

    private static boolean holdsWantedBook(Character bot) {
        for (Item item : books(bot)) {
            if (wants(bot, item.getItemId())) {
                return true;
            }
        }
        return false;
    }

    private static List<Item> books(Character bot) {
        List<Item> out = new ArrayList<>();
        var inv = bot.getInventory(InventoryType.USE);
        inv.lockInventory();
        try {
            for (Item item : inv.list()) {
                if (isBook(item.getItemId())) {
                    out.add(item);
                }
            }
        } finally {
            inv.unlockInventory();
        }
        return out;
    }

    /** Reads every wanted book the bot can read now (a failed read uses the book up); returns how many worked. */
    public static int useAll(Character bot) {
        int worked = 0;
        for (int guard = 0; guard < 40; guard++) {
            Item next = null;
            for (Item item : books(bot)) {
                if (canUseNow(bot, item.getItemId())) {
                    next = item;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            if (use(bot, next)) {
                worked++;
            }
        }
        if (worked > 0) {
            BotProgression.afterBook(bot);   // the SP the cap held back
        }
        return worked;
    }

    /** Reads one book as the book handler does for a player; true when it raised the master level. */
    static boolean use(Character bot, Item book) {
        synchronized (bot) {
            int itemId = book.getItemId();
            if (!canUseNow(bot, itemId)) {
                return false;
            }
            Stats st = data.stats(itemId, bot.getJob().getId());
            Skill skill = SkillFactory.getSkill(st.skillId());
            if (BotHoldings.consumeHeld(bot, book, 1) != 1) {
                return false;
            }
            if (!roll.test(st.successPercent())) {
                log.debug("Bot {} ({}) read book {} and nothing happened", bot.getId(), bot.getName(), itemId);
                return false;
            }
            bot.changeSkillLevel(skill, (byte) bot.getSkillLevel(skill),
                    Math.max(st.masterLevel(), bot.getMasterLevel(skill)), -1);
            log.debug("Bot {} ({}) read book {}: skill {} master level is now {}", bot.getId(), bot.getName(), itemId,
                    st.skillId(), bot.getMasterLevel(skill));
            return true;
        }
    }

    // ---------------------------------------------------------------- tests

    public static void useForTest(Data d, IntPredicate r, java.util.function.BooleanSupplier on, JobErrand.Scheduler s) {
        data = d == null ? SERVER : d;
        catalog = d == null ? BotProgression::catalog : () -> BotBuild.SkillCatalog.PLAN;   // tests have no skill data
        roll = r == null ? p -> ItemInformationProvider.rollSuccessChance(p) : r;
        switchOn = on == null ? () -> "true".equalsIgnoreCase(System.getProperty(PROP, "false").trim()) : on;
        later = s == null ? (task, ms) -> LATER.schedule(task, ms, TimeUnit.MILLISECONDS) : s;
    }
}

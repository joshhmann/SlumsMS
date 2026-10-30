package soloMapling.ArtificialPlayer.BotProgressionSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.skills.Hero;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.SaleRules;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 159, part 2: master levels of the book-limited 4th-job skills, and bots wanting, keeping, reading and
 * (when spare, under SaleRules) selling the books.
 */
class BotSkillBooksTest {

    private static final int BOT_ID = 1_000_000_000 + 5200;
    private static final int ACHILLES_BOOK = 2290001;     // test ids: the item data is faked below
    private static final int BOSS_BOOK = 2290002;
    private static final int OTHER_JOB_BOOK = 2290003;
    private static final int UNLOCK_BOOK = 2290004;      // raises a skill the bot has no level in (no level needed)

    private Map<Integer, Skill> previousSkills;
    private boolean rollWins = true;
    private boolean on = true;

    @BeforeEach
    void setUp() throws Exception {
        Map<Integer, Skill> skills = new HashMap<>();
        for (var job : BotSkillPlan.all().values()) {
            for (BotSkillPlan.Entry entry : job) {
                skills.put(entry.skillId(), new Skill(entry.skillId()));
                if (entry.altSkillId() != 0) {
                    skills.put(entry.altSkillId(), new Skill(entry.altSkillId()));
                }
            }
        }
        for (int granted : BotSkillPlan.allGranted()) {
            skills.put(granted, new Skill(granted));
        }
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Skill> previous = (Map<Integer, Skill>) field.get(null);
        previousSkills = previous;
        field.set(null, skills);
        BotProgression.useCatalogForTest(BotBuild.SkillCatalog.PLAN);
        BotSkillBooks.useForTest((itemId, jobId) -> switch (itemId) {
            case ACHILLES_BOOK, BOSS_BOOK -> jobId == 112 ? new BotSkillBooks.Stats(Hero.ACHILLES, 10, 30, 100) : null;
            case OTHER_JOB_BOOK -> new BotSkillBooks.Stats(0, 0, 30, 100);   // not for this job: skill id 0
            case UNLOCK_BOOK -> jobId == 112 ? new BotSkillBooks.Stats(Hero.ACHILLES, 0, 30, 100) : null;
            default -> null;
        }, p -> rollWins, () -> on, (task, ms) -> task.run());
        SaleRules.useForTest(Set.of(BOSS_BOOK), id -> false, id -> false);
    }

    @AfterEach
    void tearDown() throws Exception {
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        field.set(null, previousSkills);
        BotProgression.useCatalogForTest(null);
        BotSkillBooks.useForTest(null, null, null, null);
        SaleRules.useForTest(null, null, null);
        BotProgression.forget(BOT_ID);
    }

    private static Character hero() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(BOT_ID);
        chr.setName("Booker");
        chr.setJob(Job.getById(112));
        chr.setLevel(200);
        return chr;
    }

    private static Item give(Character bot, int itemId, int quantity) {
        Item item = new Item(itemId, (short) 0, (short) quantity);
        bot.getInventory(InventoryType.USE).addItem(item);
        return item;
    }

    private static Skill achilles() {
        return SkillFactory.getSkill(Hero.ACHILLES);
    }

    @Test
    void withTheSwitchOffMasterLevelsAreThePlanTargets() {
        on = false;
        Character bot = hero();
        BotProgression.buildNew(bot);
        assertEquals(30, bot.getMasterLevel(achilles()), "as if it had read the books (today's behaviour)");
    }

    @Test
    void aNewFourthJobBotStartsAtMasterTenAndItsSpWaitsForTheBook() {
        Character bot = hero();
        BotProgression.buildNew(bot);

        assertEquals(10, bot.getMasterLevel(achilles()));
        assertTrue(bot.getSkillLevel(achilles()) <= 10, "SP never takes a skill past its master level");
    }

    @Test
    void readingTheBookRaisesTheMasterLevelAndThenTheSkill() {
        Character bot = hero();
        BotProgression.buildNew(bot);
        bot.gainSp(25, 0, true);   // SP it kept for the skill
        Item book = give(bot, ACHILLES_BOOK, 1);
        assertTrue(BotSkillBooks.wants(bot, ACHILLES_BOOK));
        assertEquals(10, bot.getMasterLevel(achilles()), "before the book");

        assertEquals(1, BotSkillBooks.useAll(bot));

        assertEquals(30, bot.getMasterLevel(achilles()), "after the book");
        assertEquals(30, bot.getSkillLevel(achilles()), "its SP could go in now");
        assertEquals(0, bot.getInventory(InventoryType.USE).countById(ACHILLES_BOOK), "the book is used up");
        assertFalse(BotSkillBooks.wants(bot, ACHILLES_BOOK), "nothing left for a second copy to do");
    }

    /**
     * t-jb-2 on TEST 2026-10-30: "useAll counted a raise but the master level stayed 0". A book read on a skill the
     * bot has no level in leaves it at level 0 with a master level; the settle after the book (afterBook) captured
     * only skills with a level and rebuilt the skill map from them, so the new master level was thrown away.
     */
    @Test
    void aBookReadOnASkillWithNoLevelKeepsItsMasterLevel() {
        Character bot = hero();
        BotProgression.buildNew(bot);
        Map<Skill, client.Character.SkillEntry> without = new HashMap<>(bot.getSkills());
        without.remove(achilles());
        bot.replaceBotSkills(without);   // no level in it, no master level
        int sp = bot.getRemainingSps()[0];
        if (sp > 0) {
            bot.gainSp(-sp, 0, true);   // no SP to put in after the read: the master level must hold on its own
        }
        give(bot, UNLOCK_BOOK, 1);
        assertTrue(BotSkillBooks.canUseNow(bot, UNLOCK_BOOK));

        assertEquals(1, BotSkillBooks.useAll(bot));

        assertEquals(30, bot.getMasterLevel(achilles()), "the book's master level survives the settle");
        assertEquals(0, bot.getSkillLevel(achilles()));
        assertFalse(BotSkillBooks.wants(bot, UNLOCK_BOOK), "a second copy has nothing left to do");
        BotRealStats stored = BotProgression.snapshot(bot);
        assertEquals(new BotRealStats.SkillLevel(0, 30), stored.skills().get(Hero.ACHILLES), "and it is saved");
    }

    @Test
    void aBookThatFailsItsRollIsStillUsedUp() {
        Character bot = hero();
        BotProgression.buildNew(bot);
        give(bot, ACHILLES_BOOK, 2);
        rollWins = false;

        assertEquals(0, BotSkillBooks.useAll(bot));

        assertEquals(10, bot.getMasterLevel(achilles()));
        assertTrue(bot.getInventory(InventoryType.USE).countById(ACHILLES_BOOK) <= 1, "a failed read burns the book");
    }

    @Test
    void aBookForAnotherClassIsNotWantedAndStaysInTheBag() {
        Character bot = hero();
        BotProgression.buildNew(bot);
        give(bot, OTHER_JOB_BOOK, 1);

        assertFalse(BotSkillBooks.wants(bot, OTHER_JOB_BOOK));
        assertEquals(0, BotSkillBooks.useAll(bot));
        assertEquals(1, bot.getInventory(InventoryType.USE).countById(OTHER_JOB_BOOK));
    }

    @Test
    void aBookIsWantedBeforeItsSkillIsHighEnoughToReadIt() {
        Character bot = hero();
        BotProgression.buildNew(bot);
        bot.changeSkillLevel(achilles(), (byte) 3, 10, -1);   // below the book's requirement of 10
        give(bot, ACHILLES_BOOK, 1);

        assertTrue(BotSkillBooks.wants(bot, ACHILLES_BOOK));
        assertFalse(BotSkillBooks.canUseNow(bot, ACHILLES_BOOK));
        assertEquals(0, BotSkillBooks.useAll(bot));
        assertEquals(1, bot.getInventory(InventoryType.USE).countById(ACHILLES_BOOK), "kept for later");
    }

    @Test
    void aWantedBookIsKeptAndASpareOneMayBeSoldOnlyUnderSaleRules() {
        Character bot = hero();
        BotProgression.buildNew(bot);
        Item wanted = give(bot, ACHILLES_BOOK, 1);
        Item boss = new Item(BOSS_BOOK, (short) 0, (short) 1);

        assertEquals("kept for its skill plan", BotSkillBooks.sellRefusal(bot, wanted));
        assertEquals("kept for its skill plan", BotSkillBooks.sellRefusal(bot, boss), "wanted comes first");

        BotSkillBooks.useAll(bot);   // master 30: the plan has nothing more to ask of the book
        Item spare = new Item(ACHILLES_BOOK, (short) 0, (short) 1);
        assertNull(BotSkillBooks.sellRefusal(bot, spare), "a spare copy may be sold");
        assertEquals("boss-only drop", BotSkillBooks.sellRefusal(bot, boss), "a spare boss-only book stays blocked");
    }
}

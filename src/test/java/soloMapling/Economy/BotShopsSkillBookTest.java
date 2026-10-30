package soloMapling.Economy;

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
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotSkillBooks;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 159: a bot shop stocks a spare skill book, never one the bot still wants, never a boss-only one. */
class BotShopsSkillBookTest {

    private static final int WANTED = 2290001;
    private static final int SPARE = 2290004;     // for a skill the bot already has at the book's master level
    private static final int BOSS = 2290002;

    private Map<Integer, Skill> previousSkills;

    @BeforeEach
    void setUp() throws Exception {
        new EconomyTestSupport();
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Skill> previous = (Map<Integer, Skill>) field.get(null);
        previousSkills = previous;
        Map<Integer, Skill> skills = new HashMap<>();
        skills.put(Hero.ACHILLES, new Skill(Hero.ACHILLES));
        skills.put(Hero.MONSTER_MAGNET, new Skill(Hero.MONSTER_MAGNET));
        field.set(null, skills);
        BotSkillBooks.useForTest((id, job) -> switch (id) {
            case WANTED, BOSS -> new BotSkillBooks.Stats(Hero.ACHILLES, 0, 30, 100);
            case SPARE -> new BotSkillBooks.Stats(Hero.MONSTER_MAGNET, 0, 20, 100);
            default -> null;
        }, p -> true, () -> true, (t, ms) -> t.run());
    }

    @AfterEach
    void tearDown() throws Exception {
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        field.set(null, previousSkills);
        BotSkillBooks.useForTest(null, null, null, null);
        EconomyTestSupport.reset();
    }

    @Test
    void onlyTheSpareNonBossBookIsOffered() {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(1_000_000_000 + 5300);
        bot.setJob(Job.getById(112));
        // Achilles at master 10 (the book is wanted); Monster Magnet already at master 30 (its book is spare)
        bot.changeSkillLevel(SkillFactory.getSkill(Hero.ACHILLES), (byte) 10, 10, -1);
        bot.changeSkillLevel(SkillFactory.getSkill(Hero.MONSTER_MAGNET), (byte) 30, 30, -1);
        for (int id : new int[]{WANTED, SPARE, BOSS}) {
            bot.getInventory(InventoryType.USE).addItem(new Item(id, (short) 0, (short) 1));
        }
        SaleRules.useForTest(java.util.Set.of(BOSS), id -> false, id -> false);

        List<String> refused = new ArrayList<>();
        List<Item> offered = BotShops.sellable(bot, refused);

        List<Integer> ids = offered.stream().map(Item::getItemId).toList();
        assertTrue(ids.contains(SPARE), "a spare book is offered: " + ids + " refused " + refused);
        assertFalse(ids.contains(WANTED), "the book its plan needs is kept");
        assertFalse(ids.contains(BOSS), "a boss-only book stays blocked");
        assertEquals(2, refused.size());
    }
}

package soloMapling.ArtificialPlayer.BotProgressionSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.Item;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotProgressionSystem.RebirthBosses.Boss;
import soloMapling.ArtificialPlayer.BotRebirthQa;
import client.inventory.InventoryType;
import constants.id.ItemId;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.3 t-rb-2 (TEST 2026-11-02 00:15 UTC): the QA raise took a level 31 spearman to 200 but left a level 31 body -
 * "HP 6730/6730 MP 175/1495, holds HP pots 73 MP pots 0" - and its 4th-job casts (Dark Impale 80 MP, La Mancha Spear
 * 200) could not be paid, so the run swung plain attacks and Von Leon never cleared. A player at 200 walks into a boss
 * with full MP and a stack of MP potions.
 */
class RebirthQaBodyTest {

    private static final int BOT_ID = 1_000_000_000 + 7602;

    private Map<Integer, Skill> previousSkills;
    private boolean useRebirth, botRebirth;
    private int perDay;

    @BeforeEach
    void setUp() throws Exception {
        Map<Integer, Skill> skills = new HashMap<>();
        for (var job : BotSkillPlan.all().values()) {
            for (BotSkillPlan.Entry entry : job) {
                skills.put(entry.skillId(), new Skill(entry.skillId()));
                if (entry.altSkillId() != 0) skills.put(entry.altSkillId(), new Skill(entry.altSkillId()));
            }
        }
        for (int granted : BotSkillPlan.allGranted()) skills.put(granted, new Skill(granted));
        for (var job : new int[]{112, 122, 132, 212, 222, 232, 312, 322, 412, 422, 512, 522}) {
            for (BotSkillPlan.Grant g : BotSkillPlan.fourthJobPackage(job)) skills.put(g.skillId(), new Skill(g.skillId()));
        }
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Integer, Skill> prev = (Map<Integer, Skill>) field.get(null);
        previousSkills = prev;
        field.set(null, skills);
        BotProgression.useCatalogForTest(BotBuild.SkillCatalog.PLAN);
        var s = YamlConfig.config.server;
        useRebirth = s.USE_REBIRTH_SYSTEM;
        botRebirth = s.BOT_REBIRTH_ENABLED;
        perDay = s.BOT_REBIRTH_PER_DAY;
        s.USE_REBIRTH_SYSTEM = true;
        s.BOT_REBIRTH_ENABLED = true;
        s.BOT_REBIRTH_PER_DAY = 1;
    }

    @AfterEach
    void tearDown() throws Exception {
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        field.set(null, previousSkills);
        BotProgression.useCatalogForTest(null);
        BotProgression.forget(BOT_ID);
        var s = YamlConfig.config.server;
        s.USE_REBIRTH_SYSTEM = useRebirth;
        s.BOT_REBIRTH_ENABLED = botRebirth;
        s.BOT_REBIRTH_PER_DAY = perDay;
    }

    @Test
    void theRaisedBodyIsAPlayersBodyNotALevelThirtyOnes() {
        Character bot = bot();
        assertEquals(0, elixirs(bot), "the level 31 body carries no MP potion");

        assertTrue(BotRebirthQa.raise(before(bot)));

        assertTrue(elixirs(bot) >= BotRebirthQa.BOSS_MP_POTIONS,
                "a level 200 enters the run with the MP potions its casts need, not the 0 the raise used to leave: "
                        + elixirs(bot));
    }

    @Test
    void aRaisedBotThatAlreadyCarriesItsPotionsGetsNoSecondStack() {
        Character bot = bot();
        BotHoldings.grantItem(bot, new Item(ItemId.MANA_ELIXIR, (short) 0, (short) BotRebirthQa.BOSS_MP_POTIONS));

        assertTrue(BotRebirthQa.raise(before(bot)));

        assertEquals(BotRebirthQa.BOSS_MP_POTIONS, elixirs(bot), "no new stack for a stocked bot");
    }

    private static int elixirs(Character bot) {
        int n = 0;
        for (Item item : bot.getInventory(InventoryType.USE).listById(ItemId.MANA_ELIXIR)) {
            n += item.getQuantity();
        }
        return n;
    }

    private static Character bot() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(BOT_ID);
        chr.setName("Body1");
        chr.setJob(Job.getById(131));
        chr.setLevel(31);
        chr.setBotReborns(0);
        BotProgression.buildNew(chr);
        return chr;
    }

    private static BotRebirthQa.Before before(Character bot) {
        return new BotRebirthQa.Before(bot, bot.getLevel(), bot.getExp(), bot.getJob().getId(), bot.getReborns(),
                BotProgression.snapshot(bot), bot.getExpRate(), bot.getMeso(), 0, new LinkedHashMap<>(),
                new LinkedHashMap<>(), new java.util.EnumMap<>(server.expeditions.ExpeditionBossLog.BossLogEntry.class),
                0, ConcurrentHashMap.newKeySet(), new ConcurrentHashMap<>(), new AtomicLong(bot.getMeso()));
    }
}

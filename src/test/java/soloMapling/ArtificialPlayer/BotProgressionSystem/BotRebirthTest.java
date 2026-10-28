package soloMapling.ArtificialPlayer.BotProgressionSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static soloMapling.ArtificialPlayer.BotProgressionSystem.BotRebirth.Stage;

/** 1.6.1 ticket 30: regulars earn rebirths like players, key item included, at a server-wide daily pace. */
class BotRebirthTest {

    private static final int BOT_ID = 1_000_000_000 + 7301;
    private static final long NOW = 1_800_000_000_000L;

    private Map<Integer, Skill> previousSkills;
    private boolean useRebirth, botRebirth;
    private int perDay, r1, r2, r3, rf;

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
        previousSkills = swapSkillFactory(skills);
        BotProgression.useCatalogForTest(BotBuild.SkillCatalog.PLAN);
        var s = YamlConfig.config.server;
        useRebirth = s.USE_REBIRTH_SYSTEM;
        botRebirth = s.BOT_REBIRTH_ENABLED;
        perDay = s.BOT_REBIRTH_PER_DAY;
        r1 = s.REBIRTH_FIRST_RATE;
        r2 = s.REBIRTH_SECOND_RATE;
        r3 = s.REBIRTH_THIRD_RATE;
        rf = s.REBIRTH_FINAL_RATE;
        s.USE_REBIRTH_SYSTEM = true;
        s.BOT_REBIRTH_ENABLED = true;
        s.BOT_REBIRTH_PER_DAY = 1;
        s.REBIRTH_FIRST_RATE = 3;
        s.REBIRTH_SECOND_RATE = 2;
        s.REBIRTH_THIRD_RATE = 1;
        s.REBIRTH_FINAL_RATE = 1;
        BotRebirth.resetForTest();
    }

    @AfterEach
    void tearDown() throws Exception {
        swapSkillFactory(previousSkills);
        BotProgression.useCatalogForTest(null);
        BotProgression.forget(BOT_ID);
        BotProgression.forget(BOT_ID + 1);
        var s = YamlConfig.config.server;
        s.USE_REBIRTH_SYSTEM = useRebirth;
        s.BOT_REBIRTH_ENABLED = botRebirth;
        s.BOT_REBIRTH_PER_DAY = perDay;
        s.REBIRTH_FIRST_RATE = r1;
        s.REBIRTH_SECOND_RATE = r2;
        s.REBIRTH_THIRD_RATE = r3;
        s.REBIRTH_FINAL_RATE = rf;
        BotRebirth.resetForTest();
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Skill> swapSkillFactory(Map<Integer, Skill> skills) throws Exception {
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        Map<Integer, Skill> previous = (Map<Integer, Skill>) field.get(null);
        field.set(null, skills);
        return previous;
    }

    private static Character bot(int id, int job, int level) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName("Reborn" + (id % 10));
        chr.setJob(Job.getById(job));
        chr.setLevel(level);
        chr.setBotReborns(0);
        BotProgression.buildNew(chr);
        return chr;
    }

    private static int etc(Character c, int id) {
        return c.getInventory(InventoryType.ETC).countById(id);
    }

    @Test
    void keyItemsAndRecipesMatchThePlayerScripts() throws Exception {
        String rebirth = Files.readString(Path.of("scripts/npc/rebirth.js"));
        String craft = Files.readString(Path.of("scripts/npc/craft.js"));
        for (int r = 0; r < 3; r++) {
            int key = BotRebirth.keyItemFor(r);
            assertTrue(rebirth.contains("return " + key), "rebirth.js key for rebirth " + (r + 1));
            int[] ing = BotRebirth.ingredientsFor(r);
            assertTrue(craft.contains("cm.haveItem(" + ing[0] + ") && cm.haveItem(" + ing[1] + ")"), "recipe " + r);
            assertTrue(craft.contains("cm.gainItem(" + key + ", 1)"), "craft gives " + key);
            assertTrue(BotRebirth.keeps(key) && BotRebirth.keeps(ing[0]) && BotRebirth.keeps(ing[1]));
        }
        assertEquals(-1, BotRebirth.keyItemFor(3));
        assertFalse(BotRebirth.keeps(4000000));
    }

    @Test
    void stagesFollowRebirthJsRules() {
        int[] none = {0, 0}, both = {1, 1}, one = {1, 0};
        assertEquals(Stage.NOT_READY, BotRebirth.stage(199, 0, 412, true, 0, none));
        assertEquals(Stage.NOT_READY, BotRebirth.stage(200, 0, 411, true, 0, none), "3rd job is not fully advanced");
        assertEquals(Stage.MAXED, BotRebirth.stage(255, 3, 412, true, 0, none));
        assertEquals(Stage.NOT_BOTHERED, BotRebirth.stage(200, 0, 412, false, 0, none));
        assertEquals(Stage.GATHER, BotRebirth.stage(200, 1, 1311, true, 0, one));
        assertEquals(Stage.CRAFT, BotRebirth.stage(200, 2, 2112, true, 0, both));
        assertEquals(Stage.TRIP, BotRebirth.stage(200, 0, 412, false, 1, none), "a held key goes either way");
    }

    @Test
    void personaDecidesWhoBothersAndItIsStableForAWeek() {
        SocialProfile achiever = null, socializer = null;
        for (int i = 0; i < 4000 && (achiever == null || socializer == null); i++) {
            SocialProfile p = SocialProfile.forSlot(i);
            if (p.share(SocialProfile.Type.ACHIEVER) > .6) achiever = p;
            if (p.share(SocialProfile.Type.SOCIALIZER) > .6) socializer = p;
        }
        assertNotNull(achiever);
        assertNotNull(socializer);
        assertTrue(BotRebirth.botherChance(achiever) > BotRebirth.botherChance(socializer) + .2);
        int yes = 0, changed = 0;
        for (int id = 0; id < 2000; id++) {
            boolean a = BotRebirth.bothers(id, 0, .3, NOW);
            assertEquals(a, BotRebirth.bothers(id, 0, .3, NOW + 3_600_000L), "same week, same answer");
            if (a) yes++;
            if (a != BotRebirth.bothers(id, 0, .3, NOW + 8L * 86_400_000L)) changed++;
        }
        assertTrue(yes > 450 && yes < 750, "about 30% bother: " + yes);
        assertTrue(changed > 300, "re-rolled each week: " + changed);
    }

    @Test
    void paceCapsRebirthsPerUtcDay() {
        BotRebirth.Pace pace = new BotRebirth.Pace();
        assertTrue(pace.take(NOW, 2));
        assertTrue(pace.take(NOW, 2));
        assertFalse(pace.take(NOW, 2));
        assertEquals(0, pace.left(NOW, 2));
        assertEquals(2, pace.left(NOW + 86_400_000L, 2), "a new day");
        pace.giveBack(NOW + 86_400_000L);
        assertEquals(0, new BotRebirth.Pace().left(NOW, 0), "0 a day = none");
    }

    @Test
    void aMaxLevelBotGathersCraftsTravelsAndIsRebornLikeAPlayer() {
        Character bot = bot(BOT_ID, 412, 200);
        Equip worn = org.mockito.Mockito.mock(Equip.class); // a real Equip needs the item data
        org.mockito.Mockito.when(worn.getPosition()).thenReturn((short) -11);
        org.mockito.Mockito.when(worn.getItemId()).thenReturn(1472068);
        bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(worn);
        Map<Integer, Integer> pastSkills = new HashMap<>();
        bot.getSkills().forEach((s, e) -> pastSkills.put(s.getId(), (int) e.skillevel));
        assertFalse(pastSkills.isEmpty());
        BotRebirth.certainFor(60_000);
        long now = System.currentTimeMillis();

        assertEquals(-1, BotRebirth.tripMap(bot, now), "1st boss drop");
        assertEquals(1, etc(bot, 4032133));
        assertEquals(-1, BotRebirth.tripMap(bot, now), "2nd boss drop");
        assertEquals(1, etc(bot, 4001094));
        assertEquals(BotRebirth.NPC_MAP, BotRebirth.tripMap(bot, now), "crafted, off to Wyatt");
        assertEquals(0, etc(bot, 4032133) + etc(bot, 4001094), "the drops went into the key");
        assertEquals(1, etc(bot, 4033446));
        assertEquals(BotRebirth.NPC_MAP, BotRebirth.tripMap(bot, now));
        assertEquals(1, etc(bot, 4033446), "no second key, no more drops while it holds one");
        assertEquals(0, etc(bot, 4032133) + etc(bot, 4001094));

        BotRebirth.Result r = BotRebirth.rebirthHere(bot, now, false);
        assertTrue(r.done(), r.text());
        assertEquals(1, bot.getReborns());
        assertEquals(1, bot.getLevel());
        assertEquals(0, bot.getExp());
        assertTrue(Set.of(0, 1000, 2000).contains(bot.getJob().getId()), "reborn as a beginner");
        assertEquals(0, etc(bot, 4033446), "the rebirth took the key");
        assertEquals(3, bot.getExpRate(), "REBIRTH_FIRST_RATE");
        BotBuild.State fresh = BotBuild.plan(bot.getJob().getId(), 1,
                BotBuild.alongLineOf(bot.getJob().getId(), BotProgression.choiceFor(bot)), BotBuild.SkillCatalog.PLAN);
        assertEquals(fresh.str, bot.getStr());
        assertEquals(fresh.luk, bot.getLuk());
        assertEquals(fresh.dex, bot.getDex());
        assertEquals(1, BotProgression.settledLevel(bot.getId()));
        Map<Integer, Integer> now2 = new HashMap<>();
        bot.getSkills().forEach((s, e) -> now2.put(s.getId(), (int) e.skillevel));
        pastSkills.forEach((id, lv) -> assertEquals(lv, now2.get(id), "past skill " + id + " kept"));
        assertSame(worn, bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11), "its gear stays on");

        // it levels again: the build keeps its past-life skills
        bot.setLevel(35);
        BotProgression.afterOffscreenLevels(bot, 1);
        Map<Integer, Integer> later = new HashMap<>();
        bot.getSkills().forEach((s, e) -> later.put(s.getId(), (int) e.skillevel));
        pastSkills.forEach((id, lv) -> assertTrue(later.getOrDefault(id, 0) >= lv, "past skill " + id + " still"));
        assertTrue(bot.getJob().getId() != 0 || bot.getJob().getId() == 0 && bot.getLevel() < 10);
    }

    @Test
    void theDailyPaceStopsTheNextBotAndItKeepsItsKey() {
        Character first = bot(BOT_ID, 412, 200);
        Character second = bot(BOT_ID + 1, 122, 200);
        long now = System.currentTimeMillis();
        for (Character c : new Character[]{first, second}) {
            assertTrue(BotHoldings.grantItem(c, new Item(4033446, (short) 0, (short) 1)));
        }
        assertTrue(BotRebirth.rebirthHere(first, now, false).done());
        assertEquals(-1, BotRebirth.tripMap(second, now), "no trip while today's pace is used up");
        BotRebirth.Result r = BotRebirth.rebirthHere(second, now, false);
        assertFalse(r.done());
        assertTrue(r.text().contains("used up"), r.text());
        assertEquals(1, etc(second, 4033446));
        assertEquals(0, second.getReborns());
        assertEquals(200, second.getLevel());
    }

    @Test
    void refusedWithoutKeyLevelOrSwitch() {
        Character bot = bot(BOT_ID, 412, 199);
        long now = System.currentTimeMillis();
        assertTrue(BotHoldings.grantItem(bot, new Item(4033446, (short) 0, (short) 1)));
        assertFalse(BotRebirth.rebirthHere(bot, now, false).done(), "level 199");
        Character noKey = bot(BOT_ID + 1, 412, 200);
        assertFalse(BotRebirth.rebirthHere(noKey, now, false).done(), "no key");
        assertEquals(1, BotRebirth.paceLeft(now), "a refusal spends no pace");
        YamlConfig.config.server.BOT_REBIRTH_ENABLED = false;
        assertEquals(-1, BotRebirth.tripMap(noKey, now));
        assertFalse(BotRebirth.rebirthHere(noKey, now, false).done());
    }

    @Test
    void thirdRebirthOpensGrandisAndRatesFollowTheCount() {
        assertEquals(3, BotRebirth.rateFor(1, 1));
        assertEquals(2, BotRebirth.rateFor(2, 1));
        assertEquals(1, BotRebirth.rateFor(3, 50));
        assertEquals(0, BotRebirth.rateFor(0, 50));
        Character bot = bot(BOT_ID, 412, 1);
        bot.setBotReborns(3);
        assertEquals(255, bot.getMaxClassLevel());
        assertEquals(Stage.MAXED, BotRebirth.stageOf(bot, NOW));
    }
}

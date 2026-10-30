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
import soloMapling.ArtificialPlayer.BotRebirthQa;
import soloMapling.ArtificialPlayer.BotProgressionSystem.RebirthBosses.Boss;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 161-rebirth3 (TEST b034, QA run 3): t-rb-1 showed "mesos 16231495 -> 6231495" with no rebirth. The 10m was NPC
 * 2084000's 4th-job skill package, paid when the QA raised a 3rd-job bot to 200 - not the rebirth - and the QA's restore
 * never paid it back, so every QA run cost its bot 10m. The rebirth path itself never moves mesos. t-rb-2 timed out
 * because 1% of Von Leon is 75M HP and that bot did 51,681 a second alone (24 minutes).
 */
class RebirthQaWalletTest {

    private static final int BOT_ID = 1_000_000_000 + 7601;
    private Map<Integer, Skill> previousSkills;
    private boolean useRebirth, botRebirth;
    private int perDay;
    private OffscreenBossRun.Live realLive;

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
        BotRebirth.resetForTest();
        RebirthBossRuns.resetForTest();
        realLive = OffscreenBossRun.live;
        OffscreenBossRun.live = new RebirthKeysEarnedTest.FakeLive();
    }

    @AfterEach
    void tearDown() throws Exception {
        OffscreenBossRun.live = realLive;
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        field.set(null, previousSkills);
        BotProgression.useCatalogForTest(null);
        BotProgression.forget(BOT_ID);
        var s = YamlConfig.config.server;
        s.USE_REBIRTH_SYSTEM = useRebirth;
        s.BOT_REBIRTH_ENABLED = botRebirth;
        s.BOT_REBIRTH_PER_DAY = perDay;
        BotRebirth.resetForTest();
        RebirthBossRuns.resetForTest();
        RebirthBossRuns.liveForTest(true);
    }

    private static Character bot(int job, int level, int meso) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(BOT_ID);
        chr.setName("Wallet1");
        chr.setJob(Job.getById(job));
        chr.setLevel(level);
        chr.setBotReborns(0);
        BotProgression.buildNew(chr);
        assertTrue(BotHoldings.grantMeso(chr, meso));
        return chr;
    }

    private static BotRebirthQa.Before before(Character bot) {
        return new BotRebirthQa.Before(bot, bot.getLevel(), bot.getExp(), bot.getJob().getId(), bot.getReborns(),
                BotProgression.snapshot(bot), bot.getExpRate(), bot.getMeso(), 0, new LinkedHashMap<>(),
                new LinkedHashMap<>(), new java.util.EnumMap<>(server.expeditions.ExpeditionBossLog.BossLogEntry.class),
                0, ConcurrentHashMap.newKeySet(), new ConcurrentHashMap<>(), new AtomicLong(bot.getMeso()));
    }

    @Test
    void theQaPaysBackTheRaisesFourthJobPackage() {
        Character bot = bot(411, 120, 16_231_495);
        BotRebirthQa.Before b = before(bot);
        assertTrue(BotRebirthQa.raise(b));
        // on TEST the raise's 3rd -> 4th job bought NPC 2084000's package (this test's skill catalog has none to buy)
        if (bot.getMeso() == 16_231_495) {
            assertTrue(BotHoldings.spendMeso(bot, BotSkillPlan.FOURTH_JOB_PACKAGE_MESOS));
        }
        assertEquals(16_231_495 - BotSkillPlan.FOURTH_JOB_PACKAGE_MESOS, bot.getMeso());
        String done = BotRebirthQa.restore(b);
        assertEquals(16_231_495, bot.getMeso(), done);
        assertEquals(120, bot.getLevel());
    }

    @Test
    void theRebirthPathNeverMovesMesos() {
        Character bot = bot(412, 200, 5_000_000);
        long wallet = bot.getMeso();
        BotRebirth.certainFor(60_000);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 40 && bot.getReborns() == 0; i++) {
            if (BotRebirth.tripMap(bot, now) == BotRebirth.NPC_MAP) BotRebirth.rebirthHere(bot, now, false);
        }
        assertEquals(1, bot.getReborns(), "the off-screen runs (a strong fake group) earned the key");
        assertEquals(wallet, bot.getMeso(), "boss runs, the craft and the rebirth cost nothing");
        assertTrue(BotHoldings.grantItem(bot, new Item(Boss.PINKBEAN.clearItem, (short) 0, (short) 1)));
    }

    /**
     * QA run 4 (TEST d520c48b1, 2026-10-29 23:55 UTC): t-rb-2's bot (urreaper, bother chance .595, this week's roll for
     * its 2nd rebirth .695) held both Red-stone items after the Von Leon clear but never crafted: finishRebirth ran with
     * the pace forced but not the wish, so the bot was NOT_BOTHERED. Its walk is the QA's, as rb-1's is.
     */
    @Test
    void finishRebirthCraftsAndRebirthsABotThatWouldNotBotherThisWeek() {
        long now = System.currentTimeMillis();
        int id = BOT_ID;
        while (BotRebirth.bothers(id, 1, BotRebirth.botherChance(
                soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile.forCharacter(id)), now)) id++;
        Character bot = bot(412, 200, 1_000_000);
        bot.setID(id);
        bot.setBotReborns(1);
        for (int item : BotRebirth.ingredientsFor(1)) {
            assertTrue(BotHoldings.grantItem(bot, new Item(item, (short) 0, (short) 1)));
        }
        assertEquals(BotRebirth.Stage.NOT_BOTHERED, BotRebirth.stageOf(bot, now), "the staged bot would not bother on its own");
        BotRebirthQa.Before b = new BotRebirthQa.Before(bot, 200, 0, 412, 1, BotProgression.snapshot(bot),
                bot.getExpRate(), bot.getMeso(), 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                new java.util.EnumMap<>(server.expeditions.ExpeditionBossLog.BossLogEntry.class), 0,
                ConcurrentHashMap.newKeySet(), new ConcurrentHashMap<>(), new AtomicLong(bot.getMeso()));
        var ev = BotRebirthQa.finishRebirth(b);
        assertEquals(2, bot.getReborns(), String.join(" | ", ev));
        for (int item : BotRebirth.consumedFor(1)) assertEquals(0, bot.getItemQuantity(item, false), "used up " + item);
        BotProgression.forget(id);
    }

    @Test
    void theStagedVonLeonDiesInMinutesWhateverTheBot() {
        long hp = 7_500_000_000L;
        double weak = BotRebirthQa.qaBossHpForTest(51_681, hp);       // TEST's Dark Knight, b034 run 3
        assertTrue(weak * hp / 51_681 <= 130, "solo kill in about 2 minutes, was 24 at 1%: " + weak);
        double strong = BotRebirthQa.qaBossHpForTest(366_600, hp);    // TEST's Marksman, run 2
        assertTrue(strong <= 0.01 && strong * hp / 366_600 <= 130);
        assertEquals(0.01, BotRebirthQa.qaBossHpForTest(0, hp), 1e-12, "no estimate: the old 1%");
    }
}

package soloMapling.ArtificialPlayer.BotProgressionSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.expeditions.ExpeditionBossLog.BossLogEntry;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotProgressionSystem.RebirthBosses.Boss;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.6.1 ticket 30, promise audit ("they earn the key item from boss drops"): a regular's key items come from boss runs
 * on the players' rules, not a flat roll. Before this, a bot that bothered got a boss item from a 35% roll every 6 h
 * with no boss, no clear limit and no regard for whether it could kill the boss at all.
 */
class RebirthKeysEarnedTest {

    private static final int BOT_ID = 1_000_000_000 + 7401;

    /** The live game's answers for these tests. */
    static final class FakeLive implements OffscreenBossRun.Live {
        boolean entryLeft = true;
        double dps = 10_000_000;
        long hp = 1_000_000_000L;
        List<Double> drops = List.of();
        int[] droppers = new int[0];
        double kpm = 10;
        final List<String> logged = new ArrayList<>();

        public boolean entryLeft(Character bot, Boss boss) { return entryLeft; }
        public void logClear(Character bot, Boss boss) { logged.add(bot.getId() + ":" + boss.logEntry()); }
        public double botDps(Character bot, Boss boss) { return dps; }
        public long bossHp(Boss boss) { return hp; }
        public List<Double> dropChances(Character bot, int itemId, int[] d) { return drops; }
        public double killsPerMinute(Character bot, int mobId) { return kpm; }
        public int[] droppers(int itemId) { return droppers; }
    }

    private FakeLive fake;
    private OffscreenBossRun.Live realLive;
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
        previousSkills = swapSkillFactory(skills);
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
        fake = new FakeLive();
        OffscreenBossRun.live = fake;
        BotRebirth.certainFor(60_000); // bothers, and an off-screen run need not wait its turn: the outcome is real
    }

    @AfterEach
    void tearDown() throws Exception {
        OffscreenBossRun.live = realLive;
        swapSkillFactory(previousSkills);
        BotProgression.useCatalogForTest(null);
        for (int i = 0; i < 4; i++) BotProgression.forget(BOT_ID + i);
        var s = YamlConfig.config.server;
        s.USE_REBIRTH_SYSTEM = useRebirth;
        s.BOT_REBIRTH_ENABLED = botRebirth;
        s.BOT_REBIRTH_PER_DAY = perDay;
        BotRebirth.resetForTest();
        RebirthBossRuns.resetForTest();
        RebirthBossRuns.liveForTest(true);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Skill> swapSkillFactory(Map<Integer, Skill> skills) throws Exception {
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        Map<Integer, Skill> previous = (Map<Integer, Skill>) field.get(null);
        field.set(null, skills);
        return previous;
    }

    private static Character bot(int id, int job, int level, int reborns) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName("Seeker" + (id % 10));
        chr.setJob(Job.getById(job));
        chr.setLevel(level);
        chr.setBotReborns(reborns);
        BotProgression.buildNew(chr);
        return chr;
    }

    private static int etc(Character c, int id) {
        return c.getInventory(InventoryType.ETC).countById(id);
    }

    private static void give(Character c, int id) {
        assertTrue(BotHoldings.grantItem(c, new Item(id, (short) 0, (short) 1)));
    }

    // ---------------------------------------------------------------- the players' rules, off screen

    @Test
    void noBossItemWithoutAClearLeftToday() {
        Character bot = bot(BOT_ID, 412, 200, 0);
        fake.entryLeft = false;
        for (int i = 0; i < 20; i++) assertEquals(-1, BotRebirth.tripMap(bot, System.currentTimeMillis()));
        assertEquals(0, etc(bot, Boss.ZAKUM.clearItem), "the same daily clear limit a player has");
        assertTrue(BotRebirth.lastOutcome(bot.getId()).contains("clears left"), BotRebirth.lastOutcome(bot.getId()));
        assertTrue(fake.logged.isEmpty());
    }

    @Test
    void aGroupThatCannotKillTheBossInTimeNeverClears() {
        Character bot = bot(BOT_ID, 412, 200, 0);
        fake.dps = 1_000;                // 6 x 1,000 a second against 1B HP: 46 hours against a 2 hour limit
        for (int i = 0; i < 20; i++) BotRebirth.tripMap(bot, System.currentTimeMillis());
        assertEquals(0, etc(bot, Boss.ZAKUM.clearItem), "no flat chance: the boss has to die");
        assertTrue(BotRebirth.lastOutcome(bot.getId()).contains("not cleared"), BotRebirth.lastOutcome(bot.getId()));
        assertTrue(fake.logged.isEmpty(), "no clear, no boss-log row");
    }

    @Test
    void aClearCountsTowardTheLimitAndHandsTheClearReward() {
        Character bot = bot(BOT_ID, 412, 200, 0);
        // 6 x 10M a second against 1B HP: under 17 s, so it clears 9 runs in 10
        for (int i = 0; i < 40 && etc(bot, Boss.ZAKUM.clearItem) == 0; i++) {
            BotRebirth.tripMap(bot, System.currentTimeMillis());
        }
        assertEquals(1, etc(bot, Boss.ZAKUM.clearItem));
        assertTrue(fake.logged.contains(BOT_ID + ":" + BossLogEntry.ZAKUM), fake.logged.toString());
        assertTrue(BotRebirth.lastOutcome(bot.getId()).contains("clear reward"));
    }

    @Test
    void theBossDropRowsAreRolledOnAClear() {
        Character bot = bot(BOT_ID, 412, 200, 0);
        fake.drops = List.of(1.0);
        OffscreenBossRun.Outcome o = OffscreenBossRun.bossRun(bot, Boss.HORNTAIL, () -> 0.0);
        assertTrue(o.cleared() && o.gotItem());
        assertTrue(o.text().contains("off the floor"), o.text());
    }

    @Test
    void theClearChanceFollowsTheEventsTimeLimit() {
        assertEquals(0.9, OffscreenBossRun.clearChance(10, 120), 1e-9, "half the limit or less: 9 in 10");
        assertEquals(0.9, OffscreenBossRun.clearChance(60, 120), 1e-9);
        assertEquals(0.45, OffscreenBossRun.clearChance(90, 120), 1e-9);
        assertEquals(0, OffscreenBossRun.clearChance(120, 120), 1e-9);
        assertEquals(0, OffscreenBossRun.clearChance(Double.POSITIVE_INFINITY, 120), 1e-9);
        Character weak = bot(BOT_ID, 412, 40, 0);
        assertFalse(OffscreenBossRun.bossRun(weak, Boss.HORNTAIL, () -> 0.0).attempted(), "Horntail takes level 100");
    }

    @Test
    void theEmblemIsHuntedFromItsDroppersOrTheQuest() {
        Character bot = bot(BOT_ID, 412, 200, 2);
        assertEquals(RebirthBosses.CYGNUS_EMBLEM, BotRebirth.nextItem(bot), "the stronghold's pass before Cygnus");
        fake.kpm = 3;   // 90 kills in 30 min: quest 31126's 80
        OffscreenBossRun.Outcome quest = OffscreenBossRun.emblemHunt(bot, () -> 0.5);
        assertTrue(quest.gotItem(), quest.text());
        fake.kpm = 2;   // 60 kills: not enough
        assertFalse(OffscreenBossRun.emblemHunt(bot, () -> 0.0).gotItem());
        fake.droppers = new int[]{8610000};
        fake.drops = List.of(0.0001);
        fake.kpm = 10;  // 300 kills at 0.01%: about 3%
        assertFalse(OffscreenBossRun.emblemHunt(bot, () -> 0.5).gotItem());
        assertTrue(OffscreenBossRun.emblemHunt(bot, () -> 0.01).gotItem());
    }

    // ---------------------------------------------------------------- real runs

    @Test
    void vonLeonsItemComesFromARealRunNotARoll() {
        Character bot = bot(BOT_ID, 412, 200, 1);
        give(bot, Boss.PINKBEAN.clearItem);
        assertEquals(Boss.VONLEON.clearItem, BotRebirth.nextItem(bot));
        assertEquals(-1, BotRebirth.tripMap(bot, System.currentTimeMillis()));
        assertEquals(0, etc(bot, Boss.VONLEON.clearItem), "nothing in the bag before the boss dies");
        assertTrue(RebirthBossRuns.holds(bot.getId()), "it went into a run");
        RebirthBossRuns.Run run = RebirthBossRuns.runOf(bot.getId());
        assertEquals(Boss.VONLEON, run.boss());
        assertTrue(fake.logged.isEmpty(), "no off-screen run for a boss bots run themselves");
        assertTrue(RebirthBossRuns.waitingOn(bot.getId(), Boss.VONLEON) || RebirthBossRuns.holds(bot.getId()));

        // the boss dies in the room: the event clears and the bot is handed the item, once
        RebirthBossRuns.onClear("VonLeonBattle", List.of(bot));
        assertEquals(1, etc(bot, Boss.VONLEON.clearItem));
        assertEquals("clear reward", run.gotBy(bot.getId()));
        RebirthBossRuns.onClear("VonLeonBattle", List.of(bot));
        assertEquals(1, etc(bot, Boss.VONLEON.clearItem), "one of each");
        RebirthBossRuns.endForQa(run, "test");
        assertFalse(RebirthBossRuns.holds(bot.getId()), "the run lets it go");

        assertEquals(BotRebirth.NPC_MAP, BotRebirth.tripMap(bot, System.currentTimeMillis()), "key crafted, to Wyatt");
        assertEquals(1, etc(bot, BotRebirth.keyItemFor(1)));
    }

    @Test
    void aGroupTooWeakForTheBossDoesNotSetOut() {
        Character bot = bot(BOT_ID, 412, 200, 1);
        give(bot, Boss.PINKBEAN.clearItem);
        fake.dps = 10_000;           // alone, 7.5B HP would take days
        fake.hp = 7_500_000_000L;
        BotRebirth.tripMap(bot, System.currentTimeMillis());
        assertFalse(RebirthBossRuns.holds(bot.getId()));
        assertEquals(0, etc(bot, Boss.VONLEON.clearItem));
        assertTrue(RebirthBossRuns.minutesToKill(List.of(bot), Boss.VONLEON, 1.0) > Boss.VONLEON.timeLimitMinutes);
    }

    @Test
    void cygnusNeedsTheEmblemAsTheStrongholdDoorDoes() {
        Character bot = bot(BOT_ID, 412, 200, 2);
        assertFalse(RebirthBossRuns.mayGo(bot, Boss.CYGNUS));
        give(bot, RebirthBosses.CYGNUS_EMBLEM);
        assertTrue(RebirthBossRuns.mayGo(bot, Boss.CYGNUS));
        Character noLkc = bot(BOT_ID + 1, 412, 200, 0);
        assertFalse(RebirthBossRuns.mayGo(noLkc, Boss.VONLEON), "Lion King's Castle needs a rebirth");
    }

    @Test
    void zakumSeekersWaitToJoinAPlayersExpedition() {
        Character bot = bot(BOT_ID, 412, 200, 0);
        BotRebirth.tripMap(bot, System.currentTimeMillis());
        assertTrue(RebirthBossRuns.waitingOn(bot.getId(), Boss.ZAKUM));
        assertFalse(RebirthBossRuns.holds(bot.getId()), "bots do not run Zakum's altar on their own");
    }

    @Test
    void aClearHandsEachBotInTheRoomItsItemAndLeavesPlayersToPlunk() {
        Character a = bot(BOT_ID, 412, 200, 0);
        Character b = bot(BOT_ID + 1, 112, 200, 0);
        give(b, Boss.HORNTAIL.clearItem);
        Character player = org.mockito.Mockito.mock(Character.class); // a real player's client is no BotClient
        RebirthBossRuns.onClear("HorntailBattle", List.of(a, b, player));
        assertEquals(1, etc(a, Boss.HORNTAIL.clearItem));
        assertEquals(1, etc(b, Boss.HORNTAIL.clearItem), "it held one already");
        org.mockito.Mockito.verify(player, org.mockito.Mockito.never()).getInventory(org.mockito.ArgumentMatchers.any());
        RebirthBossRuns.onClear("KerningPQ", List.of(a));
        assertEquals(0, etc(a, Boss.ZAKUM.clearItem));
    }

    @Test
    void aRunForAnotherBossDoesNotTakeABotThatIsHeld() {
        Character bot = bot(BOT_ID, 412, 200, 1);
        assertTrue(RebirthBossRuns.request(bot, Boss.VONLEON, System.currentTimeMillis()));
        assertTrue(RebirthBossRuns.request(bot, Boss.VONLEON, System.currentTimeMillis()), "asked again: still in");
        assertEquals(1, RebirthBossRuns.liveCount());
        Character other = bot(BOT_ID + 2, 112, 200, 1);
        assertTrue(RebirthBossRuns.request(other, Boss.VONLEON, System.currentTimeMillis()));
        assertSame(RebirthBossRuns.runOf(bot.getId()), RebirthBossRuns.runOf(other.getId()), "joins the gathering run");
        assertEquals(1, RebirthBossRuns.liveCount());
    }

    // ---------------------------------------------------------------- the tables match the scripts

    @Test
    void theBossTableIsTheScripts() throws Exception {
        String plunk = Files.readString(Path.of("scripts/npc/3004006.js"));
        String pinkExit = Files.readString(Path.of("scripts/npc/2141002.js"));
        String craft = Files.readString(Path.of("scripts/npc/craft.js"));
        for (Boss b : Boss.values()) {
            String ev = Files.readString(Path.of("scripts/event/" + b.script + ".js"));
            assertTrue(ev.contains("recruitMap = " + b.recruitMap), b + " recruit map");
            assertTrue(ev.contains("exitMap = " + b.exitMap), b + " exit map");
            assertTrue(ev.contains("mobid == " + b.clearMob), b + " clear mob");
            assertTrue(Pattern.compile("eventTime = " + b.timeLimitMinutes + ";").matcher(ev).find(), b + " limit");
            assertTrue(ev.contains("setEventCleared(ExpeditionType." + b.type.name() + ")"), b + " clears as " + b.type);
            assertTrue(craft.contains("cm.haveItem(" + b.clearItem + ")"), b + " item is a key ingredient");
            if (b == Boss.PINKBEAN) {
                assertTrue(pinkExit.contains("cm.gainItem(" + b.clearItem + ", 1)"));
            } else {
                int caseAt = plunk.indexOf("case " + b.roomMap + ":");
                assertTrue(caseAt > 0, b + " room in Plunk");
                assertTrue(plunk.indexOf("playerInteraction.gainItem(" + b.clearItem + ", 1)", caseAt) > caseAt, b.name());
            }
            if (b.botsRunIt) {
                String npc = Files.readString(Path.of("scripts/npc/" + b.summonNpc + ".js"));
                assertTrue(npc.contains("spawnMonsterOnGroundBelow(" + b.summonMob + ", " + b.summonX + ", "
                        + b.summonY + ")"), b + " summon");
                assertTrue(npc.contains("destroyNPC(" + b.summonNpc + ")"));
                assertTrue(npc.contains("killMonster(" + RebirthBosses.SUMMON_PLACEHOLDER_MOB + ")"));
            }
        }
        assertEquals(25, Boss.ZAKUM.logEntry().getEntries());
        assertEquals(18, Boss.VONLEON.logEntry().getEntries());
        assertEquals(16, Boss.CYGNUS.logEntry().getEntries());
        for (int r = 0; r < 3; r++) {
            int bosses = 0;
            for (int id : BotRebirth.ingredientsFor(r)) if (Boss.byItem(id) != null) bosses++;
            assertEquals(r == 2 ? 1 : 2, bosses, "rebirth " + (r + 1) + ": the emblem is the one non-boss item");
        }
    }

    @Test
    void bossRewardLoopsSkipBotsInsteadOfThrowing() throws Exception {
        String plunk = Files.readString(Path.of("scripts/npc/3004006.js"));
        int loops = plunk.split("players\\.forEach\\(\\(chr, index\\) => \\{", -1).length - 1;
        int guarded = plunk.split("players\\.forEach\\(\\(chr, index\\) => \\{\\s*if \\(chr\\.isBot\\(\\)\\) return;", -1)
                .length - 1;
        assertTrue(loops > 0);
        assertEquals(loops, guarded, "every Plunk hand-out skips a bot (it has no client: gainItem threw)");
        for (String ev : List.of("ZakumBattle", "HorntailBattle", "PinkBeanBattle", "VonLeonBattle", "CygnusBattle")) {
            String s = Files.readString(Path.of("scripts/event/" + ev + ".js"));
            assertTrue(s.contains("isBot()"), ev + "'s leaf loop skips bots");
        }
    }
}

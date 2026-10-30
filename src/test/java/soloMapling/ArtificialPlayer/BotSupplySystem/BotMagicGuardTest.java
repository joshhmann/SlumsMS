package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.skills.Magician;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ticket 161 item 4: Magic Guard splits a bot's damage between HP and MP the way nutnnut/Cosmic 930ca5a01 does. Our
 * BotCombatFormula is the OUTGOING damage and has no part in it; the hit a bot takes goes through BotVitals.hurt, which
 * took the whole hit off HP before this.
 */
class BotMagicGuardTest {

    private static final AtomicInteger ids = new AtomicInteger(1_000_000_000 + 4000);
    private Map<Integer, Skill> previousSkills;

    @BeforeEach
    void data() throws Exception {
        SupplyData.useForTest(new SupplyData.Source() {
            @Override public SupplyData.Recovery recovery(int itemId) { return null; }
            @Override public int bulletCount(int skillId, int level) { return 1; }
            @Override public int bulletConsume(int skillId, int level) { return 0; }
            @Override public int mpCon(int skillId, int level) { return 0; }
            @Override public int hpCon(int skillId, int level) { return 0; }
            @Override public int x(int skillId, int level) { return skillId == Magician.MAGIC_GUARD ? 50 : 0; }
            @Override public int duration(int skillId, int level) { return 0; }
            @Override public int projectileWatk(int itemId) { return 0; }
        });
        Map<Integer, Skill> skills = new HashMap<>();
        skills.put(Magician.MAGIC_GUARD, new Skill(Magician.MAGIC_GUARD));
        previousSkills = swapSkillFactory(skills);
    }

    @AfterEach
    void restore() throws Exception {
        SupplyData.useForTest(null);
        swapSkillFactory(previousSkills);
        System.clearProperty("slums.bots.magicguard");
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Skill> swapSkillFactory(Map<Integer, Skill> skills) throws Exception {
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        Map<Integer, Skill> previous = (Map<Integer, Skill>) field.get(null);
        field.set(null, skills);
        return previous;
    }

    private static Character mage(int maxHp, int maxMp, boolean holdsMagicGuard) throws Exception {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(ids.incrementAndGet());
        chr.setJob(Job.MAGICIAN);
        chr.setLevel(30);
        for (String f : new String[]{"maxhp", "localmaxhp", "clientmaxhp"}) setInt(chr, f, maxHp);
        for (String f : new String[]{"maxmp", "localmaxmp", "clientmaxmp"}) setInt(chr, f, maxMp);
        chr.updateHpMp(maxHp, maxMp);
        if (holdsMagicGuard) {
            chr.changeSkillLevel(SkillFactory.getSkill(Magician.MAGIC_GUARD), (byte) 10, 20, -1);
        }
        return chr;
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(target, value);
                return;
            } catch (NoSuchFieldException e) {
                // look further up
            }
        }
        throw new NoSuchFieldException(name);
    }

    @Test
    void theSplitIsTheServersAndUpstreams() {
        assertArrayEquals(new int[]{50, 50}, BotVitals.magicGuardSplit(100, 50, 1000));
        assertArrayEquals(new int[]{85, 15}, BotVitals.magicGuardSplit(100, 15, 1000));
        assertArrayEquals(new int[]{0, 100}, BotVitals.magicGuardSplit(100, 100, 1000));
        assertArrayEquals(new int[]{50 + 30, 20}, BotVitals.magicGuardSplit(100, 50, 20), "MP short: the rest falls on HP");
        assertArrayEquals(new int[]{100, 0}, BotVitals.magicGuardSplit(100, 50, 0));
    }

    @Test
    void aGuardedBotPaysPartOfAHitInMpWhenTheSwitchIsOn() throws Exception {
        System.setProperty("slums.bots.magicguard", "true");
        Character bot = mage(1000, 500, true);
        Map<Integer, Long> cast = Map.of(Magician.MAGIC_GUARD, System.currentTimeMillis() + 60_000);
        int percent = BotVitals.magicGuardPercent(bot, cast, System.currentTimeMillis());
        assertEquals(50, percent);
        int hpPart = BotVitals.magicGuardSplit(200, percent, bot.getMp())[0];
        assertEquals(100, hpPart);
    }

    @Test
    void noGuardWithoutTheSkillOrAfterItRanOut() throws Exception {
        Character without = mage(1000, 500, false);
        long now = System.currentTimeMillis();
        assertEquals(0, BotVitals.magicGuardPercent(without, Map.of(Magician.MAGIC_GUARD, now + 60_000), now));
        Character holds = mage(1000, 500, true);
        assertEquals(0, BotVitals.magicGuardPercent(holds, Map.of(), now), "never cast");
        assertEquals(0, BotVitals.magicGuardPercent(holds, Map.of(Magician.MAGIC_GUARD, now - 1), now), "ran out");
    }

    @Test
    void hurtLeavesTheWholeHitOnHpWhenTheSwitchIsOff() throws Exception {
        Character bot = mage(1000, 500, true);
        BotVitals.hurt(bot, 200);
        assertEquals(800, bot.getHp());
        assertEquals(500, bot.getMp());
    }

    @Test
    void hurtOnAMageThatCastedMagicGuardTakesMpToo() throws Exception {
        System.setProperty("slums.bots.magicguard", "true");
        Character bot = mage(1000, 500, true);
        soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver.markCastForTest(bot.getId(), Magician.MAGIC_GUARD,
                System.currentTimeMillis() + 60_000);
        try {
            BotVitals.hurt(bot, 200);
            assertEquals(900, bot.getHp(), "half of 200 came off HP");
            assertEquals(400, bot.getMp(), "and half off MP");
        } finally {
            soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver.clearBot(bot.getId());
        }
    }
}

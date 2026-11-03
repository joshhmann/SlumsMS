package soloMapling.ArtificialPlayer.BotProgressionSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ticket 181 prog#P3: the server credits level-up skill points to the book of the job the bot has
 * ({@code Character.levelUpGainSp}: {@code gainSp(spGain, GameConstants.getSkillBook(job.getId()), true)}), while the
 * build read and wrote {@code getRemainingSps()[0]} only. Points in any other book were invisible to the build and
 * were copied back untouched by {@code apply}, so the live character and its build kept two different ledgers. The
 * three views - {@code capture}, {@code fromStored}, {@code apply} - now agree on one rule: read the sum of the
 * books, write the whole pool into the current job's book, zero the rest.
 */
class BotProgressionSpBooksTest {

    private static final int BOT_ID = 1_000_000_000 + 4747;
    private static final int BOOKS = 10;

    private Map<Integer, Skill> previousSkills;

    @BeforeEach
    void serverSkillData() throws Exception {
        previousSkills = swapSkillFactory(new HashMap<>());
        BotProgression.useCatalogForTest(BotBuild.SkillCatalog.PLAN);
    }

    @AfterEach
    void restoreSkillData() throws Exception {
        swapSkillFactory(previousSkills);
        BotProgression.useCatalogForTest(null);
        BotProgression.forget(BOT_ID);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Skill> swapSkillFactory(Map<Integer, Skill> skills) throws Exception {
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        Map<Integer, Skill> previous = (Map<Integer, Skill>) field.get(null);
        field.set(null, skills);
        return previous;
    }

    private static Character bot(int level, Job job, int[] books) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(BOT_ID);
        c.setName("SpBooksBot");
        c.setJob(job);
        c.setLevel(level);
        c.applyBotBuild(4, 4, 4, 4, 0, books, 100, 100);
        return c;
    }

    private static int[] books(int... byBook) {
        int[] out = new int[BOOKS];
        System.arraycopy(byBook, 0, out, 0, byBook.length);
        return out;
    }

    @Test
    void captureReadsEveryBook() {
        // a 3rd-job bot whose 270 points the server credited to a book other than the first
        Character bot = bot(100, Job.CRUSADER, books(0, 0, 270));
        assertEquals(270, BotProgression.capture(bot).sp, "the pool is every book, not the first one");
    }

    @Test
    void aStoredRowIsReadWhole() {
        BotRealStats stored = new BotRealStats(4, 4, 4, 4, 100, 100, 100, 100, 0, books(0, 0, 270), 100,
                new TreeMap<>());
        assertEquals(270, BotProgression.fromStored(Job.CRUSADER.getId(), 100, stored).sp,
                "a stored row's points count whichever book they were saved in");
    }

    @Test
    void applyWritesThePoolIntoTheCurrentBookAndZeroesTheRest() {
        Character bot = bot(100, Job.CRUSADER, books(0, 0, 270));
        BotBuild.State state = BotProgression.capture(bot);

        BotProgression.apply(bot, state, false);

        // Job.CRUSADER is book 0 (GameConstants.getSkillBook); the pool is there once, and book 2 no longer has a copy
        assertArrayEquals(books(270), bot.getRemainingSps(),
                "the same 270 points must not sit in the build's pool and in the game's book 2 at once");
    }
}

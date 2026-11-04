package soloMapling.ArtificialPlayer;

import client.Character;
import com.esotericsoftware.yamlbeans.YamlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * 1.6.4 run chatter (owner 2026-11-03: "outcomes once bots exit out they say things like woo we beat or dang we tried
 * but failed"): what a bot has to say about the content run it just left, and how it says it.
 *
 * <p>The run ends on the server tick, and chat has its own pacing, so the run says nothing: it leaves one
 * {@link Pending} line in the bot's {@link Slot} ({@link BotSM#runOutcome()}), once, when the run ends. The bot's own
 * macro tick (every bot type runs {@link BotSM#updateState()}) then says it at its next natural opportunity, the way
 * every other ambient line is said: only where a real player can see the map ({@link GCMovement#isMapObserved}) and
 * only if the ambient budget lets it through ({@link SocialCommands#tryAmbientSpeak}: the per-map gap, the world's
 * lines a minute, the say-do and wedding rules, then {@code SpeechGuard} and {@code RepeatGuard} inside the speak). A
 * line the budget refuses stays and is tried again on the next tick; a line nobody saw by {@link #TTL_MS} is dropped,
 * because a win line two minutes late reads as a bot talking to itself. Said once, then gone.
 *
 * <p>The lines are the pools of {@code BotDialoguePack/RunOutcomeDialogue.yaml}; the run's content name is put in when
 * the run ends ({@link #lines}), so a line holds no token by the time it reaches chat.
 */
public final class BotRunOutcome {

    private static final Logger log = LoggerFactory.getLogger(BotRunOutcome.class);

    /** How long a recorded line stays worth saying: past this the run is old news and the line is dropped unsaid. */
    public static final long TTL_MS = 120_000;

    static final String FILE = "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/RunOutcomeDialogue.yaml";
    public static final String CONTENT_TOKEN = "{CONTENT}";

    /** The four pools of the pack: a party quest or a boss, won or lost. */
    public enum Pool {
        WIN_PQ("WinPq"), WIN_BOSS("WinBoss"), LOSS_PQ("LossPq"), LOSS_BOSS("LossBoss");

        final String key;

        Pool(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Pool of(boolean won, boolean boss) {
            return won ? (boss ? WIN_BOSS : WIN_PQ) : (boss ? LOSS_BOSS : LOSS_PQ);
        }
    }

    /** One line to say, and when it goes stale. */
    public record Pending(String line, long expiresAtMs) {
    }

    /** A bot's one place for its pending line: set when its run ends, taken when it speaks. */
    public static final class Slot {
        private final AtomicReference<Pending> pending = new AtomicReference<>();

        /** Leaves {@code p} to be said, replacing a line about an earlier run that was never said. */
        public void set(Pending p) {
            pending.set(p);
        }

        public Pending get() {
            return pending.get();
        }

        boolean take(Pending p) {
            return pending.compareAndSet(p, null);
        }
    }

    private static volatile Map<String, List<String>> pools;

    private BotRunOutcome() {
    }

    /** Every line of a pool as written ({@code {CONTENT}} still in); empty when the pack cannot be read. */
    @SuppressWarnings("unchecked")
    public static List<String> pool(Pool pool) {
        Map<String, List<String>> loaded = pools;
        if (loaded == null) {
            try (FileReader file = new FileReader(soloMapling.DataFiles.file(FILE))) {
                loaded = (Map<String, List<String>>) new YamlReader(file).read();
            } catch (Exception e) {
                log.warn("Could not read {}; bots say nothing about their runs", FILE, e);
            }
            if (loaded == null) {
                loaded = Map.of();
            }
            pools = loaded;
        }
        List<String> lines = loaded.get(pool.key);
        return lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * Up to {@code count} different lines of {@code pool} with the content's name put in, in a random order from
     * {@code rng}. Different, because two bots on one map never say the same line (RepeatGuard drops the second).
     */
    public static List<String> lines(Pool pool, String contentName, int count, Random rng) {
        List<String> all = new ArrayList<>(pool(pool));
        Collections.shuffle(all, rng);
        List<String> out = new ArrayList<>(Math.min(count, all.size()));
        for (String raw : all) {
            if (out.size() >= count) {
                break;
            }
            out.add(raw.replace(CONTENT_TOKEN, contentName));
        }
        return out;
    }

    /** Called from {@link BotSM#updateState()} on every running bot's tick; one volatile read when nothing is pending. */
    public static void tick(BotSM bot) {
        Slot slot = bot.runOutcome();
        if (slot == null || slot.get() == null) {
            return;
        }
        sayIfDue(slot, bot.getChr(), System.currentTimeMillis(), GCMovement::isMapObserved,
                SocialCommands::tryAmbientSpeak);
    }

    /**
     * The decision, with the world behind two seams. Returns whether the line was said now.
     *
     * @param observed whether a real player can see the bot's map
     * @param speak    the ambient speak: false when the budget (or a say-do rule) refused the line this time
     */
    public static boolean sayIfDue(Slot slot, Character chr, long now, Predicate<Character> observed,
                            BiPredicate<Character, String> speak) {
        Pending p = slot.get();
        if (p == null) {
            return false;
        }
        if (now > p.expiresAtMs()) {
            slot.take(p);
            return false;
        }
        if (chr == null || chr.getMap() == null || !chr.isAlive() || !observed.test(chr)) {
            return false;
        }
        if (!speak.test(chr, p.line())) {
            return false;
        }
        slot.take(p);
        return true;
    }

    /** Test seam: re-read the pack. */
    static void reloadForTest() {
        pools = null;
    }
}

package soloMapling.ArtificialPlayer;

import client.Character;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/** Tests in other packages: BotFamily's seams (busy check, the walk over, the answer delay). */
public final class BotFamilyTestSeams {

    private static final List<Runnable> held = new ArrayList<>();
    private static volatile boolean hold;

    private BotFamilyTestSeams() {
    }

    /** {@code busy}: why a regular can't come (null: free); {@code come}: brings it over. Answers run at once. */
    public static void install(BiFunction<Character, Character, String> busy, BiPredicate<Character, Character> come) {
        BotFamily.busyFor = busy;
        BotFamily.comeTo = come;
        hold = false;
        held.clear();
        BotFamily.later = (ms, r) -> {
            if (hold) {
                held.add(r);
            } else {
                r.run();
            }
        };
    }

    /** From now on, answers wait for {@link #runHeldAnswers()}. */
    public static void holdAnswers() {
        hold = true;
    }

    public static void runHeldAnswers() {
        List<Runnable> now = new ArrayList<>(held);
        held.clear();
        now.forEach(Runnable::run);
    }

    public static void reset() {
        BotFamily.busyFor = soloMapling.ArtificialPlayer.BotDramaSystem.DramaKit::whyNotFreeToCome;
        BotFamily.comeTo = soloMapling.ArtificialPlayer.BotDramaSystem.DramaKit::comeWhenCalled;
        BotFamily.later = (ms, r) -> soloMapling.server.BotTiming.after(ms, r);
        held.clear();
        hold = false;
    }

    public static void noteOffer(int botId, int playerId, long at) {
        BotFamily.noteOfferForTest(botId, playerId, at);
    }

    public static void forgetOffer(int botId, int playerId) {
        BotFamily.forgetOffer(botId, playerId);
    }
}

package soloMapling.ArtificialPlayer.BotMegaphoneSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MegaReactionsTest {

    static final class Env implements MegaReactions.Env {
        long now = 10_000_000;
        final Random rng;
        final List<String> said = new ArrayList<>();
        final List<String> whispered = new ArrayList<>();
        final List<Runnable> queued = new ArrayList<>();
        int botsPerMap = 6;
        int nextBot = 1;

        Env(long seed) {
            rng = new Random(seed);
        }

        public long now() { return now; }
        public double roll() { return rng.nextDouble(); }
        public int between(int lo, int hi) { return hi <= lo ? lo : lo + rng.nextInt(hi - lo + 1); }
        public void later(long delayMs, Runnable task) { queued.add(task); }
        public List<Integer> botsNear(int playerId, int n) {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < Math.min(n, botsPerMap); i++) {
                out.add(nextBot++);
            }
            return out;
        }
        public int anyBot(int playerId) { return nextBot++; }
        public void say(int botId, String line) { said.add(line); }
        public boolean whisper(int botId, int playerId, String line) { whispered.add(line); return true; }
        void flush() {
            queued.forEach(Runnable::run);
            queued.clear();
        }
    }

    @Test
    void aboutHalfOfPlayerSmegasGetAReaction() {
        Env env = new Env(42);
        MegaReactions r = new MegaReactions(env);
        int total = 400, reacted = 0;
        for (int i = 0; i < total; i++) {
            env.now += 90_000;   // players do not smega faster than the caps allow
            if (r.react(1000 + i, "P" + i, i % 3 == 0 ? "B> red whip 5m" : i % 3 == 1 ? "S> ilbis 1m" : "ayy lmao", false) > 0) {
                reacted++;
            }
        }
        double rate = reacted / (double) total;
        assertTrue(rate >= 0.40 && rate <= 0.70, "reaction rate " + rate);
        env.flush();
        assertTrue(env.said.size() + env.whispered.size() >= reacted);
    }

    @Test
    void anAnsweredBuyRequestNeedsNoExtraReaction() {
        Env env = new Env(1);
        MegaReactions r = new MegaReactions(env);
        for (int i = 0; i < 50; i++) {
            env.now += 90_000;
            assertEquals(0, r.react(i, "P", "B> red whip", true));
        }
        assertTrue(env.queued.isEmpty());
    }

    @Test
    void capsKeepItFromSpamming() {
        Env env = new Env(7);
        System.setProperty("solomapling.megaReactChance", "1.0");
        try {
            MegaReactions r = new MegaReactions(env);
            // one player shouting again and again: one reaction per gap at most
            int first = r.react(1, "P", "lol", false);
            assertTrue(first >= 1);
            env.now += 5_000;
            assertEquals(0, r.react(1, "P", "lol again", false), "same player inside the gap");
            // many players in one minute: at most 4 reaction rolls pass
            int passed = 0;
            for (int p = 100; p < 130; p++) {
                if (r.react(p, "Q" + p, "haha", false) > 0) {
                    passed++;
                }
            }
            assertTrue(passed <= 4, "passed " + passed);
        } finally {
            System.clearProperty("solomapling.megaReactChance");
        }
    }

    @Test
    void linesVaryAndDoNotRepeatInARow() {
        Env env = new Env(3);
        MegaReactions r = new MegaReactions(env);
        Set<String> seen = new HashSet<>();
        String prev = null;
        for (int i = 0; i < 60; i++) {
            String l = r.pick(MegaReactions.Topic.OTHER);
            assertTrue(!l.equals(prev), "repeated " + l);
            seen.add(l);
            prev = l;
        }
        assertTrue(seen.size() >= 10, "only " + seen.size() + " distinct lines");
    }

    @Test
    void topicsPickMatchingLines() {
        assertEquals(MegaReactions.Topic.BUY, MegaReactions.topicOf("B> red whip 5m"));
        assertEquals(MegaReactions.Topic.SELL, MegaReactions.topicOf("S> ilbis"));
        assertEquals(MegaReactions.Topic.PARTY, MegaReactions.topicOf("lf pt ludi pq"));
        assertEquals(MegaReactions.Topic.OTHER, MegaReactions.topicOf("happy birthday guys"));
    }

    @Test
    void aBotThatJustSpokeRestsAndNobodyNearMeansAtMostAWhisper() {
        Env env = new Env(5);
        System.setProperty("solomapling.megaReactChance", "1.0");
        try {
            env.botsPerMap = 0;
            MegaReactions r = new MegaReactions(env);
            int n = 0;
            for (int i = 0; i < 40; i++) {
                env.now += 90_000;
                n += r.react(i, "P", "lol", false);
            }
            env.flush();
            assertTrue(env.said.isEmpty(), "no all-chat with nobody near");
            assertEquals(n, env.whispered.size());
        } finally {
            System.clearProperty("solomapling.megaReactChance");
        }
    }
}

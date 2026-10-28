package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Stage;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleClaimsAndTriesTest {

    private static Stage kpqStage1() {
        return StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR)).content().stream()
                .filter(c -> c.id().equals("kpq")).findFirst().orElseThrow().stage(1).orElseThrow();
    }

    private static RunMember bot(int id, boolean leader) {
        return new RunMember(id, leader, true, MechanicsPersona.AVERAGE);
    }

    @Test
    void kpqLeaderCollectsAndEveryoneElseAnswers() {
        var claims = RoleClaims.claim(kpqStage1(), List.of(bot(1, false), bot(2, true), bot(3, false), bot(4, false)));
        assertTrue(claims.complete(), "unfilled: " + claims.unfilled());
        assertEquals("leader", claims.roleOf().get(2));
        assertEquals("answerer", claims.roleOf().get(1));
        assertEquals("answerer", claims.roleOf().get(3));
        assertEquals("answerer", claims.roleOf().get(4));
    }

    @Test
    void claimsAreTheSameForEveryBotThatComputesThem() {
        List<RunMember> party = List.of(bot(7, true), bot(8, false), bot(9, false));
        assertEquals(RoleClaims.claim(kpqStage1(), party), RoleClaims.claim(kpqStage1(), party));
    }

    @Test
    void noLeaderMeansAnUnfilledRole() {
        var claims = RoleClaims.claim(kpqStage1(), List.of(bot(1, false), bot(2, false), bot(3, false)));
        assertFalse(claims.complete());
        assertTrue(claims.unfilled().get(0).startsWith("leader"));
    }

    @Test
    void perfectBotsNeverTryWrong() {
        Random rnd = new Random(1);
        for (int i = 0; i < 200; i++) {
            assertEquals(List.of(3), TryPlanner.tries(3, 10, MechanicsPersona.PERFECT, 2.0, rnd));
        }
    }

    @Test
    void triesAreDistinctCappedAndEndRight() {
        Random rnd = new Random(42);
        MechanicsPersona chaotic = new MechanicsPersona(0.0, 1.0);
        for (int options = 1; options <= 20; options++) {
            for (int i = 0; i < 50; i++) {
                int correct = rnd.nextInt(options);
                List<Integer> t = TryPlanner.tries(correct, options, chaotic, TryPlanner.tierFactor(MechanicsModel.Tier.MASTER), rnd);
                assertEquals(correct, t.get(t.size() - 1));
                assertEquals(t.size(), new HashSet<>(t).size());
                assertTrue(t.size() - 1 <= Math.min(TryPlanner.MAX_WRONG_TRIES, options - 1));
            }
        }
    }

    @Test
    void carefulBotsTryLessThanChaoticOnes() {
        Random rnd = new Random(7);
        MechanicsPersona careful = new MechanicsPersona(0.9, 0.0);
        MechanicsPersona chaotic = new MechanicsPersona(0.2, 0.8);
        int a = 0, b = 0;
        for (int i = 0; i < 2000; i++) {
            a += TryPlanner.tries(0, 10, careful, 1.0, rnd).size();
            b += TryPlanner.tries(0, 10, chaotic, 1.0, rnd).size();
        }
        assertTrue(a < b, "careful " + a + " vs chaotic " + b);
    }

    @Test
    void masterTierMeansMoreTries() {
        Random r1 = new Random(3), r2 = new Random(3);
        int normal = 0, master = 0;
        for (int i = 0; i < 2000; i++) {
            normal += TryPlanner.tries(0, 10, MechanicsPersona.AVERAGE, TryPlanner.tierFactor(MechanicsModel.Tier.NORMAL), r1).size();
            master += TryPlanner.tries(0, 10, MechanicsPersona.AVERAGE, TryPlanner.tierFactor(MechanicsModel.Tier.MASTER), r2).size();
        }
        assertTrue(normal < master);
    }

    @Test
    void personaAxesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new MechanicsPersona(1.5, 0));
        assertThrows(IllegalArgumentException.class, () -> TryPlanner.tries(5, 3, MechanicsPersona.PERFECT, 1, new Random()));
    }
}

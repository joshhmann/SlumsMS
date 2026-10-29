package client;

import config.YamlConfig;
import net.server.channel.handlers.AcceptFamilyHandler;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.FamilyQa;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TEST 2026-10-29: the first boot of release-1.6.1 stopped on families 4 and 8, whose only rows were a bot junior
 * (ShadowLuv, N1ghtPanda) naming senior 44 (AutoQaOne), a row that no longer existed. Joining makes the junior visible
 * in memory before its row is written; t-fy-4 saw the bot join and dissolved the family (DELETE ... WHERE familyid)
 * in that window, and the junior's INSERT landed after the delete. Now a join and a dissolve never overlap (one lock),
 * and a dissolve deletes juniors first and never a row another row still names as its senior.
 */
class FamilyDissolveSafetyTest {

    static final int QA = 44, BOT = 1_004_268_480;

    @Test
    void theTestBootCaseDeletesTheJuniorBeforeItsSenior() {
        List<Integer> order = Family.safeDeletionOrder(4, List.of(new Family.Row(QA, 4, 0), new Family.Row(BOT, 4, QA)));
        assertEquals(List.of(BOT, QA), order, "junior first, then the senior");
    }

    @Test
    void aRowAnotherFamilyStillNamesAsSeniorIsNeverDeleted() {
        // QA leads family 4 with junior PAL; a row of family 9 (a straggler) names PAL as its senior
        int pal = 45, straggler = 1_000_000_001;
        List<Integer> order = Family.safeDeletionOrder(4, List.of(new Family.Row(QA, 4, 0), new Family.Row(pal, 4, QA),
                new Family.Row(straggler, 9, pal)));
        assertTrue(order.isEmpty(), "PAL is named by a row that stays, so neither PAL nor its senior QA goes: " + order);
        List<Integer> two = Family.safeDeletionOrder(4, List.of(new Family.Row(QA, 4, 0), new Family.Row(pal, 4, QA),
                new Family.Row(BOT, 4, pal)));
        assertEquals(List.of(BOT, pal, QA), two, "a whole chain goes leaves first");
    }

    @Test
    void aDissolveWaitsForAJoinStillBeingWritten() throws Exception {
        boolean was = YamlConfig.config.server.USE_FAMILY_SYSTEM;
        YamlConfig.config.server.USE_FAMILY_SYSTEM = true;
        CountDownLatch joinHolds = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread join = new Thread(() -> Family.underMembershipLock(() -> {
            joinHolds.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return null;
        }));
        join.start();
        assertTrue(joinHolds.await(5, TimeUnit.SECONDS));
        try {
            Character player = mock(Character.class);
            when(player.getWorldServer()).thenReturn(mock(World.class));
            CountDownLatch dissolved = new CountDownLatch(1);
            Thread teardown = new Thread(() -> {
                FamilyQa.teardown(player, new FamilyQa.Staged(null, 4));
                dissolved.countDown();
            });
            teardown.start();
            CountDownLatch answered = new CountDownLatch(1);
            Thread answer = new Thread(() -> {
                AcceptFamilyHandler.answer(mock(Character.class), null, true);
                answered.countDown();
            });
            answer.start();
            assertFalse(dissolved.await(500, TimeUnit.MILLISECONDS), "the dissolve waits for the join in progress");
            assertFalse(answered.await(100, TimeUnit.MILLISECONDS), "another join waits too");
            release.countDown();
            assertTrue(dissolved.await(5, TimeUnit.SECONDS));
            assertTrue(answered.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            join.join(5000);
            YamlConfig.config.server.USE_FAMILY_SYSTEM = was;
        }
    }
}

package net.server.coordinator.partysearch;

import client.Character;
import config.YamlConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ticket 164: ServerConfig.PARTY_SEARCH_REENTRY_LIMIT had no initialiser, so an absent config key left it at the
 * JVM default 0 and PartySearchCoordinator.runPartySearch sent the leader to the expired queue on the very first
 * search that found nobody ({@code reentryCount < 0} is false at once). From there the leader waited in
 * timeoutLeaders until {@code updateCount % 77 == 0}, ~12.8 min at the 10 s PartySearchTask, and the only way out
 * was to stop and restart their party search.
 *
 * <p>{@code searchedJobs} is decoded from the jobs token, and token 0 decodes to no jobs at all, so every
 * runPartySearch() here is an empty search.
 */
class PartySearchReentryLimitTest {

    private static Character loggedInLeader(int id) throws Exception {
        Constructor<Character> ctor = Character.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Character chr = ctor.newInstance();

        Field idField = Character.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.setInt(chr, id);

        Field loggedIn = Character.class.getDeclaredField("loggedIn");
        loggedIn.setAccessible(true);
        loggedIn.setBoolean(chr, true);

        Field awayFromWorld = Character.class.getDeclaredField("awayFromWorld");
        awayFromWorld.setAccessible(true);
        ((AtomicBoolean) awayFromWorld.get(chr)).set(false);

        return chr;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> field(PartySearchCoordinator coordinator, String name) throws Exception {
        Field f = PartySearchCoordinator.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<Object, Object>) f.get(coordinator);
    }

    @Test
    void reentryLimitIsPositive() {
        assertTrue(YamlConfig.config.server.PARTY_SEARCH_REENTRY_LIMIT > 0,
                "a reentry limit of 0 expires a party leader on the first search that finds nobody, before they had a chance to find anyone");
    }

    @Test
    void leaderSurvivesTheAllowedEmptySearchesAndIsParkedOnlyAfterThem() throws Exception {
        int limit = YamlConfig.config.server.PARTY_SEARCH_REENTRY_LIMIT;
        int leaderId = 42;

        PartySearchCoordinator coordinator = new PartySearchCoordinator();
        Character leader = loggedInLeader(leaderId);
        coordinator.registerPartyLeader(leader, 1, 200, 0);

        for (int emptySearch = 1; emptySearch <= limit; emptySearch++) {
            coordinator.runPartySearch();

            assertTrue(field(coordinator, "searchLeaders").containsKey(leaderId),
                    "leader expired after " + emptySearch + " of " + limit + " allowed empty searches");
            assertTrue(field(coordinator, "timeoutLeaders").isEmpty(),
                    "leader was parked in the long-term queue after " + emptySearch + " of " + limit + " allowed empty searches");
        }

        coordinator.runPartySearch();

        assertFalse(field(coordinator, "searchLeaders").containsKey(leaderId),
                "leader still searching after " + (limit + 1) + " empty searches, limit is " + limit);
        assertTrue(field(coordinator, "timeoutLeaders").containsKey(leader),
                "the leader that ran out of empty searches was not parked in the long-term queue");
    }
}

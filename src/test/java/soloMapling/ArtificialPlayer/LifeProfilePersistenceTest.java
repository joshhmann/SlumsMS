package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeProfile;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.server.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.0 S1, audit finding 1: a bot's learned life profile used to live only in the roster's ActivityState, which a
 * SOCIAL or TOWN session overwrote with an empty map. These run the real roster sweep against an in-memory database.
 */
class LifeProfilePersistenceTest {

    private static final int SLOT = BotStore.planSlot(8, 1, 7);
    private static final int CHAR_ID = BotStore.BOT_ID_BASE + SLOT;

    @AfterEach
    void restore() {
        BotRoster.useConnectionSourceForTest(null);
        LifeMemoryStore.useConnectionSourceForTest(null);
        CharacterStorage.removeActiveBot(CHAR_ID);
    }

    /** A roster table and an optional botlifeprofile table, enough for the sweep's statements. */
    static final class FakeDb {
        final Map<Integer, Map<String, Object>> roster = new LinkedHashMap<>();
        final Map<Integer, String> life = new LinkedHashMap<>();
        boolean lifeTable = true;
        int lifeWrites;

        Connection connect() throws SQLException {
            Connection con = mock(Connection.class);
            when(con.prepareStatement(anyString())).thenAnswer(call -> statement(call.getArgument(0)));
            return con;
        }

        private PreparedStatement statement(String sql) throws SQLException {
            PreparedStatement ps = mock(PreparedStatement.class);
            Map<Integer, Object> bound = new HashMap<>();
            doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setString(anyInt(), org.mockito.ArgumentMatchers.any());
            doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setInt(anyInt(), anyInt());
            doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setLong(anyInt(), anyLong());
            if (sql.equals(BotRoster.SELECT_ALL)) {
                when(ps.executeQuery()).thenAnswer(c -> rows(List.copyOf(roster.values())));
            } else if (sql.equals(BotRoster.UPSERT)) {
                doAnswer(c -> { roster.put((Integer) bound.get(1), rosterRow(bound)); return null; }).when(ps).addBatch();
                when(ps.executeUpdate()).thenAnswer(c -> { roster.put((Integer) bound.get(1), rosterRow(bound)); return 1; });
            } else if (sql.equals(LifeMemoryStore.SELECT_ALL)) {
                if (!lifeTable) throw new SQLException("Table 'botlifeprofile' doesn't exist");
                when(ps.executeQuery()).thenAnswer(c -> rows(life.entrySet().stream()
                        .map(e -> Map.<String, Object>of("BotId", e.getKey(), "Profile", e.getValue())).toList()));
            } else if (sql.equals(LifeMemoryStore.UPSERT)) {
                if (!lifeTable) throw new SQLException("Table 'botlifeprofile' doesn't exist");
                doAnswer(c -> { life.put((Integer) bound.get(1), (String) bound.get(3)); lifeWrites++; return null; })
                        .when(ps).addBatch();
            } else if (sql.equals(LifeMemoryStore.SELECT_ONE)) {
                when(ps.executeQuery()).thenAnswer(c -> {
                    String json = life.get((Integer) bound.get(1));
                    return rows(json == null ? List.of() : List.of(Map.<String, Object>of("Profile", json)));
                });
            }
            return ps;
        }

        private static Map<String, Object> rosterRow(Map<Integer, Object> b) {
            Map<String, Object> row = new HashMap<>();
            String[] names = {null, "BotId", "Status", "Personality", "Persona", "PlayWindowStart", "PlayWindowLen",
                    "SessionMinutes", "HomeMapId", "Activity", "ActivityState", "LastLoginAt", "LastLogoutAt",
                    "EnrolledAt"};
            for (int i = 1; i < names.length; i++) row.put(names[i], b.get(i));
            return row;
        }

        private static ResultSet rows(List<Map<String, Object>> rows) throws SQLException {
            Deque<Map<String, Object>> pending = new ArrayDeque<>(rows);
            Map<String, Object>[] current = new Map[1];
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenAnswer(c -> (current[0] = pending.poll()) != null);
            when(rs.getInt(anyString())).thenAnswer(c -> ((Number) current[0].get(c.getArgument(0))).intValue());
            when(rs.getLong(anyString())).thenAnswer(c -> ((Number) current[0].get(c.getArgument(0))).longValue());
            when(rs.getString(anyString())).thenAnswer(c -> (String) current[0].get(c.getArgument(0)));
            return rs;
        }

        /** A server restart: both stores forget everything and read this database again. */
        void boot() {
            BotRoster.useConnectionSourceForTest(this::connect);
            LifeMemoryStore.useConnectionSourceForTest(this::connect);
            assertNotNull(BotRoster.member(SLOT), "the roster loads first at boot");
        }
    }

    /** The 1.6.0-sprint v1 shape a TrainingBot left in ActivityState: its v1 seed after one MARRIAGE nudge. */
    private static Map<String, Object> sprintV1Life() {
        Map<String, Double> blend = new LinkedHashMap<>();
        LifeProfile.forBotV1(CHAR_ID).blend().forEach((type, value) ->
                blend.put(type.name(), value * .995 + (type == LifeProfile.Archetype.SOCIALITE ? .005 : 0)));
        Map<String, Object> v1 = new LinkedHashMap<>();
        v1.put("version", 1);
        v1.put("blend", blend);
        v1.put("goals", List.of());
        v1.put("lastJob", 110);
        v1.put("lastSocial", 5_000L);
        v1.put("driftDay", 0L);
        v1.put("changesToday", 1);
        v1.put("events", Map.of("MARRIAGE", 1_000L));
        return v1;
    }

    private static FakeDb trainedBot() {
        FakeDb db = new FakeDb();
        Map<String, Object> row = new HashMap<>();
        row.put("BotId", SLOT);
        row.put("Status", "ACTIVE");
        row.put("Personality", null);
        row.put("Persona", null);
        row.put("PlayWindowStart", 0);
        row.put("PlayWindowLen", 0);
        row.put("SessionMinutes", 90);
        row.put("HomeMapId", 100000000);
        row.put("Activity", "grind");
        row.put("ActivityState", Json.write(Map.of(BotRoster.TRAIN_STATE, 104040000, "life", sprintV1Life())));
        row.put("LastLoginAt", 0L);
        row.put("LastLogoutAt", 0L);
        row.put("EnrolledAt", 1L);
        db.roster.put(SLOT, row);
        return db;
    }

    /** The bot logs in as a SocialBot: its snapshot is the default empty one every non-training type writes. */
    private static client.Character socialSession() {
        client.Character chr = mock(client.Character.class);
        when(chr.getId()).thenReturn(CHAR_ID);
        BotSM social = mock(BotSM.class);
        when(social.getChr()).thenReturn(chr);
        when(social.activitySnapshot()).thenReturn(new BotRoster.Activity("social", 0, Map.of()));
        CharacterStorage.addActiveBot(CHAR_ID, social);
        return chr;
    }

    private static Map<LifeProfile.Archetype, Double> sprintDrift() {
        Map<LifeProfile.Archetype, Double> drift = new EnumMap<>(LifeProfile.Archetype.class);
        var v1Seed = LifeProfile.forBotV1(CHAR_ID).blend();
        v1Seed.forEach((type, value) -> drift.put(type,
                value * .995 + (type == LifeProfile.Archetype.SOCIALITE ? .005 : 0) - value));
        return drift;
    }

    private static void assertSprintDrift(LifeMemory memory) {
        assertTrue(memory.restored(), "a saved profile was found");
        Map<LifeProfile.Archetype, Double> expected = sprintDrift();
        for (var type : LifeProfile.Archetype.values()) {
            assertEquals(expected.get(type), memory.delta().getOrDefault(type, 0.0), 1e-12, type.name());
        }
        assertTrue(memory.profile().blend().get(LifeProfile.Archetype.SOCIALITE)
                > memory.seed().blend().get(LifeProfile.Archetype.SOCIALITE), "the drift is applied on the seed");
        assertEquals(110, memory.save(0).get("lastJob"));
    }

    @Test
    void aSocialSessionAfterTrainingNoLongerWipesTheLearnedProfile() {
        FakeDb db = trainedBot();
        db.boot();
        client.Character chr = socialSession();

        assertEquals(1, BotRoster.recordSessions(List.of(chr), 10_000L, false));
        assertTrue(db.life.containsKey(SLOT), "the v1 copy was migrated into botlifeprofile");
        Object state = db.roster.get(SLOT).get("ActivityState");
        assertTrue(state == null || !((String) state).contains("\"life\""), "the roster row stops carrying it");

        db.boot(); // restart
        LifeMemory reloaded = LifeMemoryStore.of(CHAR_ID);
        assertSprintDrift(reloaded);
        assertFalse(reloaded.migrated(), "read from the table, not v1 again");

        // Another SOCIAL session and another restart: still there, and an unchanged memory is not rewritten.
        int writes = db.lifeWrites;
        assertEquals(1, BotRoster.recordSessions(List.of(chr), 20_000L, true));
        assertEquals(writes, db.lifeWrites, "nothing changed, nothing written");
        db.boot();
        assertSprintDrift(LifeMemoryStore.of(CHAR_ID));
    }

    @Test
    void withoutMigration028TheProfileRidesInTheRosterRowForEveryBotType() {
        FakeDb db = trainedBot();
        db.lifeTable = false;
        db.boot();
        client.Character chr = socialSession();

        assertEquals(1, BotRoster.recordSessions(List.of(chr), 10_000L, false));
        String state = (String) db.roster.get(SLOT).get("ActivityState");
        assertNotNull(state);
        assertTrue(state.contains("\"life\"") && state.contains("\"version\":2"), state);

        db.boot();
        assertSprintDrift(LifeMemoryStore.of(CHAR_ID));
    }

    @Test
    void driftLearnedThisRunIsSavedByANonTrainingSessionAndSurvivesARestart() {
        FakeDb db = trainedBot();
        db.boot();
        LifeMemory memory = LifeMemoryStore.of(CHAR_ID);
        assertTrue(memory.event(LifeMemory.Event.BIG_DROP, 2 * 86_400_000L));
        Map<LifeProfile.Archetype, Double> learned = memory.delta();
        client.Character chr = socialSession();

        BotRoster.recordSessions(List.of(chr), 2 * 86_400_000L + 5, true);
        db.boot();
        LifeMemory reloaded = LifeMemoryStore.of(CHAR_ID);
        assertEquals(learned, reloaded.delta());
        assertEquals(memory.profile(), reloaded.profile());
        assertEquals(memory.social(), reloaded.social());
    }

    @Test
    void theReadModelJoinsTheLayersAndShowsSeedAndLearnedApart() {
        FakeDb db = trainedBot();
        db.roster.get(SLOT).put("Personality", "{\"seed\":1,\"grind\":0.2,\"town\":0.3,\"social\":0.5,\"chattiness\":0.8}");
        db.roster.get(SLOT).put("Persona", new BotPersona.Persona(true, true, "lol", "a chill player",
                List.of("fishing", "zakum someday")).toJson());
        db.boot();
        var self = soloMapling.ArtificialPlayer.BotLifeSystem.SocialSelf.of(CHAR_ID);
        assertEquals(SLOT, self.slot());
        assertEquals(soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile.forSlot(SLOT), self.socialSeed());
        assertEquals(self.socialSeed().leading(), self.leading());
        assertFalse(self.leadingFlipped());
        assertEquals(self.social().rivalPull(), self.rivalPull());
        assertEquals(.8, self.chattiness(), 1e-12, "the stored trait, not the voice's draw");
        assertEquals(.8, self.voice().chattiness(), 1e-12, "the voice gets the stored chattiness too");
        assertNotEquals(self.lifeSeed(), self.life(), "the sprint drift sits on top of the seed");
        assertSame(LifeMemoryStore.of(CHAR_ID).profile(), self.life(), "the same memory the brain uses");
        List<String> lines = self.describe();
        assertTrue(lines.get(0).startsWith("Bartle " + self.leading() + "-led"), lines.toString());
        assertTrue(lines.get(1).contains("; seed "), "learned and seed shown apart: " + lines);
        assertTrue(lines.get(2).contains("a chill player") && lines.get(2).contains("social 0.50"), lines.toString());
    }

    @Test
    void theQaRoundTripPassesOnASocialBotAndRefusesWithoutTheTable() {
        FakeDb db = trainedBot();
        db.boot();
        client.Character chr = socialSession();
        List<String> lines = soloMapling.ArtificialPlayer.BotLifeSystem.LifeProfileProof.run(chr, 3 * 86_400_000L);
        assertTrue(lines.getLast().startsWith("LIFE PROFILE PASS bot=" + CHAR_ID), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("NUDGE event=JOB_ADVANCE")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("recorded=social") && l.contains("rosterCarriesLife=false")),
                lines.toString());
        assertTrue(db.life.containsKey(SLOT));

        FakeDb missing = trainedBot();
        missing.lifeTable = false;
        missing.boot();
        var refused = assertThrows(IllegalStateException.class,
                () -> soloMapling.ArtificialPlayer.BotLifeSystem.LifeProfileProof.run(chr, 3 * 86_400_000L));
        assertTrue(refused.getMessage().contains("migration 028"), refused.getMessage());
    }

    @Test
    void aBotThatLearnedNothingWritesNoProfileAnywhereAsOnLiveWithTheBrainOff() {
        for (boolean table : new boolean[]{true, false}) {
            FakeDb db = trainedBot();
            db.roster.get(SLOT).put("ActivityState", Json.write(Map.of(BotRoster.TRAIN_STATE, 104040000)));
            db.lifeTable = table;
            db.boot();
            assertTrue(LifeMemoryStore.of(CHAR_ID).pristine(), "TrainingBot asks for its memory at spawn");
            client.Character chr = socialSession();
            assertEquals(1, BotRoster.recordSessions(List.of(chr), 10_000L, true));
            assertEquals(0, db.lifeWrites);
            assertTrue(db.life.isEmpty());
            assertNull(db.roster.get(SLOT).get("ActivityState"), "the social snapshot as before, nothing added");
        }
    }

    @Test
    void migration028IsGuardedLedgeredAndHasARollback() throws Exception {
        Path dir = Path.of("database/migrations");
        String sql = Files.readString(dir.resolve("028-bot-life-profile.sql"));
        String rollback = Files.readString(dir.resolve("028-bot-life-profile.rollback.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS botlifeprofile"));
        assertTrue(sql.contains("mapleroot_integration_migrations(migration_id) VALUES ('028-bot-life-profile')"));
        assertTrue(rollback.contains("DROP TABLE IF EXISTS botlifeprofile"));
        assertTrue(rollback.contains("migration_id='028-bot-life-profile'"));
        for (String column : List.of("BotId", "Version", "Profile", "UpdatedAt")) {
            assertTrue(sql.contains(column), column);
            assertTrue(LifeMemoryStore.UPSERT.contains(column), column);
        }
    }
}

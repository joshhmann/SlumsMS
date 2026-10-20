package config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ticket 41: config.yaml worlds can name their id; without one the list position is the id, as before. */
class WorldConfigsTest {
    private static final int NAMES = 21;   // GameConstants.WORLD_NAMES.length

    private static WorldConfig world(int id) {
        WorldConfig wc = new WorldConfig();
        wc.id = id;
        return wc;
    }

    @Test
    void anEntryWithoutAnIdIsTheWorldAtItsPosition() {
        List<WorldConfig> worlds = List.of(world(-1), world(-1), world(-1));
        assertEquals(List.of(0, 1, 2), WorldConfigs.ids(worlds));
        assertDoesNotThrow(() -> WorldConfigs.validate(worlds, NAMES));
        assertSame(worlds.get(2), WorldConfigs.forWorld(worlds, 2));
    }

    @Test
    void theDefaultIdIsUnset() {
        assertEquals(WorldConfigs.UNSET, new WorldConfig().id);
    }

    @Test
    void liveBeraAloneIsWorldOneAndThereIsNoWorldZero() {
        List<WorldConfig> worlds = List.of(world(1));
        assertEquals(List.of(1), WorldConfigs.ids(worlds));
        assertDoesNotThrow(() -> WorldConfigs.validate(worlds, NAMES));
        assertSame(worlds.get(0), WorldConfigs.forWorld(worlds, 1));
        assertNull(WorldConfigs.forWorld(worlds, 0));
    }

    @Test
    void explicitAndPositionalIdsMix() {
        List<WorldConfig> worlds = List.of(world(1), world(-1), world(5));   // entry #2 has no id, so it takes position 1: Bera twice
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> WorldConfigs.validate(worlds, NAMES));
        assertTrue(e.getMessage().contains("#1 and #2"), e.getMessage());
        assertTrue(e.getMessage().contains("world id 1"), e.getMessage());

        List<WorldConfig> ok = List.of(world(1), world(0));
        assertDoesNotThrow(() -> WorldConfigs.validate(ok, NAMES));
        assertEquals(List.of(1, 0), WorldConfigs.ids(ok));
    }

    @Test
    void aDuplicateIdStopsTheBoot() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WorldConfigs.validate(List.of(world(3), world(3)), NAMES));
        assertTrue(e.getMessage().contains("both world id 3"), e.getMessage());
    }

    @Test
    void anIdTheClientDoesNotKnowStopsTheBoot() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WorldConfigs.validate(List.of(world(21)), NAMES));
        assertTrue(e.getMessage().contains("0..20"), e.getMessage());

        List<WorldConfig> tooMany = new ArrayList<>();
        for (int i = 0; i <= NAMES; i++) {
            tooMany.add(world(-1));
        }
        assertThrows(IllegalStateException.class, () -> WorldConfigs.validate(tooMany, NAMES));
    }

    @Test
    void aNegativeIdOtherThanUnsetStopsTheBoot() {
        assertThrows(IllegalStateException.class, () -> WorldConfigs.validate(List.of(world(-2)), NAMES));
    }

    @Test
    void noWorldsAtAllStopsTheBoot() {
        assertThrows(IllegalStateException.class, () -> WorldConfigs.validate(List.of(), NAMES));
        assertThrows(IllegalStateException.class, () -> WorldConfigs.validate(null, NAMES));
    }

    @Test
    void theYamlKeyIsRead() throws Exception {
        String yaml = "worlds:\n  - id: 1\n    channels: 8\n    exp_rate: 3\n";
        com.esotericsoftware.yamlbeans.YamlReader reader = new com.esotericsoftware.yamlbeans.YamlReader(yaml);
        YamlConfig cfg = reader.read(YamlConfig.class);
        assertEquals(1, cfg.worlds.size());
        assertEquals(1, cfg.worlds.get(0).id);
        assertEquals(8, cfg.worlds.get(0).channels);
        assertEquals(List.of(1), WorldConfigs.ids(cfg.worlds));
    }
}

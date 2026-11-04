package server.maps;

import client.BotClient;
import client.Character;
import config.YamlConfig;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.life.Monster;
import server.life.MonsterGlobalDropEntry;
import server.life.MonsterInformationProvider;
import tools.DatabaseConnection;

import java.awt.Point;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Ticket 81: the 268 post-Big-Bang mobs (Arcane River, Grandis, Temple of Time/Future/Twilight, Mirror World) that
 * have no {@code drop_data} rows of their own used to drop absolutely nothing, because {@code dropFromMonster}
 * returned before the map's global table was rolled. A mob with an empty own-drop list must still roll
 * {@code drop_data_global} (NX cards, Maple Leaf, Chaos/White Scroll, Flaming Feather).
 */
class MapleMapGlobalDropTest {

    private static final int MAP_ID = 450000000;            // an Arcane River map (post-Big-Bang)
    private static final int MOB_ID = 8644100;              // a mob with no drop_data rows of its own (mocked below)
    private static final int GLOBAL_ITEM = 4031865;         // Nexon Game Card - 100 points, a drop_data_global row
    private static final Point AT = new Point(10, 10);

    private static Monster rowlessMob() {
        Monster mob = mock(Monster.class);
        when(mob.getId()).thenReturn(MOB_ID);
        when(mob.getStats()).thenReturn(mock(server.life.MonsterStats.class)); // not FFA, not explosive
        when(mob.getPosition()).thenReturn(new Point(0, 0));
        when(mob.getMobMaxHp()).thenReturn(300_000L);
        return mob;
    }

    /** The map's registered drop of {@link #GLOBAL_ITEM}, the way DropCommandsProvenanceTest reads a real drop. */
    private static MapItem globalDropOn(MapleMap map) {
        for (MapObject object : map.getMapObjectsInRange(AT, 40_000.0, List.of(MapObjectType.ITEM))) {
            MapItem drop = (MapItem) object;
            if (!drop.isPickedUp() && drop.getItemId() == GLOBAL_ITEM) {
                return drop;
            }
        }
        return null;
    }

    @Test
    void aMobWithNoRowsOfItsOwnStillRollsTheMapsGlobalDrops() {
        boolean previousAnimation = YamlConfig.config.server.USE_SPAWN_LOOT_ON_ANIMATION;
        boolean previousRelevant = YamlConfig.config.server.USE_SPAWN_RELEVANT_LOOT;
        YamlConfig.config.server.USE_SPAWN_LOOT_ON_ANIMATION = false;   // roll now, not on die1
        YamlConfig.config.server.USE_SPAWN_RELEVANT_LOOT = false;       // the server's default: retrieveEffectiveDrop

        // MonsterInformationProvider's static init reads drop_data_global; stub the connection first, so the class
        // initializes seeing an empty table, and only then replace getInstance() with our own provider.
        try (MockedStatic<DatabaseConnection> db = mockStatic(DatabaseConnection.class)) {
            db.when(DatabaseConnection::getConnection).thenReturn(mock(Connection.class, RETURNS_DEEP_STUBS));

            try (MockedStatic<MonsterInformationProvider> providers = mockStatic(MonsterInformationProvider.class)) {
                MonsterInformationProvider mi = mock(MonsterInformationProvider.class);
                providers.when(MonsterInformationProvider::getInstance).thenReturn(mi);
                when(mi.retrieveEffectiveDrop(MOB_ID)).thenReturn(List.of());   // the mob has no rows at all
                when(mi.getRelevantGlobalDrops(MAP_ID)).thenReturn(List.of(
                        new MonsterGlobalDropEntry(GLOBAL_ITEM, 999_999, -1, 1, 1, (short) 0)));   // guaranteed

                MapleMap map = spy(new MapleMap(MAP_ID, 0, 1, 0, 1f));
                doReturn(AT).when(map).calcDropPos(any(), any());            // no footholds loaded in tests

                Character chr = Character.getDefault(new BotClient(0, 0));
                chr.setID(3);

                map.dropFromFriendlyMonster(chr, rowlessMob());

                assertNotNull(globalDropOn(map), "a mob with no drop rows of its own still drops the map's global items");
            }
        } finally {
            YamlConfig.config.server.USE_SPAWN_LOOT_ON_ANIMATION = previousAnimation;
            YamlConfig.config.server.USE_SPAWN_RELEVANT_LOOT = previousRelevant;
        }
    }
}

package server.life;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import soloMapling.WzTestData;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * s-maproute round 4 (b253): a mob skill id or level this server has no data for must cost that one skill, never the
 * mob and never the map.
 *
 * <p>Mob.wz comes from a later client than the one this server speaks. 470 of its mobs name a skill id the
 * {@link MobSkillType} enum does not carry, or a level {@code Skill.wz/MobSkill.img} does not have, and
 * {@code LifeFactory.getMonsterStats} used to throw on both ({@code Optional.orElseThrow()} and
 * {@code MobSkillFactory.getMobSkillOrThrow}). A mob that fails to load makes {@code MapFactory.loadLifeRaw} fail, so
 * the whole map cannot load: 48 maps spawn one of those mobs, and two of them (867120100, 867120900) are fallbacks in
 * {@code soloMapling/map-routing.tsv}, so the QA check t-mp-1 died with
 * {@code NoSuchElementException: No value present}.
 */
class MobSkillToleranceTest {

    /** 3501008's skill block names skill 201, which is not in {@link MobSkillType} at all. */
    private static final int UNKNOWN_SKILL_ID = 3501008;

    /** 2600030 names skill 132 at level 14; the data has levels 1 to 6 for 132. */
    private static final int UNKNOWN_SKILL_LEVEL = 2600030;

    /** 9410204 names skill 136 (FEAR) at level 3, which this server does have. */
    private static final int ORDINARY_MOB = 9410204;

    @Test
    void aMobSkillIdTheServerDoesNotImplementCostsTheSkillAndNotTheMob() {
        WzTestData.assumePresent("Mob.wz");
        Monster monster = load(UNKNOWN_SKILL_ID);

        assertNotNull(monster, "mob 3501008 spawns in map 867120900");
        assertEquals(38, monster.getLevel());
        assertTrue(monster.getStats().getSkills().isEmpty(),
                "skill 201 is not implemented here, so the mob loads without it: " + monster.getStats().getSkills());
    }

    @Test
    void aMobSkillLevelTheServerHasNoDataForCostsTheSkillAndNotTheMob() {
        WzTestData.assumePresent("Mob.wz");
        Monster monster = load(UNKNOWN_SKILL_LEVEL);

        assertNotNull(monster, "mob 2600030 spawns in five maps");
        // its skill block is 132/14, 122/15, 201/73, 201/74, 201/75 and 105/5: only WEAKNESS 15 is one this server
        // can run (MobSkill.img has levels 1-6 for 132, not 14; 201 and 105 are not in MobSkillType)
        assertEquals(Set.of(new MobSkillId(MobSkillType.WEAKNESS, 15)), monster.getStats().getSkills(),
                "the skills it cannot run are skipped one at a time: " + monster.getStats().getSkills());
    }

    @Test
    void aMobKeepsTheSkillTheServerDoesImplement() {
        WzTestData.assumePresent("Mob.wz");
        Monster monster = load(ORDINARY_MOB);

        assertNotNull(monster);
        assertEquals(Set.of(new MobSkillId(MobSkillType.FEAR, 3)), monster.getStats().getSkills(),
                "skipping the skills this server lacks must not also skip the ones it has");
    }

    /** {@code LifeFactory.getMonsterStats} also reads the drop table, so the pool has to answer. */
    private static Monster load(int mobId) {
        try (MockedStatic<DatabaseConnection> db = Mockito.mockStatic(DatabaseConnection.class)) {
            db.when(DatabaseConnection::getConnection).thenReturn(mock(Connection.class, RETURNS_DEEP_STUBS));
            return LifeFactory.getMonster(mobId);
        }
    }
}

package config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ticket 164: the four family tuning fields in ServerConfig had no initialiser, and yamlbeans only binds keys that
 * are actually present in config.yaml, so an absent key left them at the JVM default 0. Zero is not a neutral value
 * for any of them:
 *
 * <ul>
 *   <li>the three rep rates pay nothing, so every kill and level-up gives the senior 0 rep, and all 11 entitlements
 *       (costs 300..5000, client.FamilyEntitlement) stay permanently unaffordable - FamilyUseHandler returns silently
 *       and a Family window button appears dead;</li>
 *   <li>{@code FAMILY_MAX_GENERATIONS} is a raw threshold, so 0 inverts it into a wall:
 *       net.server.channel.handlers.FamilyAddHandler:71 refuses with error 76 ("The family cannot extend more than
 *       1000 generations from above and below.") as soon as both players are in a family, and
 *       net.server.channel.handlers.AcceptFamilyHandler:105 never lets the target join. A populated family is always
 *       at least 1 generation (client.FamilyEntry.doFullCount sets {@code counts.getLeft() + 1}), so any two
 *       families sum to >= 2 and 2 > 0 refuses everything. Only the very first pair of unattached players can form
 *       a family, and nobody can ever join or merge into it.</li>
 * </ul>
 *
 * <p>The assertions below read a fresh {@code ServerConfig} rather than {@code YamlConfig.config.server}: the
 * committed config.yaml carries no family keys at all, so the static instance only proves what the initialisers
 * already prove, and a fresh instance proves it on a checkout whose config.yaml a developer has edited. ServerConfig
 * has no explicit constructor and its initialisers only assign literals, {@code new HashMap<>()} and one copied
 * constant - no I/O and no statics - so constructing it is free of side effects. Nothing here mutates shared state,
 * so no restore is needed.
 */
class FamilyConfigDefaultsTest {

    @Test
    void repRatesArePositiveSoKillsAndLevelUpsPaySomething() {
        ServerConfig defaults = new ServerConfig();

        assertTrue(defaults.FAMILY_REP_PER_KILL > 0,
                "a rep per kill of 0 pays the senior nothing for every regular kill, and the cheapest entitlement costs 300 rep");
        assertTrue(defaults.FAMILY_REP_PER_BOSS_KILL > 0,
                "a rep per boss kill of 0 pays the senior nothing for every boss, and the cheapest entitlement costs 300 rep");
        assertTrue(defaults.FAMILY_REP_PER_LEVELUP > 0,
                "a rep per level-up of 0 pays the senior nothing for every level their juniors gain");
    }

    @Test
    void generationCapAllowsAJoinInsteadOfInvertingIntoAWall() {
        // A family is always at least 1 generation, and FamilyAddHandler:71 / AcceptFamilyHandler:105 test
        // (inviter + target) <= cap to allow and > cap to refuse with error 76. A cap of 0 or 1 therefore refuses
        // every join; 2 is the smallest cap that lets two single-generation families merge.
        assertTrue(new ServerConfig().FAMILY_MAX_GENERATIONS >= 2,
                "a generation cap below 2 refuses every family join and merge, because two families sum to at least 2");
    }

    @Test
    void theDocumentedLiveTuningIsTheDefault() {
        // docs/tickets/53-family.md:18-19 records the owner's live tuning. Retuning means changing that ticket and
        // this test together; the assertions above are the contract, these are the values the contract currently is.
        ServerConfig defaults = new ServerConfig();
        assertEquals(4, defaults.FAMILY_REP_PER_KILL);
        assertEquals(20, defaults.FAMILY_REP_PER_BOSS_KILL);
        assertEquals(200, defaults.FAMILY_REP_PER_LEVELUP);
        assertEquals(1000, defaults.FAMILY_MAX_GENERATIONS);
    }
}

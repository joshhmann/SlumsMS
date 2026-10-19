package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import server.maps.FieldLimit;
import server.maps.MapleMap;

/*
 * Which movement-skill edges (TELEPORT / FLASH_JUMP, nav graph v60+) a bot may plan with. The gate idea is from
 * nutnnut/Cosmic's BotNavigationManager.hasTeleport/hasFlashJump (AGPL-3.0. Credit: NutNNut), which reads the
 * bot's skill levels; ours gates by JOB, because bot skill trees are not persisted yet (real-bots spec ticket 5).
 *
 * Stock classes only, by the job that first learns the skill (every stock id below exists in MapleRoot's
 * Skill.wz):
 *   Teleport   - F/P Wizard 2101002, I/L Wizard 2201002, Cleric 2301001 (jobs 210-212, 220-222, 230-232),
 *                Blaze Wizard 12101003 (jobs 1210-1212).
 *   Flash Jump - Hermit 4111006 (jobs 411-412), Night Walker 14101004 (jobs 1410-1412).
 * Evan is not stock in MapleRoot's data and custom classes are out of scope, so anything else plans walk-only.
 * Maps with the MOVEMENTSKILLS field limit forbid both (the same flag BotMovementProfile uses).
 */
final class NavMovementSkills {
    private NavMovementSkills() {
    }

    /* The skill-mask bits (BotNavigationGraph.SKILL_*) this job may use; 0 = walk-only. */
    static int maskForJob(int jobId) {
        int mask = 0;
        if (isTeleportJob(jobId)) {
            mask |= BotNavigationGraph.SKILL_TELEPORT;
        }
        if (isFlashJumpJob(jobId)) {
            mask |= BotNavigationGraph.SKILL_FLASH_JUMP;
        }
        return mask;
    }

    /* The mask for a live bot on its current map (0 when the map forbids movement skills). */
    static int maskFor(Character bot) {
        if (bot == null || bot.getJob() == null) {
            return 0;
        }
        MapleMap map = bot.getMap();
        if (map != null && FieldLimit.MOVEMENTSKILLS.check(map.getFieldLimit())) {
            return 0;
        }
        return maskForJob(bot.getJob().getId());
    }

    /* Whether an edge is usable under a skill mask: non-skill edges always are. */
    static boolean allows(BotNavigationGraph.Edge edge, int skillMask) {
        return switch (edge.type) {
            case TELEPORT -> (skillMask & BotNavigationGraph.SKILL_TELEPORT) != 0;
            case FLASH_JUMP -> (skillMask & BotNavigationGraph.SKILL_FLASH_JUMP) != 0;
            default -> true;
        };
    }

    static boolean isTeleportJob(int id) {
        return (id >= 210 && id <= 212) || (id >= 220 && id <= 222) || (id >= 230 && id <= 232)
                || (id >= 1210 && id <= 1212);
    }

    static boolean isFlashJumpJob(int id) {
        return id == 411 || id == 412 || (id >= 1410 && id <= 1412);
    }
}

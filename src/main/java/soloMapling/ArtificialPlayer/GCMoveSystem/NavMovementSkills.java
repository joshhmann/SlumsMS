package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import server.maps.FieldLimit;
import server.maps.MapleMap;

/*
 * Which movement-skill edges (TELEPORT / FLASH_JUMP, nav graph v60+) a bot may plan with. The gate idea is from
 * nutnnut/Cosmic's BotNavigationManager.hasTeleport/hasFlashJump (AGPL-3.0. Credit: NutNNut), which reads the
 * bot's skill levels.
 *
 * Ticket 02: a bot needs BOTH a stock job that learns the skill AND the skill itself at level 1 or more (its real
 * build, botskills / BotSkillPlan), and it must be able to pay for the cast right now (NavSkillCast: the skill's WZ
 * mpCon at the bot's level, an MP potion it could drink, and a cooldown if the skill data ever sets one).
 *
 * Stock classes only, by the job that first learns the skill (MapleRoot's Skill.wz, docs/real-bots/mapleroot-skills.md):
 *   Teleport   - F/P Wizard 2101002, I/L Wizard 2201002, Cleric 2301001 (jobs 210-212, 220-222, 230-232),
 *                Blaze Wizard 12101003 (jobs 1210-1212). mpCon 60 at level 1 down to 1 at level 20.
 *   Flash Jump - Hermit 4111006 (jobs 411-412), Night Walker 14101004 (jobs 1410-1412). mpCon 60 down to 1.
 * Neither skill has a cooltime in MapleRoot's data. Evan is not stock in MapleRoot's data and custom classes are out
 * of scope, so anything else plans walk-only. Maps with the MOVEMENTSKILLS field limit forbid both (the same flag
 * BotMovementProfile uses). -Dbot.nav.mobility=false turns every skill edge off (the nav lab's "without" mode).
 */
final class NavMovementSkills {
    private NavMovementSkills() {
    }

    static final int[] TELEPORT_SKILLS = {2101002, 2201002, 2301001, 12101003};
    static final int[] FLASH_JUMP_SKILLS = {4111006, 14101004};

    /* Master switch: false plans every bot walk-only (live kill switch and the nav lab's baseline). */
    static volatile boolean enabled = !"false".equalsIgnoreCase(System.getProperty("bot.nav.mobility", "true"));

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

    /*
     * The skills the bot holds and may use on its current map, cost aside: its job learns them, it has them at a
     * level, and the map allows movement skills. Learning uses this to tell "could not" from "failed".
     */
    static int heldMask(Character bot) {
        if (!enabled || bot == null || bot.getJob() == null) {
            return 0;
        }
        MapleMap map = bot.getMap();
        if (map != null && FieldLimit.MOVEMENTSKILLS.check(map.getFieldLimit())) {
            return 0;
        }
        int mask = maskForJob(bot.getJob().getId());
        if ((mask & BotNavigationGraph.SKILL_TELEPORT) != 0 && heldSkill(bot, TELEPORT_SKILLS) == 0) {
            mask &= ~BotNavigationGraph.SKILL_TELEPORT;
        }
        if ((mask & BotNavigationGraph.SKILL_FLASH_JUMP) != 0 && heldSkill(bot, FLASH_JUMP_SKILLS) == 0) {
            mask &= ~BotNavigationGraph.SKILL_FLASH_JUMP;
        }
        return mask;
    }

    /* The mask a live bot plans and executes with: what it holds, less what it cannot pay for right now. */
    static int maskFor(Character bot) {
        int mask = heldMask(bot);
        if ((mask & BotNavigationGraph.SKILL_TELEPORT) != 0
                && !NavSkillCast.affordable(bot, BotNavigationGraph.EdgeType.TELEPORT)) {
            mask &= ~BotNavigationGraph.SKILL_TELEPORT;
        }
        if ((mask & BotNavigationGraph.SKILL_FLASH_JUMP) != 0
                && !NavSkillCast.affordable(bot, BotNavigationGraph.EdgeType.FLASH_JUMP)) {
            mask &= ~BotNavigationGraph.SKILL_FLASH_JUMP;
        }
        return mask;
    }

    /* The id of the first skill of this list the bot holds at level 1 or more, 0 for none. */
    static int heldSkill(Character bot, int[] skillIds) {
        for (int id : skillIds) {
            try {
                if (bot.getSkillLevel(id) > 0) {
                    return id;
                }
            } catch (RuntimeException unavailable) {
                return 0; // skill data not loaded (a partial mock): hold nothing
            }
        }
        return 0;
    }

    /* The skill a move of this type casts for this bot, 0 when it holds none. */
    static int skillFor(Character bot, BotNavigationGraph.EdgeType type) {
        if (bot == null) {
            return 0;
        }
        return switch (type) {
            case TELEPORT -> heldSkill(bot, TELEPORT_SKILLS);
            case FLASH_JUMP -> heldSkill(bot, FLASH_JUMP_SKILLS);
            default -> 0;
        };
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

package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

// Maps a bot's job to how it moves while grinding. Gated on job (which implies job-tier/level), so low-level
// bots stay PLANTED (slow = organic) and only advanced classes blink/dash. Ours.
public final class MovementStylePolicy {
    private MovementStylePolicy() {
    }

    public static MovementStyle forBot(Character bot) {
        if (bot == null || bot.getJob() == null) {
            return MovementStyle.PLANTED;
        }
        int id = bot.getJob().getId();
        // Ticket 02: blink / dash only with the skill actually held (its real build); a fresh Hermit still saving
        // SP for Flash Jump fights like the other haste thieves, a mage without Teleport stays planted.
        if (GCMovement.holdsTeleport(bot)) { // mages 2nd job+, Blaze Wizard 2nd job+
            return MovementStyle.TELEPORT;
        }
        if (GCMovement.holdsFlashJump(bot)) { // Hermit, Night Lord, Night Walker 2nd job+
            return MovementStyle.FLASH_JUMP;
        }
        if (id == 410 || id == 411 || id == 412 || id == 420 || id == 421 || id == 422
                || (id >= 1410 && id <= 1412)) { // haste thieves
            return MovementStyle.JUMP_ATTACK;
        }
        if (isRangedBowman(id)) {
            return MovementStyle.RANGED;
        }
        return MovementStyle.PLANTED;
    }

    // Bowman 2nd job and above: Hunter/Ranger/Bowmaster (310-312), Crossbowman/Sniper/Marksman (320-322).
    // 1st-job Bowman (300) stays PLANTED so low-levels read organic.
    private static boolean isRangedBowman(int id) {
        return (id >= 310 && id <= 312) || (id >= 320 && id <= 322);
    }

}

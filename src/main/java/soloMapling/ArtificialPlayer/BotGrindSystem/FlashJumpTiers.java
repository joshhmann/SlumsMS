package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;

// Level tier for the flash-jump dash: how much of the full (maxed) dash a bot may use. Skill-authentic
// flavor knob — a Hermit still leveling Flash Jump dashes visibly shorter than a Night Lord — resolved
// once per grind episode (GrindBrain.start) and passed into GCMovement.flashJump as the scale cap.
// The per-jump platform fit (GCMovementSkills) may downshift below this cap; it never exceeds it.
// Policy-level like MovementStylePolicy, so it lives here, not in the GC package. Ours (SoloMapling).
public final class FlashJumpTiers {

    private FlashJumpTiers() {
    }

    private static final int HERMIT = 411;
    private static final int NIGHT_LORD = 412;
    private static final int MAXED_LEVEL = 100;       // Hermit at/above this = maxed Flash Jump
    private static final float DEVELOPING_SCALE = 0.75f; // Hermit 70-99: still leveling the skill
    private static final int MAXED_SKILL_LEVEL = 20;     // Flash Jump's master level
    private static final int[] FLASH_JUMP = {4111006, 14101004}; // Hermit, Night Walker

    private static int heldLevel(Character bot) {
        try {
            for (int id : FLASH_JUMP) {
                int level = bot.getSkillLevel(id);
                if (level > 0) {
                    return level;
                }
            }
        } catch (RuntimeException unavailable) {
            // skill data not loaded
        }
        return 0;
    }

    // The dash-scale cap for this bot: Night Lord (and Hermit 100+) = full dash, younger Hermit = 0.75.
    // Non-FLASH_JUMP jobs return 1 (harmless — only FLASH_JUMP-style bots ever dash).
    public static float capFor(Character bot) {
        if (bot == null || bot.getJob() == null) {
            return 1f;
        }
        // Ticket 02: the real Flash Jump level when the bot holds the skill (its build raises it to 20).
        int held = heldLevel(bot);
        if (held > 0) {
            return held >= MAXED_SKILL_LEVEL ? 1f : DEVELOPING_SCALE;
        }
        int id = bot.getJob().getId();
        if (id == HERMIT) {
            return bot.getLevel() >= MAXED_LEVEL ? 1f : DEVELOPING_SCALE;
        }
        return 1f; // Night Lord, or any other caller
    }
}

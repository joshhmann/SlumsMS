package soloMapling.mechanics;

/**
 * A party member as the orchestrator sees it: a bot (or a real player it plans around, never steers). Real players
 * get the first claim on every role ({@link RoleClaims}); the orchestrator never asks a solver for a player's step.
 */
public record RunMember(int characterId, boolean leader, boolean bot, MechanicsPersona persona) {

    public static RunMember player(int characterId, boolean leader) {
        return new RunMember(characterId, leader, false, MechanicsPersona.PERFECT);
    }
}

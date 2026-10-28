package soloMapling.mechanics;

/** A party member as the orchestrator sees it: a bot (or a real player it plans around, never steers). */
public record RunMember(int characterId, boolean leader, boolean bot, MechanicsPersona persona) {
}

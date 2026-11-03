package soloMapling.mechanics;

/**
 * A party member as the orchestrator sees it: a bot (or a real player it plans around, never steers). Real players
 * get the first claim on every role ({@link RoleClaims}); the orchestrator never asks a solver for a player's step.
 */
public record RunMember(int characterId, boolean leader, boolean bot, MechanicsPersona persona, int jobId) {

    /** The member's job id, or 0 when the caller did not say (every pre-1.6.4 call site). */
    public RunMember(int characterId, boolean leader, boolean bot, MechanicsPersona persona) {
        this(characterId, leader, bot, persona, 0);
    }

    public static RunMember player(int characterId, boolean leader) {
        return new RunMember(characterId, leader, false, MechanicsPersona.PERFECT, 0);
    }

    /**
     * The member's job <b>class</b> - the first digit of the job id (0 adventurer/beginner, 1 warrior, 2 magician,
     * 3 bowman, 4 thief, 5 pirate). Check {@link #hasJob()} before trusting it. Crimsonwood Keep's sign-up hands each member a reactor by job class, so a row
     * that has to put a member on a sign-up needs this and nothing more; it is not a job model.
     */
    public int jobClass() {
        return jobId / 100;
    }

    /** Whether a job was carried at all. Job id 0 is Beginner, which is a fact; "nothing carried" is {@code !hasJob()}. */
    public boolean hasJob() {
        return jobId > 0;
    }
}

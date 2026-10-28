package soloMapling.mechanics;

/**
 * How a bot plays mechanics (ticket 130, step 2 "imperfection by persona"). Both axes run 0..1. {@code careful}
 * lowers wrong tries and deaths; {@code chaos} raises them and adds chatter and odd choices. Later built from the
 * bot's {@code SocialProfile} (Bartle blend), skill and mood; a perfect bot is {@link #PERFECT}.
 */
public record MechanicsPersona(double careful, double chaos) {

    /** Step 1 (perfect runs): never a wrong try. */
    public static final MechanicsPersona PERFECT = new MechanicsPersona(1.0, 0.0);
    public static final MechanicsPersona AVERAGE = new MechanicsPersona(0.5, 0.3);

    public MechanicsPersona {
        if (!(careful >= 0 && careful <= 1) || !(chaos >= 0 && chaos <= 1)) {
            throw new IllegalArgumentException("persona axes run 0..1: careful=" + careful + " chaos=" + chaos);
        }
    }

    /**
     * Phase 1 stand-in until step 2 builds personas from SocialProfile: a steady persona per character, spread from
     * fairly careful to a little chaotic (expected wrong tries 0 to about 1.6 before caps), the same every run.
     */
    public static MechanicsPersona forCharacter(int characterId) {
        long h = characterId * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        double a = ((h & 0xFFFF) / 65535.0);
        double b = (((h >>> 16) & 0xFFFF) / 65535.0);
        return new MechanicsPersona(0.5 + 0.5 * a, 0.4 * b);
    }

    /** Expected wrong tries before the right one, before tier scaling and caps. */
    public double wrongTryRate() {
        return (1.0 - careful) * 1.5 + chaos * 2.0;
    }
}

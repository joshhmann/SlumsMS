package soloMapling.mechanics;

/**
 * How a bot plays mechanics (ticket 114, step 2 "imperfection by persona"). Both axes run 0..1. {@code careful}
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

    /** Expected wrong tries before the right one, before tier scaling and caps. */
    public double wrongTryRate() {
        return (1.0 - careful) * 1.5 + chaos * 2.0;
    }
}

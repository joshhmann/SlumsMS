package soloMapling.autoqa;

/** Test access to {@link Steps}' precondition rule (a refusal it matches SKIPs a check instead of failing it). */
public final class StepsAccess {
    private StepsAccess() {
    }

    public static boolean isPrecondition(String refusal) {
        return Steps.PRECONDITION.matcher(refusal).find();
    }
}

package soloMapling.mechanics;

/** Test access to KpqStaging's package-private helpers from other packages. */
public final class KpqStagingAccess {
    private KpqStagingAccess() {
    }

    public static int baseClassFor(int index, int ranged) {
        return KpqStaging.baseClassFor(index, ranged);
    }
}

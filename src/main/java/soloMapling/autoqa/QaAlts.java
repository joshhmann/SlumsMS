package soloMapling.autoqa;

import client.Character;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

/**
 * 163: the QA account's two companion alts, made once and kept: t-oa-1 keeps them for the cap and mx-5
 * ({@code soloMapling.qa.MechanicsScenarios}) leads them in KPQ. A character name is global in the game and both the
 * lookup ({@code Character.getIdByName}) and the create path work by name, so a fixed "AutoQaAlt" was created once by
 * the first lane to run t-oa-1 and then looked up by every other lane, on an account that did not own it: {@code
 * @botme} refused ("Choose another character on this account and world") and mx-5 could not stage at all (TEST
 * 2026-11-01 19:14 UTC). One pair of alts per GM account, made on demand by this one path.
 */
public final class QaAlts {

    /** t-oa-1 / t-sc-3: character slots the QA account needs (AutoQaOne, two alts, a throwaway, vb-2's). */
    public static final int QA_SLOTS = 8;

    private QaAlts() {
    }

    /**
     * This GM account's alt name for {@code which} ('A' or 'B'): one pair per account, never shared with another lane.
     */
    public static String altName(int accountId, char which) {
        return "Qa" + accountId + which;
    }

    /** The server-side steps of making the alts, so the rules are testable without a database or a client. */
    public interface Host {
        /** {@link #QA_SLOTS} character slots on the GM's account, in the client and in the database. */
        void roomForCharacters() throws Exception;

        /** {@code Character.getIdByName}: the character with this name, or -1. */
        int idByName(String name);

        /** The real create path. 0 = made; anything else is the status {@code BeginnerCreator} refused with. */
        int make(String name);
    }

    /**
     * The ids of this account's two alts ({@code 'A'}, {@code 'B'}), made now if missing; -1 for an alt that could not
     * be made. What was made, or why not, goes into {@code out}. Never throws: {@code QaScenario.stage} cannot.
     */
    public static int[] ensure(Host host, int accountId, String accountOwner, List<String> out) {
        try {
            host.roomForCharacters();
        } catch (Exception e) {
            out.add("could not give " + accountOwner + "'s account its slots: " + e.getMessage());
        }
        int[] ids = {-1, -1};
        char[] which = {'A', 'B'};
        for (int i = 0; i < which.length; i++) {
            String name = altName(accountId, which[i]);
            int id = host.idByName(name);
            if (id < 0) {
                int made = host.make(name);
                if (made == 0) {
                    id = host.idByName(name);
                    out.add("made " + name + " (" + id + ") on " + accountOwner + "'s account");
                } else {
                    out.add("could not make " + name + " (status " + made + ")");
                }
            }
            ids[i] = id;
        }
        return ids;
    }

    /** The real server: the GM's client and account row, and the beginner's create path. */
    public static Host liveHost(Character gm) {
        return new Host() {
            @Override
            public void roomForCharacters() throws Exception {
                if (gm.getClient().getCharacterSlots() < QA_SLOTS) {
                    gm.getClient().setCharacterSlots((byte) QA_SLOTS);
                }
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement("UPDATE accounts SET characterslots = ? WHERE id = ? AND characterslots < ?")) {
                    ps.setInt(1, QA_SLOTS);
                    ps.setInt(2, gm.getAccountID());
                    ps.setInt(3, QA_SLOTS);
                    ps.executeUpdate();
                }
            }

            @Override
            public int idByName(String name) {
                return Character.getIdByName(name);
            }

            @Override
            public int make(String name) {
                return client.creator.novice.BeginnerCreator.createCharacter(gm.getClient(), name, 20000, 30000, 0, 0,
                        0, 0, 0, 0);
            }
        };
    }
}

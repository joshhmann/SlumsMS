package client;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservedNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {"admin", "ADMIN", "Admin", "gm", "GM", "administrator", "owner", "staff", "mod",
            "moderator", "sysop", "gamemaster", "root",
            // digit/letter swaps and lookalike letters
            "4dm1n", "adm1n", "@dmin", "admln", "adrnin", "m0d", "M0D", "0wn3r", "5taff", "$taff", "5y5op", "sys0p",
            "m0d3r4t0r", "6m", "9M",
            // separators and trailing digits
            "a.d.m.i.n", "a_d_m_i_n", "ad-min", "ad min", "admin1", "admin_01", "mod2", "gm_1", "owner99", "[GM]",
            // GM followed by a separator or digit, or a staff word anywhere
            "gm_josh", "gm-josh", "gm2", "[gm]josh", "xXadminXx", "superadmin", "the_m0derator", "SysOpBob"})
    void staffLookingAccountNamesAreReserved(String name) {
        assertTrue(ReservedNames.isReservedAccountName(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"josh", "mapler", "Gmail", "gmoney", "modern", "model", "stuff", "owen", "joshgm",
            "slums", "leafre", "ad", "min", "Odin"})
    void ordinaryAccountNamesAreFree(String name) {
        assertFalse(ReservedNames.isReservedAccountName(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GMJosh", "GmJosh", "gmjosh", "GM", "GM1", "6MJosh", "9mBob", "Admin", "4dm1n",
            "xXAdminXx", "Owner", "0wner", "TheStaff", "St4ff", "Moderator", "M0derat0r", "SysOp", "Mod", "M0d",
            "Mod7", "GameMaster", "Adrnin"})
    void gmLookingCharacterNamesAreRefused(String name) {
        assertTrue(ReservedNames.looksLikeStaffCharacterName(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Josh", "Mapler", "Modern", "Modo", "Leafre", "BigM", "SoloMapler", "Kobold", "Stuffy",
            "JoshGm", "Admiral", "Owen"})
    void ordinaryCharacterNamesPass(String name) {
        assertFalse(ReservedNames.looksLikeStaffCharacterName(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"4dm1n", "admln", "adrnin", "ADMIN", "a.d-m_i n"})
    void lookalikesFoldToOneSpelling(String name) {
        assertTrue(ReservedNames.canonical(name).equals("admin"), name + " -> " + ReservedNames.canonical(name));
    }
}

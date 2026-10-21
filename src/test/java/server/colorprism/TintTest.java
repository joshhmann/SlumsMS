package server.colorprism;

import client.Character;
import client.Client;
import client.inventory.Equip;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * The package README's mandatory model rule: hue only -360..-1 (absolute hue h is -(h+1)), 0/0/0 untinted,
 * chroma/bright clamped to -100..100, and any other hue CLEARS the tint (it is never wrapped or kept).
 */
class TintTest {
    private static final int CASH_WEAPON = 1702000;

    /** A real Equip asks ItemInformationProvider for its level; give it one with no WZ and no database. */
    @BeforeAll
    static void initializeItemProviderWithoutExternalData() throws Exception {
        initItemProvider();
    }

    static void initItemProvider() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection).thenReturn(mock(java.sql.Connection.class, RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    /** Equip's constructor reads the item level; an empty provider answers it. */
    static <T> T withItemProvider(java.util.function.Supplier<T> body) {
        try (var ii = mockStatic(server.ItemInformationProvider.class)) {
            ii.when(server.ItemInformationProvider::getInstance).thenReturn(mock(server.ItemInformationProvider.class));
            return body.get();
        }
    }

    static Equip equip(int itemId, int position) {
        return withItemProvider(() -> new Equip(itemId, (short) position));
    }

    @Test
    void hueEncodingIsMinusHuePlusOne() {
        assertEquals(-1, Tint.encodeHue(0));
        assertEquals(-360, Tint.encodeHue(359));
        assertEquals(-181, Tint.encodeHue(180));
        assertTrue(Tint.isAbsoluteHue(-1));
        assertTrue(Tint.isAbsoluteHue(-360));
        assertFalse(Tint.isAbsoluteHue(0));
        assertFalse(Tint.isAbsoluteHue(1));      // the old relative encoding
        assertFalse(Tint.isAbsoluteHue(359));
        assertFalse(Tint.isAbsoluteHue(-361));
    }

    @Test
    void handlerValidationDoesNotClamp() {
        assertTrue(Tint.isValid(-1, -100, 100));
        assertFalse(Tint.isValid(-1, 101, 0));
        assertFalse(Tint.isValid(-1, 0, -101));
        assertFalse(Tint.isValid(0, 10, 10));
    }

    @Test
    void equipBodyTintAcceptsOnlyAbsoluteHuesAndClampsDeltas() {
        Equip e = equip(CASH_WEAPON, 1);
        assertFalse(e.isTinted());

        e.setTint(-360, 150, -150);
        assertTrue(e.isTinted());
        assertEquals(-360, e.getTintHue());
        assertEquals(100, e.getTintChroma());
        assertEquals(-100, e.getTintBright());

        for (int bad : new int[]{0, 1, 120, 359, 360, -361, Short.MIN_VALUE}) {
            e.setTint(-5, 5, 5);
            e.setTint(bad, 20, 20);
            assertFalse(e.isTinted(), "hue " + bad + " must clear the tint");
            assertEquals(0, e.getTintHue());
            assertEquals(0, e.getTintChroma());
            assertEquals(0, e.getTintBright());
        }
    }

    @Test
    void equipEffectTintIsIndependentOfTheBody() {
        Equip e = equip(CASH_WEAPON, 1);
        e.setTint(-10, 0, 0);
        e.setFxTint(-200, -40, 30);
        assertTrue(e.isFxTinted());
        assertEquals(-200, e.getTintFxHue());
        assertEquals(-40, e.getTintFxChroma());
        assertEquals(30, e.getTintFxBright());

        e.setFxTint(0, 50, 50);
        assertFalse(e.isFxTinted());
        assertTrue(e.isTinted());
        e.clearTint();
        assertFalse(e.isTinted());
    }

    @Test
    void copyKeepsBothTints() {
        Equip e = equip(CASH_WEAPON, 1);
        e.setTint(-1, 10, -10);
        e.setFxTint(-360, 100, 100);
        Equip c = withItemProvider(() -> (Equip) e.copy());
        assertEquals(-1, c.getTintHue());
        assertEquals(10, c.getTintChroma());
        assertEquals(-10, c.getTintBright());
        assertEquals(-360, c.getTintFxHue());
        assertEquals(100, c.getTintFxChroma());
        assertEquals(100, c.getTintFxBright());
    }

    @Test
    void hairAndFaceFollowTheSameRule() {
        Character chr = Character.getDefault(mock(Client.class));
        chr.setHairTint(-50, 200, -200);
        assertTrue(chr.isHairTinted());
        assertEquals(-50, chr.getHairTintHue());
        assertEquals(100, chr.getHairTintChroma());
        assertEquals(-100, chr.getHairTintBright());
        assertFalse(chr.isFaceTinted());

        chr.setFaceTint(-7, 1, 2);
        assertTrue(chr.isFaceTinted());
        chr.setFaceTint(7, 1, 2);            // a positive hue clears
        assertFalse(chr.isFaceTinted());
        assertEquals(0, chr.getFaceTintChroma());

        chr.setHairTint(0, 0, 0);
        assertFalse(chr.isHairTinted());
        chr.setHairTint(-1, 0, 0);           // hue 0 degrees with no deltas is still a tint
        assertTrue(chr.isHairTinted());
        chr.clearHairTint();
        assertFalse(chr.isHairTinted());
    }
}

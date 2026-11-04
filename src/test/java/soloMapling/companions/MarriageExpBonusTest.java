package soloMapling.companions;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 189 stage 3: marriage EXP bonus, +10% playerbot spouse, +5% out altbot spouse, behind a switch. */
class MarriageExpBonusTest {
    private String before;
    private MapleMap map;

    @BeforeEach
    void on() {
        before = System.getProperty(MarriageExpBonus.SWITCH);
        System.setProperty(MarriageExpBonus.SWITCH, "true");
        map = mock(MapleMap.class);
    }

    @AfterEach
    void restore() {
        if (before == null) System.clearProperty(MarriageExpBonus.SWITCH);
        else System.setProperty(MarriageExpBonus.SWITCH, before);
    }

    private Character me(boolean married) {
        Character c = mock(Character.class);
        when(c.isMarried()).thenReturn(married);
        when(c.getPartnerId()).thenReturn(married ? 7 : -1);
        when(c.getMap()).thenReturn(map);
        return c;
    }

    private void stub(int id, Character c) {
        when(map.getCharacterById(id)).thenReturn(c);
    }

    private Character bot() {
        Character b = mock(Character.class);
        when(b.getClient()).thenReturn(mock(BotClient.class));
        return b;
    }

    private Character alt(boolean closing) {
        Character a = bot();
        when(a.isOwnedAlt()).thenReturn(true);
        when(a.isOwnedAltClosing()).thenReturn(closing);
        return a;
    }

    @Test
    void switchOffGivesNothing() {
        System.clearProperty(MarriageExpBonus.SWITCH);
        stub(7, bot());
        assertEquals(0, MarriageExpBonus.bonusFor(me(true), 1000));
        stub(7, alt(false));
        assertEquals(0, MarriageExpBonus.bonusFor(me(true), 1000));
    }

    @Test
    void playerbotSpouseSameMapGivesTenPercent() {
        stub(7, bot());
        assertEquals(100, MarriageExpBonus.bonusFor(me(true), 1000));
    }

    @Test
    void outAltSpouseSameMapGivesFivePercent() {
        stub(7, alt(false));
        assertEquals(50, MarriageExpBonus.bonusFor(me(true), 1000));
    }

    @Test
    void spouseElsewhereOfflineOrDismissedGivesNothing() {
        stub(7, null); // other map or offline
        assertEquals(0, MarriageExpBonus.bonusFor(me(true), 1000));
        stub(7, alt(true)); // dismissed
        assertEquals(0, MarriageExpBonus.bonusFor(me(true), 1000));
    }

    @Test
    void realPlayerSpouseGivesNothing() {
        Character p = mock(Character.class);
        stub(7, p);
        assertEquals(0, MarriageExpBonus.bonusFor(me(true), 1000));
    }

    @Test
    void divorcedOrUnmarriedGivesNothing() {
        stub(-1, bot());
        stub(7, bot());
        assertEquals(0, MarriageExpBonus.bonusFor(me(false), 1000));
    }

    @Test
    void roundingFloorsNeverNegativeZeroStaysZero() {
        stub(7, bot());
        Character c = me(true);
        assertEquals(0, MarriageExpBonus.bonusFor(c, 9));
        assertEquals(1, MarriageExpBonus.bonusFor(c, 19));
        assertEquals(0, MarriageExpBonus.bonusFor(c, 0));
        assertEquals(0, MarriageExpBonus.bonusFor(c, -500));
        assertEquals(214748364, MarriageExpBonus.bonusFor(c, Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, MarriageExpBonus.share(Long.MAX_VALUE, 0.10));
    }

    @Test
    void bonusIsAFlatShareOfTheBaseNotCompounded() {
        // a coupon or family buff only scales the base the share is taken from; no rate is stacked on the rate
        stub(7, bot());
        Character c = me(true);
        int plain = MarriageExpBonus.bonusFor(c, 1000);
        int doubled = MarriageExpBonus.bonusFor(c, 2000);
        assertEquals(100, plain);
        assertEquals(200, doubled);
    }
}

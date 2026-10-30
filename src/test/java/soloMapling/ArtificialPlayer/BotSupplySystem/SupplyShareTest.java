package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotSupplySystem.SupplyShare.Kind;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade.Give;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 ticket 99: supply sharing (nutnnut/Cosmic BotPotionManager / BotAmmoManager / BotRockManager): the donor with
 * the most to spare gives, always keeping its own share, at most one trade's worth.
 */
class SupplyShareTest {

    final Map<Integer, List<Item>> hp = new HashMap<>();

    @BeforeEach
    void setUp() {
        SupplyShare.useForTest((holder, kind, needy) -> kind == Kind.HP ? hp.getOrDefault(holder.getId(), List.of()) : List.of());
    }

    @AfterEach
    void tearDown() {
        SupplyShare.useForTest(null);
    }

    private Character chr(int id, int... stacks) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        Item[] items = new Item[stacks.length];
        for (int i = 0; i < stacks.length; i++) {
            items[i] = new Item(2000000, (short) (i + 1), (short) stacks[i]);
        }
        hp.put(id, List.of(items));
        return c;
    }

    @Test
    void aDonorKeepsItsShareAndGivesAtMostOneTradesWorth() {
        Character rich = chr(1, 200, 100);
        Character modest = chr(2, 60);
        Character poor = chr(3, 40);
        Character needy = chr(4);
        assertEquals(SupplyShare.GIVE_POTS, SupplyShare.spare(rich, Kind.HP, needy));
        assertEquals(60 - SupplyShare.KEEP_POTS, SupplyShare.spare(modest, Kind.HP, needy));
        assertEquals(0, SupplyShare.spare(poor, Kind.HP, needy));
        assertSame(rich, SupplyShare.bestDonor(List.of(poor, modest, rich), Kind.HP, needy));
        assertNull(SupplyShare.bestDonor(List.of(poor), Kind.HP, needy), "nobody has spare");
        assertNull(SupplyShare.bestDonor(List.of(needy), Kind.HP, needy), "never itself");
    }

    @Test
    void theHandoverTakesFromTheFullestStackFirst() {
        Character rich = chr(1, 30, 200);
        List<Give> gives = SupplyShare.handover(rich, Kind.HP, chr(9));
        assertEquals(1, gives.size());
        assertEquals(200, gives.get(0).item().getQuantity());
        assertEquals(SupplyShare.GIVE_POTS, gives.get(0).quantity());
    }

    @Test
    void lowOnlyCountsWhatItUses() {
        Character empty = chr(5, 3);
        assertTrue(SupplyShare.low(empty, Kind.HP, true));
        assertFalse(SupplyShare.low(empty, Kind.HP, false));
        assertFalse(SupplyShare.low(chr(6, 500), Kind.HP, true));
    }

    @Test
    void needWords() {
        assertEquals(List.of(Kind.HP, Kind.MP), SupplyShare.kindsOf("pots"));
        assertEquals(List.of(Kind.MP), SupplyShare.kindsOf("mp"));
        assertEquals(List.of(Kind.AMMO), SupplyShare.kindsOf("ammo"));
        assertEquals(List.of(Kind.ROCKS), SupplyShare.kindsOf("rocks"));
        assertTrue(SupplyShare.kindsOf("anything").isEmpty());
    }
}

package server.setitem;

import client.BotClient;
import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The set bonus goes through Character's own stat recalculation (recalcEquipStats via reapplyLocalStats). */
class SetBonusStatsTest {

    private Character chr;
    private short slot = -1;

    @BeforeEach
    void setUp() throws Exception {
        SetItemCatalog.useForTest(SetItemCatalogTest.marx());
        chr = Character.getDefault(new BotClient(0, 0));
    }

    @AfterEach
    void tearDown() {
        SetItemCatalog.useForTest(null);
    }

    private void wear(int itemId, int str, int watk) {
        Equip e = mock(Equip.class);   // a real Equip loads the item data the tests do not have
        when(e.getItemId()).thenReturn(itemId);
        when(e.getPosition()).thenReturn(slot--);
        when(e.getStr()).thenReturn((short) str);
        when(e.getWatk()).thenReturn((short) watk);
        chr.getInventory(InventoryType.EQUIPPED).addItemFromDB(e);
    }

    private void recalc() throws Exception {
        Field changed = Character.class.getDeclaredField("equipchanged");
        changed.setAccessible(true);
        changed.setBoolean(chr, true);
        Method m = Character.class.getDeclaredMethod("reapplyLocalStats");
        m.setAccessible(true);
        m.invoke(chr);
    }

    @Test
    void wornTiersAddToTheTotals() throws Exception {
        recalc();
        int str = chr.getTotalStr(), dex = chr.getTotalDex(), int_ = chr.getTotalInt(), luk = chr.getTotalLuk();
        int watk = chr.getTotalWatk(), matk = chr.getTotalMagic(), hp = chr.getCurrentMaxHp(), mp = chr.getCurrentMaxMp();

        // Three pieces: below the first tier, only the items' own stats count.
        wear(1003154, 7, 0);
        wear(1052299, 0, 0);
        wear(1082285, 0, 1);
        recalc();
        assertEquals(str + 7, chr.getTotalStr());
        assertEquals(watk + 1, chr.getTotalWatk());
        assertEquals(server.setitem.SetBonus.NONE, chr.getSetBonus());

        // Five pieces: tier 4 (AllStat 4) + tier 5 (AllStat 6, PAD 10, MAD 10).
        wear(1072471, 0, 0);
        wear(1402091, 0, 100);
        recalc();
        assertEquals(str + 7 + 10, chr.getTotalStr());
        assertEquals(dex + 10, chr.getTotalDex());
        assertEquals(int_ + 10, chr.getTotalInt());
        assertEquals(luk + 10, chr.getTotalLuk());
        assertEquals(watk + 1 + 100 + 10, chr.getTotalWatk());
        assertEquals(matk + 10 + 10, chr.getTotalMagic(), "set INT raises magic like item INT, plus MAD");

        // Both Goddess pieces: +300 MaxHP, +200 MaxMP on top.
        wear(1072455, 0, 0);
        wear(1082232, 0, 0);
        recalc();
        assertEquals(hp + 300, chr.getCurrentMaxHp());
        assertEquals(mp + 200, chr.getCurrentMaxMp());
        assertEquals(new SetBonus(10, 10, 10, 10, 300, 200, 10, 10), chr.getSetBonus());

        // Taking pieces off drops the bonus again.
        chr.getInventory(InventoryType.EQUIPPED).removeSlot((short) -5);
        chr.getInventory(InventoryType.EQUIPPED).removeSlot((short) -7);
        recalc();
        assertEquals(str + 7 + 4, chr.getTotalStr(), "four Marx pieces: tier 4 only");
        assertEquals(hp, chr.getCurrentMaxHp(), "one Goddess piece: no tier");
    }

    @Test
    void anEmptyCatalogChangesNothing() throws Exception {
        SetItemCatalog.useForTest(SetItemCatalog.EMPTY);
        wear(1003154, 0, 0);
        wear(1052299, 0, 0);
        wear(1082285, 0, 0);
        wear(1072471, 0, 0);
        recalc();
        int str = chr.getTotalStr();
        SetItemCatalog.useForTest(SetItemCatalogTest.marx());
        recalc();
        assertEquals(str + 4, chr.getTotalStr());
    }
}

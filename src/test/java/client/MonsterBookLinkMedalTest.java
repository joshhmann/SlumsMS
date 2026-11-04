package client;

import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The Monster Book bonus is written onto the link medal item 1113232, which before this only {@code CharacterFactory}
 * ever created - in the new-character starter set. A character that completed a tier without going through that path
 * reached {@code applyLinkStatsBoost}, found no medal, and got nothing but a console line.
 */
class MonsterBookLinkMedalTest {

    private static final int LINK_MEDAL = 1113232;

    private MockedStatic<ItemInformationProvider> itemData;

    /** ItemInformationProvider reads its card and equip tables on construction, so it needs a database to exist. */
    @BeforeAll
    static void loadItemProviderClass() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @BeforeEach
    void setUp() {
        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        when(ii.getEquipById(LINK_MEDAL)).thenAnswer(invocation -> new Equip(LINK_MEDAL, (short) 0));
        itemData = mockStatic(ItemInformationProvider.class);
        itemData.when(ItemInformationProvider::getInstance).thenReturn(ii);
    }

    @AfterEach
    void tearDown() {
        itemData.close();
    }

    private static Character character() {
        return Character.getDefault(mock(Client.class));
    }

    private static int medalCount(Character chr) {
        int count = chr.getInventory(InventoryType.EQUIP).listById(LINK_MEDAL).size();
        return count + chr.getInventory(InventoryType.EQUIPPED).listById(LINK_MEDAL).size();
    }

    @Test
    void aCompletedTierEarnsTheMedal() {
        Character chr = character();
        chr.AddTier1(1);

        chr.applyLinkStatsBoost();

        assertEquals(1, medalCount(chr), "a character with a completed tier and no medal must end up with the medal");
        Equip medal = (Equip) chr.getInventory(InventoryType.EQUIP).findById(LINK_MEDAL);
        assertNotNull(medal, "the grant lands in the EQUIP tab, where the stat rewrite can reach it");
        assertEquals(25, medal.getHp(), "tier 1 is worth 25 HP on the medal");
        assertEquals(1, medal.getStr(), "tier 1 is worth 1 STR on the medal");
    }

    @Test
    void applyingTwiceGrantsOnlyOneMedal() {
        Character chr = character();
        chr.AddTier1(1);

        chr.applyLinkStatsBoost();
        chr.applyLinkStatsBoost();
        chr.applyLinkStatsBoost();

        assertEquals(1, medalCount(chr), "a repeat login or completion must not hand out a second medal");
    }

    @Test
    void aWornMedalIsReusedRatherThanDuplicated() {
        Character chr = character();
        chr.AddTier1(1);
        Equip worn = new Equip(LINK_MEDAL, (short) -1);
        chr.getInventory(InventoryType.EQUIPPED).addItem(worn);

        chr.applyLinkStatsBoost();

        assertEquals(1, medalCount(chr), "the worn medal is the one the bonus belongs on");
        assertEquals(25, worn.getHp(), "and it is the one that gets the stats");
    }

    @Test
    void nothingIsGrantedBeforeATierIsCompleted() {
        Character chr = character();

        chr.applyLinkStatsBoost();

        assertEquals(0, medalCount(chr), "the medal is a reward, not a starter item, for a character that has earned none");
    }

    @Test
    void aFullEquipTabLeavesTheRewardOwedRatherThanDropped() {
        Character chr = character();
        chr.AddTier1(1);
        Inventory equip = chr.getInventory(InventoryType.EQUIP);
        byte limit = equip.getSlotLimit();
        for (short slot = 1; slot <= limit; slot++) {
            equip.addItem(new Equip(1000000 + slot, slot));
        }
        assertTrue(equip.isFull(), "the EQUIP tab starts full");

        chr.applyLinkStatsBoost();
        assertEquals(0, equip.listById(LINK_MEDAL).size(), "a full tab cannot hold the medal");

        equip.removeItem((short) 1);
        chr.applyLinkStatsBoost();
        assertEquals(1, equip.listById(LINK_MEDAL).size(), "the grant is retried instead of being lost");
    }
}

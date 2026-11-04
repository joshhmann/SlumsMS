package soloMapling.ArtificialPlayer.BotMakerSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.MakerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotGearSystem.GearScoring;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Ticket 181 prog#P2: the junk test asked the live gear score "would the bot wear it?", which answers 0 for any piece
 * above the bot's level - so every endgame piece a bot could not wear yet was disassembled. The test now asks
 * {@link GearScoring#holdingValue}, the same question at the piece's own required level.
 */
class BotMakerJunkTest {

    private static final int WORN_HAT = 1002000;
    private static final int HIGH_HAT = 1002003;    // above the bot's level: worth keeping
    private static final int DEAD_EQUIP = 1002004;  // no gain even for someone who can wear it: junk

    private MockedStatic<ItemInformationProvider> itemData;
    private MockedStatic<MakerService> maker;

    @BeforeAll
    static void loadItemProviderClass() throws Exception {
        // ItemInformationProvider's static init reads the DB and the WZ; load the class once against mocks.
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                    org.mockito.ArgumentMatchers.any(provider.wz.WZFiles.class))).thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @BeforeEach
    void setUp() {
        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        itemData = mockStatic(ItemInformationProvider.class);
        itemData.when(ItemInformationProvider::getInstance).thenReturn(ii);
        maker = mockStatic(MakerService.class);
        maker.when(() -> MakerService.canDisassemble(anyInt())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        maker.close();
        itemData.close();
    }

    /**
     * Stands in for the live planner: a piece above the wearer's level is dropped, otherwise it is worth its STR.
     */
    private static GearScoring scoring(Map<Integer, Integer> reqLevels) {
        return new GearScoring() {
            @Override
            public double upgradeValue(Character chr, Equip candidate) {
                return reqLevels.getOrDefault(candidate.getItemId(), 0) > chr.getLevel() ? 0 : worth(candidate);
            }

            @Override
            public double upgradeFraction(Character chr, Equip candidate) {
                return 0;
            }

            @Override
            public double holdingValue(Character chr, Equip candidate) {
                return worth(candidate);   // asked at the piece's own level, so it always fits
            }

            @Override
            public Map<Short, Equip> bestSet(Character chr, Collection<Equip> extras) {
                return Map.of();
            }
        };
    }

    private static double worth(Equip candidate) {
        return candidate.getStr() * 10.0;
    }

    @Test
    void aPieceAboveTheBotsLevelIsNotJunk() {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(9_000_100);
        bot.setJob(Job.BEGINNER);
        bot.setLevel(30);
        bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(new Equip(WORN_HAT, (short) -1));
        Equip endgame = new Equip(HIGH_HAT, (short) 1);
        endgame.setStr((short) 40);                                     // a big hat it cannot wear yet
        bot.getInventory(InventoryType.EQUIP).addItemFromDB(endgame);
        Equip dead = new Equip(DEAD_EQUIP, (short) 2);                   // nothing to gain, even at its own level
        bot.getInventory(InventoryType.EQUIP).addItemFromDB(dead);

        List<Equip> junk = BotMaker.junk(bot, scoring(Map.of(HIGH_HAT, 120, DEAD_EQUIP, 30)));

        assertEquals(List.of(dead), junk,
                "the piece too high to wear is kept for later; the one that brings nothing is broken down");
    }
}

package soloMapling.ArtificialPlayer.BotMakerSystem;

import client.BotClient;
import client.Character;
import client.Skill;
import client.SkillFactory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.MakerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.MakerItemFactory;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Ticket 112: a population bot's crystal goes through the Maker window's own code (MakerService's status checks and
 * result) with the bot's ledger as its hands: 100 leftovers out, one crystal in, nothing without the skill.
 */
class BotMakerPathTest {

    private Map<Integer, Skill> skillsWere;

    @BeforeAll
    static void initItemProvider() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection).thenReturn(mock(java.sql.Connection.class, RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
            Class.forName("server.MakerItemFactory");
        }
    }

    @BeforeEach
    void skills() throws Exception {
        skillsWere = swap(new HashMap<>(Map.of(1007, new Skill(1007))));
    }

    @AfterEach
    void restore() throws Exception {
        swap(skillsWere);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Skill> swap(Map<Integer, Skill> skills) throws Exception {
        Field f = SkillFactory.class.getDeclaredField("skills");
        f.setAccessible(true);
        Map<Integer, Skill> was = (Map<Integer, Skill>) f.get(null);
        f.set(null, skills);
        return was;
    }

    private static Character bot(int makerLevel) {
        Character chr = Character.getDefault(new BotClient(1, 1));
        chr.setName("CrystalBot");
        chr.setJob(client.Job.FIGHTER);
        chr.setLevel(60);
        if (makerLevel > 0) {
            chr.changeSkillLevel(SkillFactory.getSkill(1007), (byte) makerLevel, 3, -1);
        }
        chr.getInventory(InventoryType.ETC).addItem(new Item(4000000, (short) 0, (short) 100));
        chr.getInventory(InventoryType.ETC).addItem(new Item(4000000, (short) 0, (short) 30));
        return chr;
    }

    private static short craft(Character chr) {
        MakerService.Hands h = new BotMakerHands(chr);
        var recipe = MakerItemFactory.generateLeftoverCrystalEntry(4000000, 4260000);
        short status = MakerService.getCreateStatus(h, recipe);
        MakerService.applyMakerResult(h, status, 4260000, recipe, -1, (short) -1, -1, new LinkedHashMap<>(), true, 3);
        return status;
    }

    @Test
    void aBotMakesACrystalThroughTheMakerPath() {
        Character chr = bot(1);
        assertEquals(0, craft(chr));
        assertEquals(30, chr.getInventory(InventoryType.ETC).countById(4000000));
        assertEquals(1, chr.getInventory(InventoryType.ETC).countById(4260000));
        assertEquals(1, MakerService.getCreateStatus(new BotMakerHands(chr),
                MakerItemFactory.generateLeftoverCrystalEntry(4000000, 4260000)), "30 leftovers left: not enough");
        assertEquals(1, chr.getInventory(InventoryType.ETC).countById(4260000));
    }

    @Test
    void aBotWithoutMakerMakesNothing() {
        Character chr = bot(0);
        assertEquals(4, craft(chr));
        assertEquals(130, chr.getInventory(InventoryType.ETC).countById(4000000));
        assertEquals(0, chr.getInventory(InventoryType.ETC).countById(4260000));
    }
}

package soloMapling.companions;

import client.BotClient;
import client.Character;
import client.inventory.Equip;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGearSystem.GearUpgrades;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 alt-qol-batch group D: the upgrade offer after looting. One ask per piece, at most one per
 * {@link CompanionUpgradeOffer#OFFER_GAP_MS}, silenced by "offers off", and the owner's yes hands the piece over.
 */
class CompanionUpgradeOfferTest {

    private static final int OWNER = 7, ALT = 2;
    private static final int UPGRADE = 1302000, OTHER = 1202000; // the scorer below knows one band of ids

    private Character owner;
    private Character alt;
    private MapleMap field;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<String> handed = new ArrayList<>();
    private MockedStatic<OwnedAltService> services;
    private MockedStatic<ItemInformationProvider> itemData;

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
    void stage() {
        // an Equip's constructor asks the item data for its equip level; the offer's line asks for a name
        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        itemData = mockStatic(ItemInformationProvider.class);
        itemData.when(ItemInformationProvider::getInstance).thenReturn(ii);
        when(ii.getEquipLevel(anyInt(), anyBoolean())).thenReturn(0);
        when(ii.getName(anyInt())).thenReturn("Sword");
        CompanionSettingsStore.useForTest(new CompanionSettingsStore.Memory(), Runnable::run);
        CompanionSettingsStore.forgetForTest();
        CompanionUpgradeOffer.forget();
        GearUpgrades.install((wearer, candidate) -> candidate.getItemId() >= UPGRADE ? 4 : 0);
        CompanionUpgradeOffer.useForTest((from, to, piece, say) -> {
            handed.add(from.getName() + " -> " + to.getName() + " " + piece.getItemId());
            return true;
        }, now::get);

        field = mock(MapleMap.class);
        owner = mock(Character.class);
        when(owner.getId()).thenReturn(OWNER);
        when(owner.getName()).thenReturn("Main");
        when(owner.getMap()).thenReturn(field);
        alt = mock(Character.class);
        when(alt.getId()).thenReturn(ALT);
        when(alt.getName()).thenReturn("Jane");
        when(alt.isOwnedAlt()).thenReturn(true);
        when(alt.getOwnedAltOwner()).thenReturn(owner);
        when(alt.getClient()).thenReturn(mock(BotClient.class));
        when(alt.getMap()).thenReturn(field);

        OwnedAltService service = mock(OwnedAltService.class);
        services = mockStatic(OwnedAltService.class);
        services.when(OwnedAltService::getInstance).thenReturn(service);
        when(service.active()).thenReturn(List.of(new OwnedAltService.Active(owner, alt)));
    }

    @AfterEach
    void unstage() {
        itemData.close();
        services.close();
        CompanionUpgradeOffer.useForTest(null, null);
        CompanionUpgradeOffer.forget();
        GearUpgrades.install(null);
        CompanionSettingsStore.forgetForTest();
        CompanionSettingsStore.useForTest(null, null);
    }

    private static Equip equip(int id) {
        return new Equip(id, (short) 0);
    }

    @Test
    void anUpgradeForTheOwnerIsOfferedOnce() {
        List<String> said = new ArrayList<>();
        Equip piece = equip(UPGRADE);

        String line = CompanionUpgradeOffer.consider(alt, owner, piece, said::add);

        assertNotNull(line, "the alt looted a piece better than what its owner wears");
        assertTrue(line.startsWith("found a "), line);
        assertTrue(line.endsWith(", better than yours, want it?"), line);
        assertEquals(List.of(line), said);
        assertTrue(CompanionUpgradeOffer.waitingFor(owner));

        assertNull(CompanionUpgradeOffer.consider(alt, owner, piece, said::add),
                "the same piece is never offered twice");
        assertEquals(1, said.size());
    }

    @Test
    void aPieceThatHelpsNobodyIsNeverOffered() {
        List<String> said = new ArrayList<>();
        assertNull(CompanionUpgradeOffer.consider(alt, owner, equip(OTHER), said::add),
                "not an upgrade for the owner and no sibling wants it");
        assertTrue(said.isEmpty());
    }

    @Test
    void theRateLimitHoldsAndTheToggleSilencesIt() {
        List<String> said = new ArrayList<>();
        assertNotNull(CompanionUpgradeOffer.consider(alt, owner, equip(UPGRADE), said::add));

        now.addAndGet(CompanionUpgradeOffer.OFFER_GAP_MS - 1);
        assertNull(CompanionUpgradeOffer.consider(alt, owner, equip(UPGRADE + 2), said::add),
                "one ask per window, however many good pieces drop");

        now.addAndGet(2);
        assertNotNull(CompanionUpgradeOffer.consider(alt, owner, equip(UPGRADE + 2), said::add),
                "after the window a new piece is offered");
        assertEquals(2, said.size());

        CompanionSettingsStore.save(owner, alt, CompanionSettings.DEFAULT.withOffers(false));
        now.addAndGet(CompanionUpgradeOffer.OFFER_GAP_MS);
        assertNull(CompanionUpgradeOffer.consider(alt, owner, equip(UPGRADE + 3), said::add),
                "\"offers off\" silences it");
        assertEquals(2, said.size());
    }

    @Test
    void theSwitchOffForTheCompanionSilencesIt() {
        System.setProperty("slums.companion.loot.alts", "false");
        try {
            List<String> said = new ArrayList<>();
            assertNull(CompanionUpgradeOffer.consider(alt, owner, equip(UPGRADE), said::add),
                    "with the loot switch off for it there is no new line");
            assertTrue(said.isEmpty());
        } finally {
            System.clearProperty("slums.companion.loot.alts");
        }
    }

    @Test
    void theOwnersYesHandsThePieceOverAndAnythingElseDropsTheOffer() {
        List<String> said = new ArrayList<>();
        Equip piece = equip(UPGRADE);
        CompanionUpgradeOffer.consider(alt, owner, piece, said::add);

        assertFalse(CompanionUpgradeOffer.answer(owner, "brb"), "\"brb\" is not an answer");
        assertTrue(CompanionUpgradeOffer.waitingFor(owner), "the offer keeps waiting");

        assertTrue(CompanionUpgradeOffer.answer(owner, "yes"), "the yes hands it over");
        assertEquals(List.of("Jane -> Main " + UPGRADE), handed);
        assertFalse(CompanionUpgradeOffer.waitingFor(owner), "one yes, one handover");

        CompanionUpgradeOffer.consider(alt, owner, equip(UPGRADE + 5), said::add);
        assertFalse(CompanionUpgradeOffer.answer(owner, "no thanks"), "a no drops the offer");
        assertEquals(1, handed.size());
        assertFalse(CompanionUpgradeOffer.waitingFor(owner));
    }

    @Test
    void anExpiredOfferIsNotHandedOver() {
        List<String> said = new ArrayList<>();
        CompanionUpgradeOffer.consider(alt, owner, equip(UPGRADE), said::add);
        now.addAndGet(CompanionUpgradeOffer.OFFER_GAP_MS + 1);
        assertFalse(CompanionUpgradeOffer.answer(owner, "yes"), "too late: the piece may have moved on");
        assertTrue(handed.isEmpty());
    }
}

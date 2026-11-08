package soloMapling.companions;

import client.BotClient;
import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapItem;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 alt-qol-batch group D: the loot modes. An owned alt with no saved choice picks up only its own kills; every
 * mode narrows the drops the loot rules already allow (never widens); mesos and the companion's own active quest items
 * are taken in every mode except "nothing"; a party regular with the switch on and no choice keeps today's behaviour.
 */
class CompanionLootModeTest {

    static final int OWNER = 1, ALT = 2;

    private Character alt;
    private Monster snail;

    @BeforeEach
    void stage() {
        CompanionSettingsStore.useForTest(new CompanionSettingsStore.Memory(), Runnable::run);
        CompanionSettingsStore.forgetForTest();
        alt = mock(Character.class);
        when(alt.getId()).thenReturn(ALT);
        when(alt.isOwnedAlt()).thenReturn(true);
        when(alt.getClient()).thenReturn(mock(BotClient.class));
        snail = mock(Monster.class);
        when(snail.isAlive()).thenReturn(false);
    }

    @AfterEach
    void unstage() {
        CompanionSettingsStore.forgetForTest();
        CompanionSettingsStore.useForTest(null, null);
        System.clearProperty(PartyCompanionPolicy.Feature.LOOT.property(true));
        System.clearProperty(PartyCompanionPolicy.Feature.LOOT.property(false));
    }

    /** A drop of {@code itemId} owned by {@code ownerId}, dropped by {@code mob} (null: nobody). */
    static MapItem drop(int ownerId, int itemId, Monster mob) {
        MapItem d = mock(MapItem.class);
        when(d.getOwnerId()).thenReturn(ownerId);
        if (itemId > 0) {
            when(d.getItem()).thenReturn(new Item(itemId, (short) 0, (short) 1));
            when(d.getItemId()).thenReturn(itemId);
        }
        when(d.getDropper()).thenReturn(mob);
        return d;
    }

    static MapItem meso(int amount) {
        MapItem d = mock(MapItem.class);
        when(d.getMeso()).thenReturn(amount);
        return d;
    }

    /** A quest drop: the drop names quest {@code questId}, and the companion needs it. */
    static MapItem questDrop(Character chr, int itemId, int questId, boolean needed) {
        MapItem d = drop(ALT, itemId, null);
        when(d.getQuest()).thenReturn(questId);
        when(chr.needQuestItem(questId, itemId)).thenReturn(needed);
        return d;
    }

    private void save(String mode) {
        CompanionSettingsStore.save(mock(Character.class, org.mockito.Mockito.RETURNS_DEFAULTS), alt,
                CompanionSettings.DEFAULT.withLootMode(mode));
    }

    @Test
    void noSavedSettingIsMyKillsForAnAltAndEverythingWithTheSwitchOff() {
        assertEquals(CompanionLootMode.MY_KILLS, CompanionLootMode.of(alt),
                "an alt with no saved choice picks up only its own kills (owner decision)");

        System.setProperty(PartyCompanionPolicy.Feature.LOOT.property(true), "false");
        assertEquals(CompanionLootMode.EVERYTHING, CompanionLootMode.of(alt),
                "with the switch off for alts nothing narrows: today's behaviour");

        System.setProperty(PartyCompanionPolicy.Feature.LOOT.property(true), "true");
        Character regular = mock(Character.class);
        when(regular.getClient()).thenReturn(mock(BotClient.class));
        assertEquals(CompanionLootMode.EVERYTHING, CompanionLootMode.of(regular),
                "a regular with the switch off is unchanged");

        System.setProperty(PartyCompanionPolicy.Feature.LOOT.property(false), "true");
        assertEquals(CompanionLootMode.EVERYTHING, CompanionLootMode.of(regular),
                "with the regulars switch on and no saved choice, still today's behaviour");
    }

    @Test
    void eachModePicksAndSkipsWithTheMesoAndQuestExemptions() {
        MapItem partyDrop = drop(OWNER, 4000000, snail);
        when(snail.isAttackedBy(alt)).thenReturn(false);
        assertTrue(CompanionLootMode.EVERYTHING.permits(alt, partyDrop));
        assertFalse(CompanionLootMode.MY_KILLS.permits(alt, partyDrop), "the owner's kill is not its kill");
        assertFalse(CompanionLootMode.QUEST_ONLY.permits(alt, partyDrop));
        assertFalse(CompanionLootMode.NOTHING.permits(alt, partyDrop));

        MapItem itsKill = drop(OWNER, 4000001, snail);
        when(snail.isAttackedBy(alt)).thenReturn(true);
        assertTrue(CompanionLootMode.MY_KILLS.permits(alt, itsKill), "a mob it hit and that died");
        assertTrue(CompanionLootMode.EVERYTHING.permits(alt, itsKill));

        assertTrue(CompanionLootMode.MY_KILLS.permits(alt, meso(500)), "mesos are taken in every mode but nothing");
        assertTrue(CompanionLootMode.QUEST_ONLY.permits(alt, meso(500)));
        assertFalse(CompanionLootMode.NOTHING.permits(alt, meso(500)), "\"nothing\" means nothing");

        MapItem needed = questDrop(alt, 4030000, 1234, true);
        assertTrue(CompanionLootMode.QUEST_ONLY.permits(alt, needed), "its own active quest item");
        assertTrue(CompanionLootMode.MY_KILLS.permits(alt, needed));
        assertFalse(CompanionLootMode.NOTHING.permits(alt, needed));
        MapItem notNeeded = questDrop(alt, 4030001, 1235, false);
        assertFalse(CompanionLootMode.QUEST_ONLY.permits(alt, notNeeded));

        MapItem etcJunk = drop(ALT, 4000000, snail);
        assertFalse(CompanionLootMode.LIKE_YOU.permits(alt, etcJunk), "etc junk is what a junk-skipping bot leaves");
        MapItem equip = drop(ALT, 1302000, snail);
        assertTrue(CompanionLootMode.LIKE_YOU.permits(alt, equip), "gear is never junk");
        assertTrue(CompanionLootMode.LIKE_YOU.permits(alt, meso(50)));
    }

    @Test
    void aLivingMobsDropIsNoOnesKill() {
        Monster alive = mock(Monster.class);
        when(alive.isAlive()).thenReturn(true);
        when(alive.isAttackedBy(alt)).thenReturn(true);
        assertFalse(CompanionLootMode.MY_KILLS.permits(alt, drop(OWNER, 4000000, alive)),
                "a mob still standing has not been killed by anybody");
        MapItem credited = drop(ALT, 4000000, alive);
        assertTrue(CompanionLootMode.MY_KILLS.permits(alt, credited), "credited to it all the same");
        assertFalse(CompanionLootMode.MY_KILLS.permits(alt, drop(OWNER, 4000000, null)),
                "a drop off no mob (a reactor's) is not its kill either");
    }

    @Test
    void modesAreStoredAndReadBack() {
        assertEquals(CompanionLootMode.EVERYTHING, CompanionLootMode.parse("everything"));
        assertEquals(CompanionLootMode.MY_KILLS, CompanionLootMode.parse("my kills"));
        assertEquals(CompanionLootMode.QUEST_ONLY, CompanionLootMode.parse("quest only"));
        assertEquals(CompanionLootMode.LIKE_YOU, CompanionLootMode.parse("like you"));
        assertEquals(CompanionLootMode.NOTHING, CompanionLootMode.parse("nothing"));
        assertNull(CompanionLootMode.parse(""), "\"\" is no choice, never everything");
        assertNull(CompanionLootMode.parse("nonsense"));
        for (CompanionLootMode mode : CompanionLootMode.values()) {
            assertEquals(mode, CompanionLootMode.parse(mode.stored()), mode.name());
            assertEquals(mode, CompanionLootMode.parse(" " + mode.stored().toUpperCase(java.util.Locale.ROOT) + " "));
        }
        assertEquals(CompanionLootMode.EVERYTHING, CompanionLootMode.NOTHING.next(), "the card's one line cycles");
        assertEquals(5, CompanionLootMode.values().length);
    }

    @Test
    void aRegularIsUnchangedUntilTheSwitchIsOnAndAModeIsSaved() {
        Character regular = mock(Character.class);
        when(regular.getId()).thenReturn(ALT + 1);
        when(regular.getClient()).thenReturn(mock(BotClient.class));
        MapItem itsOwnDrop = drop(ALT + 1, 4000000, snail);   // the etc junk a junk-skipping bot leaves
        MapItem someoneElses = drop(OWNER, 4000000, snail);
        assertTrue(CompanionLootMode.allows(regular, itsOwnDrop), "a regular with the switches off: today's rules only");
        assertTrue(CompanionLootMode.allows(regular, someoneElses));

        System.setProperty(PartyCompanionPolicy.Feature.LOOT.property(false), "true");
        assertTrue(CompanionLootMode.allows(regular, someoneElses), "the switch alone narrows nothing (no saved choice)");

        CompanionSettingsStore.save(mock(Character.class), regular, CompanionSettings.DEFAULT.withLootMode("my kills"));
        assertFalse(CompanionLootMode.allows(regular, someoneElses), "the saved mode is applied to a regular too");
        when(snail.isAttackedBy(regular)).thenReturn(true);
        assertTrue(CompanionLootMode.allows(regular, itsOwnDrop));
    }

    @Test
    void theStoredModeIsWhatAllowsAnswersWith() {
        save("nothing");
        MapItem mesos = meso(1_000);
        assertFalse(CompanionLootMode.allows(alt, mesos), "the saved mode is in force, not the default");
        assertEquals(InventoryType.ETC, client.inventory.InventoryType.ETC); // keep the import honest
    }
}

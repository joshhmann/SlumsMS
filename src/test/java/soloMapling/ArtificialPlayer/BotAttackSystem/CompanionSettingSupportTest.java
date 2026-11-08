package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.BotClient;
import client.Character;
import client.Job;
import constants.skills.Fighter;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import soloMapling.companions.CompanionSettings;
import soloMapling.companions.CompanionSettingsStore;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 alt-settings: "support off" must stop a companion's party buffs while its own self-buffs stay - the gate is in
 * BotBuffDriver (the shared layer both owned alts and party regulars run), not in OwnedAltCompanion.
 */
class CompanionSettingSupportTest {

    private static final int ALT = 1_000_000_777;
    private static final int OWNER = 4_000_777;

    @AfterEach
    void clean() {
        BotBuffDriver.clearBot(ALT);
        System.clearProperty("slums.bots.partysupport");
        CompanionSettingsStore.useForTest(null, null);
    }

    @Test
    void supportOffSkipsThePartyBuffsAndKeepsTheSelfBuffs() {
        System.setProperty("slums.bots.partysupport", "true");
        Character alt = partiedAlt();
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(OWNER);
        CompanionSettingsStore.useForTest(new CompanionSettingsStore.Memory(), Runnable::run);

        try (MockedStatic<BotBuffEffects> fx = Mockito.mockStatic(BotBuffEffects.class);
             MockedStatic<soloMapling.server.MethodScheduler> sched =
                     Mockito.mockStatic(soloMapling.server.MethodScheduler.class)) {
            fx.when(() -> BotBuffEffects.isPartyBuff(Fighter.RAGE)).thenReturn(true);
            fx.when(() -> BotBuffEffects.isPartyBuff(Fighter.POWER_GUARD)).thenReturn(false);
            fx.when(() -> BotBuffEffects.durationOf(any(), anyInt())).thenReturn(60_000);
            fx.when(() -> BotBuffEffects.castLevel(any(), anyInt())).thenReturn(10);
            fx.when(() -> BotBuffEffects.payFor(any(), anyInt())).thenReturn(true);
            fx.when(() -> BotBuffEffects.partyMemberLacks(any(), anyInt())).thenReturn(false);

            int untouched = BotBuffDriver.maintain(alt, 0, BotBuffDriver.Pay.ALL);
            assertTrue(untouched >= 2, "untouched: the party buff and the self-buff(s), got " + untouched);
            BotBuffDriver.clearBot(ALT);

            CompanionSettingsStore.save(owner, alt, CompanionSettings.DEFAULT.withSupport(false));
            assertEquals(untouched - 1, BotBuffDriver.maintain(alt, 0, BotBuffDriver.Pay.ALL),
                    "support off: the party buff is not cast, the self-buffs still are");
            BotBuffDriver.clearBot(ALT);

            CompanionSettingsStore.save(owner, alt, CompanionSettings.DEFAULT.withSupport(true));
            assertEquals(untouched, BotBuffDriver.maintain(alt, 0, BotBuffDriver.Pay.ALL),
                    "support on again: both");
        } finally {
            BotBuffDriver.clearBot(ALT);
        }
    }

    @Test
    void aRegularWithTheDefaultsStillGetsItsPartyBuff() {
        System.setProperty("slums.bots.partysupport", "true");
        Character regular = partiedAlt();
        when(regular.isOwnedAlt()).thenReturn(false);
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(OWNER);
        CompanionSettingsStore.useForTest(new CompanionSettingsStore.Memory(), Runnable::run);

        try (MockedStatic<BotBuffEffects> fx = Mockito.mockStatic(BotBuffEffects.class);
             MockedStatic<soloMapling.server.MethodScheduler> sched =
                     Mockito.mockStatic(soloMapling.server.MethodScheduler.class)) {
            fx.when(() -> BotBuffEffects.isPartyBuff(Fighter.RAGE)).thenReturn(true);
            fx.when(() -> BotBuffEffects.isPartyBuff(Fighter.POWER_GUARD)).thenReturn(false);
            fx.when(() -> BotBuffEffects.durationOf(any(), anyInt())).thenReturn(60_000);
            fx.when(() -> BotBuffEffects.castLevel(any(), anyInt())).thenReturn(10);
            fx.when(() -> BotBuffEffects.payFor(any(), anyInt())).thenReturn(true);
            fx.when(() -> BotBuffEffects.partyMemberLacks(any(), anyInt())).thenReturn(false);

            int plain = BotBuffDriver.maintain(regular, 0, BotBuffDriver.Pay.PARTY_BUFFS);
            BotBuffDriver.clearBot(ALT);

            CompanionSettingsStore.save(owner, regular, CompanionSettings.DEFAULT.withSupport(false));
            assertEquals(plain, BotBuffDriver.maintain(regular, 0, BotBuffDriver.Pay.PARTY_BUFFS),
                    "the settings switch is off for party regulars: a row changes nothing for it");
        }
    }

    /** A Fighter alt with Rage (a party buff) and Power Guard (its own), partied with one real player. */
    private static Character partiedAlt() {
        Character alt = mock(Character.class);
        when(alt.getId()).thenReturn(ALT);
        when(alt.getClient()).thenReturn(mock(BotClient.class));
        when(alt.isOwnedAlt()).thenReturn(true);
        when(alt.getJob()).thenReturn(Job.FIGHTER);
        when(alt.getSkillLevel(anyInt())).thenReturn(0);
        when(alt.getSkillLevel(Fighter.POWER_GUARD)).thenReturn(10);
        when(alt.getSkillLevel(Fighter.RAGE)).thenReturn(5);

        Party party = mock(Party.class);
        PartyCharacter me = mock(PartyCharacter.class);
        when(me.getPlayer()).thenReturn(alt);
        PartyCharacter playerSlot = mock(PartyCharacter.class);
        when(playerSlot.getPlayer()).thenReturn(mock(Character.class));
        when(party.getMembers()).thenReturn(List.of(me, playerSlot));
        when(alt.getParty()).thenReturn(party);
        return alt;
    }
}

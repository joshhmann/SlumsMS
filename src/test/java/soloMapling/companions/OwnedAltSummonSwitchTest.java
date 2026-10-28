package soloMapling.companions;

import client.Character;
import client.Client;
import client.command.commands.gm0.BotMeCommand;
import client.command.commands.gm0.DismissCommand;
import config.YamlConfig;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.BotSM;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 1.6.0.4 change of plan (owner 2026-10-28): alts are half-built (no fighting), so @botme summoning goes off on live
 * with OWNED_ALTS_SUMMON_ENABLED=false. OWNED_ALTS_ENABLED keeps its meaning (per-owner bot-friend capacity), and
 * @dismiss still releases an alt called before the switch.
 */
class OwnedAltSummonSwitchTest {
    static Character owner(int id) { return OwnedAltServiceTest.owner(id); }

    @Test void summonAndBringHereAreRefusedWithTheRestingMessageWhenOff() {
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory(); db.add(2);
        OwnedAltService s = new OwnedAltService(db, () -> true, () -> false, Runnable::run);
        Character owner = owner(1);
        assertFalse(s.summoningOpen());
        var e = assertThrows(IllegalStateException.class, () -> s.summon(owner, "Alt2"));
        assertEquals(OwnedAltService.RESTING, e.getMessage());
        assertEquals(OwnedAltService.RESTING,
                assertThrows(IllegalStateException.class, () -> s.bringHere(owner)).getMessage());
        assertEquals(0, db.loads.get(), "nothing loaded");
        assertTrue(OwnedAltService.RESTING.startsWith("Alts are resting while we finish them"));
    }

    @Test void anAltSummonedBeforeTheSwitchCanStillBeDismissed() {
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory(); db.add(2); db.add(3);
        AtomicBoolean open = new AtomicBoolean(true);
        OwnedAltService s = new OwnedAltService(db, () -> true, open::get, Runnable::run);
        Character owner = owner(1);
        s.summon(owner, "Alt2"); s.summon(owner, "Alt3");
        open.set(false);
        assertTrue(s.dismiss(owner, "Alt2"));
        assertEquals(1, s.dismissAll(owner, false));
        assertFalse(db.online.contains(2)); assertFalse(db.online.contains(3));
        assertEquals(2, db.saves.get(), "each saved once on the way home");
    }

    @Test void theBotMeCommandSaysRestingAndDismissStillRuns() {
        boolean enabled = YamlConfig.config.server.OWNED_ALTS_ENABLED;
        boolean summon = YamlConfig.config.server.OWNED_ALTS_SUMMON_ENABLED;
        try {
            YamlConfig.config.server.OWNED_ALTS_ENABLED = true;
            YamlConfig.config.server.OWNED_ALTS_SUMMON_ENABLED = false;
            Character owner = owner(420_001);
            Client c = mock(Client.class); when(c.getPlayer()).thenReturn(owner);
            new BotMeCommand().execute(c, new String[0]);
            new BotMeCommand().execute(c, new String[]{"here"});
            new BotMeCommand().execute(c, new String[]{"Someone"});
            verify(owner, times(3)).yellowMessage(OwnedAltService.RESTING);
            assertFalse(OwnedAltService.getInstance().summoningOpen());
            // @dismiss is not gated by the switch: it still answers (nothing summoned here, so "not with you").
            new DismissCommand().execute(c, new String[]{"usage", "wrong"});
            verify(owner).yellowMessage("Usage: @dismiss <name> | all");
        } finally {
            YamlConfig.config.server.OWNED_ALTS_ENABLED = enabled;
            YamlConfig.config.server.OWNED_ALTS_SUMMON_ENABLED = summon;
        }
    }

    /** The new switch must not touch bot-friend capacity: per owner (5) while OWNED_ALTS_ENABLED is on, either way. */
    @Test void botFriendCapacityIsUnchangedBySummonSwitch() {
        boolean enabled = YamlConfig.config.server.OWNED_ALTS_ENABLED;
        boolean summon = YamlConfig.config.server.OWNED_ALTS_SUMMON_ENABLED;
        var manager = OwnedAltService.getInstance();
        int base = 1_000_420_000, botId = base + 100, ownerId = 420_002;
        try (var affinity = mockStatic(soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.class)) {
            YamlConfig.config.server.OWNED_ALTS_ENABLED = true;
            for (int n = 0; n < 30; n++) { // far past the legacy world-wide FOLLOWER_CAP
                BotSM bot = mock(BotSM.class); when(bot.getBotType()).thenReturn("FollowerBot");
                CharacterStorage.addActiveBot(base + n, bot);
            }
            Character bot = mock(Character.class); when(bot.getLevel()).thenReturn(10); when(bot.getId()).thenReturn(botId);
            Character owner = owner(ownerId);
            for (boolean open : new boolean[]{true, false}) {
                YamlConfig.config.server.OWNED_ALTS_SUMMON_ENABLED = open;
                assertEquals(BotRecruitManager.RecruitAnswer.ACCEPTED, BotRecruitManager.rollPartyAsk(bot, owner, 1.0, true),
                        "per-owner capacity, not the legacy cap (summon " + open + ")");
                assertTrue(BotRecruitManager.tryReserveFollower(botId));
                BotRecruitManager.releaseFollowerReservation(botId);
                BotRecruitManager.clearArmed(botId);
                for (int n = 0; n < 5; n++) assertTrue(manager.reserveCompanion(ownerId, base + 200 + n));
                assertEquals(BotRecruitManager.RecruitAnswer.FOLLOWERS_FULL, BotRecruitManager.rollPartyAsk(bot, owner, 1.0, true),
                        "five per owner (summon " + open + ")");
                for (int n = 0; n < 5; n++) manager.releaseCompanion(base + 200 + n);
            }
        } finally {
            for (int n = 0; n < 30; n++) CharacterStorage.removeActiveBot(base + n);
            for (int n = 0; n < 5; n++) manager.releaseCompanion(base + 200 + n);
            BotRecruitManager.releaseFollowerReservation(botId);
            BotRecruitManager.clearArmed(botId);
            YamlConfig.config.server.OWNED_ALTS_ENABLED = enabled;
            YamlConfig.config.server.OWNED_ALTS_SUMMON_ENABLED = summon;
        }
    }
}

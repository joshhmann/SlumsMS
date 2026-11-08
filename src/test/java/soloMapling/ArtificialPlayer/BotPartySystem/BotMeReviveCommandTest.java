package soloMapling.ArtificialPlayer.BotPartySystem;

import client.BotClient;
import client.Character;
import client.Client;
import client.command.commands.gm0.BotMeCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import soloMapling.companions.OwnedAltService;
import soloMapling.server.SoloMaplingUtilities;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 alt-revive-respawn (owner, 8 Nov 2026): the owner types {@code @botme revive <name>} while its alt waits dead in
 * a boss run and it gets up at once, for 10,000 x level / 10 mesos charged to the owner (the player typing it), doubled
 * for each paid revive in the same run. Without the mesos, one line with the price and nothing happens.
 */
class BotMeReviveCommandTest {

    private static final long NOW = 90_000_000L;
    private static final int OWNER = 1;
    private static final int ALT = 2;

    private MockedStatic<OwnedAltService> services;
    private OwnedAltService service;
    private Client client;
    private Character owner;
    private Character alt;

    @BeforeEach
    void setUp() {
        client = mock(Client.class);
        owner = mock(Character.class);
        alt = mock(Character.class);
        MapleMap map = mock(MapleMap.class);

        when(client.getPlayer()).thenReturn(owner);
        when(owner.getId()).thenReturn(OWNER);
        when(owner.getName()).thenReturn("Main");
        when(owner.getMeso()).thenReturn(1_000_000);

        when(alt.getId()).thenReturn(ALT);
        when(alt.getName()).thenReturn("Second");
        when(alt.getLevel()).thenReturn(120);
        when(alt.isOwnedAlt()).thenReturn(true);
        when(alt.getOwnedAltOwner()).thenReturn(owner);
        when(alt.getClient()).thenReturn(mock(BotClient.class));
        when(alt.getMap()).thenReturn(map);
        when(alt.isAlive()).thenReturn(true);
        when(alt.getCurrentMaxHp()).thenReturn(1_400);

        service = mock(OwnedAltService.class);
        Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(service).runAsync(org.mockito.ArgumentMatchers.any());
        when(service.summoningOpen()).thenReturn(true);
        when(service.active()).thenReturn(List.of(new OwnedAltService.Active(owner, alt)));
        services = Mockito.mockStatic(OwnedAltService.class);
        services.when(OwnedAltService::getInstance).thenReturn(service);

        lying().put(ALT, 0L); // BotDeaths says it is down
        CompanionBossRevive.downForTest(alt, NOW + 10_000L);
    }

    @AfterEach
    void tearDown() {
        CompanionBossRevive.runEnded(ALT);
        BotDeaths.clearBot(ALT);
        System.clearProperty("slums.companion.deathrules.regulars");
        services.close();
    }

    @Test
    void aPaidReviveIsChargedToTheOwnerAndSaidWithItsPrice() {
        new BotMeCommand().execute(client, new String[]{"revive", "Second"});

        verify(owner).gainMeso(-120_000, true, false, true); // the normal meso-change call, player's mesos
        verify(owner).yellowMessage("Second is back up - 120,000 mesos.");
        verify(alt).applyHpMpChange(0, 1_400, 0); // the HP a player gets from a revive
        assertFalse(CompanionBossRevive.waiting(alt), "paid for, so it is not still waiting");
    }

    @Test
    void withoutTheMesosItSaysThePriceAndChargesNothing() {
        when(owner.getMeso()).thenReturn(5_000);

        new BotMeCommand().execute(client, new String[]{"revive", "Second"});

        verify(owner, never()).gainMeso(anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(owner).yellowMessage("Second is still down: reviving it now costs 120,000 mesos and you have 5,000.");
        assertTrue(CompanionBossRevive.waiting(alt), "nothing happened: it still waits for its own ten seconds");
        assertTrue(BotDeaths.isDead(ALT));
    }

    @Test
    void aNameThatIsNotOneOfYourDownAltsSaysSoAndChargesNothing() {
        new BotMeCommand().execute(client, new String[]{"revive", "Nobody"});

        verify(owner).yellowMessage("There is nobody called Nobody with you right now.");
        verify(owner, never()).gainMeso(anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    // card f: for a party regular it is its leader who pays, because the leader is the player it follows
    @Test
    void aRegularsInstantReviveIsPaidByItsLeader() {
        int regularId = 3;
        Character regular = mock(Character.class);
        when(regular.getId()).thenReturn(regularId);
        when(regular.getName()).thenReturn("Clock4");
        when(regular.getLevel()).thenReturn(70);
        when(regular.getClient()).thenReturn(mock(BotClient.class));
        when(regular.getCurrentMaxHp()).thenReturn(2_000);
        FollowerBot follower = mock(FollowerBot.class);
        when(follower.leaderId()).thenReturn(OWNER);
        System.setProperty("slums.companion.deathrules.regulars", "true"); // the regulars' switch: off by default
        CharacterStorage.addActiveBot(regularId, follower);
        lying().put(regularId, 0L);
        CompanionBossRevive.downForTest(regular, NOW + 10_000L);
        try (MockedStatic<SoloMaplingUtilities> world = Mockito.mockStatic(SoloMaplingUtilities.class)) {
            net.server.PlayerStorage storage = mock(net.server.PlayerStorage.class);
            when(storage.getCharacterByName("Clock4")).thenReturn(regular);
            net.server.world.World w = mock(net.server.world.World.class);
            when(w.getPlayerStorage()).thenReturn(storage);
            world.when(SoloMaplingUtilities::world).thenReturn(w);

            new BotMeCommand().execute(client, new String[]{"revive", "Clock4"});
        }
        verify(owner).gainMeso(-70_000, true, false, true); // 10,000 x level 70 / 10, paid by its leader
        verify(owner).yellowMessage("Clock4 is back up - 70,000 mesos.");
        CompanionBossRevive.runEnded(regularId);
        CharacterStorage.removeActiveBot(regularId);
        BotDeaths.clearBot(regularId);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Long> lying() {
        try {
            Field field = BotDeaths.class.getDeclaredField("lyingUntil");
            field.setAccessible(true);
            return (Map<Integer, Long>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

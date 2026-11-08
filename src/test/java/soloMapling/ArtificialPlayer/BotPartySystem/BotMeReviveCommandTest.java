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
import soloMapling.companions.OwnedAltService;

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

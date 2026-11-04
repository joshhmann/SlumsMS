package soloMapling.mechanics;

import client.BotClient;
import client.Character;
import client.command.commands.gm3.MechanicsCommand;
import net.server.channel.Channel;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventManager;
import scripting.event.EventScriptManager;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionBossLog;
import server.expeditions.ExpeditionType;
import server.maps.MapManager;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotPartySystem.ExpeditionBots;
import soloMapling.ArtificialPlayer.BotPartySystem.ExpeditionBotsAccess;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.4 (owner 2026-11-03): {@code !mechanics boss TYPE [bots]} is the one caller of
 * {@link soloMapling.ArtificialPlayer.BotPartySystem.ExpeditionBots#startAsCaptain}, so a bot-captained run is
 * reachable in game instead of being dead code. The command is the only trigger: nothing opens a boss room by itself.
 */
class BossStagingTest {

    private static final int CAPTAIN = 950;
    private static final int ALTAR = 211042400;

    private final BossStaging.Crew realCrew = BossStaging.crew;
    private MapleMap altar;
    private Channel channel;
    private EventManager em;
    private final List<Character> crew = new ArrayList<>();

    @BeforeAll
    static void timers() {
        server.TimerManager.getInstance().start();
    }

    @BeforeEach
    void stubs() {
        ExpeditionBotsAccess.noQuotaRead();
        System.setProperty(Mechanics.BOSS_FLAG, "true");

        altar = mock(MapleMap.class);
        when(altar.getId()).thenReturn(ALTAR);
        net.server.PlayerStorage storage = mock(net.server.PlayerStorage.class);
        net.server.world.World world = mock(net.server.world.World.class);
        when(world.getPlayerStorage()).thenReturn(storage);
        when(altar.getWorldServer()).thenReturn(world);
        when(altar.getChannelServer()).thenReturn(channel);
        MapManager maps = mock(MapManager.class);
        when(maps.getMap(ALTAR)).thenReturn(altar);

        channel = mock(Channel.class);
        when(channel.getId()).thenReturn(1);
        when(channel.getMapFactory()).thenReturn(maps);
        when(channel.getExpedition(any(ExpeditionType.class))).thenReturn(null);
        // the real Channel.addExpedition opens registration (Channel.java:462); the mock must do the same
        when(channel.addExpedition(any(Expedition.class))).thenAnswer(inv -> {
            inv.getArgument(0, Expedition.class).beginRegistration();
            return true;
        });
        em = mock(EventManager.class);
        EventScriptManager scripts = mock(EventScriptManager.class);
        when(scripts.getEventManager("ZakumBattle")).thenReturn(em);
        when(channel.getEventSM()).thenReturn(scripts);
        when(em.startInstance(any(Expedition.class))).thenReturn(true);

        BossStaging.crew = (recruit, want, spawned, entryNpc, min, max) -> new ArrayList<>(crew);
    }

    @AfterEach
    void cleanUp() {
        ExpeditionBotsAccess.restoreQuota();
        BossStaging.crew = realCrew;
        System.clearProperty(Mechanics.BOSS_FLAG);
        System.clearProperty(ExpeditionBots.CAPTAIN_SWITCH);
    }

    private Character gm() {
        Character gm = mock(Character.class);
        when(gm.getName()).thenReturn("Josh");
        client.Client client = mock(client.Client.class);
        when(client.getChannelServer()).thenReturn(channel);
        when(gm.getClient()).thenReturn(client);
        return gm;
    }

    private Character bot(int id, String name) {
        Character chr = mock(Character.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getMap()).thenReturn(altar);
        when(chr.getLevel()).thenReturn(60);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getClient()).thenReturn(mock(BotClient.class));
        return chr;
    }

    private Character human(int id, String name) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getMap()).thenReturn(altar);
        when(chr.getLevel()).thenReturn(60);
        when(chr.isAlive()).thenReturn(true);
        return chr;
    }

    /** Six bots, the first the captain; the rest are its alts so they follow without a brain. */
    private void sixBots() {
        Character captain = bot(CAPTAIN, "Botcap");
        for (int i = 1; i <= 5; i++) {
            Character alt = bot(CAPTAIN + i, "Alt" + i);
            when(alt.isOwnedAlt()).thenReturn(true);
            when(alt.getOwnedAltOwner()).thenReturn(captain);
            crew.add(alt);
        }
        crew.add(0, captain);
    }

    private MockedStatic<Party> partyStatics() {
        MockedStatic<Party> party = mockStatic(Party.class);
        party.when(() -> Party.createParty(any(Character.class), anyBoolean())).thenReturn(true);
        party.when(() -> Party.joinParty(any(Character.class), anyInt(), anyBoolean())).thenReturn(true);
        return party;
    }

    /** Gives every bot the same party mock, as Party.createParty would. */
    private void partyOfTheCrew() {
        List<PartyCharacter> list = new ArrayList<>();
        for (Character c : crew) {
            PartyCharacter pc = mock(PartyCharacter.class);
            int id = c.getId();          // read the mock before stubbing, not inside a when(...)
            list.add(pc);
            when(pc.getId()).thenReturn(id);
            when(pc.getPlayer()).thenReturn(c);
        }
        Party party = mock(Party.class);
        when(party.getId()).thenReturn(7);
        when(party.getMembers()).thenReturn(list);
        for (Character c : crew) {
            when(c.getParty()).thenReturn(party);
        }
    }

    // ── the one caller ───────────────────────────────────────────────────────

    @Test
    void theCommandStartsARunWithABotCaptain() {
        sixBots();
        try (MockedStatic<Party> parties = partyStatics();
             MockedStatic<ExpeditionBossLog> logs = mockStatic(ExpeditionBossLog.class)) {
            partyOfTheCrew();
            when(crew.get(0).getClient().getChannelServer()).thenReturn(channel);
            logs.when(() -> ExpeditionBossLog.attemptBoss(any(Integer.class), any(Integer.class),
                    any(Expedition.class), any(Boolean.class))).thenReturn(true);

            String line = MechanicsCommand.runBoss(gm(), new MechanicsCommand.BossRequest(ExpeditionType.ZAKUM, 6));

            assertTrue(line.contains("STARTED"), line);
            assertTrue(line.contains("bot captain"), line);
            verify(em).setProperty(eq("leader"), eq("Botcap"));
            verify(em).startInstance(any(Expedition.class));
        }
    }

    @Test
    void theCommandAnswersHumanLeadsWhenARealPlayerIsInTheParty() {
        sixBots();
        Character human = human(1, "Josh");
        crew.add(human); // a human in the party: the door stays his
        try (MockedStatic<Party> parties = partyStatics()) {
            partyOfTheCrew();
            when(crew.get(0).getClient().getChannelServer()).thenReturn(channel);

            String line = MechanicsCommand.runBoss(gm(), new MechanicsCommand.BossRequest(ExpeditionType.ZAKUM, 6));

            assertTrue(line.contains("HUMAN_LEADS"), line);
            verify(em, never()).startInstance(any(Expedition.class));
        }
    }

    @Test
    void withTheBossSwitchOffTheCommandRefusesInsteadOfHalfStarting() {
        System.clearProperty(Mechanics.BOSS_FLAG);
        sixBots();
        try (MockedStatic<Party> parties = partyStatics()) {
            partyOfTheCrew();

            BossStaging.Staged s = BossStaging.start(gm(), ExpeditionType.ZAKUM, 6);

            assertEquals(ExpeditionBots.Door.SWITCH_OFF, s.door());
            assertTrue(s.why().contains(Mechanics.BOSS_FLAG), s.why());
            verify(em, never()).startInstance(any(Expedition.class));
        }
    }

    @Test
    void aTypeWithNoContentRowAnswersNoScript() {
        String line = MechanicsCommand.runBoss(gm(), new MechanicsCommand.BossRequest(ExpeditionType.MAGNUS, 6));
        assertTrue(line.contains("NO_SCRIPT"), line);
        verify(em, never()).startInstance(any(Expedition.class));
    }

    @Test
    void theCrewIsTakenOutAgainWhenTheDoorRefuses() {
        System.setProperty(ExpeditionBots.CAPTAIN_SWITCH, "false");
        sixBots();
        try (MockedStatic<Party> parties = partyStatics()) {
            partyOfTheCrew();
            when(crew.get(0).getClient().getChannelServer()).thenReturn(channel);

            BossStaging.Staged s = BossStaging.start(gm(), ExpeditionType.ZAKUM, 6);

            assertEquals(ExpeditionBots.Door.SWITCH_OFF, s.door());
            assertFalse(s.started());
            assertTrue(s.spawned().isEmpty(), "a refused start leaves no loose party behind");
            verify(em, never()).startInstance(any(Expedition.class));
        }
    }

    @Test
    void everyDoorValueHasASentence() {
        for (ExpeditionBots.Door d
                : ExpeditionBots.Door.values()) {
            String s = BossStaging.sentence(d);
            assertFalse(s == null || s.isBlank(), d.name());
        }
    }
}

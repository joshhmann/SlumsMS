package soloMapling.ArtificialPlayer.BotPartySystem;

import client.BotClient;
import client.Character;
import net.server.PlayerStorage;
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
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.4 (owner, 2026-11-03, -Dslums.exped.botcaptain): a bot may captain an expedition - its party bots register and
 * the captain opens the run through the same code the recruiter NPC uses - but a real player always outranks it.
 */
class ExpeditionBotsCaptainTest {

    private static final int CAPTAIN = 900;
    private static final int ALTAR = 211042400;

    private final List<Expedition> made = new ArrayList<>();
    private final ExpeditionBots.Quota realQuota = ExpeditionBots.quota;
    private MapleMap altar;
    private Channel channel;
    private EventManager em;
    private PlayerStorage storage;

    @BeforeAll
    static void timers() {
        server.TimerManager.getInstance().start();
    }

    @BeforeEach
    void stubs() {
        ExpeditionBots.quota = (bot, exped) -> true;
        altar = mock(MapleMap.class);
        when(altar.getId()).thenReturn(ALTAR);
        net.server.world.World world = mock(net.server.world.World.class);
        storage = mock(PlayerStorage.class);
        when(world.getPlayerStorage()).thenReturn(storage);
        when(altar.getWorldServer()).thenReturn(world);
        channel = mock(Channel.class);
        when(channel.getId()).thenReturn(1);
        when(channel.getExpedition(any(ExpeditionType.class))).thenReturn(null);
        // the real Channel.addExpedition opens registration (Channel.java:462); the mock must do the same
        when(channel.addExpedition(any(Expedition.class))).thenAnswer(inv -> {
            inv.getArgument(0, Expedition.class).beginRegistration();
            return true;
        });
        when(altar.getChannelServer()).thenReturn(channel);
        em = mock(EventManager.class);
        EventScriptManager scripts = mock(EventScriptManager.class);
        when(scripts.getEventManager("ZakumBattle")).thenReturn(em);
        when(channel.getEventSM()).thenReturn(scripts);
        when(em.startInstance(any(Expedition.class))).thenReturn(true);
    }

    @AfterEach
    void cleanUp() {
        made.forEach(ExpeditionBots::release);
        ExpeditionBots.quota = realQuota;
        System.clearProperty(ExpeditionBots.CAPTAIN_SWITCH);
    }

    private Character bot(int id, String name, Party party) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getMap()).thenReturn(altar);
        when(chr.getLevel()).thenReturn(70);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getClient()).thenReturn(mock(BotClient.class));
        when(chr.getParty()).thenReturn(party);
        return chr;
    }

    private Character player(int id, String name, Party party) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getMap()).thenReturn(altar);
        when(chr.getLevel()).thenReturn(70);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getParty()).thenReturn(party);
        return chr;
    }

    private static Party partyOf(Character... members) {
        Party party = mock(Party.class);
        List<PartyCharacter> list = new ArrayList<>();
        for (Character c : members) {
            PartyCharacter pc = mock(PartyCharacter.class);
            int id = c.getId();
            when(pc.getId()).thenReturn(id);
            when(pc.getPlayer()).thenReturn(c);
            list.add(pc);
        }
        when(party.getMembers()).thenReturn(list);
        return party;
    }

    /** A bot captain leading five of its own bots: the smallest party Zakum's minimum of six accepts. */
    private Character captainWithCrew() {
        Character captain = bot(CAPTAIN, "Botcap", null);
        List<Character> crew = new ArrayList<>();
        crew.add(captain);
        for (int i = 1; i <= 5; i++) {
            Character member = bot(CAPTAIN + i, "Crew" + i, null);
            when(member.isOwnedAlt()).thenReturn(true);     // an alt of the captain: it follows without a brain
            when(member.getOwnedAltOwner()).thenReturn(captain);
            crew.add(member);
        }
        Party party = partyOf(crew.toArray(new Character[0]));
        for (Character c : crew) {
            when(c.getParty()).thenReturn(party);
        }
        when(captain.getClient().getChannelServer()).thenReturn(channel);
        return captain;
    }

    // ── the captain's door ───────────────────────────────────────────────────

    @Test
    void aBotCaptainRegistersItsPartyBotsAndStartsTheRun() {
        Character captain = captainWithCrew();
        try (MockedStatic<ExpeditionBossLog> log = mockStatic(ExpeditionBossLog.class)) {
            log.when(() -> ExpeditionBossLog.attemptBoss(any(Integer.class), any(Integer.class),
                    any(Expedition.class), any(Boolean.class))).thenReturn(true);

            assertEquals(ExpeditionBots.Door.STARTED, ExpeditionBots.startAsCaptain(captain, ExpeditionType.ZAKUM));
        }
        verify(em).setProperty(eq("leader"), eq("Botcap"));
        verify(em).setProperty(eq("channel"), eq("1"));
        verify(em).startInstance(any(Expedition.class));
    }

    @Test
    void withTheSwitchOffABotCaptainCannotOpenTheRoom() {
        System.setProperty(ExpeditionBots.CAPTAIN_SWITCH, "false");
        Character captain = captainWithCrew();
        assertEquals(ExpeditionBots.Door.SWITCH_OFF, ExpeditionBots.startAsCaptain(captain, ExpeditionType.ZAKUM));
        verify(em, never()).startInstance(any(Expedition.class));
    }

    @Test
    void aBotLedPartyRegistersNobodyWithTheSwitchOff() {
        System.setProperty(ExpeditionBots.CAPTAIN_SWITCH, "false");
        Character captain = captainWithCrew();
        Expedition exped = new Expedition(captain, ExpeditionType.ZAKUM, true, 0, 0);
        exped.beginRegistration();
        made.add(exped);
        assertTrue(ExpeditionBots.autoJoin(captain, exped).isEmpty(), "1.6.0 refused a bot leader outright");
    }

    @Test
    void aHumanInThePartyKeepsTheDoorForHimself() {
        Character captain = bot(CAPTAIN, "Botcap", null);
        Character human = player(1, "Josh", null);
        Party party = partyOf(captain, human);
        when(captain.getParty()).thenReturn(party);
        when(captain.getClient().getChannelServer()).thenReturn(channel);
        assertEquals(ExpeditionBots.Door.HUMAN_LEADS, ExpeditionBots.startAsCaptain(captain, ExpeditionType.ZAKUM));
        verify(em, never()).startInstance(any(Expedition.class));
    }

    @Test
    void aTypeTheTreeHasNoScriptForCannotBeStartedByABot() {
        Character captain = captainWithCrew();
        assertEquals(ExpeditionBots.Door.NO_SCRIPT, ExpeditionBots.startAsCaptain(captain, ExpeditionType.MAGNUS));
    }

    @Test
    void aRoomAlreadyHeldIsNotTaken() {
        Character captain = captainWithCrew();
        when(channel.getExpedition(ExpeditionType.ZAKUM)).thenReturn(mock(Expedition.class));
        assertEquals(ExpeditionBots.Door.ROOM_TAKEN, ExpeditionBots.startAsCaptain(captain, ExpeditionType.ZAKUM));
    }

    @Test
    void aRefusedStartLeavesTheDoorClosed() {
        Character captain = captainWithCrew();
        when(em.startInstance(any(Expedition.class))).thenReturn(false);
        try (MockedStatic<ExpeditionBossLog> log = mockStatic(ExpeditionBossLog.class)) {
            log.when(() -> ExpeditionBossLog.attemptBoss(any(Integer.class), any(Integer.class),
                    any(Expedition.class), any(Boolean.class))).thenReturn(true);
            assertEquals(ExpeditionBots.Door.REFUSED, ExpeditionBots.startAsCaptain(captain, ExpeditionType.ZAKUM));
        }
    }

    // ── a real player outranks a bot ─────────────────────────────────────────

    @Test
    void aHumanMemberLeadsTheRunTheBotOpened() {
        Character captain = bot(CAPTAIN, "Botcap", null);
        Character human = player(1, "Josh", null);
        Party party = partyOf(captain, human);
        when(captain.getParty()).thenReturn(party);
        when(human.getParty()).thenReturn(party);
        includeOnChannel(human);
        Expedition exped = new Expedition(captain, ExpeditionType.ZAKUM, true, 0, 0);
        exped.beginRegistration();
        made.add(exped);
        exped.addMemberInt(human);

        Character leader = ExpeditionBots.preferredLeader(exped, captain);

        assertSame(human, leader, "the real player leads");
        assertSame(human, exped.getLeader(), "and every script reads him as the leader");
        verify(human).dropMessage(eq(6), org.mockito.ArgumentMatchers.contains("You lead this ZAKUM run"));
    }

    @Test
    void aHumanLeaderIsNeverDisplaced() {
        Character human = player(1, "Josh", null);
        Character captain = bot(CAPTAIN, "Botcap", null);
        Expedition exped = new Expedition(human, ExpeditionType.ZAKUM, true, 0, 0);
        exped.beginRegistration();
        made.add(exped);

        Character leader = ExpeditionBots.preferredLeader(exped, human);

        assertSame(human, leader);
        assertSame(human, exped.getLeader());
    }

    @Test
    void withTheSwitchOffTheLeaderIsLeftAlone() {
        System.setProperty(ExpeditionBots.CAPTAIN_SWITCH, "false");
        Character captain = bot(CAPTAIN, "Botcap", null);
        Character human = player(1, "Josh", null);
        includeOnChannel(human);
        Expedition exped = new Expedition(captain, ExpeditionType.ZAKUM, true, 0, 0);
        exped.beginRegistration();
        made.add(exped);
        exped.addMemberInt(human);
        assertSame(captain, ExpeditionBots.preferredLeader(exped, captain));
        assertSame(captain, exped.getLeader());
        assertNotSame(human, exped.getLeader());
    }

    private void includeOnChannel(Character chr) {
        when(storage.getCharacterById(chr.getId())).thenReturn(chr);
    }
}

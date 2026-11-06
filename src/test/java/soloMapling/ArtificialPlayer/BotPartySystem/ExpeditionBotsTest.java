package soloMapling.ArtificialPlayer.BotPartySystem;

import client.BotClient;
import client.Character;
import config.YamlConfig;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.server.PlayerStorage;
import net.server.channel.Channel;
import net.server.Server;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 1.6.0 (owner 2026-10-26): party bots auto-join their player's boss expedition. */
class ExpeditionBotsTest {

    private static final int LEADER = 1;
    private static final int ALTAR = 211042400; // Zakum's altar, the recruiting map
    private final List<Expedition> made = new ArrayList<>();
    private final MapleMap altar = mock(MapleMap.class);
    private PlayerStorage storage;

    @BeforeAll
    static void timers() {
        server.TimerManager.getInstance().start(); // the registration timer
    }

    private final ExpeditionBots.Quota realQuota = ExpeditionBots.quota;

    @org.junit.jupiter.api.BeforeEach
    void noDatabase() {
        ExpeditionBots.quota = (bot, exped) -> true; // the boss log is a DB read
        net.server.world.World world = mock(net.server.world.World.class);
        storage = mock(PlayerStorage.class);
        when(world.getPlayerStorage()).thenReturn(storage);
        when(altar.getWorldServer()).thenReturn(world); // dispose() reads the online members
        when(altar.getId()).thenReturn(ALTAR);
        when(altar.getChannelServer()).thenReturn(mock(Channel.class)); // the registration timer removes the expedition
    }

    @AfterEach
    void cleanUp() {
        made.forEach(ExpeditionBots::release);
        ExpeditionBots.quota = realQuota;
    }

    // ── the rules ────────────────────────────────────────────────────────────

    private static ExpeditionBots.Facts facts(int owner, boolean sole, boolean sameMap, int level, boolean quest) {
        return new ExpeditionBots.Facts(true, false, false, false, sameMap, level, owner, sole, quest, false, false);
    }

    /** An owned alt: whether it is one, and whether the owner it follows is a member of the expedition. */
    private static ExpeditionBots.Facts altFacts(int owner, boolean ownerMember) {
        return new ExpeditionBots.Facts(true, false, false, false, true, 60, owner, false, true, true, ownerMember);
    }

    @Test
    void hisCompanionOnTheMapAtTheRightLevelJoins() {
        assertNull(ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, facts(LEADER, false, true, 60, true)));
    }

    @Test
    void anotherPlayersCompanionInTheSamePartyNeverJoins() {
        assertEquals("owner", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, facts(2, false, true, 60, true)));
        assertEquals("owner", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, facts(2, true, true, 60, true)));
        // an alt whose owner logged off is nobody's to take
        assertEquals("owner", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, facts(-2, true, true, 60, true)));
    }

    @Test
    void aBotNobodyLeadsJoinsOnlyWhenHeIsTheOneRealPlayerInTheParty() {
        assertNull(ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, facts(-1, true, true, 60, true)));
        assertEquals("owner", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, facts(-1, false, true, 60, true)));
    }

    @Test
    void aBotElsewhereOrOutOfTheLevelBandStaysOut() {
        assertEquals("map", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, facts(LEADER, false, false, 60, true)));
        assertEquals("level", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, facts(LEADER, false, true, 49, true)));
        assertNull(ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, facts(LEADER, false, true, 50, true)));
        assertEquals("level", ExpeditionBots.skip(ExpeditionType.HORNTAIL, LEADER, facts(LEADER, false, true, 99, true)));
    }

    @Test
    void theRecruitersMemberQuestAndOtherBlocksApply() {
        assertEquals("quest", ExpeditionBots.skip(ExpeditionType.VONLEON, LEADER, facts(LEADER, false, true, 130, false)));
        ExpeditionBots.Facts member = new ExpeditionBots.Facts(true, true, false, false, true, 60, LEADER, false, true, false, false);
        assertEquals("member", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, member));
        ExpeditionBots.Facts banned = new ExpeditionBots.Facts(true, false, true, false, true, 60, LEADER, false, true, false, false);
        assertEquals("banned", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, banned));
        ExpeditionBots.Facts dead = new ExpeditionBots.Facts(true, false, false, true, true, 60, LEADER, false, true, false, false);
        assertEquals("dead", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, dead));
        ExpeditionBots.Facts human = new ExpeditionBots.Facts(false, false, false, false, true, 60, LEADER, false, true, false, false);
        assertEquals("not a bot", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, human));
    }

    @Test
    void anAltWhoseOwnerIsAMemberFollowsIntoAStrangerLedExpedition() {
        assertNull(ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, altFacts(2, true)));
        assertTrue(ExpeditionBots.ownerAdmits(LEADER, altFacts(2, true)));
    }

    @Test
    void anAltWhoseOwnerIsNotAMemberIsStillSkipped() {
        assertEquals("owner", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, altFacts(2, false)));
        // an alt whose owner logged off is nobody's to take
        assertEquals("owner", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, altFacts(-2, false)));
    }

    @Test
    void withAltFollowOffTheOwnerRuleIsTheOldOne() {
        System.setProperty(ExpeditionBots.ALT_FOLLOW_SWITCH, "false");
        try {
            assertEquals("owner", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, altFacts(2, true)));
            assertNull(ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, altFacts(LEADER, false)),
                    "the leader's own alt always comes");
        } finally {
            System.clearProperty(ExpeditionBots.ALT_FOLLOW_SWITCH);
        }
    }

    @Test
    void aCompanionOfAMemberWhoIsNotItsOwnerIsNotAdmitted() {
        // only an owned alt follows its owner; a population bot's "owner" is whoever it follows
        ExpeditionBots.Facts companion = new ExpeditionBots.Facts(true, false, false, false, true, 60, 2, false, true,
                false, true);
        assertEquals("owner", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, companion));
    }

    @Test
    void memberQuestsMatchTheRecruiterScripts() {
        assertArrayEquals(new int[]{3171, 1}, ExpeditionBots.memberQuest(ExpeditionType.VONLEON));
        assertArrayEquals(new int[]{31166, 0}, ExpeditionBots.memberQuest(ExpeditionType.MAGNUS));
        assertArrayEquals(new int[]{31165, 0}, ExpeditionBots.memberQuest(ExpeditionType.LUCID));
        assertArrayEquals(new int[]{31164, 0}, ExpeditionBots.memberQuest(ExpeditionType.NVERUS));
        assertNull(ExpeditionBots.memberQuest(ExpeditionType.ZAKUM));
        assertNull(ExpeditionBots.memberQuest(ExpeditionType.HORNTAIL));
        assertTrue(ExpeditionBots.excluded(ExpeditionType.ARIANT));
        assertFalse(ExpeditionBots.excluded(ExpeditionType.CWKPQ));
    }

    // ── registration on a real Expedition ───────────────────────────────────

    private Character player(int id, String name) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getMap()).thenReturn(altar);
        when(chr.getLevel()).thenReturn(70);
        when(chr.isAlive()).thenReturn(true);
        return chr;
    }

    private Character altOf(Character owner, int id, String name) {
        Character alt = player(id, name);
        when(alt.getClient()).thenReturn(mock(BotClient.class));
        when(alt.isOwnedAlt()).thenReturn(true);
        when(alt.getOwnedAltOwner()).thenReturn(owner);
        return alt;
    }

    private static void party(Character leader, Character... others) {
        Party party = mock(Party.class);
        List<PartyCharacter> members = new ArrayList<>();
        for (Character c : concat(leader, others)) {
            int id = c.getId(); // read before stubbing: a mock call inside when() breaks it
            PartyCharacter pc = mock(PartyCharacter.class);
            when(pc.getId()).thenReturn(id);
            when(pc.getPlayer()).thenReturn(c);
            members.add(pc);
            when(c.getParty()).thenReturn(party);
        }
        when(party.getMembers()).thenReturn(members);
    }

    private static List<Character> concat(Character first, Character... rest) {
        List<Character> all = new ArrayList<>(List.of(first));
        all.addAll(List.of(rest));
        return all;
    }

    private Expedition expedition(Character leader, ExpeditionType type, int maxPlayers) {
        Expedition exped = new Expedition(leader, type, true, 0, maxPlayers);
        exped.beginRegistration();
        made.add(exped);
        return exped;
    }

    @Test
    void anAdmittedAltWhoseOwnerIsAMemberCountsAsAPartyMember() {
        boolean saved = YamlConfig.config.server.USE_ENABLE_SOLO_EXPEDITIONS;
        YamlConfig.config.server.USE_ENABLE_SOLO_EXPEDITIONS = false;
        try {
            Character leader = player(LEADER, "Josh");       // a stranger to both of them
            Character owner = player(2, "Owner");
            Character alt = altOf(owner, 3, "Alt3");
            party(leader, owner, alt);
            Expedition exped = new Expedition(leader, ExpeditionType.ZAKUM, true, 3, 3);
            exped.beginRegistration();
            made.add(exped);
            exped.addMemberInt(owner);                        // the owner is in this expedition
            when(storage.getCharacterById(2)).thenReturn(owner);
            when(storage.getCharacterById(3)).thenReturn(alt);
            when(owner.isPresentInWorld()).thenReturn(true);
            when(alt.isPresentInWorld()).thenReturn(true);

            assertEquals(List.of("Alt3"), ExpeditionBots.autoJoin(leader, exped));

            assertEquals(3, exped.getMembers().size(), "the alt is a member, not an onlooker");
            assertTrue(exped.getMemberList().stream().anyMatch(e -> "Alt3".equals(e.getValue())));
            assertTrue(exped.getMembers().size() >= exped.getMinSize(), "the script's size gate counts it");
            assertTrue(exped.getActiveMembers().contains(alt), "and broadcasts reach it");
        } finally {
            YamlConfig.config.server.USE_ENABLE_SOLO_EXPEDITIONS = saved;
        }
    }

    @Test
    void anAltWhoseOwnerIsOutsideTheExpeditionDoesNotFollow() {
        Character leader = player(LEADER, "Josh");
        Character owner = player(2, "Owner");
        Character alt = altOf(owner, 3, "Alt3");
        party(leader, owner, alt);
        Expedition exped = expedition(leader, ExpeditionType.ZAKUM, 3);

        assertTrue(ExpeditionBots.autoJoin(leader, exped).isEmpty());
        assertFalse(exped.getMembers().containsKey(3));
    }

    @Test
    void fivePartyBotsCountTowardZakumsMinimumOfSix() {
        boolean saved = YamlConfig.config.server.USE_ENABLE_SOLO_EXPEDITIONS;
        YamlConfig.config.server.USE_ENABLE_SOLO_EXPEDITIONS = false;
        try {
            Character leader = player(LEADER, "Josh");
            List<Character> bots = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                bots.add(altOf(leader, 100 + i, "Clock" + i));
            }
            party(leader, bots.toArray(new Character[0]));
            Expedition exped = expedition(leader, ExpeditionType.ZAKUM, 0);
            assertTrue(exped.getMemberList().size() < exped.getMinSize(), "alone he cannot start");

            List<String> names = ExpeditionBots.autoJoin(leader, exped);

            assertEquals(List.of("Clock0", "Clock1", "Clock2", "Clock3", "Clock4"), names);
            assertEquals(6, exped.getMemberList().size());
            assertTrue(exped.getMemberList().size() >= exped.getMinSize(), "Start the fight! now passes");
            verify(leader).dropMessage(eq(6), eq("[Expedition] Clock4 joined with you."));
            assertTrue(ExpeditionBots.committed(bots.get(0)), "registered: held for the run");
            assertTrue(ExpeditionBots.autoJoin(leader, exped).isEmpty(), "the next NPC step adds nobody twice");
        } finally {
            YamlConfig.config.server.USE_ENABLE_SOLO_EXPEDITIONS = saved;
        }
    }

    @Test
    void neverPastTheMaximumSize() {
        Character leader = player(LEADER, "Josh");
        List<Character> bots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            bots.add(altOf(leader, 200 + i, "Tick" + i));
        }
        party(leader, bots.toArray(new Character[0]));
        Expedition exped = expedition(leader, ExpeditionType.ZAKUM, 3);

        List<String> names = ExpeditionBots.autoJoin(leader, exped);

        assertEquals(2, names.size());
        assertEquals(3, exped.getMembers().size());
    }

    @Test
    void anotherPlayersAltABotElsewhereAndAnUsedUpQuotaStayOut() {
        Character leader = player(LEADER, "Josh");
        Character friend = player(2, "Ann");
        Character mine = altOf(leader, 300, "Mine");
        Character hers = altOf(friend, 301, "Hers");
        Character away = altOf(leader, 302, "Away");
        when(away.getMap()).thenReturn(mock(MapleMap.class));
        Character tired = altOf(leader, 303, "Tired");
        Character low = altOf(leader, 304, "Low");
        when(low.getLevel()).thenReturn(20);
        party(leader, friend, mine, hers, away, tired, low);
        ExpeditionBots.quota = (bot, exped) -> bot.getId() != 303;
        Expedition exped = expedition(leader, ExpeditionType.ZAKUM, 0);

        assertEquals(List.of("Mine"), ExpeditionBots.autoJoin(leader, exped));
        assertFalse(exped.hasMember(301), "never another player's bot");
        assertFalse(exped.hasMember(302));
        assertFalse(exped.hasMember(303));
        assertFalse(exped.hasMember(304));
        assertFalse(exped.hasMember(2), "a real player registers himself");
    }

    @Test
    void aBotOutsideHisPartyNeverJoins() {
        Character leader = player(LEADER, "Josh");
        Character inParty = altOf(leader, 400, "In");
        Character outside = altOf(leader, 401, "Out"); // his alt, on the map, but not in his party
        party(leader, inParty);
        Expedition exped = expedition(leader, ExpeditionType.ZAKUM, 0);

        ExpeditionBots.autoJoin(leader, exped);

        assertTrue(exped.hasMember(400));
        assertFalse(exped.hasMember(401));
    }

    @Test
    void onlyTheLeaderBringsBotsAndOnlyWhileRegistering() {
        Character leader = player(LEADER, "Josh");
        Character other = player(5, "Ann");
        Character bot = altOf(other, 500, "Hers");
        party(other, bot);
        Expedition exped = expedition(leader, ExpeditionType.ZAKUM, 0);
        assertTrue(ExpeditionBots.autoJoin(other, exped).isEmpty(), "not the captain");

        Character mine = altOf(leader, 501, "Mine");
        party(leader, mine);
        exped.finishRegistration();
        assertTrue(ExpeditionBots.autoJoin(leader, exped).isEmpty(), "registration closed");
    }

    @Test
    void theCommitmentEndsWhenTheExpeditionIsDisposed() {
        Character leader = player(LEADER, "Josh");
        Character bot = altOf(leader, 600, "Clock4");
        party(leader, bot);
        Expedition exped = expedition(leader, ExpeditionType.ZAKUM, 0);
        ExpeditionBots.autoJoin(leader, exped);
        assertTrue(ExpeditionBots.committed(bot));
        assertFalse(ExpeditionBots.inRun(bot), "not inside yet");

        exped.dispose(false);

        assertFalse(ExpeditionBots.committed(bot));
    }

    @Test
    void aBotThatLeftTheExpeditionIsNoLongerHeld() {
        Character leader = player(LEADER, "Josh");
        Character bot = altOf(leader, 700, "Clock4");
        party(leader, bot);
        Expedition exped = expedition(leader, ExpeditionType.ZAKUM, 0);
        ExpeditionBots.autoJoin(leader, exped);

        exped.removeMember(bot);

        assertFalse(ExpeditionBots.committed(bot));
    }

    // ── the door (1.6.2 b120): a held bot is never stranded ─────────────────

    @Test
    void aBotIsLetGoWhenTheRegistrationWindowExpires() {
        Character leader = player(LEADER, "Josh");
        Character bot = altOf(leader, 800, "Clock4");
        party(leader, bot);
        Expedition exped = expedition(leader, ExpeditionType.ZAKUM, 0);
        ExpeditionBots.autoJoin(leader, exped);
        assertTrue(ExpeditionBots.committed(bot), "held while the window is open");

        exped.expireRegistration(); // the timer's body, without waiting five minutes

        assertFalse(ExpeditionBots.committed(bot), "the window lapsed: the bot is free again");
        verify(altar.getChannelServer()).removeExpedition(exped);
    }

    @Test
    void aBotHeldWhileTheLeaderCannotStartIsLetGoWhenTheWindowLapses() {
        Character leader = player(LEADER, "Josh");
        Character bot = altOf(leader, 810, "Clock4");
        party(leader, bot);
        Expedition exped = expedition(leader, ExpeditionType.ZAKUM, 0);
        ExpeditionBots.autoJoin(leader, exped);
        assertTrue(exped.getMemberList().size() < exped.getMinSize(), "the size check would refuse tonight");
        assertTrue(ExpeditionBots.committed(bot), "so he waits at the altar - for the window, not forever");

        exped.expireRegistration();

        assertFalse(ExpeditionBots.committed(bot));
        assertFalse(ExpeditionBots.inRun(bot));
    }

    @Test
    void aBotTheRunLeftBehindIsFreeOnceTheRunStarts() {
        Character leader = player(LEADER, "Josh");
        Character bot = altOf(leader, 820, "Clock4");
        party(leader, bot);
        Expedition exped = expedition(leader, ExpeditionType.ZAKUM, 0);
        ExpeditionBots.autoJoin(leader, exped);
        assertTrue(ExpeditionBots.committed(bot));

        exped.finishRegistration(); // the run began; registerExpeditionTeam left him out (he is in no instance)

        assertFalse(ExpeditionBots.committed(bot), "not registering and not inside: nothing holds him");
        exped.dispose(true);
        assertFalse(ExpeditionBots.inRun(bot));
    }

    @Test
    void aBotAddedToThePartyAfterTheExpeditionWasMadeJoinsAtTheStart() {
        Character leader = player(LEADER, "Josh");
        Expedition exped = expedition(leader, ExpeditionType.ZAKUM, 0);
        Character bot = altOf(leader, 830, "Clock4");
        party(leader, bot); // the invite lands after the expedition exists

        System.setProperty("solomapling.enabled", "true"); // the hook that carries autoJoin into start()
        System.setProperty("solomapling.template-id", "9100000");
        try (MockedStatic<Server> servers = mockStatic(Server.class)) {
            servers.when(Server::getInstance).thenReturn(mock(Server.class));
            exped.start(); // Expedition.start re-runs autoJoin before it closes registration
        } finally {
            System.clearProperty("solomapling.enabled");
            System.clearProperty("solomapling.template-id");
        }

        assertTrue(exped.hasMember(830), "the start step is a party-change boundary too");
        assertTrue(exped.getMembers().containsValue("Clock4"));
    }

    @Test
    void theStartNamesEachRegisteredBotThatIsNotAtTheAltarAndNothingElse() {
        System.setProperty(ExpeditionDoor.PROPERTY, "true");
        try {
            Character leader = player(LEADER, "Josh");
            Character here = altOf(leader, 840, "Here");
            Character away = altOf(leader, 841, "Away");
            party(leader, here, away);
            Expedition exped = expedition(leader, ExpeditionType.ZAKUM, 0);
            assertEquals(List.of("Here", "Away"), ExpeditionBots.autoJoin(leader, exped), "both were at the altar");
            when(storage.getCharacterById(840)).thenReturn(here);
            when(storage.getCharacterById(841)).thenReturn(away);

            MapleMap town = mock(MapleMap.class);
            when(town.getId()).thenReturn(100000000);
            when(away.getMap()).thenReturn(town); // it wandered off while the leader was gathering people

            ExpeditionBots.warnNotAtTheAltar(exped);

            verify(leader).dropMessage(6, ExpeditionBots.notAtTheAltarLine("Away"));
            verify(leader, never()).dropMessage(6, ExpeditionBots.notAtTheAltarLine("Here"));
        } finally {
            System.clearProperty(ExpeditionDoor.PROPERTY);
        }
    }

    @Test
    void withTheDoorShutTheLeaderHearsNothingAboutIt() {
        Character leader = player(LEADER, "Josh");
        Character bot = altOf(leader, 850, "Clock4");
        party(leader, bot);
        Expedition exped = expedition(leader, ExpeditionType.ZAKUM, 0);
        ExpeditionBots.autoJoin(leader, exped); // storage knows nobody: it would be named if the door were open

        ExpeditionBots.warnNotAtTheAltar(exped);

        verify(leader, never()).dropMessage(eq(6), contains("at the altar"));
    }

    @Test
    void aDismissedAltLeavesItsExpeditionAndAVanishedOneLeavesNothingElse() {
        System.setProperty(ExpeditionDoor.PROPERTY, "true");
        try {
            Character leader = player(LEADER, "Josh");
            Character alt = altOf(leader, 860, "Alt");
            Channel channel = mock(Channel.class);
            client.Client client = mock(client.Client.class);
            when(alt.getClient()).thenReturn(client);
            when(client.getChannelServer()).thenReturn(channel);
            Expedition exped = expedition(leader, ExpeditionType.ZAKUM, 0);
            exped.addMemberInt(alt);
            when(channel.getExpedition(ExpeditionType.ZAKUM)).thenReturn(exped);

            soloMapling.ArtificialPlayer.BotEventExit.leave(alt); // what a dismiss did before b120
            assertTrue(exped.contains(alt), "confirmed: leaving an event never touched the member list");

            ExpeditionDoor.leaveExpedition(alt);

            assertFalse(exped.contains(alt), "no ghost in the member list, no boss-log entry in its name");
        } finally {
            System.clearProperty(ExpeditionDoor.PROPERTY);
        }
    }

    @Test
    void withTheDoorShutADismissedAltStillLingers() {
        Character leader = player(LEADER, "Josh");
        Character alt = altOf(leader, 861, "Alt");
        Channel channel = mock(Channel.class);
        client.Client client = mock(client.Client.class);
        when(alt.getClient()).thenReturn(client);
        when(client.getChannelServer()).thenReturn(channel);
        Expedition exped = expedition(leader, ExpeditionType.ZAKUM, 0);
        exped.addMemberInt(alt);
        when(channel.getExpedition(ExpeditionType.ZAKUM)).thenReturn(exped);

        ExpeditionDoor.leaveExpedition(alt);

        assertTrue(exped.contains(alt), "the switch is off: today's behaviour, unchanged");
    }

    // ── 1.6.4: Chaos Zakum, bots join like they join Zakum, held to the shared daily runs ──

    private List<Character> chaosBots(Character leader, int count) {
        List<Character> bots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Character bot = altOf(leader, soloMapling.server.SoloMaplingConstants.GameConstants.BOT_BASE_ID + 10 + i,
                    "Chaos" + i);
            when(bot.getLevel()).thenReturn(120);
            bots.add(bot);
        }
        party(leader, bots.toArray(new Character[0]));
        return bots;
    }

    @Test
    void fivePartyBotsAtLevel120JoinAChaosZakumExpeditionAndReachItsMinimum() {
        boolean daily = YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS;
        YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS = true;
        server.expeditions.ExpeditionBossLog.useChaosBotRunsToday(() -> 0);
        try {
            Character leader = player(LEADER, "Josh");
            chaosBots(leader, 5);
            Expedition exped = expedition(leader, ExpeditionType.CHAOS_ZAKUM, 0);

            assertEquals(5, ExpeditionBots.autoJoin(leader, exped).size());

            assertTrue(exped.getMembers().size() >= exped.getMinSize(), "6 of " + exped.getMinSize());
            verify(leader, never()).dropMessage(eq(6), contains("Chaos Zakum runs"));
        } finally {
            server.expeditions.ExpeditionBossLog.useChaosBotRunsToday(null);
            YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS = daily;
        }
    }

    @Test
    void whenTheBotsDailyChaosRunsAreSpentNoBotJoinsAndTheLeaderIsToldOnce() {
        boolean daily = YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS;
        YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS = true;
        server.expeditions.ExpeditionBossLog.useChaosBotRunsToday(
                () -> server.expeditions.ExpeditionBossLog.CHAOS_ZAKUM_BOT_DAILY_RUNS);
        ExpeditionBots.quota = realQuota; // the real boss-log rule, not the stub
        try {
            Character leader = player(LEADER, "Josh");
            chaosBots(leader, 5);
            Expedition exped = expedition(leader, ExpeditionType.CHAOS_ZAKUM, 0);

            assertTrue(ExpeditionBots.autoJoin(leader, exped).isEmpty());
            assertTrue(ExpeditionBots.autoJoin(leader, exped).isEmpty(), "the next NPC step");

            assertEquals(1, exped.getMembers().size(), "only the leader");
            verify(leader, org.mockito.Mockito.times(1)).dropMessage(6, "[Expedition] "
                    + ExpeditionBots.CHAOS_QUOTA_TEXT);
            assertTrue(ExpeditionBots.CHAOS_QUOTA_TEXT.contains("25"));
        } finally {
            server.expeditions.ExpeditionBossLog.useChaosBotRunsToday(null);
            YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS = daily;
        }
    }

    @Test
    void aPartyIsCutToTheChaosRunsThatAreLeft() {
        boolean daily = YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS;
        YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS = true;
        server.expeditions.ExpeditionBossLog.useChaosBotRunsToday(
                () -> server.expeditions.ExpeditionBossLog.CHAOS_ZAKUM_BOT_DAILY_RUNS - 2);
        try {
            Character leader = player(LEADER, "Josh");
            chaosBots(leader, 5);
            Expedition exped = expedition(leader, ExpeditionType.CHAOS_ZAKUM, 0);

            assertEquals(2, ExpeditionBots.autoJoin(leader, exped).size(), "rows are written at the start: 2 left");
            verify(leader).dropMessage(6, "[Expedition] " + ExpeditionBots.CHAOS_QUOTA_TEXT);
        } finally {
            server.expeditions.ExpeditionBossLog.useChaosBotRunsToday(null);
            YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS = daily;
        }
    }

    @Test
    void chaosZakumAsksForLevel120LikeItsRow() {
        assertEquals("level", ExpeditionBots.skip(ExpeditionType.CHAOS_ZAKUM, LEADER,
                facts(LEADER, false, true, 100, true)));
        assertNull(ExpeditionBots.skip(ExpeditionType.CHAOS_ZAKUM, LEADER, facts(LEADER, false, true, 120, true)));
    }
}

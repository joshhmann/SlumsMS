package soloMapling.ArtificialPlayer.BotPartySystem;

import client.BotClient;
import client.Character;
import config.YamlConfig;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 1.6.0 (owner 2026-10-26): party bots auto-join their player's boss expedition. */
class ExpeditionBotsTest {

    private static final int LEADER = 1;
    private final List<Expedition> made = new ArrayList<>();
    private final MapleMap altar = mock(MapleMap.class);

    @BeforeAll
    static void timers() {
        server.TimerManager.getInstance().start(); // the registration timer
    }

    private final ExpeditionBots.Quota realQuota = ExpeditionBots.quota;

    @org.junit.jupiter.api.BeforeEach
    void noDatabase() {
        ExpeditionBots.quota = (bot, exped) -> true; // the boss log is a DB read
        net.server.world.World world = mock(net.server.world.World.class);
        when(world.getPlayerStorage()).thenReturn(mock(net.server.PlayerStorage.class));
        when(altar.getWorldServer()).thenReturn(world); // dispose() reads the online members
    }

    @AfterEach
    void cleanUp() {
        made.forEach(ExpeditionBots::release);
        ExpeditionBots.quota = realQuota;
    }

    // ── the rules ────────────────────────────────────────────────────────────

    private static ExpeditionBots.Facts facts(int owner, boolean sole, boolean sameMap, int level, boolean quest) {
        return new ExpeditionBots.Facts(true, false, false, false, sameMap, level, owner, sole, quest);
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
        ExpeditionBots.Facts member = new ExpeditionBots.Facts(true, true, false, false, true, 60, LEADER, false, true);
        assertEquals("member", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, member));
        ExpeditionBots.Facts banned = new ExpeditionBots.Facts(true, false, true, false, true, 60, LEADER, false, true);
        assertEquals("banned", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, banned));
        ExpeditionBots.Facts dead = new ExpeditionBots.Facts(true, false, false, true, true, 60, LEADER, false, true);
        assertEquals("dead", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, dead));
        ExpeditionBots.Facts human = new ExpeditionBots.Facts(false, false, false, false, true, 60, LEADER, false, true);
        assertEquals("not a bot", ExpeditionBots.skip(ExpeditionType.ZAKUM, LEADER, human));
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
}

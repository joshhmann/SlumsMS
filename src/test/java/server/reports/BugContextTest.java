package server.reports;

import client.Character;
import client.Client;
import client.Job;
import client.QuestStatus;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.server.Json;

import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

class BugContextTest {

    static final BugContext.Names NAMES = new BugContext.Names() {
        public String skill(int id) { return "Skill" + id; }
        public String item(int id) { return "Item" + id; }
        public String quest(int id) { return "Quest" + id; }
    };

    private static Character reporter() {
        Character chr = mock(Character.class);
        when(chr.getName()).thenReturn("Tester");
        when(chr.getId()).thenReturn(321);
        when(chr.getJob()).thenReturn(Job.FIGHTER);
        when(chr.getLevel()).thenReturn(35);
        when(chr.gmLevel()).thenReturn(0);
        when(chr.getPosition()).thenReturn(new Point(120, -45));
        when(chr.getFh()).thenReturn(17);
        when(chr.getHp()).thenReturn(800);
        when(chr.getCurrentMaxHp()).thenReturn(1200);
        when(chr.getMp()).thenReturn(90);
        when(chr.getCurrentMaxMp()).thenReturn(150);
        when(chr.getMeso()).thenReturn(1_234_567);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getAvailableBuffs()).thenReturn(Set.of(1001003, -2022003));
        List<QuestStatus> quests = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            QuestStatus q = mock(QuestStatus.class);
            when(q.getQuestID()).thenReturn((short) (1000 + i));
            quests.add(q);
        }
        when(chr.getStartedQuests()).thenReturn(quests);

        Party party = mock(Party.class);
        when(party.getId()).thenReturn(9);
        when(party.getLeaderId()).thenReturn(321);
        PartyCharacter me = mock(PartyCharacter.class);
        when(me.getName()).thenReturn("Tester");
        when(me.getId()).thenReturn(321);
        PartyCharacter bot = mock(PartyCharacter.class);
        when(bot.getName()).thenReturn("Regular");
        when(bot.getId()).thenReturn(1_000_000_123);
        when(party.getMembers()).thenReturn(List.of(me, bot));
        when(chr.getParty()).thenReturn(party);

        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(100000000);
        when(map.getMapName()).thenReturn("Henesys");
        when(map.getStreetName()).thenReturn("Victoria Road");
        Monster snail = mock(Monster.class);
        when(snail.getId()).thenReturn(100100);
        when(snail.getName()).thenReturn("Snail");
        Monster shroom = mock(Monster.class);
        when(shroom.getId()).thenReturn(120100);
        when(map.getAllMonsters()).thenReturn(List.of(snail, snail, shroom));
        when(map.getMapObjectsInRange(any(), anyDouble(), any())).thenReturn(List.of());
        Character other = mock(Character.class);
        when(other.getName()).thenReturn("Friend");
        when(other.getId()).thenReturn(400);
        Character botP = mock(Character.class);
        when(botP.getName()).thenReturn("Bot1");
        when(botP.getId()).thenReturn(1_000_000_500);
        when(map.getAllPlayers()).thenReturn(List.of(chr, other, botP));
        when(chr.getMap()).thenReturn(map);

        PlayerTrail t = new PlayerTrail();
        t.recordMap(1_000, 104000000, "Lith Harbor");
        t.recordNpc(2_000, 1012000, "Regular Cab");
        t.record(3_000, "attack", 0, "close");
        t.record(3_500, "attack", 1001004, "close");
        t.record(3_600, "attack", 1001004, "close");
        t.record(4_000, "pickup", 500, "meso");
        t.record(4_500, "use", 2000000, null);
        when(chr.bugTrail()).thenReturn(t);
        return chr;
    }

    private static Client client(Character chr) {
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(chr);
        when(c.getWorld()).thenReturn(1);
        when(c.getChannel()).thenReturn(2);
        when(c.getClientBuild()).thenReturn(10680);
        when(c.getClientRelease()).thenReturn("1.6.1");
        when(c.getClientStampFormat()).thenReturn(2);
        when(c.getClientCaps()).thenReturn(3);
        return c;
    }

    @Test
    void captureHasTheAgreedShape() {
        Character chr = reporter();
        Map<String, Object> ctx = BugContext.capture(client(chr), chr, 1_793_000_000_000L, NAMES);
        Map<?, ?> m = (Map<?, ?>) Json.parse(Json.write(ctx)); // through JSON, as the file is read back
        assertEquals(List.of("schema", "capturedAt", "versions", "character", "party", "buffs", "quests", "trail",
                "surroundings", "serverLog"), new ArrayList<>(m.keySet()));
        Map<?, ?> v = (Map<?, ?>) m.get("versions");
        assertEquals(constants.net.ServerConstants.SLUMSMS_VERSION, v.get("server"));
        Map<?, ?> cl = (Map<?, ?>) v.get("client");
        assertEquals(10680L, cl.get("build"));
        assertEquals(List.of("customNpcData", "logUpload"), cl.get("capsNames"));
        assertEquals(2L, v.get("channel"));

        Map<?, ?> c = (Map<?, ?>) m.get("character");
        assertEquals("Tester", c.get("name"));
        assertEquals("FIGHTER", ((Map<?, ?>) c.get("job")).get("name"));
        assertEquals(1_234_567L, c.get("meso"));
        assertEquals(17L, c.get("foothold"));
        assertEquals("Henesys", ((Map<?, ?>) c.get("map")).get("name"));

        List<?> members = (List<?>) ((Map<?, ?>) m.get("party")).get("members");
        assertEquals(Boolean.FALSE, ((Map<?, ?>) members.get(0)).get("bot"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) members.get(0)).get("leader"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) members.get(1)).get("bot"));

        List<?> buffs = (List<?>) m.get("buffs");
        assertEquals(Map.of("item", 2022003L, "name", "Item2022003"), buffs.get(0));
        assertEquals(Map.of("skill", 1001003L, "name", "Skill1001003"), buffs.get(1));

        Map<?, ?> q = (Map<?, ?>) m.get("quests");
        assertEquals(60L, q.get("count"));
        assertEquals(BugContext.MAX_QUESTS, ((List<?>) q.get("started")).size());

        Map<?, ?> t = (Map<?, ?>) m.get("trail");
        assertEquals("Lith Harbor", ((Map<?, ?>) ((List<?>) t.get("maps")).get(0)).get("name"));
        List<?> ev = (List<?>) t.get("events");
        assertEquals(4, ev.size());
        assertEquals("basic attack", ((Map<?, ?>) ev.get(0)).get("name"));
        assertEquals(2L, ((Map<?, ?>) ev.get(1)).get("count"));
        assertNull(((Map<?, ?>) ev.get(2)).get("name"), "mesos have no item name");
        assertEquals("Item2000000", ((Map<?, ?>) ev.get(3)).get("name"));

        Map<?, ?> s = (Map<?, ?>) m.get("surroundings");
        List<?> mobs = (List<?>) s.get("mobs");
        assertEquals(2L, ((Map<?, ?>) mobs.get(0)).get("count"));
        assertEquals(2L, s.get("playerCount"), "the reporter is not counted");
        List<?> players = (List<?>) s.get("players");
        assertEquals(Map.of("name", "Friend", "bot", false), players.get(0));
        assertEquals(Map.of("name", "Bot1", "bot", true), players.get(1));
        assertInstanceOf(List.class, m.get("serverLog"));
        assertFalse(Json.write(ctx).contains("accountName"), "no account data");
    }

    @Test
    void aBrokenSectionDoesNotStopTheRest() {
        Character chr = reporter();
        when(chr.getStartedQuests()).thenThrow(new IllegalStateException("db"));
        Map<String, Object> ctx = BugContext.capture(client(chr), chr, 0, NAMES);
        assertEquals(Map.of("error", "IllegalStateException"), ctx.get("quests"));
        assertInstanceOf(Map.class, ctx.get("trail"));
    }

    @Test
    void capKeepsTheReportUnder64KbAndSaysWhatWasCut() {
        Character chr = reporter();
        Map<String, Object> ctx = BugContext.capture(client(chr), chr, 0, NAMES);
        List<Object> logs = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            logs.add("2026-10-28T10:00:00.000Z WARN X - line " + i + " " + "y".repeat(380));
        }
        ctx.put("serverLog", logs);
        BugContext.cap(ctx, BugContext.MAX_BYTES);
        assertTrue(BugContext.bytes(ctx) <= BugContext.MAX_BYTES);
        assertEquals(List.of("serverLog"), ctx.get("truncated"));
        List<?> kept = (List<?>) ctx.get("serverLog");
        assertTrue(((String) kept.getLast()).contains("line 499"), "the newest lines are kept");
        assertTrue(kept.size() > 50);

        Map<String, Object> small = BugContext.capture(client(chr), chr, 0, NAMES);
        BugContext.cap(small, BugContext.MAX_BYTES);
        assertFalse(small.containsKey("truncated"), "nothing is cut when it fits");

        Map<String, Object> huge = new LinkedHashMap<>();
        huge.put("schema", 1);
        huge.put("character", new LinkedHashMap<>(Map.of("name", "z".repeat(5000))));
        huge.put("other", "q".repeat(10_000));
        BugContext.cap(huge, 2_000);
        assertTrue(BugContext.bytes(huge) <= 2_000, "last resort keeps only the basics: " + huge.keySet());
    }

    @Test
    void summaryIsShort() {
        Character chr = reporter();
        Map<String, Object> ctx = BugContext.capture(client(chr), chr, 0, NAMES);
        Map<String, Object> s = BugContext.summary(ctx, "context/B-1.json");
        assertEquals("context/B-1.json", s.get("file"));
        assertEquals(10680, s.get("clientBuild"));
        assertEquals(35, s.get("level"));
        assertEquals(100000000, s.get("mapId"));
        assertEquals(4, s.get("events"));
        assertTrue(BugContext.bytes(s) < 600);
    }

    @Test
    void capsNames() {
        assertEquals(List.of(), BugContext.capsNames(0));
        assertEquals(List.of("customNpcData"), BugContext.capsNames(1));
        assertEquals(List.of("logUpload", "bit5"), BugContext.capsNames(2 | 32));
    }
}

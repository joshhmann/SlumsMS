package scripting;

import client.BotClient;
import client.Character;
import client.Client;
import config.YamlConfig;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotPartySystem.ExpeditionBotsAccess;
import soloMapling.WzTestData;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * b075 (live report #5, "Aldol npc doesn't activate even after quest chain completion"): a level-100 player who
 * finished the Spooky World chain (quest 4576, the pass 4032246) and brought party bots could not tell why Aldol
 * (9270047) would not start Scarga. The whole recruiter flow runs here against a real {@link Expedition} and the real
 * party-bot registration: five level-100 bots let him start; with bots that cannot come Aldol names each one and why;
 * without the pass he is told where it comes from.
 */
class AldolScargaScriptTest {

    private static final int LEADER = 1;
    private static final int PASS = 4032246;

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakeEm {
        public boolean started;

        public void setProperty(String key, Object value) {
        }

        public String getProperty(String key) {
            return "";
        }

        public boolean startInstance(Expedition expedition) {
            started = expedition != null;
            return true;
        }
    }

    /** The parts of NPCConversationManager Aldol uses; getExpedition and createExpedition work like the real ones. */
    public final class Cm {
        final Character player;
        final FakeEm em = new FakeEm();
        boolean pass = true;
        public String ok;
        public boolean disposed;

        Cm(Character player) {
            this.player = player;
        }

        public Character getPlayer() {
            return player;
        }

        public Expedition getExpedition(ExpeditionType type) {
            if (current != null && current.isRegistering() && current.isLeader(player)) {
                ExpeditionBotsAccess.autoJoin(player, current);
            }
            return current;
        }

        public int createExpedition(ExpeditionType type) {
            current = new Expedition(player, type, true, 0, 0);
            current.beginRegistration();
            made.add(current);
            ExpeditionBotsAccess.autoJoin(player, current);
            return 0;
        }

        public boolean haveItem(int id) {
            return id == PASS && pass;
        }

        public FakeEm getEventManager(String name) {
            return em;
        }

        public void sendOk(String text) {
            ok = text;
        }

        public void sendSimple(String text) {
        }

        public void dispose() {
            disposed = true;
        }
    }

    private Expedition current;
    private final List<Expedition> made = new ArrayList<>();
    private final MapleMap entrance = mock(MapleMap.class);
    private boolean savedSolo;

    @BeforeAll
    static void timers() {
        server.TimerManager.getInstance().start(); // the registration timer
    }

    @BeforeEach
    void setUp() {
        savedSolo = YamlConfig.config.server.USE_ENABLE_SOLO_EXPEDITIONS;
        YamlConfig.config.server.USE_ENABLE_SOLO_EXPEDITIONS = false; // Scarga's real minimum of 6
        ExpeditionBotsAccess.noQuotaRead();
        net.server.world.World world = mock(net.server.world.World.class);
        when(world.getPlayerStorage()).thenReturn(mock(net.server.PlayerStorage.class));
        when(entrance.getWorldServer()).thenReturn(world);
    }

    @AfterEach
    void tearDown() {
        made.forEach(ExpeditionBotsAccess::release);
        ExpeditionBotsAccess.restoreQuota();
        YamlConfig.config.server.USE_ENABLE_SOLO_EXPEDITIONS = savedSolo;
    }

    private Character chr(int id, String name, int level) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getMap()).thenReturn(entrance);
        when(c.getLevel()).thenReturn(level);
        when(c.isAlive()).thenReturn(true);
        return c;
    }

    private Character leader() {
        Character p = chr(LEADER, "Josh", 105);
        when(p.getClient()).thenReturn(mock(Client.class));
        return p;
    }

    private Character alt(Character owner, int id, String name, int level) {
        Character a = chr(id, name, level);
        when(a.getClient()).thenReturn(mock(BotClient.class));
        when(a.isOwnedAlt()).thenReturn(true);
        when(a.getOwnedAltOwner()).thenReturn(owner);
        return a;
    }

    private static void party(Character leader, List<Character> others) {
        Party party = mock(Party.class);
        List<PartyCharacter> members = new ArrayList<>();
        List<Character> all = new ArrayList<>(List.of(leader));
        all.addAll(others);
        for (Character c : all) {
            int id = c.getId();
            String name = c.getName();
            PartyCharacter pc = mock(PartyCharacter.class);
            when(pc.getId()).thenReturn(id);
            when(pc.getName()).thenReturn(name);
            when(pc.getPlayer()).thenReturn(c);
            members.add(pc);
            when(c.getParty()).thenReturn(party);
        }
        when(party.getMembers()).thenReturn(members);
    }

    /** One NPC conversation: a fresh script (status 0), then each selection in turn. */
    private void talk(Cm cm, int... selections) throws Exception {
        ScriptEngine engine = new Loader().load("npc/9270047.js");
        assertNotNull(engine);
        engine.put("cm", cm);
        Invocable iv = (Invocable) engine;
        cm.ok = null;
        cm.disposed = false;
        iv.invokeFunction("start");
        for (int s : selections) {
            if (cm.disposed) {
                break;
            }
            iv.invokeFunction("action", 1, 0, s);
        }
    }

    @Test
    void fiveLevel100PartyBotsLetHimStartScarga() throws Exception {
        Character josh = leader();
        List<Character> bots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            bots.add(alt(josh, 1_000_000_100 + i, "Clock" + i, 100 + i));
        }
        party(josh, bots);
        Cm cm = new Cm(josh);

        talk(cm, 1); // "Lets get this going!"
        assertNotNull(current, "the expedition was created: " + cm.ok);
        assertEquals(6, current.getMemberList().size(), "his five party bots joined");

        talk(cm, 2, 0); // "Start the fight!", then OK on the escort notice
        assertTrue(cm.em.started, "Scarga started: " + cm.ok);
    }

    @Test
    void tooFewMembersNamesEachBotThatCannotComeAndWhy() throws Exception {
        Character josh = leader();
        Character ok1 = alt(josh, 1_000_000_200, "Able", 110);
        Character ok2 = alt(josh, 1_000_000_201, "Baker", 100);
        Character low = alt(josh, 1_000_000_202, "Lowly", 87);
        Character away = alt(josh, 1_000_000_203, "Faraway", 120);
        when(away.getMap()).thenReturn(mock(MapleMap.class));
        Character tired = alt(josh, 1_000_000_204, "Tired", 130);
        party(josh, List.of(ok1, ok2, low, away, tired));
        ExpeditionBotsAccess.quota((bot, exped) -> bot.getId() != 1_000_000_204);
        Cm cm = new Cm(josh);

        talk(cm, 1);
        assertEquals(3, current.getMemberList().size());
        talk(cm, 2);

        assertFalse(cm.em.started);
        assertTrue(cm.disposed);
        String said = cm.ok;
        assertTrue(said.startsWith("You need at least 6 members registered in your expedition; you have 3."), said);
        assertTrue(said.contains("Lowly is level 87; this expedition needs level 100 or higher."), said);
        assertTrue(said.contains("Faraway is not on this map. Bring it here first."), said);
        assertTrue(said.contains("Tired has no entries left for this boss today."), said);
        assertFalse(said.contains("Able"), "a bot that joined is not listed: " + said);
        assertTrue(said.contains("Other players join by talking to me."), said);
    }

    @Test
    void withoutThePassHeIsToldWhereItComesFrom() throws Exception {
        Character josh = leader();
        party(josh, List.of());
        Cm cm = new Cm(josh);
        cm.pass = false;

        talk(cm, 1);

        assertEquals(null, current, "no expedition without the pass");
        assertTrue(cm.ok.contains("#t4032246#"), cm.ok);
        assertTrue(cm.ok.contains("#bLam#k gives you one when you complete #b#y4576##k."), cm.ok);
    }

    /** The pass Aldol checks is the one quest 4576 ("Off to the Fantasy Theme Park") rewards. Needs wz/Quest.wz. */
    @Test
    void theQuestChainRewardsThePassAldolChecks() throws Exception {
        WzTestData.assumePresent("Quest.wz");
        String src = Files.readString(Path.of("scripts/npc/9270047.js"));
        Matcher item = Pattern.compile("var expedItem = (\\d+);").matcher(src);
        Matcher quest = Pattern.compile("var expedQuest = (\\d+);").matcher(src);
        assertTrue(item.find() && quest.find());

        Path act = WzTestData.dir().resolve("Quest.wz/Act.img.xml");
        org.w3c.dom.Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(act.toFile());
        org.w3c.dom.NodeList top = doc.getDocumentElement().getChildNodes();
        boolean rewards = false;
        for (int i = 0; i < top.getLength(); i++) {
            if (top.item(i) instanceof org.w3c.dom.Element q && quest.group(1).equals(q.getAttribute("name"))) {
                org.w3c.dom.NodeList ids = q.getElementsByTagName("int");
                for (int j = 0; j < ids.getLength(); j++) {
                    org.w3c.dom.Element e = (org.w3c.dom.Element) ids.item(j);
                    if ("id".equals(e.getAttribute("name")) && item.group(1).equals(e.getAttribute("value"))) {
                        org.w3c.dom.Element count = (org.w3c.dom.Element) e.getParentNode();
                        rewards |= count.getElementsByTagName("int").getLength() > 1
                                && Integer.parseInt(((org.w3c.dom.Element) count.getElementsByTagName("int").item(1))
                                .getAttribute("value")) > 0;
                    }
                }
            }
        }
        assertTrue(rewards, "quest " + quest.group(1) + " gives item " + item.group(1));
    }
}

package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.skills.Magician;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyChat;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionSupplies;
import soloMapling.ArtificialPlayer.BotPartySystem.ExpeditionBots;
import soloMapling.ArtificialPlayer.BotPartySystem.ExpeditionBotsAccess;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy;
import soloMapling.companions.OwnedAltOrders;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 alt-supplies-in-boss-fights. TEST Horntail, 8 Nov 2026 04:09-04:13 UTC: the alts joined with almost no
 * potions (HP 0-7, MP 0), two Hero alts cast Brandish 47 times a minute until their MP was 13 of 1200 and then swung
 * the plain attack, both died in room 3, and nothing was said to the owner before or during the run.
 *
 * <p>This pins the three answers: in a boss run a companion drinks at the boss lines (HP under 70%, MP under 50%, the
 * nutnnut defaults) and not at the open world's lines, the owner or leader is told once when its potions are down to
 * ten and once when they are gone, and the recruiter names every member that will fight short of supplies.
 */
class CompanionSuppliesTest {

    private static final int RED = 2000000;    // 50 HP
    private static final int BLUE = 2000003;   // 100 MP
    private static final int SWORD = 1302000;
    private static final int LEADER = 1;

    private static final Map<Integer, SupplyData.Recovery> POTIONS = Map.of(
            RED, new SupplyData.Recovery(50, 0, 0, 0),
            BLUE, new SupplyData.Recovery(0, 100, 0, 0));

    // skillId -> {bulletCount, bulletConsume, mpCon, hpCon, x, duration}
    private static final Map<Integer, int[]> SKILLS = Map.of(Magician.MAGIC_CLAW, new int[]{0, 0, 10, 0, 0, 0});

    private static final AtomicInteger ids = new AtomicInteger(900_000_000);

    private final MapleMap altar = mock(MapleMap.class);
    private final Character owner = owner();
    private final List<Expedition> runs = new ArrayList<>();
    private Map<Integer, Skill> previousSkills;
    private MockedStatic<OwnedAltOrders> said;      // the owner's line is scheduled; catch it instead of sending it

    @BeforeAll
    static void itemProviderClass() throws Exception {
        // ItemInformationProvider's static init reads the DB and the WZ; load the class once against mocks.
        try (MockedStatic<tools.DatabaseConnection> db = mockStatic(tools.DatabaseConnection.class);
             MockedStatic<provider.DataProviderFactory> providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                    org.mockito.ArgumentMatchers.any(provider.wz.WZFiles.class))).thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @BeforeEach
    void data() throws Exception {
        Ledgers.clear();
        SupplyData.useForTest(new SupplyData.Source() {
            @Override
            public SupplyData.Recovery recovery(int itemId) {
                return POTIONS.get(itemId);
            }

            @Override
            public int bulletCount(int skillId, int level) {
                return 1;
            }

            @Override
            public int bulletConsume(int skillId, int level) {
                return n(skillId)[1];
            }

            @Override
            public int mpCon(int skillId, int level) {
                return n(skillId)[2];
            }

            @Override
            public int hpCon(int skillId, int level) {
                return n(skillId)[3];
            }

            @Override
            public int x(int skillId, int level) {
                return n(skillId)[4];
            }

            @Override
            public int duration(int skillId, int level) {
                return n(skillId)[5];
            }

            @Override
            public int projectileWatk(int itemId) {
                return 15;
            }

            private int[] n(int skillId) {
                return SKILLS.getOrDefault(skillId, new int[6]);
            }
        });
        Map<Integer, Skill> skills = new HashMap<>();
        skills.put(Magician.MAGIC_CLAW, new Skill(Magician.MAGIC_CLAW));
        previousSkills = swapSkillFactory(skills);
        said = mockStatic(OwnedAltOrders.class);
    }

    @AfterEach
    void restore() throws Exception {
        SupplyData.useForTest(null);
        swapSkillFactory(previousSkills);
        runs.forEach(ExpeditionBotsAccess::release);
        runs.clear();
        said.close();
        System.clearProperty(PartyCompanionPolicy.Feature.SUPPLIES.property(false));
        System.clearProperty(PartyCompanionPolicy.Feature.SUPPLIES.property(true));
    }

    // ── the boss lines ───────────────────────────────────────────────────────

    @Test
    void inABossRunAnAltPotsAtSeventyPercentButOutsideOneOnlyUnderHalf() throws Exception {
        Character alt = ownedAlt(400, 1000);
        give(alt, RED, 10);
        alt.updateHp(260);                       // 65%

        BotVitals.topUp(alt);
        assertEquals(260, alt.getHp(), "outside a boss run the line is still half HP");
        assertEquals(10, count(alt, RED));

        bossRun(alt);

        BotVitals.topUp(alt);
        assertEquals(310, alt.getHp(), "in a boss run it drinks before half");
        assertEquals(9, count(alt, RED));
    }

    @Test
    void aBossRunAltDrinksManaOnItsAttackPath() throws Exception {
        Character alt = ownedAlt(400, 1000);
        hold(alt, Magician.MAGIC_CLAW, 20);
        give(alt, BLUE, 3);
        bossRun(alt);
        alt.updateMp(450);                       // 45%: over the open world's 30% line

        SwingSupply swing = SwingSupply.prepare(alt, BotAttackProfile.magic(Magician.MAGIC_CLAW, 2), null, 20);
        assertEquals(Magician.MAGIC_CLAW, swing.profile().skillId, "450 MP still pays for the spell");
        swing.commit(alt, BotAttackProfile.Route.MAGIC, Magician.MAGIC_CLAW);   // pays 10, then tops up

        assertEquals(540, alt.getMp(), "after the cast it drank a mana potion at 44%");
        assertEquals(2, count(alt, BLUE), "and not a second one");
    }

    @Test
    void theOwnerIsToldOnceAtTenPotionsAndOnceWhenTheyAreGone() throws Exception {
        Character alt = ownedAlt(400, 1000);
        hold(alt, Magician.MAGIC_CLAW, 20);
        give(alt, RED, 10);
        give(alt, BLUE, 10);
        bossRun(alt);

        BotVitals.topUp(alt);
        BotVitals.topUp(alt);
        said.verify(() -> OwnedAltOrders.say(alt, "I'm down to 10 HP potions"), times(1));
        said.verify(() -> OwnedAltOrders.say(alt, "I'm down to 10 mana potions"), times(1));

        take(alt, RED);
        BotVitals.topUp(alt);
        BotVitals.topUp(alt);
        said.verify(() -> OwnedAltOrders.say(alt, "I'm out of HP potions"), times(1));

        take(alt, BLUE);
        BotVitals.topUp(alt);
        BotVitals.topUp(alt);
        said.verify(() -> OwnedAltOrders.say(alt, "I'm out of mana potions"), times(1));
        said.verify(() -> OwnedAltOrders.say(alt, "I'm out of HP potions"), times(1));
    }

    @Test
    void aCompanionOutOfARunSaysNothing() throws Exception {
        Character alt = ownedAlt(400, 1000);
        give(alt, RED, 2);
        BotVitals.topUp(alt);
        said.verifyNoInteractions();
    }

    // ── the pre-run check at the recruiter ───────────────────────────────────

    @Test
    void theRecruiterNamesTheMemberThatWillFightWithoutManaPotions() throws Exception {
        Character leader = player(LEADER, "Josh");
        Character shortOfMana = ownedAlt(400, 1000);
        Character stocked = ownedAlt(400, 1000);
        for (Character alt : List.of(shortOfMana, stocked)) {
            hold(alt, Magician.MAGIC_CLAW, 20);
            give(alt, RED, 60);
            equipSword(alt);
        }
        give(stocked, BLUE, 60);                 // this one is ready
        shortOfMana.setName("Second");
        stocked.setName("Third");
        party(leader, shortOfMana, stocked);

        Expedition exped = registration(leader, shortOfMana, stocked);
        String text = ExpeditionBots.shortfall(leader, exped);

        assertTrue(text.contains("Joining, but short of supplies:"), text);
        assertTrue(text.contains("Second has no mana potions."), text);
        assertFalse(text.contains("Third"), text);
        assertFalse(text.toLowerCase().matches("(?s).*\\bbots?\\b.*"), text);
    }

    // ── a party regular: off by default ─────────────────────────────────────

    @Test
    void aRegularKeepsTheOldLinesUntilTheRegularSwitchIsOn() throws Exception {
        Character regular = bot(400, 1000);      // not an owned alt: a party regular
        hold(regular, Magician.MAGIC_CLAW, 20);
        give(regular, RED, 10);
        give(regular, BLUE, 10);
        bossRun(regular);
        regular.updateHp(260);

        assertNull(CompanionSupplies.shortOf(regular), "the default switch says nothing about a regular");
        try (MockedStatic<BotPartyChat> said = mockStatic(BotPartyChat.class)) {
            BotVitals.topUp(regular);
            assertEquals(260, regular.getHp(), "and drinks at the open world's line");
            said.verifyNoInteractions();
        }

        System.setProperty(PartyCompanionPolicy.Feature.SUPPLIES.property(false), "true");
        try (MockedStatic<BotPartyChat> said = mockStatic(BotPartyChat.class)) {
            BotVitals.topUp(regular);
            assertEquals(310, regular.getHp(), "with the switch on it drinks at the boss line");
            said.verify(() -> BotPartyChat.sayInParty(regular, "I'm down to 10 HP potions"), times(1));
            said.verify(() -> BotPartyChat.sayInParty(regular, "I'm down to 10 mana potions"), times(1));
        }
        assertNotNull(CompanionSupplies.shortOf(regular), "and the recruiter check covers it too");
    }

    // ── resting after a boss revive (owner, 8 Nov 2026: "70% plus drinking") ──

    @Test
    void aRestingAltDrinksItsOwnPotionsUpToTheRestLine() throws Exception {
        Character alt = ownedAlt(400, 1000);
        give(alt, RED, 10);
        give(alt, BLUE, 10);
        alt.updateHpMp(100, 200);                // 25% HP, 20% MP: just revived

        BotVitals.restTopUp(alt, 70);

        assertEquals(300, alt.getHp(), "four reds take it from 25% to 75%, the first step at or over 70%");
        assertEquals(700, alt.getMp(), "five blues take it from 20% to 70%");
        assertEquals(6, count(alt, RED));
        assertEquals(5, count(alt, BLUE));

        BotVitals.restTopUp(alt, 70);
        assertEquals(6, count(alt, RED), "at the line it drinks nothing more");
        assertEquals(5, count(alt, BLUE));
    }

    @Test
    void aRestingAltWithoutPotionsJustWaits() throws Exception {
        Character alt = ownedAlt(400, 1000);
        alt.updateHpMp(100, 200);
        BotVitals.restTopUp(alt, 70);
        assertEquals(100, alt.getHp());
        assertEquals(200, alt.getMp());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static Character owner() {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(LEADER);
        when(chr.getName()).thenReturn("Josh");
        when(chr.getAccountID()).thenReturn(7);
        when(chr.getWorld()).thenReturn(1);
        return chr;
    }

    private static Character player(int id, String name) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        return chr;
    }

    /** A real character, a bot by its client: an owned alt of the owner when owned. */
    private static Character character(boolean owned, int maxHp, int maxMp) throws Exception {
        BotClient client = new BotClient(1, 1);
        client.setAccID(7);
        Character chr = Character.getDefault(client);
        chr.setID(ids.incrementAndGet());
        chr.setName("Alt" + chr.getId());
        chr.setWorld(1);
        client.setPlayer(chr);
        chr.setJob(Job.MAGICIAN);
        chr.setLevel(20);
        for (String f : new String[]{"maxhp", "localmaxhp", "clientmaxhp"}) {
            setInt(chr, f, maxHp);
        }
        for (String f : new String[]{"maxmp", "localmaxmp", "clientmaxmp"}) {
            setInt(chr, f, maxMp);
        }
        chr.updateHpMp(maxHp, maxMp);
        if (owned) {
            chr.markOwnedAlt(owner());
        }
        return chr;
    }

    private static Character ownedAlt(int maxHp, int maxMp) throws Exception {
        return character(true, maxHp, maxMp);
    }

    private static Character bot(int maxHp, int maxMp) throws Exception {
        return character(false, maxHp, maxMp);
    }

    private static void equipSword(Character chr) {
        chr.getInventory(InventoryType.EQUIPPED).addItemFromDB(new Equip(SWORD, (short) -11));
    }

    /** Puts the companions inside a boss instance: held by an expedition that has them as members. */
    private Expedition bossRun(Character... bots) {
        Expedition exped = mock(Expedition.class);
        for (Character bot : bots) {
            when(exped.hasMember(bot.getId())).thenReturn(true);
            ExpeditionBotsAccess.hold(bot, exped);
            bot.setEventInstance(mock(EventInstanceManager.class));
        }
        runs.add(exped);
        return exped;
    }

    /** A recruiting expedition that already lists these characters as members. */
    private Expedition registration(Character leader, Character... members) {
        Expedition exped = mock(Expedition.class);
        Map<Integer, String> listed = new HashMap<>();
        listed.put(leader.getId(), leader.getName());
        for (Character member : members) {
            when(exped.hasMember(member.getId())).thenReturn(true);
            listed.put(member.getId(), member.getName());
        }
        when(exped.getType()).thenReturn(ExpeditionType.ZAKUM);
        when(exped.getMinSize()).thenReturn(6);
        when(exped.getMembers()).thenReturn(listed);
        when(exped.getRecruitingMap()).thenReturn(altar);
        runs.add(exped);
        return exped;
    }

    private static void party(Character leader, Character... members) {
        Party party = mock(Party.class);
        List<PartyCharacter> all = new ArrayList<>();
        List<Character> everyone = new ArrayList<>();
        everyone.add(leader);
        everyone.addAll(List.of(members));
        for (Character chr : everyone) {
            int id = chr.getId();                    // read before stubbing: a mock call inside when() breaks it
            String name = chr.getName();
            PartyCharacter pc = mock(PartyCharacter.class);
            when(pc.getId()).thenReturn(id);
            when(pc.getName()).thenReturn(name);
            when(pc.getPlayer()).thenReturn(chr);
            all.add(pc);
        }
        when(party.getMembers()).thenReturn(all);
        for (Character chr : everyone) {
            if (org.mockito.Mockito.mockingDetails(chr).isMock()) {
                when(chr.getParty()).thenReturn(party);
            } else {
                chr.setParty(party);
            }
        }
    }

    private static void give(Character bot, int itemId, int quantity) {
        Item item = new Item(itemId, (short) 0, (short) quantity, -1);
        assertTrue(bot.getInventory(InventoryType.USE).addItem(item) > 0);
    }

    private static void take(Character bot, int itemId) {
        for (Item stack : bot.getInventory(InventoryType.USE).listById(itemId).toArray(new Item[0])) {
            BotHoldings.consumeHeld(bot, stack, stack.getQuantity());
        }
    }

    private static int count(Character bot, int itemId) {
        int n = 0;
        for (Item item : bot.getInventory(InventoryType.USE).listById(itemId)) {
            n += item.getQuantity();
        }
        return n;
    }

    private static void hold(Character bot, int skillId, int level) {
        bot.changeSkillLevel(SkillFactory.getSkill(skillId), (byte) level, level, -1);
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(target, value);
                return;
            } catch (NoSuchFieldException e) {
                // look further up
            }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Skill> swapSkillFactory(Map<Integer, Skill> skills) throws Exception {
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        Map<Integer, Skill> previous = (Map<Integer, Skill>) field.get(null);
        field.set(null, skills);
        return previous;
    }

    // The ledger is static; start every test from an empty one.
    private static final class Ledgers {
        static void clear() throws Exception {
            java.lang.reflect.Method clear = BotHoldings.class.getDeclaredMethod("clearForTest");
            clear.setAccessible(true);
            clear.invoke(null);
        }
    }
}

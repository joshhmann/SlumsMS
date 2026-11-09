package scripting;

import client.Character;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;
import server.maps.MapleMap;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Execute the real clear/claim script with a JS event and a real player, without a running world.
 *
 * <p>1.6.7 bagrewards: the script now measures each player's bag before it grants, through
 * {@code InventoryManipulator.checkSpaceProgressively(Character, …)}, which takes a real {@link Character}. The
 * player is therefore a mocked character over real bags (see {@link BossRewardBagTestSupport}), not a JS object, and
 * a reward is asserted by what the player holds afterwards — the same way it reaches them in game.
 */
class BossForgeMaterialsTest {
    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load() {
            return getInvocableScriptEngine("npc/3004006.js");
        }
    }

    public static final class FakeEim {
        final Map<String, String> props = new HashMap<>();
        final List<Character> players = new ArrayList<>();

        public void setProperty(String key, Object value) {
            props.put(key, String.valueOf(value));
        }

        public String getProperty(String key) {
            return props.get(key);
        }

        public boolean isEventCleared() {
            return true;
        }

        public List<Character> getPlayers() {
            return players;
        }

        public String sendDmgDealt(int world) {
            return "";
        }
    }

    public static final class ExitCm {
        public FakeEim eim;
        public Character player;
        public int map;
        public final List<String> oks = new ArrayList<>();
        public boolean disposed;
        public int warpTo = -1;

        public FakeEim getEventInstance() {
            return eim;
        }

        public int getMapId() {
            return map;
        }

        public Character getPlayer() {
            return player;
        }

        public void sendYesNo(String text) {
        }

        public void sendOk(String text) {
            oks.add(text);
        }

        public void warp(int mapId) {
            warpTo = mapId;
        }

        public void dispose() {
            disposed = true;
        }
    }

    private record Run(ScriptEngine engine, BossRewardBagTestSupport.Member member, FakeEim eim) {
        /** How many of that item the player now holds, across stacks and tabs. */
        int amount(int itemId) {
            return member.countEverywhere(itemId);
        }

        void talkAgain() throws Exception {
            Invocable npc = (Invocable) engine;
            npc.invokeFunction("start");
            npc.invokeFunction("action", 1, 0, 0);
        }
    }

    private MockedStatic<ItemInformationProvider> itemData;

    @BeforeAll
    static void loadItemProviderClass() throws Exception {
        BossRewardBagTestSupport.loadItemProviderClass();
    }

    @BeforeEach
    void setUp() {
        itemData = BossRewardBagTestSupport.stubItemData((short) 32000, Map.of());
    }

    @AfterEach
    void tearDown() {
        itemData.close();
    }

    private Run clear(int map) throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(123, mock(World.class));
        when(member.chr.getMap()).thenReturn(mock(MapleMap.class));

        FakeEim eim = new FakeEim();
        eim.players.add(member.chr);
        ExitCm cm = new ExitCm();
        cm.eim = eim;
        cm.player = member.chr;
        cm.map = map;

        ScriptEngine engine = new Loader().load();
        assertNotNull(engine);
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");
        npc.invokeFunction("action", 1, 0, 0);
        return new Run(engine, member, eim);
    }

    @Test
    void normalLucidGivesOneNonQuestForgeTrophy() throws Exception {
        Run run = clear(450004751);
        assertEquals(1, run.amount(4001879));
        assertEquals(1, run.amount(4033732));
    }

    @Test
    void hardLucidGivesTwoTrophiesAndTheHardToken() throws Exception {
        Run run = clear(450004750);
        assertEquals(2, run.amount(4001879));
        assertEquals(1, run.amount(4036535));
    }

    @Test
    void normalClearDoesNotGiveTheHardModeToken() throws Exception {
        assertEquals(0, clear(450004751).amount(4036535));
    }

    @Test
    void everySetWeaponTrophyHasAnActualClearReceipt() throws Exception {
        for (int[] pair : new int[][]{{211070100, 4001693}, {271040100, 4000659}, {450004751, 4001879},
                {450007441, 4001890}, {450012211, 4001894}}) {
            assertEquals(1, clear(pair[0]).amount(pair[1]), "item " + pair[1]);
        }
    }

    @Test
    void theHardTokensAndMagnusInputsHaveClearReceipts() throws Exception {
        for (int[] pair : new int[][]{{450004750, 4036535}, {450007440, 4036536}, {450012210, 4036537},
                {450010100, 4036538}, {401060100, 4036540}, {401060100, 4036543}}) {
            assertEquals(1, clear(pair[0]).amount(pair[1]), "item " + pair[1]);
        }
    }

    @Test
    void eachRequiredBossClearGivesEnoughAnyClassCoresForTheExchange() throws Exception {
        for (int[] pair : new int[][]{{271040100, 1}, {450004751, 3}, {450007441, 3}, {450012211, 3},
                {450004750, 5}, {450007440, 5}, {450012210, 5}, {450010100, 5}, {401060100, 7}}) {
            Run run = clear(pair[0]);
            int total = 0;
            for (int id = 4036544; id <= 4036548; id++) {
                total += run.amount(id);
            }
            assertEquals(pair[1], total, "map " + pair[0]);
        }
    }

    @Test
    void anotherTalkCannotClaimTheSameClearAgain() throws Exception {
        Run run = clear(450004751);
        run.talkAgain();
        assertEquals(1, run.amount(4001879));
    }
}

package soloMapling.ArtificialPlayer;

import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.6.0 S3: the stock-code taps (PQ clears, trades), and scam detection only in clear cases. */
class SocialHooksTest {

    static final int SLOT = 31;
    static final int BOT = BotStore.BOT_ID_BASE + SLOT;
    static final int PLAYER = 4242;
    static final int CLAW = 1472030;    // a claw the price index knows at 1m
    static final int JUNK = 4000019;    // a snail shell, 10 mesos
    static final int MYSTERY = 4999999; // nobody can price it

    private final FakeMemoryTable table = new FakeMemoryTable(false);
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    private final List<SocialProfile.Push> pushes = new ArrayList<>();
    private final Map<Integer, Long> prices = Map.of(CLAW, 1_000_000L, JUNK, 10L);

    @BeforeEach
    void setUp() {
        table.install();
        BotMemory.useForTest(Runnable::run, clock::get, 0);
        BotEpisodes.useForTest(Runnable::run, map -> null, (bot, push) -> pushes.add(push));
        SocialHooks.useForTest(item -> prices.getOrDefault(item.getItemId(), 0L),
                id -> id == CLAW ? "Red Craven" : id == JUNK ? "Snail Shell" : "Thing", clock::get);
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, clock::get, id -> true);
        BotAffinity.useNotesForTest((slot, playerId, note) -> { });
        SocialGate.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotMemory.useConnectionSourceForTest(null);
        BotMemory.useForTest(null, null, 0);
        BotEpisodes.useForTest(null, null, null);
        SocialHooks.useForTest(null, null, null);
        BotAffinity.useForTest(null, null, null, null);
        BotAffinity.useNotesForTest(null);
        SocialGate.resetForTest();
    }

    private static Item item(int id, int qty) {
        Item i = mock(Item.class);
        when(i.getItemId()).thenReturn(id);
        when(i.getQuantity()).thenReturn((short) qty);
        return i;
    }

    private static Character chr(int id, String name) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        return c;
    }

    private static SocialHooks.Side side(long mesos, Item... items) {
        return new SocialHooks.Side(List.of(items), mesos);
    }

    @Test
    void onlyObviousShortChangesAreScams() {
        // the bot gave a 1m claw
        assertEquals(SocialHooks.Outcome.SCAM, SocialHooks.read(side(0, item(CLAW, 1)), side(10_000)).outcome(),
                "a 1m claw for 10k");
        assertEquals(SocialHooks.Outcome.SCAM, SocialHooks.read(side(0, item(CLAW, 1)), side(0, item(JUNK, 3))).outcome(),
                "a claw for three snail shells");
        assertEquals(SocialHooks.Outcome.TRADE, SocialHooks.read(side(0, item(CLAW, 1)), side(400_000)).outcome(),
                "a lowball (40%) is a haggle, not a scam");
        assertEquals(SocialHooks.Outcome.TRADE, SocialHooks.read(side(0, item(CLAW, 1)), side(950_000)).outcome());
        assertEquals(SocialHooks.Outcome.TRADE, SocialHooks.read(side(0, item(CLAW, 1)), side(0, item(MYSTERY, 1))).outcome(),
                "an item nobody can price is never a scam");
        assertEquals(SocialHooks.Outcome.TRADE, SocialHooks.read(side(50_000), side(0, item(JUNK, 1))).outcome(),
                "too small to call a scam");
        assertEquals(SocialHooks.Outcome.BOT_GAVE, SocialHooks.read(side(0, item(CLAW, 1)), side(0)).outcome(),
                "nothing back is the bot's own gift");
        assertEquals(SocialHooks.Outcome.GIFT, SocialHooks.read(side(0), side(0, item(CLAW, 1))).outcome());
        assertEquals(SocialHooks.Outcome.GIFT, SocialHooks.read(side(0), side(5_000)).outcome(), "mesos are a gift too");
        assertEquals(SocialHooks.Outcome.FAVOR, SocialHooks.read(side(300_000), side(0, item(CLAW, 1))).outcome(),
                "a 1m claw for 300k: the player did the bot a favor");
        // the bot paid mesos for junk
        assertEquals(SocialHooks.Outcome.SCAM, SocialHooks.read(side(500_000), side(0, item(JUNK, 1))).outcome());
    }

    @Test
    void aScamTradeIsAGrudgeAnEpisodeAndStartsIt() {
        Character bot = chr(BOT, "Nayrb");
        Character player = chr(PLAYER, "Josh");
        assertTrue(BotAffinity.stage(BOT, PLAYER, AffinityState.NEUTRAL)); // the player's ties are loaded
        // through the Trade.java hook: the bot put in the claw and receives 10k; the player receives nothing
        SocialHooks.onTradeDone(bot, List.of(item(CLAW, 1)), 10_000, player, List.of(), 0);
        assertTrue(BotAffinity.score(BOT, PLAYER) <= -19, "a scam is a grudge: " + BotAffinity.score(BOT, PLAYER));
        assertTrue(SocialGate.startedIt(BOT, PLAYER), "the player started it");
        List<BotMemory.Note> notes = BotMemory.about(SLOT, PLAYER);
        assertEquals(1, notes.size());
        assertEquals(BotMemory.Kind.SCAM, notes.get(0).kind());
        assertEquals("scammed me on a trade: my Red Craven for 10k mesos",
                notes.get(0).text());
        assertEquals(9, notes.get(0).weight());
        assertEquals(List.of(SocialProfile.Push.SCAMMED), pushes, "a small push toward Killer");
    }

    @Test
    void aFairTradeAGiftAndAFavorAreKindEpisodes() {
        Character bot = chr(BOT, "Nayrb");
        Character player = chr(PLAYER, "Josh");
        BotAffinity.stage(BOT, PLAYER, AffinityState.NEUTRAL);
        // the player buys the bot's claw for 950k: player is a, receives the claw; the bot receives 950k
        SocialHooks.onTradeDone(player, List.of(), 0, bot, List.of(item(CLAW, 1)), 950_000);
        clock.addAndGet(TimeUnit.MINUTES.toMillis(11));
        SocialHooks.onTradeDone(player, List.of(item(JUNK, 5)), 0, bot, List.of(), 0); // a gift
        clock.addAndGet(TimeUnit.MINUTES.toMillis(11));
        SocialHooks.onTradeDone(player, List.of(item(CLAW, 1)), 300_000, bot, List.of(), 0); // sold to the bot cheap
        List<String> texts = BotMemory.about(SLOT, PLAYER).stream().map(BotMemory.Note::text).toList();
        assertEquals(List.of("bought my Red Craven", "gave me 5 Snail Shell", "gave me a good deal: a Red Craven for 300k mesos"),
                texts);
        assertTrue(BotAffinity.score(BOT, PLAYER) > 15, "trade + gift + favor");
        assertFalse(SocialGate.startedIt(BOT, PLAYER));
        assertTrue(pushes.isEmpty());
    }

    @Test
    void aScamAgainstAnOptedOutPlayerLeavesNothing() {
        Character bot = chr(BOT, "Nayrb");
        Character player = chr(PLAYER, "Josh");
        BotAffinity.stage(BOT, PLAYER, AffinityState.NEUTRAL);
        BotAffinity.setOptedOut(player, true); // @botdrama off: no grudges about them
        SocialHooks.tradeDone(bot, player, side(0, item(CLAW, 1)), side(10_000));
        assertEquals(0, BotAffinity.score(BOT, PLAYER), 0.01);
        assertFalse(SocialGate.startedIt(BOT, PLAYER));
        assertTrue(BotMemory.about(SLOT, PLAYER).isEmpty());
    }

    @Test
    void clearsAreNamedAsPlayersSayThem() {
        assertEquals(new SocialHooks.Clear("KPQ", false), SocialHooks.clearOf("KerningPQ"));
        assertEquals(new SocialHooks.Clear("MPQ", false), SocialHooks.clearOf("MagatiaPQ_Z"));
        assertEquals(new SocialHooks.Clear("zakum", true), SocialHooks.clearOf("ZakumBattle"));
        assertNull(SocialHooks.clearOf("WeddingCathedral"));
        assertNull(SocialHooks.clearOf("AreaBossMano"));
        assertNull(SocialHooks.clearOf("3rdJob_magician"));
        assertNull(SocialHooks.clearOf("Boats"));
        assertNull(SocialHooks.clearOf(null));
    }
}

package soloMapling.ArtificialPlayer.BotGuildSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore.MemberRow;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.5.0 pre-release review: guild loads, queued membership writes, refills. */
class BotGuildSafetyTest {

    GuildStore.Memory store;
    final List<Integer> roster = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = new GuildStore.Memory();
        Random rng = new Random(3);
        for (int i = 0; i < 500; i++) {
            int botId = 200 + i;
            roster.add(botId);
            store.bots.add(new GuildStore.StoredBot(botId, "Bot" + botId, 1 + rng.nextInt(150), 100));
        }
        BotGuilds.useForTest(store, id -> null, () -> roster);
    }

    @AfterEach
    void tearDown() {
        BotGuilds.useForTest(null, null, null);
    }

    @Test
    void aFailedLoadFoundsNothingAndIsTriedAgain() {
        BotGuilds.seed(1000, new Random(5));
        int stored = store.guilds.size();
        BotGuilds.useForTest(store, id -> null, () -> roster); // a restart...
        store.failing = true; // ...with the database not answering
        BotGuilds.step(5000, new Random(1));
        assertFalse(BotGuilds.loaded());
        assertEquals(stored, store.guilds.size(), "nothing founded a second time");
        store.failing = false;
        BotGuilds.retryLoadForTest();
        BotGuilds.step(6000, new Random(1));
        assertTrue(BotGuilds.loaded());
        assertEquals(stored, BotGuilds.botGuilds().size(), "the stored guilds are back");
        assertEquals(stored, store.guilds.size());
    }

    @Test
    void membershipWritesAreQueuedOffTheCallerInOrder() {
        Deque<Runnable> queued = new ArrayDeque<>();
        BotGuilds.useWriterForTest(queued::add);
        int charId = BotStore.BOT_ID_BASE + 900;
        MemberRow row = new MemberRow(charId, 42, 5, 1000, 1000, "Zed", 30, 100);
        store.members.put(charId, row);
        BotGuilds.putForTest(row);
        BotGuilds.onRankChanged(charId, 3); // the stock rank change: a packet thread
        assertEquals(5, store.members.get(charId).rank(), "nothing written on the caller's thread");
        assertEquals(3, BotGuilds.membership(charId).rank(), "memory has it at once");
        BotGuilds.leave(charId, "expelled"); // the stock expel: holding the guild's member lock
        assertEquals(1, queued.size(), "the delete waits behind the save");
        queued.poll().run();
        assertEquals(3, store.members.get(charId).rank());
        queued.poll().run();
        assertNull(store.members.get(charId), "the delete landed last");
    }

    @Test
    void aRefillPicksABotOfTheGuildsLevelBand() {
        GuildPlan.Spec legendz = BotGuilds.specOf("legendz");
        Character low = mock(Character.class);
        when(low.getLevel()).thenReturn(30);
        Character high = mock(Character.class);
        when(high.getLevel()).thenReturn(80);
        assertEquals(high, BotGuilds.pickRefill(List.of(low, high), legendz, new Random(1)));
        assertNull(BotGuilds.pickRefill(List.of(low), legendz, new Random(1)), "nobody in the band: nobody");
    }

    @Test
    void foundedGuildsHaveLittleGpAndRcGuildsAreRescaledOnce() {
        BotGuilds.seed(1000, new Random(5));
        GuildStore.GuildRow legendz = BotGuilds.byName("legendz");
        assertEquals(GuildRules.seedGp(legendz.targetSize()), store.gp(legendz.guildId()));
        store.gp.put(legendz.guildId(), legendz.targetSize() * 120 + 150); // as rc3 founded it, one staged clear
        GuildStore.GuildRow other = BotGuilds.byName("snailgang");
        store.gp.put(other.guildId(), 5555); // players earned GP there
        assertEquals(1, BotGuilds.rescaleOldGp().size());
        assertEquals(GuildRules.seedGp(legendz.targetSize()) + GuildRules.bossGp(), store.gp(legendz.guildId()));
        assertEquals(5555, store.gp(other.guildId()));
        assertTrue(BotGuilds.rescaleOldGp().isEmpty(), "idempotent");
    }
}

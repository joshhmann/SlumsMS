package soloMapling.ArtificialPlayer.BotGuildSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore.GuildRow;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore.MemberRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotGuildsTest {

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
    void seedingFoundsTenGuildsWithLeadersRanksAndTwoRivalPairs() {
        int founded = BotGuilds.seed(1000, new Random(5));
        assertEquals(10, founded);
        assertEquals(10, store.guilds.size());
        int members = 0;
        int rivals = 0;
        for (GuildRow g : BotGuilds.botGuilds()) {
            List<MemberRow> ms = BotGuilds.membersOf(g.guildId());
            members += ms.size();
            assertEquals(1, ms.stream().filter(m -> m.rank() == 1).count(), g.name() + " has one leader");
            assertEquals(g.leaderId(), ms.get(0).charId(), "the leader is the stock row's leader");
            assertTrue(ms.stream().allMatch(m -> m.name().startsWith("Bot") && m.job() == 100), "names and jobs from the store");
            if (g.rivalId() > 0) {
                rivals++;
                assertEquals(g.guildId(), BotGuilds.guild(g.rivalId()).rivalId(), "rivalry goes both ways");
            }
        }
        assertEquals(220, members);
        assertEquals(220, store.members.size(), "every member is stored");
        assertEquals(4, rivals);
        assertTrue(BotGuilds.isBotGuild(BotGuilds.byName("lionheart").guildId()));
    }

    @Test
    void seedingWaitsForTheRosterAndNeverRunsWithoutTheMigration() {
        roster.clear();
        assertEquals(-1, BotGuilds.seed(1000, new Random(1)), "no roster yet: try again later");
        store.available = false;
        assertEquals(0, BotGuilds.seed(1000, new Random(1)));
        assertTrue(store.guilds.isEmpty());
    }

    @Test
    void seedingTwiceDoesNotFoundAgainAndAReloadKeepsEverything() {
        BotGuilds.seed(1000, new Random(5));
        assertEquals(0, BotGuilds.seed(2000, new Random(5)));
        BotGuilds.useForTest(store, id -> null, () -> roster); // a restart: everything from the store
        BotGuilds.ensureLoaded();
        assertEquals(10, BotGuilds.botGuilds().size());
        assertEquals(220, BotGuilds.botGuilds().stream().mapToInt(g -> BotGuilds.membersOf(g.guildId()).size()).sum());
        assertEquals(10, BotGuilds.botGuilds().stream().filter(g -> !BotGuilds.membersOf(g.guildId()).isEmpty()).count());
    }

    @Test
    void foundingSkipsATakenName() {
        BotGuilds.seed(1000, new Random(5));
        int before = store.guilds.size();
        String result = BotGuilds.formNow("Lionheart", GuildRules.Vibe.CASUAL, 5, 3000);
        assertTrue(result.contains("could not found"), result);
        assertEquals(before, store.guilds.size());
        String ok = BotGuilds.formNow("Mushrooms", GuildRules.Vibe.NEWBIE, 5, 3000);
        assertTrue(ok.startsWith("founded Mushrooms"), ok);
        assertEquals("bad", BotGuilds.formNow("no spaces", GuildRules.Vibe.CASUAL, 5, 3000).contains("3-12 letters") ? "bad" : "ok");
    }

    @Test
    void theRankChangeIsKeptAndBackdateAgesAMember() {
        int charId = BotStore.BOT_ID_BASE + 900;
        MemberRow row = new MemberRow(charId, 42, 5, 1000, 1000, "Zed", 30, 100);
        BotGuilds.putForTest(row);
        BotGuilds.onRankChanged(charId, 3);
        assertEquals(3, BotGuilds.membership(charId).rank());
        assertEquals(3, store.members.get(charId).rank());
        assertTrue(BotGuilds.backdate(charId, 30));
        assertTrue(System.currentTimeMillis() - BotGuilds.membership(charId).attentionAt() >= 29L * 86_400_000L);
        assertFalse(BotGuilds.backdate(charId + 1, 30));
    }

    @Test
    void applicationLinesAreRecognised() {
        for (String yes : List.of("can i join ur guild?", "Can I join your guild", "any room in ur guild",
                "pls invite me to the guild", "is ur guild recruiting", "can i join")) {
            assertTrue(BotGuilds.APPLY.matcher(yes).matches(), yes);
        }
        for (String no : List.of("hi", "nice guild name", "wanna party?", "my guild is cool")) {
            assertFalse(BotGuilds.APPLY.matcher(no).matches(), no);
        }
    }

    @Test
    void recruitTownsAreHenesysAndTheFm() {
        assertTrue(BotGuilds.inRecruitTown(100000000));
        assertTrue(BotGuilds.inRecruitTown(910000011));
        assertFalse(BotGuilds.inRecruitTown(104040000));
    }

    @Test
    void thePromptKnowsTheGuild() {
        BotGuilds.seed(1000, new Random(5));
        GuildRow lion = BotGuilds.byName("Lionheart");
        MemberRow leader = BotGuilds.membersOf(lion.guildId()).get(0);
        String line = BotGuilds.promptLine(leader.charId());
        assertTrue(line.contains("the leader of the guild Lionheart") && line.contains("hardcore") && line.contains("Nightfall"), line);
        assertEquals("", BotGuilds.promptLine(BotStore.BOT_ID_BASE + 1));
    }
}

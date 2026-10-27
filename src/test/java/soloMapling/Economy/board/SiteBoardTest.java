package soloMapling.Economy.board;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.Economy.MegaBuyDesk;
import soloMapling.server.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SiteBoardTest {
    static final int GLOVE = 2040804, ORE = 4004000;
    @TempDir Path dir;
    FakeBoardWorld w;
    Path inbox, outbox, stateFile;
    final List<String> log = new ArrayList<>();
    long mtime = 1_000_000;

    @BeforeEach
    void setUp() {
        w = new FakeBoardWorld().account(7, "Momo", "MomoAlt").account(8, "Pip").item(GLOVE, "Gloves for ATT 60%")
                .item(ORE, "Power Crystal Ore");
        inbox = dir.resolve("in");
        outbox = dir.resolve("out");
        stateFile = dir.resolve("state.json");
    }

    SiteBoard.Engine engine() {
        SiteBoard.Engine e = new SiteBoard.Engine(inbox, outbox, stateFile, w, new MegaBuyDesk.Limits(0, 1000, 1000, 1000, 0));
        e.forQa(0, 0, log::add);
        return e;
    }

    void writeInbox(String name, String json) throws Exception {
        Files.createDirectories(inbox);
        Path f = inbox.resolve(name);
        Files.writeString(f, json);
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtime += 1000));
    }

    String post(String id, int account, String character, String kind, int item, int qty, long price) {
        return "{\"id\":\"" + id + "\",\"account\":" + account + ",\"accountName\":\"secretlogin\",\"character\":\""
                + character + "\",\"kind\":\"" + kind + "\",\"itemId\":" + item + ",\"qty\":" + qty + ",\"price\":"
                + price + ",\"createdAt\":" + (w.now - 60_000) + "}";
    }

    void posts(String... rows) throws Exception {
        writeInbox("posts.json", "{\"schema\":1,\"posts\":[" + String.join(",", rows) + "]}");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> out(String name) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(outbox.resolve(name)));
    }

    @SuppressWarnings("unchecked")
    List<String> ids(String file, String list) throws Exception {
        return ((List<Map<String, Object>>) out(file).get(list)).stream().map(m -> (String) m.get("id")).toList();
    }

    @Test
    void tradeRatingsGoToThePrivateOutboxEveryCycle() throws Exception {
        SiteBoard.Engine e = engine();
        e.cycle();
        assertFalse(Files.exists(outbox.resolve("trade-ratings.json")), "nothing to write: no file");

        w.tradeRatings = soloMapling.site.TradeRatings.privateFile(
                List.of(new soloMapling.site.SiteLeaderboards.Person(70, "Momo", 50, 0, 100, 0, 0, 0)),
                List.of(new soloMapling.site.TradeRatings.Row(70, 2, 1)), w.now);
        e.cycle();
        Path f = outbox.resolve("trade-ratings.json");
        assertEquals("{\"generated\":\"" + java.time.Instant.ofEpochMilli(w.now)
                + "\",\"ratings\":{\"Momo\":{\"completed\":2,\"fellThrough\":1}}}", Files.readString(f));
        assertEquals("rw-r-----", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(f)),
                "group-readable like the other outbox files, not world-readable like the public site files");
        try (var listing = Files.list(outbox)) {
            assertTrue(listing.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "written atomically");
        }
        assertFalse(soloMapling.site.PublicSiteExport.FILES.contains("trade-ratings.json"));
        try (var all = Files.walk(dir)) {
            assertEquals(List.of(f), all.filter(p -> p.getFileName().toString().equals("trade-ratings.json")).toList(),
                    "only in the outbox");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void gmAndAutoQaPostersAreNotNamedOnTheBoard() throws Exception {
        w.account(9, "GmGuy").account(10, "AutoQaOne");
        w.hidden.add(90);   // GmGuy's gm level is above 0
        posts(post("p1", 7, "Momo", "WTB", GLOVE, 1, 3_000_000), post("p2", 9, "GmGuy", "WTB", GLOVE, 1, 3_000_000),
                post("p3", 10, "AutoQaOne", "WTS", ORE, 5, 2000));
        engine().poll();
        Map<String, String> named = new java.util.TreeMap<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) out("board.json").get("posts")) {
            named.put((String) r.get("id"), (String) r.get("character"));
        }
        assertEquals(Map.of("p1", "Momo", "p2", "", "p3", ""), named, "the posts stay, the names go");
        String text = Files.readString(outbox.resolve("board.json"));
        assertFalse(text.contains("GmGuy") || text.contains("AutoQa"), text);
    }

    @Test
    void aGoodPostIsOnTheBoardABadOneInRejectedAndNoAccountNameAnywhere() throws Exception {
        posts(post("p1", 7, "Momo", "WTB", GLOVE, 1, 3_000_000), post("p2", 7, "Pip", "WTS", ORE, 5, 2000));
        SiteBoard.Engine e = engine();
        assertTrue(e.poll());
        assertFalse(e.poll(), "unchanged files are not read again");
        assertEquals(List.of("p1"), ids("board.json", "posts"));
        Map<String, Object> row = ((List<Map<String, Object>>) out("board.json").get("posts")).get(0);
        assertEquals("Momo", row.get("character"));
        assertEquals("WTB", row.get("kind"));
        assertEquals((long) GLOVE, row.get("itemId"));
        assertEquals(List.of("id", "character", "kind", "itemId", "qty", "price", "createdAt", "expiresAt"),
                List.copyOf(row.keySet()));
        assertEquals(1L, out("board.json").get("schema"));
        assertEquals(List.of("p2"), ids("rejected.json", "rejected"));
        for (String f : SiteBoard.OUTBOX_FILES) {
            String text = Files.readString(outbox.resolve(f));
            assertFalse(text.contains("secretlogin"), f);
            assertEquals("rw-r-----", java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(outbox.resolve(f))), f);
        }
    }

    @Test
    void aBotWithTheItemWhispersAnOnlinePosterOnceAndANoteGoesToAnOfflineOne() throws Exception {
        w.bag(1_000_000_001, "Shroomy", GLOVE, 1, 2_800_000);
        w.goOnline(7, "MomoAlt");
        posts(post("p1", 7, "Momo", "WTB", GLOVE, 1, 3_000_000));
        SiteBoard.Engine e = engine();
        e.poll();
        assertEquals(1, w.whispers.size(), log.toString());
        assertTrue(w.whispers.get(0).startsWith("Shroomy -> MomoAlt: "), w.whispers.toString());
        assertTrue(w.whispers.get(0).contains("gloves for att 60%"), w.whispers.toString());
        e.cycle();
        assertEquals(1, w.whispers.size(), "the same bot never answers a post twice");

        // the poster is offline: the offer waits as a note on the posting character
        w.online.clear();
        w.bag(1_000_000_002, "Fungo", ORE, 20, 40_000);
        posts(post("p1", 7, "Momo", "WTB", GLOVE, 1, 3_000_000), post("p3", 8, "Pip", "WTB", ORE, 20, 2000));
        e.poll();
        assertEquals(1, w.notes.size(), log.toString());
        assertTrue(w.notes.get(0).startsWith("Fungo -> Pip: "), w.notes.toString());
    }

    @Test
    void offersJsonListsEachOfferForTheBell() throws Exception {
        w.bag(1_000_000_001, "Shroomy", GLOVE, 1, 2_800_000);
        w.goOnline(7, "Momo");
        posts(post("p1", 7, "Momo", "WTB", GLOVE, 1, 3_000_000));
        engine().poll();
        Map<String, Object> f = out("offers.json");
        assertEquals(1L, f.get("schema"));
        List<Map<String, Object>> offers = (List<Map<String, Object>>) f.get("offers");
        assertEquals(1, offers.size(), log.toString());
        assertEquals(List.of("postId", "bot", "price", "t"), List.copyOf(offers.get(0).keySet()));
        assertEquals("p1", offers.get(0).get("postId"));
        assertEquals("Shroomy", offers.get(0).get("bot"));
        assertEquals(3_000_000L, ((Number) offers.get(0).get("price")).longValue());
        assertTrue(((String) offers.get(0).get("t")).endsWith("Z"));
    }

    @Test
    void aHeldWhisperFallsBackToANote() throws Exception {
        w.bag(1_000_000_001, "Shroomy", GLOVE, 1, 2_800_000);
        w.goOnline(7, "Momo");
        w.whisperOk = false;
        posts(post("p1", 7, "Momo", "WTB", GLOVE, 1, 3_000_000));
        engine().poll();
        assertEquals(1, w.notes.size());
        assertTrue(w.notes.get(0).startsWith("Shroomy -> Momo: "));
    }

    @Test
    void aPostGetsAtMostTwoOffersOneLaterAndNeverFromTheSameBot() throws Exception {
        for (int i = 1; i <= 4; i++) {
            w.bag(1_000_000_000 + i, "Bot" + i, GLOVE, 1, 2_800_000);
        }
        posts(post("p1", 7, "Momo", "WTB", GLOVE, 1, 3_000_000));
        SiteBoard.Engine e = engine();
        e.poll();
        assertEquals(1, w.notes.size(), "one offer when the post is new");
        e.cycle();
        assertEquals(1, w.notes.size(), "no second offer within the first hour");
        w.now += SiteBoard.SECOND_OFFER_AFTER_MS;
        for (int i = 0; i < 5; i++) {
            e.cycle();
            w.now += TimeUnit.MINUTES.toMillis(15);
        }
        assertEquals(BoardState.OFFER_CAP, w.notes.size(), w.notes.toString());
        assertNotEquals(w.notes.get(0).split(" ")[0], w.notes.get(1).split(" ")[0]);
        // a restart remembers who answered
        SiteBoard.Engine again = engine();
        again.poll();
        again.cycle();
        assertEquals(BoardState.OFFER_CAP, w.notes.size());
    }

    @Test
    void noOfferWhenThePosterIsFarBelowOrNoBotHasIt() throws Exception {
        w.bag(1_000_000_001, "Shroomy", GLOVE, 1, 2_800_000);
        posts(post("p1", 7, "Momo", "WTB", GLOVE, 1, 1_000), post("p2", 7, "Momo", "WTB", ORE, 1, 5_000));
        engine().poll();
        assertTrue(w.notes.isEmpty() && w.whispers.isEmpty());
        assertEquals(2, log.stream().filter(l -> l.startsWith("NOOFFER ")).count(), log.toString());
    }

    @Test
    void aBotWithMesosOffersToBuyAWtsPostInsideItsBand() throws Exception {
        w.buyers.add(new BoardOffers.Buyer(1_000_000_005, "Moneybags", 2, 10_000_000, 2_000));
        posts(post("p1", 8, "Pip", "WTS", ORE, 10, 1_500), post("p2", 7, "Momo", "WTS", ORE, 10, 100_000));
        engine().poll();
        assertEquals(1, w.notes.size(), log.toString());
        assertTrue(w.notes.get(0).startsWith("Moneybags -> Pip: "));
        assertTrue(w.notes.get(0).contains("15k"), "the asking price, inside the band: " + w.notes.get(0));
    }

    @Test
    void anAlertFiresOnceToTheLastPlayedCharacterAndIsRecordedForTheSite() throws Exception {
        w.listings.put(GLOVE, 2_400_000L);
        w.lastPlayed.put(7, "MomoAlt");
        writeInbox("alerts.json", "{\"schema\":1,\"accounts\":[{\"account\":7,\"watchlist\":[2040804],\"alerts\":["
                + "{\"id\":\"a1\",\"itemId\":2040804,\"direction\":\"below\",\"price\":2500000,\"armed\":true}]}]}");
        SiteBoard.Engine e = engine();
        e.cycle();
        assertEquals(1, w.notes.size());
        assertTrue(w.notes.get(0).startsWith(SiteBoard.NOTE_SENDER + " -> MomoAlt: Price alert: Gloves for ATT 60% is listed at"),
                w.notes.get(0));
        assertEquals(java.util.Set.of("7"), out("alerts-fired.json").keySet());
        @SuppressWarnings("unchecked")
        Map<String, Object> fired = ((List<Map<String, Object>>) out("alerts-fired.json").get("7")).get(0);
        assertEquals(List.of("id", "t", "price"), List.copyOf(fired.keySet()));
        assertEquals("a1", fired.get("id"));
        assertEquals(2_400_000L, fired.get("price"));
        w.now += TimeUnit.MINUTES.toMillis(15);
        e.cycle();
        assertEquals(1, w.notes.size(), "once only");
        // online: a yellow line instead of a note
        w.goOnline(7, "Momo");
        writeInbox("alerts.json", "{\"accounts\":[{\"account\":7,\"alerts\":[{\"id\":\"a1\",\"itemId\":2040804,"
                + "\"direction\":\"below\",\"price\":2500000,\"armed\":false}]}]}");
        e.cycle();
        writeInbox("alerts.json", "{\"accounts\":[{\"account\":7,\"alerts\":[{\"id\":\"a1\",\"itemId\":2040804,"
                + "\"direction\":\"below\",\"price\":2500000,\"armed\":true}]}]}");
        e.cycle();
        assertEquals(1, w.yellows.size());
        assertTrue(w.yellows.get(0).startsWith("Momo: [Market] Price alert: "));
    }

    @Test
    void aGmRemovalTakesThePostOffAndTheSiteIsTold() throws Exception {
        posts(post("p1", 7, "Momo", "WTB", GLOVE, 1, 3_000_000), post("p2", 7, "Momo", "WTB", ORE, 1, 3_000));
        SiteBoard.Engine e = engine();
        e.poll();
        assertTrue(e.remove("p1", "GmBob", "spam").startsWith("Removed post p1"));
        assertTrue(e.remove("p1", "GmBob", "spam").contains("already removed"));
        assertEquals(List.of("p2"), e.board().stream().map(Board.Post::id).toList());
        e.cycle();
        assertEquals(List.of("p2"), ids("board.json", "posts"));
        assertEquals(List.of("p1"), ids("removed.json", "removed"));
        assertFalse(Files.readString(outbox.resolve("removed.json")).contains("GmBob"));
        assertTrue(Files.readString(outbox.resolve("rejected.json")).contains("removed by a GM"));
        assertTrue(Files.readString(outbox.resolve("removed.json")).contains("\"reason\":\"spam\""));
        // a restart keeps it removed
        SiteBoard.Engine again = engine();
        again.poll();
        assertEquals(List.of("p2"), again.board().stream().map(Board.Post::id).toList());
    }

    @Test
    void outboxFilesAreWrittenThroughATempFileInTheSameFolder() throws Exception {
        List<Path> temps = new ArrayList<>();
        BoardFiles.onTemp = temps::add;
        try {
            Path f = dir.resolve("fresh/outbox/board.json");
            BoardFiles.writeJson(f, Map.of("schema", 1));
            assertEquals(1, temps.size());
            assertEquals(f.toAbsolutePath().getParent(), temps.get(0).getParent());
            assertFalse(Files.exists(temps.get(0)), "renamed into place");
            assertEquals("rw-r-----", java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(f)));
        } finally {
            BoardFiles.onTemp = null;
        }
    }

    @Test
    void postsExpireAfterThreeDays() throws Exception {
        posts(post("p1", 7, "Momo", "WTB", GLOVE, 1, 3_000_000));
        SiteBoard.Engine e = engine();
        e.poll();
        assertEquals(1, e.board().size());
        w.now += BoardRules.LIFE_MS;
        e.cycle();
        assertEquals(0, e.board().size());
        assertTrue(Files.readString(outbox.resolve("rejected.json")).contains("expired"));
    }

    @Test
    void anUnreadablePostsFileKeepsTheLastBoard() throws Exception {
        posts(post("p1", 7, "Momo", "WTB", GLOVE, 1, 3_000_000));
        SiteBoard.Engine e = engine();
        e.poll();
        writeInbox("posts.json", "{half a fi");
        e.poll();
        assertEquals(List.of("p1"), e.board().stream().map(Board.Post::id).toList());
    }

    @Test
    void botsPostBetweenFiveAndFifteenBackedOffersThatGoWhenNoLongerBacked() throws Exception {
        for (int i = 0; i < 30; i++) {
            w.candidates.add(new BotBoardPosts.Candidate(1_000_000_100 + i, "Seller" + i,
                    i % 2 == 0 ? Board.Kind.WTS : Board.Kind.WTB, 4_000_000 + i, 10, 1_000 + i));
        }
        BotBoardPosts bp = new BotBoardPosts();
        List<Board.Post> open = List.of();
        for (int i = 0; i < 10; i++) {
            open = bp.refresh(w, java.util.Set.of());
            w.now += TimeUnit.MINUTES.toMillis(15);
        }
        assertEquals(BotBoardPosts.MIN, open.size(), "between(lo, hi) is lo in the fake");
        assertTrue(open.stream().allMatch(Board.Post::bot));
        assertTrue(open.stream().anyMatch(p -> p.kind() == Board.Kind.WTB) && open.stream().anyMatch(p -> p.kind() == Board.Kind.WTS));
        assertEquals(open.size(), open.stream().map(Board.Post::characterId).distinct().count(), "one post per bot");
        int gone = open.get(0).characterId();
        w.unbacked.add(gone);
        open = bp.refresh(w, java.util.Set.of());
        assertTrue(open.stream().noneMatch(p -> p.characterId() == gone));
        // no bot flag in board.json
        String board = soloMapling.server.Json.write(BoardOutbox.board(open, w.now));
        assertFalse(board.contains("bot"), board);
        assertTrue(open.stream().allMatch(p -> p.id().matches("p[0-9a-f]{12}")));
    }

    @Test
    void theNpcShowsPostsNewestFirstAPageAtATime() {
        List<Board.Post> posts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            posts.add(new Board.Post("p" + i, 7, "Momo" + i, 70, i % 2 == 0 ? Board.Kind.WTB : Board.Kind.WTS, GLOVE,
                    1 + i % 2, 3_000_000, w.now + i, w.now + 999_999, false));
        }
        SiteBoard.NpcPage first = SiteBoard.npcPage(posts, 0);
        assertEquals(2, first.pages());
        assertTrue(first.hasNext() && !first.hasPrev());
        assertTrue(first.text().indexOf("Momo9") < first.text().indexOf("Momo8"), first.text());
        assertTrue(first.text().contains("#t2040804#"));
        assertTrue(first.text().contains("#L0#Next page#l"));
        assertFalse(first.text().contains("Momo1#k"));
        SiteBoard.NpcPage second = SiteBoard.npcPage(posts, 5);
        assertEquals(1, second.page());
        assertTrue(second.text().contains("Momo0") && second.text().contains("#L1#Previous page#l"));
        assertTrue(SiteBoard.npcPage(List.of(), 0).text().contains("No posts right now"));
    }
}
